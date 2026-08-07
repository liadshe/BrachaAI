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
    /** Called once, at the moment a recording is given up on. Name, then reason. */
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
     */
    suspend fun processNow(file: File) {
        mutex.withLock {
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
            val files = candidateFiles()
            index.pruneTo(files.map { it.name }.toSet())

            val pending = files.filter { file ->
                val state = index.stateOf(file.name)
                !state.done && !state.stuck
            }
            if (pending.isEmpty()) return@withLock

            Log.i(TAG, "Sweeping ${pending.size} unprocessed recording(s)")
            pending.forEach { file ->
                // A file that is still being written is almost certainly the call happening
                // right now; the observer will pick it up on CLOSE_WRITE.
                if (nowMs() - file.lastModified() < MIN_AGE_MS) {
                    Log.d(TAG, "Skipping ${file.name} this sweep: written too recently")
                    return@forEach
                }
                processOneLocked(file)
            }
        }
    }

    /** Caller must hold [mutex]. */
    private suspend fun processOneLocked(file: File) {
        val name = file.name
        val before = index.stateOf(name)
        if (before.done || before.stuck) {
            Log.d(TAG, "Ignoring $name: already ${if (before.done) "done" else "given up on"}")
            return
        }

        when (val outcome = processor.process(file)) {
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
    }

    /** Never lets a notification failure break the sweep — the bookkeeping already happened. */
    private fun notifyStuck(name: String, reason: String) {
        try {
            onStuck(name, reason)
        } catch (e: Exception) {
            Log.w(TAG, "Could not report $name as stuck", e)
        }
    }

    private fun candidateFiles(): List<File> =
        watchDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()

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
