package com.brachaai.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Sole owner of auth token persistence. The token originates in the WebView's
 * localStorage and is pushed here via NativeBridge.
 */
class AuthStore(context: Context) : TokenStore {

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

    override fun getToken(): String? = try {
        prefs.getString(KEY_TOKEN, null)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read auth token", e)
        null
    }

    override fun getRefreshToken(): String? = try {
        prefs.getString(KEY_REFRESH_TOKEN, null)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read refresh token", e)
        null
    }

    /**
     * Written as one edit: a half-stored pair — a fresh access token beside a stale
     * refresh token — would survive the next 401 and then fail to refresh, logging the
     * user out with no way back until the next foreground login.
     */
    override fun setTokens(accessToken: String, refreshToken: String) {
        try {
            prefs.edit()
                .putString(KEY_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .apply()
            markEverAuthenticated()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist auth tokens", e)
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
    override fun hasEverAuthenticated(): Boolean = try {
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

    override fun clear() {
        try {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear auth tokens", e)
        }
    }

    /**
     * Durable mirror of the *web* session.
     *
     * The WebView runs from a `file://` origin, and its localStorage does not survive the
     * app process being killed — verified on device: backgrounding keeps the session,
     * swiping the app away loses it. Android exposes no API to force a localStorage flush,
     * so the web app mirrors its session here on every write and restores from it at boot.
     *
     * Kept in separate keys from [KEY_TOKEN]/[KEY_REFRESH_TOKEN]: those are native's *own*
     * pair for background uploads, which rotates independently of the web session's. Mixing
     * them would have the two clients invalidate each other's refresh token.
     */
    fun setWebSession(accessToken: String, refreshToken: String, user: String) {
        try {
            prefs.edit()
                .putString(KEY_WEB_TOKEN, accessToken)
                .putString(KEY_WEB_REFRESH_TOKEN, refreshToken)
                .putString(KEY_WEB_USER, user)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist the web session mirror", e)
        }
    }

    /**
     * The mirrored web session as a JSON object, or `null` if there is nothing usable.
     *
     * Returns JSON rather than a data class because the sole consumer is JavaScript across
     * the WebView bridge, which can only receive primitives.
     */
    fun getWebSessionJson(): String? = try {
        val token = prefs.getString(KEY_WEB_TOKEN, null)
        val refreshToken = prefs.getString(KEY_WEB_REFRESH_TOKEN, null)

        // Both halves or nothing: handing back an access token with no refresh token would
        // restore a session that cannot renew itself.
        if (token.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            null
        } else {
            JSONObject()
                .put("token", token)
                .put("refreshToken", refreshToken)
                .put("user", prefs.getString(KEY_WEB_USER, "") ?: "")
                .toString()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read the web session mirror", e)
        null
    }

    fun clearWebSession() {
        try {
            prefs.edit()
                .remove(KEY_WEB_TOKEN)
                .remove(KEY_WEB_REFRESH_TOKEN)
                .remove(KEY_WEB_USER)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear the web session mirror", e)
        }
    }

    companion object {
        private const val TAG = "AuthStore"
        private const val PREFS_NAME = "bracha_auth"
        private const val KEY_TOKEN = "jwt"
        private const val KEY_REFRESH_TOKEN = "refresh_jwt"
        private const val KEY_WEB_TOKEN = "web_jwt"
        private const val KEY_WEB_REFRESH_TOKEN = "web_refresh_jwt"
        private const val KEY_WEB_USER = "web_user"

        /**
         * `internal` rather than `private` only so unit tests can seed the login history.
         * [setTokens] cannot be exercised on the JVM — EncryptedSharedPreferences needs the
         * Android keystore, which Robolectric does not provide — so tests write this pref
         * directly instead of duplicating the name and key.
         */
        internal const val HISTORY_PREFS_NAME = "bracha_auth_history"
        internal const val KEY_EVER_AUTHENTICATED = "has_ever_authenticated"
    }
}
