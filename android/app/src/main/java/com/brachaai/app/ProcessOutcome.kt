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
