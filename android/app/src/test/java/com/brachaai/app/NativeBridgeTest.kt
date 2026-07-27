package com.brachaai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBridgeTest {

    private fun newBridge() = NativeBridge(RuntimeEnvironment.getApplication()) {}

    @Test
    fun deleteAudioReadsAsOnByDefaultOverTheBridge() {
        assertTrue(newBridge().getDeleteAudioAfterProcessing())
    }

    // The bridge and the store must resolve to the same prefs file and key. If they
    // drift apart the toggle appears to work and silently changes nothing in the
    // background pipeline, which is the worst possible failure for this feature.
    @Test
    fun writingOverTheBridgeIsVisibleToSettingsStore() {
        val app = RuntimeEnvironment.getApplication()
        newBridge().setDeleteAudioAfterProcessing(false)
        assertFalse(SettingsStore(app).deleteAudioAfterProcessing)
    }

    @Test
    fun writingOverTheBridgeRoundTripsBackToTrue() {
        val app = RuntimeEnvironment.getApplication()
        newBridge().setDeleteAudioAfterProcessing(false)
        newBridge().setDeleteAudioAfterProcessing(true)
        assertTrue(SettingsStore(app).deleteAudioAfterProcessing)
    }
}
