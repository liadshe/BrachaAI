package com.brachaai.app

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Bridge exposed to the WebView as `BrachaNative`.
 *
 * Auth: the web app owns login, so it hands the JWT to native code here; the
 * background recorder has no other way to learn who is logged in.
 *
 * Settings: device-local settings that the background recorder must be able to read
 * offline are owned natively and edited from the web Settings page through here.
 */
class NativeBridge(
    context: Context,
    private val onAuthenticated: () -> Unit
) {
    private val appContext = context.applicationContext
    private val authStore = AuthStore(appContext)
    private val settingsStore = SettingsStore(appContext)

    /**
     * Receives the *native* token pair, minted by the web app via /auth/device-token.
     * Native deliberately holds its own pair rather than the web session's: refresh tokens
     * rotate per client, so sharing one would have the two clients invalidate each other.
     */
    @JavascriptInterface
    fun setAuth(token: String?, refreshToken: String?) {
        if (token.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            authStore.clear()
            Log.d(TAG, "setAuth called with an incomplete pair; cleared")
            return
        }
        authStore.setTokens(token, refreshToken)
        Log.d(TAG, "Auth tokens stored from WebView")
        onAuthenticated()
    }

    @JavascriptInterface
    fun clearAuth() {
        authStore.clear()
        authStore.clearWebSession()
        Log.d(TAG, "Auth tokens and web session mirror cleared")
    }

    /**
     * Durable mirror of the web session.
     *
     * The WebView's localStorage lives on a `file://` origin and does not survive the app
     * process being killed, so the web app mirrors its session here on every write and
     * restores from it at boot. Without this, logging in and then swiping the app away
     * logs the user straight back out — the original bug.
     */
    @JavascriptInterface
    fun setWebSession(token: String?, refreshToken: String?, user: String?) {
        if (token.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            authStore.clearWebSession()
            Log.d(TAG, "setWebSession called with an incomplete pair; mirror cleared")
            return
        }
        authStore.setWebSession(token, refreshToken, user ?: "")
        Log.d(TAG, "Web session mirrored to durable storage")
    }

    /** Returns the mirrored session as JSON, or an empty string when there is none. */
    @JavascriptInterface
    fun getWebSession(): String = authStore.getWebSessionJson() ?: ""

    @JavascriptInterface
    fun clearWebSession() {
        authStore.clearWebSession()
        Log.d(TAG, "Web session mirror cleared")
    }

    @JavascriptInterface
    fun getDeleteAudioAfterProcessing(): Boolean = settingsStore.deleteAudioAfterProcessing

    @JavascriptInterface
    fun setDeleteAudioAfterProcessing(enabled: Boolean) {
        settingsStore.deleteAudioAfterProcessing = enabled
        Log.d(TAG, "deleteAudioAfterProcessing set to $enabled")
    }

    companion object {
        private const val TAG = "NativeBridge"
        const val JS_NAME = "BrachaNative"
    }
}
