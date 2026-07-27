package com.brachaai.app

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Bridge exposed to the WebView as `BrachaNative`. The web app owns login, so it
 * hands the JWT to native code here; the background recorder has no other way to
 * learn who is logged in.
 */
class AuthBridge(
    context: Context,
    private val onAuthenticated: () -> Unit
) {
    private val authStore = AuthStore(context.applicationContext)

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

    companion object {
        private const val TAG = "AuthBridge"
        const val JS_NAME = "BrachaNative"
    }
}
