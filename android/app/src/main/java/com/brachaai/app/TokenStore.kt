package com.brachaai.app

/**
 * The token surface the uploader and refresher depend on.
 *
 * Exists as an interface purely for testability: the real implementation is backed by
 * EncryptedSharedPreferences, which needs the Android keystore and therefore cannot run
 * under Robolectric. Tests substitute an in-memory fake.
 */
interface TokenStore {
    fun getToken(): String?
    fun getRefreshToken(): String?
    fun setTokens(accessToken: String, refreshToken: String)
    fun clear()
    fun hasEverAuthenticated(): Boolean
}
