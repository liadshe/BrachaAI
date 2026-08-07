package com.brachaai.app

import java.io.File

/**
 * How one processing attempt ended.
 *
 * The split that matters is terminal-vs-retryable, because it decides two separate things:
 * whether the recording may be deleted, and whether anything should ever look at it again.
 */
sealed class ProcessOutcome {
    /**
     * The call landed: the backend accepted it, or its transcript is durably queued for
     * delivery. Either way the audio has no further use — re-transcribing it would cost
     * money and, on the queued path, upload the same call twice.
     */
    object Completed : ProcessOutcome()

    /** Deliberately not processed (under five seconds). Terminal, and not a failure. */
    object Skipped : ProcessOutcome()

    /**
     * No attempt was made and no state changed. Either the queue already holds a terminal
     * answer for this recording (`done` or `stuck`), or the directory entry is not an
     * eligible recording at all — a hidden temp file the recorder has not renamed yet, or a
     * subdirectory.
     *
     * Deliberately *not* [Skipped]. [Skipped] means "the audio was inspected and is not a
     * call worth transcribing", which is a success and therefore deletion-eligible in
     * [AudioProcessor.applyDeletion]. Reusing it for the short-circuit would make a **stuck**
     * recording deletable the moment any caller routed this value into that gate — a
     * recording destroyed although its call never landed, the exact failure this queue
     * exists to prevent. This value is never deletion-eligible, and no [RecordingProcessor]
     * ever returns it: only [PendingAudioQueue] produces it, without running an attempt.
     */
    object AlreadyHandled : ProcessOutcome()

    /** Transient failure — no internet, a timeout, a rate limit. Try again later. */
    data class RetryLater(val reason: String) : ProcessOutcome()

    /** Permanent failure. The recording is kept forever, but must never be retried. */
    data class GiveUp(val reason: String) : ProcessOutcome()
}

/**
 * One attempt at one recording.
 *
 * Exists so [PendingAudioQueue] — which holds all the retry policy — can be unit-tested
 * against a hand-written fake, without FFmpeg, OpenAI, or a network.
 */
interface RecordingProcessor {
    suspend fun process(audioFile: File): ProcessOutcome
}
