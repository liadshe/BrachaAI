package com.brachaai.app

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WhisperApiClient(private val apiKey: String) {

    // Give the app plenty of time to upload the audio and wait for the AI
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun transcribeAudio(audioFile: File): String {

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
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // If it fails, print the exact reason from OpenAI
                val errorBody = response.body?.string()
                println("OPENAI ERROR DETAILS: $errorBody")
                throw IOException("Unexpected code $response")
            }

            val responseData = response.body?.string() ?: throw IOException("Empty response")

            // 4. Parse the JSON to get just the transcript string
            val jsonObject = JSONObject(responseData)
            return jsonObject.getString("text")
        }
    }
}