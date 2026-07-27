package com.brachaai.app

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

    private fun newProcessor(settingsStore: SettingsStore): AudioProcessor {
        val app = RuntimeEnvironment.getApplication()
        return AudioProcessor(
            openAiApiKey = "unused-in-this-test",
            cacheDir = tempFolder.newFolder("cache"),
            authStore = AuthStore(app),
            pendingStore = PendingUploadStore(tempFolder.newFolder("pending")),
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
}
