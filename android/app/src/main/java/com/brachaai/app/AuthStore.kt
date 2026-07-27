package com.brachaai.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sole owner of auth token persistence. The token originates in the WebView's
 * localStorage and is pushed here via NativeBridge.
 */
class AuthStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Separate, *unencrypted* prefs holding only "has this device ever held a token".
     *
     * Not a secret — it is one bit saying a login once happened. Kept out of
     * EncryptedSharedPreferences deliberately: [getToken] swallows read failures and
     * returns null, which is fine for a token that can be re-fetched but wrong for a flag
     * that decides whether a call recording gets destroyed (same reasoning as
     * [SettingsStore]).
     */
    private val historyPrefs: SharedPreferences =
        appContext.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(): String? = try {
        prefs.getString(KEY_TOKEN, null)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read auth token", e)
        null
    }

    fun setToken(token: String) {
        try {
            prefs.edit().putString(KEY_TOKEN, token).apply()
            markEverAuthenticated()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist auth token", e)
        }
    }

    /**
     * True if a token has ever been stored on this device — i.e. the user has logged in at
     * least once, so "it will be retried after login" is a promise that can plausibly be
     * kept.
     *
     * Deliberately NOT cleared by [clear]: a 401 wipes the current token but does not undo
     * the fact that this user logs in. Callers use this to decide whether a queued-because-
     * unauthenticated transcript is safe enough to delete its recording over, so a read
     * failure returns `false` — the direction that keeps the recording.
     */
    fun hasEverAuthenticated(): Boolean = try {
        // The `|| token present` arm covers upgrade: users who logged in before this flag
        // existed have a token but no flag, and must not be treated as never-logged-in.
        historyPrefs.getBoolean(KEY_EVER_AUTHENTICATED, false) || !getToken().isNullOrBlank()
    } catch (e: Exception) {
        Log.e(TAG, "Could not read auth history; assuming never authenticated", e)
        false
    }

    private fun markEverAuthenticated() {
        try {
            historyPrefs.edit().putBoolean(KEY_EVER_AUTHENTICATED, true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not record that a token was stored", e)
        }
    }

    fun clear() {
        try {
            prefs.edit().remove(KEY_TOKEN).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear auth token", e)
        }
    }

    companion object {
        private const val TAG = "AuthStore"
        private const val PREFS_NAME = "bracha_auth"
        private const val KEY_TOKEN = "jwt"

        /**
         * `internal` rather than `private` only so unit tests can seed the login history.
         * [setToken] cannot be exercised on the JVM — EncryptedSharedPreferences needs the
         * Android keystore, which Robolectric does not provide — so tests write this pref
         * directly instead of duplicating the name and key.
         */
        internal const val HISTORY_PREFS_NAME = "bracha_auth_history"
        internal const val KEY_EVER_AUTHENTICATED = "has_ever_authenticated"
    }
}
