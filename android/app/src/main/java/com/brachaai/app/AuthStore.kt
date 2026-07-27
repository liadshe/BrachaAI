package com.brachaai.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sole owner of auth token persistence. The token originates in the WebView's
 * localStorage and is pushed here via AuthBridge.
 */
class AuthStore(context: Context) {

    private val appContext = context.applicationContext

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
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist auth token", e)
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
    }
}
