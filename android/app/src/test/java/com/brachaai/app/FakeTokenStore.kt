package com.brachaai.app

/**
 * In-memory [TokenStore] for JVM tests. The real [AuthStore] cannot be exercised here:
 * EncryptedSharedPreferences needs the Android keystore, which Robolectric does not provide.
 */
class FakeTokenStore(
    private var accessToken: String? = null,
    private var refreshToken: String? = null,
    private var everAuthenticated: Boolean = false
) : TokenStore {

    var clearCount = 0
        private set

    override fun getToken(): String? = accessToken

    override fun getRefreshToken(): String? = refreshToken

    override fun setTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.everAuthenticated = true
    }

    override fun clear() {
        accessToken = null
        refreshToken = null
        clearCount++
    }

    override fun hasEverAuthenticated(): Boolean = everAuthenticated
}
