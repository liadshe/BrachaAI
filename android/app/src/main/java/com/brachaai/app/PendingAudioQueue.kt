package com.brachaai.app

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Decides which recordings still need transcribing, and when to stop trying.
 *
 * This exists because the pipeline had failover for the *transcript* but none for the
 * *audio*: with no internet there is no transcript to queue, Whisper threw, and the
 * recording sat in the watch directory forever because `FileObserver` only fires for newly
 * written files. Recordings are never moved or copied — the watch directory is the queue,
 * and this class plus [RecordingIndex] is the bookkeeping over it.
 *
 * All retry policy lives here. [RecordingIndex] only stores state and [AudioProcessor] only
 * makes one attempt, so each of the three can be tested without the other two.
 */
class PendingAudioQueue(
    private val watchDir: File,
    private val index: RecordingIndex,
    private val processor: RecordingProcessor,
    /**
     * Called once, at the moment a recording is given up on. Name, then reason.
     *
     * Invoked while the queue's internal lock is held, so it must never call back into
     * [processNow] or [sweep] — [Mutex] is not reentrant, and a re-entrant call would hang
     * the coroutine forever rather than throw.
     */
    private val onStuck: (String, String) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Marker file whose existence means the first-run baseline has been taken. Its contents
     * are irrelevant; only whether it exists. See [adoptPreExistingRecordingsLocked].
     */
    private val baselineMarker: File = File(watchDir.parentFile, ".bracha-baseline")
) {

    // Serializes every attempt in the process. Without it a sweep triggered by the network
    // coming back and a FileObserver event for a just-finished call could transcribe the
    // same recording twice, uploading the call twice.
    private val mutex = Mutex()

    /**
     * Processes a recording the file observer just saw close.
     *
     * No age check: `FileObserver.CLOSE_WRITE` already means the writer is finished, which
     * is exactly the guarantee [sweep] lacks and has to approximate with [MIN_AGE_MS].
     *
     * Returns the outcome so the caller can tell a landed call from one that did not — in
     * particular, whether following up with a sweep of the rest of the queue is justified
     * (see the caller in `CallMonitorService.handleNewFile`).
     * [ProcessOutcome.AlreadyHandled] comes back when the recording was already `done` or
     * `stuck`, or when the entry is not an eligible recording at all: no attempt was made,
     * nothing changed, and — unlike [ProcessOutcome.Skipped] — nothing may be deleted.
     */
    suspend fun processNow(file: File): ProcessOutcome {
        return mutex.withLock {
            // Take the baseline first so a call arriving before the first sweep cannot cause
            // the folder to be adopted around it. The file this call is about is excluded
            // from adoption by name — the observer saw it close, so it is genuinely new.
            adoptPreExistingRecordingsLocked(except = file.name)
            processOneLocked(file)
        }
    }

    /**
     * Retries everything in the watch directory that is neither done nor given up on.
     *
     * Keeps going past a failure, unlike the transcript queue's flush: one recording failing
     * to transcribe says nothing about the next, and stopping early would strand every call
     * behind a single bad file.
     */
    suspend fun sweep() {
        mutex.withLock {
            val entries = listEntries()
            if (entries == null) {
                // File.listFiles() returns null (not empty) whenever the directory does not
                // exist or cannot be read — exactly the state right after a reboot before
                // MANAGE_EXTERNAL_STORAGE is re-established, after the user revokes storage
                // access, or when external storage isn't mounted yet. Treating that as "no
                // recordings" would prune every index entry, including every done and stuck
                // flag, and once the directory reappears every recording still on disk would
                // be re-transcribed and re-uploaded from scratch. Abandon the sweep instead:
                // do not prune, do not process, try again next time.
                Log.e(TAG, "Cannot list $watchDir; skipping this sweep without touching the index")
                return@withLock
            }

            adoptPreExistingRecordingsLocked(except = null)

            // Prune against every entry actually present, not just the ones eligible for
            // processing below — otherwise a hidden file (or any other entry excluded from
            // `candidates`) whose recording is still on disk would be dropped from the index
            // as if it were gone.
            index.pruneTo(entries.map { it.name }.toSet())

            val candidates = entries
                .filter { isEligibleRecording(it) }
                .sortedBy { it.name }

            // One read of the index for the whole listing, never stateOf() per file.
            // stateOf parses the entire index file on every call, so asking it about N
            // recordings costs N parses of a file that itself grows with N. On a device with
            // ~3,800 recordings and a 400 KB index that was ~1.5 GB of JSON parsing per
            // sweep, burning minutes of CPU while holding the mutex below — which is the lock
            // processNow needs, so a call that had just been recorded sat unprocessed until
            // the sweep finished. It had been instant before this queue existed.
            val states = index.snapshot()
            val pending = candidates.filter { file ->
                val state = states[file.name] ?: RecordingState()
                !state.done && !state.stuck
            }
            if (pending.isEmpty()) return@withLock

            Log.i(TAG, "Sweeping ${pending.size} unprocessed recording(s)")
            pending.forEach { file ->
                // A file that is still being written is almost certainly the call happening
                // right now; the observer will pick it up on CLOSE_WRITE. A negative age
                // means the file's mtime is ahead of "now" — a fast device clock while
                // recording, or a later NTP/manual correction moving the clock back — and
                // must be treated as old, not fresh, or the recording would be skipped on
                // every sweep forever with no attempt ever counted and no stuck notification
                // ever sent: silently lost, the exact failure this queue exists to prevent.
                val age = nowMs() - file.lastModified()
                if (age in 0 until MIN_AGE_MS) {
                    Log.d(TAG, "Skipping ${file.name} this sweep: written too recently")
                    return@forEach
                }
                processOneLocked(file)
            }
        }
    }

    /**
     * The one definition of what this queue will process, shared by both entry points.
     *
     * [sweep] filters the directory listing with it; [processOneLocked] enforces it again so
     * `processNow` — which is handed whatever path `FileObserver` reported — cannot admit
     * something a sweep would refuse. Without that, a recorder that writes `.pending_x.m4a`
     * and renames it afterwards (or any stray file dropped in the folder) would get an index
     * entry, five `RetryLater` attempts, and a user-facing "Could not process" notification
     * for something that was never a call.
     */
    private fun isEligibleRecording(file: File): Boolean =
        file.isFile && !file.name.startsWith(".")

    /**
     * Marks every recording already in the watch directory as handled, without transcribing
     * any of it — once, the first time this queue ever runs on a device.
     *
     * Without this, the queue is a mass-backfill machine. [sweep] processes every eligible
     * file that has no index entry, and on a fresh install the index is empty, so the first
     * sweep transcribes and uploads *the user's entire recording history* — including calls
     * recorded long before the app was installed. On a phone with hundreds of old recordings
     * that is hundreds of Whisper charges and a call list flooded with years-old calls. The
     * trigger fires immediately too: `NetworkWatcher` delivers a callback for the
     * already-connected network as soon as the service starts.
     *
     * The queue's job is calls recorded *while the app is watching*. Anything already sitting
     * in the folder when it first looks predates that, and is adopted as finished business.
     *
     * This also contains the blast radius when the index is lost — cleared app data, or a
     * snapshot that fails to parse. Previously that meant every recording still on disk was
     * re-transcribed and re-uploaded; now the marker survives independently, and if it is
     * gone too the folder is re-adopted rather than re-processed. A genuinely pending
     * recording present at that moment is silently adopted and never transcribed, which is
     * the deliberate trade: one lost call beats re-uploading every call on the device.
     *
     * [except] is the recording `processNow` was just handed. `FileObserver` saw it close, so
     * it is new by definition and must not be adopted by a baseline that happens to run first.
     *
     * Caller must hold [mutex].
     */
    private fun adoptPreExistingRecordingsLocked(except: String?) {
        if (baselineMarker.exists()) return

        val existing = listEntries()
        if (existing == null) {
            // Cannot see the directory, so cannot know what predates us. Taking the baseline
            // now would adopt nothing and then mark us as having looked, so the real contents
            // would be swept as if new the moment the directory becomes readable — the exact
            // mass upload this guards against. Try again next time instead.
            Log.w(TAG, "Cannot list $watchDir; deferring the first-run baseline")
            return
        }

        val adopted = existing
            .filter { isEligibleRecording(it) && it.name != except }
            .associate { it.name to RecordingState(done = true) }

        if (adopted.isNotEmpty() && !index.putAll(adopted)) {
            // The adoption did not reach disk. Marking the baseline as taken anyway would
            // leave those recordings with no index entry and no second chance to adopt them,
            // so the next sweep would upload the lot. Leave the marker unwritten and retry.
            Log.e(TAG, "Could not persist the first-run baseline; leaving it untaken so it retries")
            return
        }

        val marked = try {
            baselineMarker.parentFile?.mkdirs()
            baselineMarker.createNewFile() || baselineMarker.exists()
        } catch (e: Exception) {
            Log.e(TAG, "Could not write the baseline marker ${baselineMarker.name}", e)
            false
        }

        if (marked) {
            Log.i(TAG, "First run: adopted ${adopted.size} pre-existing recording(s) as handled; they will never be transcribed")
        } else {
            // Harmless: the index entries above already stop these being processed, and the
            // next run simply re-adopts the same files, which is idempotent.
            Log.w(TAG, "Adopted ${adopted.size} pre-existing recording(s) but could not mark the baseline; it will run again")
        }
    }

    /** Caller must hold [mutex]. */
    private suspend fun processOneLocked(file: File): ProcessOutcome {
        val name = file.name
        if (!isEligibleRecording(file)) {
            // Before any index read or write: an ineligible entry must leave no trace.
            Log.d(TAG, "Ignoring $name: not an eligible recording (hidden entry or directory)")
            return ProcessOutcome.AlreadyHandled
        }

        val before = index.stateOf(name)
        if (before.done || before.stuck) {
            Log.d(TAG, "Ignoring $name: already ${if (before.done) "done" else "given up on"}")
            return ProcessOutcome.AlreadyHandled
        }

        val outcome = processor.process(file)
        when (outcome) {
            is ProcessOutcome.Completed, is ProcessOutcome.Skipped -> {
                // attempts resets to 0: the entry now only records that this is finished, and
                // a stale count would be misleading if the file is somehow seen again.
                val persisted = index.put(name, RecordingState(attempts = 0, done = true))
                reportLostTerminalState(persisted, name, done = true)
                Log.i(TAG, "Finished $name ($outcome)")
            }

            is ProcessOutcome.GiveUp -> {
                val persisted =
                    index.put(name, RecordingState(attempts = before.attempts + 1, stuck = true, lastError = outcome.reason))
                reportLostTerminalState(persisted, name, done = false)
                Log.e(TAG, "Giving up on $name: ${outcome.reason}. The recording is kept and will not be retried.")
                notifyStuck(name, outcome.reason)
            }

            is ProcessOutcome.RetryLater -> {
                val attempts = before.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    val persisted =
                        index.put(name, RecordingState(attempts = attempts, stuck = true, lastError = outcome.reason))
                    reportLostTerminalState(persisted, name, done = false)
                    Log.e(TAG, "Giving up on $name after $attempts attempts: ${outcome.reason}. The recording is kept.")
                    notifyStuck(name, outcome.reason)
                } else {
                    // Deliberately not reported: a lost attempt count only costs one extra
                    // retry, which is the safe direction. Only the terminal flags matter.
                    index.put(name, RecordingState(attempts = attempts, lastError = outcome.reason))
                    Log.w(TAG, "Attempt $attempts/$MAX_ATTEMPTS failed for $name: ${outcome.reason}")
                }
            }

            is ProcessOutcome.AlreadyHandled -> {
                // No RecordingProcessor produces this — it is this class's own answer for a
                // file it never handed over, returned above without reaching here. Listed so
                // the `when` stays exhaustive over the sealed class, and deliberately writes
                // nothing: an outcome that reports no attempt must not record one.
                Log.w(TAG, "Processor returned AlreadyHandled for $name; recording nothing")
            }
        }
        return outcome
    }

    /**
     * Shouts when a *terminal* index flag never reached disk.
     *
     * [RecordingIndex] keeps nothing in memory, so a failed write is a lost write. That is
     * harmless for an attempt count but not for `done`/`stuck`, and there is nothing this
     * class can do about it beyond making the consequence visible in the log — retrying the
     * write would fail for the same reason (a full or read-only disk).
     */
    private fun reportLostTerminalState(persisted: Boolean, name: String, done: Boolean) {
        if (persisted) return
        if (done) {
            Log.e(
                TAG,
                "Could not persist done=true for $name. If the recording is still on disk " +
                    "(delete-after-processing off, or the delete failed) the next sweep will " +
                    "transcribe and upload this call a second time."
            )
        } else {
            Log.e(
                TAG,
                "Could not persist stuck=true for $name. It will be retried on every sweep " +
                    "and re-notified instead of staying given up on."
            )
        }
    }

    /** Never lets a notification failure break the sweep — the bookkeeping already happened. */
    private fun notifyStuck(name: String, reason: String) {
        try {
            onStuck(name, reason)
        } catch (e: Exception) {
            Log.w(TAG, "Could not report $name as stuck", e)
        }
    }

    /** Null when [watchDir] does not exist or cannot be read; distinct from an empty, readable directory. */
    private fun listEntries(): List<File>? = watchDir.listFiles()?.toList()

    companion object {
        private const val TAG = "PendingAudioQueue"

        /**
         * Consecutive transient failures before a recording is given up on. It is still
         * never deleted — this only stops the retrying, so a permanently-broken file cannot
         * re-run Whisper on every single reconnect.
         */
        const val MAX_ATTEMPTS = 5

        /**
         * A sweep ignores anything written this recently. `FileObserver` guarantees a file
         * is complete via CLOSE_WRITE; a sweep has no such guarantee and would otherwise
         * happily transcribe the call that is still in progress.
         */
        const val MIN_AGE_MS = 10_000L
    }
}
