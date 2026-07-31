package com.brachaai.app

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads briefings from the backend.
 *
 * Mirrors the upload path's auth handling: send the stored access token, and on a 401 ask
 * [TokenRefresher] once for a fresh one and retry exactly once. One retry, never a loop — a
 * refreshed token that is also rejected means the session is genuinely over.
 *
 * Every failure returns null. Callers must distinguish that from an empty list, which is the
 * legitimate answer for a user with no contacts.
 */
class BriefingClient(
    private val tokenStore: TokenStore,
    private val tokenRefresher: TokenRefresher,
    private val baseUrl: String = BackendConfig.BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    /** Every contact for the signed-in user. Null on any failure. */
    fun fetchAll(): List<Briefing>? {
        val body = get("$baseUrl/api/briefings") ?: return null
        return try {
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::briefingFromBackendJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not parse the briefing list", e)
            null
        }
    }

    /** One contact, for the live refresh while a card is on screen. Null on any failure. */
    fun fetchOne(contactId: String): Briefing? {
        val body = get("$baseUrl/api/briefings/$contactId") ?: return null
        return try {
            briefingFromBackendJson(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "Could not parse the briefing for $contactId", e)
            null
        }
    }

    private fun get(url: String): String? {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "No access token stored; skipping briefing fetch")
            return null
        }

        val first = execute(url, token) ?: return null
        if (first.code != 401) {
            return first.bodyOrNull()
        }

        val refreshed = tokenRefresher.refresh(token)
        if (refreshed.isNullOrBlank()) {
            Log.w(TAG, "Briefing fetch unauthorized and refresh failed")
            return null
        }

        val second = execute(url, refreshed) ?: return null
        return second.bodyOrNull()
    }

    private class Result(val code: Int, val body: String?) {
        fun bodyOrNull(): String? =
            if (code in 200..299) body
            else {
                Log.w(TAG, "Briefing fetch failed with HTTP $code")
                null
            }
    }

    private fun execute(url: String, token: String): Result? = try {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            Result(response.code, response.body?.string())
        }
    } catch (e: Exception) {
        Log.e(TAG, "Briefing request failed", e)
        null
    }

    companion object {
        private const val TAG = "BriefingClient"
    }
}
