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
        Log.d(TAG, "Auth token cleared")
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
