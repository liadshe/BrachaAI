package com.brachaai.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WhisperApiClient(private val apiKey: String) {

    companion object {
        private const val TAG = "WhisperApiClient"
    }

    // Give the app plenty of time to upload the audio and wait for the AI
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun transcribeAudio(audioFile: File): String {
        Log.d(TAG, "Starting transcription for file: ${audioFile.name}")

        // 1. Package the file as a true MP3
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // We use "audio.mp3" to hide the Hebrew filename from the internet headers
            .addFormDataPart("file", "audio.mp3", audioFile.asRequestBody("audio/mpeg".toMediaTypeOrNull()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "he") // Force it to understand Hebrew
            .build()

        // 2. Build the request to OpenAI
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        // 3. Send it and read the response
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "OpenAI Transcription Error: $errorBody")
                    throw IOException("Unexpected code $response")
                }

                val responseData = response.body?.string() ?: throw IOException("Empty response")
                val jsonObject = JSONObject(responseData)
                val text = jsonObject.getString("text")
                Log.d(TAG, "Transcription successful")
                return text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during transcription: ${e.message}")
            throw e
        }
    }

    /**
     * Sends the transcript to GPT-4o to fix spelling and grammar errors.
     */
    fun correctSpelling(transcript: String): String {
        Log.d(TAG, "Starting spelling correction with GPT-4o")
        
        val json = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "אתה מומחה לדקדוק וכתיב בעברית. ערוך את הטקסט הבא שהתקבל מתמלול שיחה, תקן שגיאות כתיב ודקדוק, אך שמור על המשמעות המקורית ועל סגנון הדיבור. החזר אך ורק את הטקסט המתוקן ללא הערות נוספות.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", transcript)
                })
            })
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "OpenAI GPT Error: $errorBody")
                    throw IOException("Unexpected code $response")
                }
                val data = response.body?.string() ?: ""
                val jsonResponse = JSONObject(data)
                val correctedText = jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Log.d(TAG, "Spelling correction successful")
                return correctedText
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during spelling correction: ${e.message}")
            throw e
        }
    }
}
