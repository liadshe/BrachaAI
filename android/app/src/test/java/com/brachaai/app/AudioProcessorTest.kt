package com.brachaai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers [AudioProcessor.deleteOriginalIfEnabled] only. Everything else on AudioProcessor
 * needs FFmpegKit and live network calls, which have no JVM seam; that behavior is covered
 * by the on-device verification matrix instead. This method is pure File/SharedPreferences
 * work, so it is testable directly under Robolectric without a mocking framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioProcessorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newProcessor(
        settingsStore: SettingsStore,
        pendingStore: PendingUploadStore = PendingUploadStore(tempFolder.newFolder("pending")),
        authStore: AuthStore = AuthStore(RuntimeEnvironment.getApplication())
    ): AudioProcessor {
        val app = RuntimeEnvironment.getApplication()
        return AudioProcessor(
            openAiApiKey = "unused-in-this-test",
            cacheDir = tempFolder.newFolder("cache"),
            authStore = authStore,
            pendingStore = pendingStore,
            callerLookup = CallerLookup(app),
            settingsStore = settingsStore,
            tokenRefresher = TokenRefresher(authStore),
            baseUrl = BackendConfig.BASE_URL
        )
    }

    @Test
    fun deletesOriginalWhenFlagIsOn() {
        val settingsStore = SettingsStore(RuntimeEnvironment.getApplication())
        settingsStore.deleteAudioAfterProcessing = true
        val processor = newProcessor(settingsStore)
        val recording = tempFolder.newFile("call.m4a")

        processor.deleteOriginalIfEnabled(recording)

        assertFalse("recording should have been deleted", recording.exists())
    }

    @Test
    fun keepsOriginalWhenFlagIsOff() {
        val settingsStore = SettingsStore(RuntimeEnvironment.getApplication())
        settingsStore.deleteAudioAfterProcessing = false
        val processor = newProcessor(settingsStore)
        val recording = tempFolder.newFile("call.m4a")

        processor.deleteOriginalIfEnabled(recording)

        assertTrue("recording should have been left alone", recording.exists())
    }

    @Test
    fun missingFileDoesNotThrow() {
        val settingsStore = SettingsStore(RuntimeEnvironment.getApplication())
        settingsStore.deleteAudioAfterProcessing = true
        val processor = newProcessor(settingsStore)
        val missing = File(tempFolder.root, "already-gone.m4a")

        // Must not throw even though the file was never created.
        processor.deleteOriginalIfEnabled(missing)
    }

    // ------------------------------------------------ queuedTranscriptIsDurable
    //
    // The decision that stands between a queued transcript and a destroyed recording.
    // Driven with real collaborators: a real PendingUploadStore over a temp directory and
    // a real AuthStore over Robolectric's prefs.

    private fun settings() = SettingsStore(RuntimeEnvironment.getApplication())

    private fun samplePayload(name: String) = PendingUpload(
        contactName = name,
        date = "250101_120000",
        callerNumber = null,
        transcript = "transcript for $name"
    )

    @Test
    fun notDurableWhenTheQueueWriteFailed() {
        val processor = newProcessor(settings())

        assertFalse(
            "a failed queue write leaves the recording as the only copy",
            processor.queuedTranscriptIsDurable(enqueued = false, wasUnauthenticated = false)
        )
        assertFalse(
            processor.queuedTranscriptIsDurable(enqueued = false, wasUnauthenticated = true)
        )
    }

    @Test
    fun notDurableWhenUnauthenticatedAndNoTokenWasEverStored() {
        val app = RuntimeEnvironment.getApplication()
        val authStore = AuthStore(app)
        assertFalse("precondition: this device has never held a token", authStore.hasEverAuthenticated())
        val processor = newProcessor(settings(), authStore = authStore)

        assertFalse(
            "a login that may never happen is not durability",
            processor.queuedTranscriptIsDurable(enqueued = true, wasUnauthenticated = true)
        )
    }

    @Test
    fun durableWhenUnauthenticatedButTheUserHasLoggedInBefore() {
        val app = RuntimeEnvironment.getApplication()
        // Stands in for a past AuthStore.setToken(): that call cannot run on the JVM
        // because EncryptedSharedPreferences needs the Android keystore. The state it
        // leaves behind — the login-history flag set, the token itself since cleared by a
        // 401 — is exactly what is written here.
        app.getSharedPreferences(AuthStore.HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AuthStore.KEY_EVER_AUTHENTICATED, true)
            .commit()
        val authStore = AuthStore(app)
        authStore.clear()   // a 401 wipes the token; the login history must survive that
        assertTrue(authStore.hasEverAuthenticated())
        val processor = newProcessor(settings(), authStore = authStore)

        assertTrue(
            "an expired token on an account that logs in will be flushed after re-login",
            processor.queuedTranscriptIsDurable(enqueued = true, wasUnauthenticated = true)
        )
    }

    @Test
    fun durableOnAGenuinelyTransientFailureEvenWithNoTokenHistory() {
        // Transient means attemptUpload got past the token check, so a token was present.
        // Preserving this path is the point: network-down must still reclaim storage.
        val processor = newProcessor(settings())

        assertTrue(
            processor.queuedTranscriptIsDurable(enqueued = true, wasUnauthenticated = false)
        )
    }

    @Test
    fun notDurableOnceTheQueueIsAtCapacity() {
        val pendingStore = PendingUploadStore(tempFolder.newFolder("full-queue"))
        repeat(PendingUploadStore.MAX_ENTRIES) {
            assertTrue(pendingStore.enqueue(samplePayload("caller-$it")))
        }
        assertEquals(PendingUploadStore.MAX_ENTRIES, pendingStore.size())
        val processor = newProcessor(settings(), pendingStore = pendingStore)

        assertFalse(
            "at the cap the next enqueue evicts a transcript whose recording is already gone",
            processor.queuedTranscriptIsDurable(enqueued = true, wasUnauthenticated = false)
        )
    }

    @Test
    fun durableWhileTheQueueIsOneShortOfCapacity() {
        val pendingStore = PendingUploadStore(tempFolder.newFolder("nearly-full-queue"))
        repeat(PendingUploadStore.MAX_ENTRIES - 1) {
            assertTrue(pendingStore.enqueue(samplePayload("caller-$it")))
        }
        val processor = newProcessor(settings(), pendingStore = pendingStore)

        assertTrue(
            processor.queuedTranscriptIsDurable(enqueued = true, wasUnauthenticated = false)
        )
    }

    // ------------------------------------------------------------ applyDeletion
    //
    // The single gate between a failed call and a destroyed recording. Every row of the
    // spec's outcome table is asserted here, because this is the only place that decides.

    private fun recordingIn(name: String): File =
        File(tempFolder.newFolder(name), "call.m4a").apply { parentFile?.mkdirs(); writeText("audio") }

    private fun processorWithDeleteFlag(delete: Boolean): AudioProcessor {
        val settingsStore = SettingsStore(RuntimeEnvironment.getApplication())
        settingsStore.deleteAudioAfterProcessing = delete
        return newProcessor(settingsStore)
    }

    @Test
    fun completedAndDeletableHonoursTheDeleteSetting() {
        val recording = recordingIn("completed-on")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = true), recording
        )
        assertFalse("a landed call with the setting on should be deleted", recording.exists())
    }

    @Test
    fun completedButSettingOffKeepsTheRecording() {
        val recording = recordingIn("completed-off")
        processorWithDeleteFlag(false).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = true), recording
        )
        assertTrue(recording.exists())
    }

    @Test
    fun skippedShortRecordingHonoursTheDeleteSetting() {
        val recording = recordingIn("skipped")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Skipped, mayDeleteRecording = true), recording
        )
        assertFalse("a deliberately skipped recording is finished, not failed", recording.exists())
    }

    @Test
    fun completedButNotDurablyQueuedKeepsTheRecordingEvenWithTheSettingOn() {
        // The transcript is queued (so it must not be re-transcribed) but the queue is not a
        // trustworthy home for it, so the recording is the only real copy.
        val recording = recordingIn("completed-not-durable")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = false), recording
        )
        assertTrue(recording.exists())
    }

    @Test
    fun retryLaterNeverDeletesEvenWithTheSettingOn() {
        val recording = recordingIn("retry")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.RetryLater("no internet"), mayDeleteRecording = false),
            recording
        )
        assertTrue("an unprocessed recording must survive regardless of the setting", recording.exists())
    }

    @Test
    fun giveUpNeverDeletesEvenWithTheSettingOn() {
        val recording = recordingIn("giveup")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.GiveUp("backend rejected it"), mayDeleteRecording = false),
            recording
        )
        assertTrue("giving up is not permission to destroy the only copy", recording.exists())
    }

    @Test
    fun aFailureOutcomeIsNeverDeletedEvenIfTheFlagSaysItMayBe() {
        // Belt and braces: the outcome alone must be able to veto deletion, so a future
        // caller that miscomputes the flag cannot destroy a recording.
        val recording = recordingIn("veto")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.RetryLater("bad flag"), mayDeleteRecording = true),
            recording
        )
        assertTrue(recording.exists())
    }

    @Test
    fun aGiveUpOutcomeIsNeverDeletedEvenIfTheFlagSaysItMayBe() {
        // Same veto as above, but for GiveUp specifically: retryLaterNeverDeletes... and
        // giveUpNeverDeletes... both pin mayDeleteRecording = false, so neither would catch a
        // future edit that adds GiveUp to applyDeletion's terminalSuccess check. This is the
        // one that would.
        val recording = recordingIn("giveup-veto")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.GiveUp("bad flag"), mayDeleteRecording = true),
            recording
        )
        assertTrue(recording.exists())
    }

    @Test
    fun alreadyHandledIsNeverDeletionEligible() {
        // PendingAudioQueue returns AlreadyHandled for a recording that is already done OR
        // already *stuck*. If a future caller routed it into this gate and it were treated
        // like Skipped, a stuck recording — a call that never landed — would be destroyed.
        val recording = recordingIn("already-handled")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.AlreadyHandled, mayDeleteRecording = true),
            recording
        )
        assertTrue("no attempt was made, so nothing may be deleted", recording.exists())
    }

    // ------------------------------------------------------- pipelineResultFor
    //
    // The applyDeletion tests above hand-construct their PipelineResults, so nothing pinned
    // that the pipeline *produces* the right one. This is the table that decides, driven for
    // every row of UploadResult.

    @Test
    fun aSuccessfulUploadCompletesAndReleasesTheRecording() {
        val result = newProcessor(settings()).pipelineResultFor(AudioProcessor.UploadResult.Success, enqueued = false)

        assertEquals(ProcessOutcome.Completed, result.outcome)
        assertTrue(result.mayDeleteRecording)
    }

    @Test
    fun aRejectedUploadGivesUpAndKeepsTheRecording() {
        // The spec's headline behaviour change: a backend 400/422 used to set
        // transcriptIsDurable = true and delete. The call never reached the backend, so the
        // recording is the only copy of it and is now kept and marked stuck instead.
        val result = newProcessor(settings()).pipelineResultFor(AudioProcessor.UploadResult.Rejected, enqueued = false)

        assertTrue("a rejected payload must never be retried", result.outcome is ProcessOutcome.GiveUp)
        assertFalse("a call that never landed must not lose its only recording", result.mayDeleteRecording)
    }

    @Test
    fun aRejectedUploadSurvivesTheDeletionGateWithTheSettingOn() {
        // End to end for that same row: the produced result, through the real gate.
        val recording = recordingIn("rejected-end-to-end")
        val processor = processorWithDeleteFlag(true)

        processor.applyDeletion(
            processor.pipelineResultFor(AudioProcessor.UploadResult.Rejected, enqueued = false),
            recording
        )

        assertTrue("a 400/422 must keep the recording even with delete-after-processing on", recording.exists())
    }

    @Test
    fun aTransientFailureWithADurableQueueEntryCompletesAndReleasesTheRecording() {
        // Transient means a token was present, and the queue here is empty, so
        // queuedTranscriptIsDurable says yes: the transcript has a real home.
        val result = newProcessor(settings()).pipelineResultFor(AudioProcessor.UploadResult.Transient, enqueued = true)

        assertEquals(
            "the transcript is queued, so re-transcribing would upload the call twice",
            ProcessOutcome.Completed,
            result.outcome
        )
        assertTrue(result.mayDeleteRecording)
    }

    @Test
    fun anUnauthenticatedFailureWithNoLoginHistoryCompletesButKeepsTheRecording() {
        val authStore = AuthStore(RuntimeEnvironment.getApplication())
        assertFalse("precondition: this device has never held a token", authStore.hasEverAuthenticated())

        val result = newProcessor(settings(), authStore = authStore)
            .pipelineResultFor(AudioProcessor.UploadResult.Unauthenticated, enqueued = true)

        assertEquals(
            "the transcript is queued either way, so it must not be transcribed again",
            ProcessOutcome.Completed,
            result.outcome
        )
        assertFalse(
            "a login that may never happen is not a reason to destroy the recording",
            result.mayDeleteRecording
        )
    }

    @Test
    fun aFailedEnqueueRetriesLaterAndKeepsTheRecording() {
        val processor = newProcessor(settings())

        listOf(AudioProcessor.UploadResult.Transient, AudioProcessor.UploadResult.Unauthenticated)
            .forEach { uploadResult ->
                val result = processor.pipelineResultFor(uploadResult, enqueued = false)

                assertTrue(
                    "nothing was persisted anywhere, so the audio is the only copy: $uploadResult",
                    result.outcome is ProcessOutcome.RetryLater
                )
                assertFalse(result.mayDeleteRecording)
            }
    }

    // ------------------------------------------------------- unparseable filenames

    @Test
    fun aFilenameThatCannotParseIsTheTriggerForGivingUp() {
        // Documents what actually throws out of the pipeline: parseFilename needs at least
        // Name_YYMMDD_HHMMSS. Before this, that IllegalArgumentException landed in the general
        // catch as RetryLater and burned all five attempts on a name that can never parse.
        try {
            parseFilename("recording-001.m4a")
            org.junit.Assert.fail("expected an IllegalArgumentException for a non-conforming filename")
        } catch (expected: IllegalArgumentException) {
            // exactly what AudioProcessor now catches specifically
        }
    }

    @Test
    fun anUnparseableFilenameGivesUpImmediatelyAndKeepsTheRecording() {
        val recording = recordingIn("unparseable")
        val processor = processorWithDeleteFlag(true)

        val result = processor.unparseableFilenameResult("recording-001.m4a")

        assertTrue("no retry can change a filename", result.outcome is ProcessOutcome.GiveUp)
        assertTrue(
            "the reason should name the file so the notification is actionable",
            (result.outcome as ProcessOutcome.GiveUp).reason.contains("recording-001.m4a")
        )
        assertFalse(result.mayDeleteRecording)

        processor.applyDeletion(result, recording)
        assertTrue("giving up never destroys the recording", recording.exists())
    }

    // ------------------------------------------------------- whisper status mapping

    @Test
    fun permanentWhisperStatusesGiveUp() {
        val processor = processorWithDeleteFlag(true)
        listOf(400, 413, 415, 422).forEach { code ->
            val outcome = processor.outcomeForWhisperFailure(WhisperHttpException(code, "boom"), "transcription")
            assertTrue("HTTP $code should be permanent, got $outcome", outcome is ProcessOutcome.GiveUp)
        }
    }

    @Test
    fun transientWhisperStatusesRetry() {
        val processor = processorWithDeleteFlag(true)
        // 401/403 stay retryable on purpose: a bad or expired API key is fixed by a new
        // build, and marking every recording stuck on the first attempt would strand them all.
        listOf(401, 403, 429, 500, 502, 503).forEach { code ->
            val outcome = processor.outcomeForWhisperFailure(WhisperHttpException(code, "boom"), "transcription")
            assertTrue("HTTP $code should be retryable, got $outcome", outcome is ProcessOutcome.RetryLater)
        }
    }

    @Test
    fun aConnectionFailureRetries() {
        val processor = processorWithDeleteFlag(true)

        val outcome = processor.outcomeForWhisperFailure(java.io.IOException("Unable to resolve host"), "transcription")

        assertTrue("being offline is the whole reason this queue exists", outcome is ProcessOutcome.RetryLater)
    }
}
