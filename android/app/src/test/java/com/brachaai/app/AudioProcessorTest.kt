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
            settingsStore = settingsStore
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
}
