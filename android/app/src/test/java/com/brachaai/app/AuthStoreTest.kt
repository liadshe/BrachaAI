package com.brachaai.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers [AuthStore.hasEverAuthenticated] only. The token itself lives in
 * EncryptedSharedPreferences, which needs the Android keystore and therefore cannot be
 * driven on the JVM — that half is covered by the on-device verification matrix. The login
 * history flag is deliberately in plain prefs, so it is testable here, which matters
 * because it decides whether a call recording gets destroyed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthStoreTest {

    private fun app() = RuntimeEnvironment.getApplication()

    private fun seedLoginHistory(value: Boolean) {
        app().getSharedPreferences(AuthStore.HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AuthStore.KEY_EVER_AUTHENTICATED, value)
            .commit()
    }

    @Test
    fun defaultsToNeverAuthenticatedOnAFreshInstall() {
        // The direction that keeps recordings: a fresh install must not be mistaken for
        // one whose queue will eventually be flushed by a login.
        assertFalse(AuthStore(app()).hasEverAuthenticated())
    }

    @Test
    fun reportsAuthenticatedOnceALoginHasBeenRecorded() {
        seedLoginHistory(true)
        assertTrue(AuthStore(app()).hasEverAuthenticated())
    }

    @Test
    fun clearDoesNotUndoTheLoginHistory() {
        seedLoginHistory(true)
        val store = AuthStore(app())

        store.clear()   // what a 401 does

        assertTrue(
            "an expired token does not mean the user never logs in",
            store.hasEverAuthenticated()
        )
    }
}
