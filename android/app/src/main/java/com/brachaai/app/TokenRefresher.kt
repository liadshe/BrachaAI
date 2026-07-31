package com.brachaai.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Trades the stored refresh token for a fresh access token.
 *
 * [CallMonitorService] uploads transcripts long after the app was last opened — hours or
 * days — so it cannot depend on the WebView being around to hand it a new token. Without
 * this, an expired access token means every upload 401s into the pending queue until the
 * user next opens the app.
 *
 * Native holds its own token pair, provisioned by the web app via `/auth/device-token`.
 * Refresh tokens rotate per client, so this never touches the web session's pair.
 */
class TokenRefresher(
    private val tokenStore: TokenStore,
    private val baseUrl: String = BackendConfig.BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /**
     * @param staleToken the access token the caller just had rejected.
     * @return a usable access token, or null if the session is over or the attempt failed.
     *
     * Short-circuits when the store already holds a token newer than [staleToken]. Refresh
     * tokens are single-use: parallel callers flushing the pending queue would otherwise
     * rotate each other out and log the device out spuriously.
     *
     * Synchronized on [refreshLock] — a lock shared by every [TokenRefresher] instance in
     * the process, not just this one. Every instance ultimately wraps the same underlying
     * [tokenStore] and redeems tokens against the same backend, so the single-flight
     * guarantee has to be process-wide: two different `TokenRefresher` objects (e.g. one
     * built fresh by [CallOverlayService] each time a briefing card shows, another shared by
     * [CallMonitorService]) can race to redeem the same refresh token just as easily as two
     * callers sharing one instance can. Locking on `this` would let those two objects hold
     * two different monitors and both proceed at once.
     */
    fun refresh(staleToken: String?): String? = synchronized(refreshLock) {
        val current = tokenStore.getToken()
        if (!current.isNullOrBlank() && current != staleToken) {
            Log.d(TAG, "Another caller already refreshed; reusing the stored token")
            return@synchronized current
        }

        val refreshToken = tokenStore.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No refresh token stored; cannot refresh")
            return@synchronized null
        }

        try {
            val body = JSONObject()
                .put("refreshToken", refreshToken)
                .put("client", CLIENT)
                .toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$baseUrl/api/auth/refresh")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val json = JSONObject(response.body?.string().orEmpty())
                        val access = json.optString("token")
                        val rotated = json.optString("refreshToken")

                        if (access.isBlank() || rotated.isBlank()) {
                            // Storing half a pair would leave a token that cannot renew.
                            Log.e(TAG, "Refresh response was missing a token")
                            null
                        } else {
                            tokenStore.setTokens(access, rotated)
                            Log.d(TAG, "Access token refreshed")
                            access
                        }
                    }
                    response.code == 401 || response.code == 403 -> {
                        // The refresh token itself is dead. This is a real logout.
                        Log.w(TAG, "Refresh token rejected (${response.code}); clearing session")
                        tokenStore.clear()
                        null
                    }
                    else -> {
                        // A 5xx or anything else is not evidence the session ended. Keep the
                        // tokens and let the pending queue retry — clearing here would log
                        // the device out because the backend hiccuped.
                        Log.e(TAG, "Refresh failed with HTTP ${response.code}; keeping tokens")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            // Network failure. Same reasoning as a 5xx: not proof the session is over.
            Log.e(TAG, "Refresh request failed; keeping tokens", e)
            null
        }
    }

    companion object {
        private const val TAG = "TokenRefresher"
        private const val CLIENT = "native"

        // Shared across every instance in the process — see the kdoc on refresh(). A plain
        // Any() rather than the TokenStore itself: TokenStore is an interface implemented by
        // whatever the caller passes in, and locking on an object we don't own/control would
        // be fragile (e.g. if some future TokenStore implementation happened to synchronize
        // on itself elsewhere).
        private val refreshLock = Any()
    }
}
