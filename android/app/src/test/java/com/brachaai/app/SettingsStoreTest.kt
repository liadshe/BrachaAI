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
class SettingsStoreTest {

    // A fresh instance each time, so the assertions go through SharedPreferences
    // rather than through an in-memory field on one object.
    private fun newStore() = SettingsStore(RuntimeEnvironment.getApplication())

    @Test
    fun deleteAudioDefaultsToTrueBeforeAnyWrite() {
        assertTrue(newStore().deleteAudioAfterProcessing)
    }

    @Test
    fun deleteAudioPersistsFalse() {
        newStore().deleteAudioAfterProcessing = false
        assertFalse(newStore().deleteAudioAfterProcessing)
    }

    @Test
    fun deleteAudioPersistsBackToTrue() {
        newStore().deleteAudioAfterProcessing = false
        newStore().deleteAudioAfterProcessing = true
        assertTrue(newStore().deleteAudioAfterProcessing)
    }
}
