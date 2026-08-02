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

    // Privacy: cached briefings are the signed-out user's call summaries and open tasks.
    // Nothing downstream would ever overwrite them -- BriefingClient returns null with no
    // token and BriefingSync keeps the previous snapshot on a null fetch -- so if clearAuth
    // doesn't drop them here, the next person to hold the phone sees them over the ringing
    // screen, permanently.
    @Test
    fun clearAuthDropsTheCachedBriefings() {
        val app = RuntimeEnvironment.getApplication()
        val store = BriefingStore.default(app.filesDir)
        store.replaceAll(
            listOf(
                Briefing(
                    contactId = "c1",
                    name = "David Cohen",
                    phone = "+972501234567",
                    lastCallSummary = "Promised to send price quote.",
                    openTasks = listOf(BriefingTask("t1", "Send contract by Tuesday", "HIGH")),
                    openTaskCount = 1,
                )
            )
        )
        assertTrue(store.readAll().isNotEmpty())

        newBridge().clearAuth()

        assertTrue(BriefingStore.default(app.filesDir).readAll().isEmpty())
    }
}
