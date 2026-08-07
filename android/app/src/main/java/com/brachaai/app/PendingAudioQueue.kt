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
    private val nowMs: () -> Long = System::currentTimeMillis
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
     * (see the caller in `CallMonitorService.handleNewFile`). [ProcessOutcome.Skipped] is
     * also what comes back when the recording was already `done` or `stuck`: that is a
     * terminal, not-a-failure result, not a signal about the network.
     */
    suspend fun processNow(file: File): ProcessOutcome {
        return mutex.withLock {
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

            // Prune against every entry actually present, not just the ones eligible for
            // processing below — otherwise a hidden file (or any other entry excluded from
            // `candidates`) whose recording is still on disk would be dropped from the index
            // as if it were gone.
            index.pruneTo(entries.map { it.name }.toSet())

            val candidates = entries
                .filter { it.isFile && !it.name.startsWith(".") }
                .sortedBy { it.name }

            val pending = candidates.filter { file ->
                val state = index.stateOf(file.name)
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

    /** Caller must hold [mutex]. */
    private suspend fun processOneLocked(file: File): ProcessOutcome {
        val name = file.name
        val before = index.stateOf(name)
        if (before.done || before.stuck) {
            Log.d(TAG, "Ignoring $name: already ${if (before.done) "done" else "given up on"}")
            return ProcessOutcome.Skipped
        }

        val outcome = processor.process(file)
        when (outcome) {
            is ProcessOutcome.Completed, is ProcessOutcome.Skipped -> {
                // attempts resets to 0: the entry now only records that this is finished, and
                // a stale count would be misleading if the file is somehow seen again.
                index.put(name, RecordingState(attempts = 0, done = true))
                Log.i(TAG, "Finished $name ($outcome)")
            }

            is ProcessOutcome.GiveUp -> {
                index.put(name, RecordingState(attempts = before.attempts + 1, stuck = true, lastError = outcome.reason))
                Log.e(TAG, "Giving up on $name: ${outcome.reason}. The recording is kept and will not be retried.")
                notifyStuck(name, outcome.reason)
            }

            is ProcessOutcome.RetryLater -> {
                val attempts = before.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    index.put(name, RecordingState(attempts = attempts, stuck = true, lastError = outcome.reason))
                    Log.e(TAG, "Giving up on $name after $attempts attempts: ${outcome.reason}. The recording is kept.")
                    notifyStuck(name, outcome.reason)
                } else {
                    index.put(name, RecordingState(attempts = attempts, lastError = outcome.reason))
                    Log.w(TAG, "Attempt $attempts/$MAX_ATTEMPTS failed for $name: ${outcome.reason}")
                }
            }
        }
        return outcome
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
