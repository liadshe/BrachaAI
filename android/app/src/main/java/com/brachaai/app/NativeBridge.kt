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

    @JavascriptInterface
    fun setAuth(token: String?) {
        if (token.isNullOrBlank()) {
            authStore.clear()
            Log.d(TAG, "setAuth called with empty token; cleared")
            return
        }
        authStore.setToken(token)
        Log.d(TAG, "Auth token stored from WebView")
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
