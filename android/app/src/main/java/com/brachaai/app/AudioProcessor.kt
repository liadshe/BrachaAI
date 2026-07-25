package com.brachaai.app

import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class AudioProcessor(private val context: Context, private val openAiApiKey: String, private val cacheDir: File) {

    private val whisperClient = WhisperApiClient(openAiApiKey)
    private val TAG = "BrachaAI_Processor"

    suspend fun processAndSendToBackend(audioFile: File) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "1. Processing file: ${audioFile.name}")

                val parsedInfo = try {
                    parseFilename(audioFile.name)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse filename: ${audioFile.name}")
                    return@withContext
                }
                
                // Try to get details from Call Log
                val callLogInfo = getCallLogDetails(parsedInfo) ?: getLatestCallLogEntry()
                
                val phoneNumber = callLogInfo?.number ?: "Unknown"
                val callType = callLogInfo?.type ?: "UNKNOWN"
                val finalName = if (parsedInfo.contactName.isEmpty() || parsedInfo.contactName == "Unknown") {
                    callLogInfo?.name ?: parsedInfo.contactName
                } else {
                    parsedInfo.contactName
                }
                
                Log.d(TAG, "2. Metadata - Name: $finalName, Phone: $phoneNumber, Type: $callType")

                Log.d(TAG, "3. Converting to MP3...")
                val mp3File = convertToMp3(audioFile) ?: return@withContext

                Log.d(TAG, "4. Transcribing...")
                val rawTranscript = whisperClient.transcribeAudio(mp3File)

                Log.d(TAG, "5. Cleaning Transcript...")
                val cleanTranscript = whisperClient.correctSpelling(rawTranscript)

                Log.d(TAG, "6. Sending to Backend...")
                sendDataToNodeServer(finalName, phoneNumber, callType, parsedInfo, cleanTranscript)

                mp3File.delete()
                Log.d(TAG, "7. Process Complete!")

            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL ERROR: ${e.message}", e)
                throw e
            }
        }
    }

    private fun getCallLogDetails(parsedInfo: ParsedFile): CallLogInfo? {
        try {
            val sdf = SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault())
            val dateObj = sdf.parse("${parsedInfo.date}_${parsedInfo.time}")
            val timestamp = dateObj?.time ?: System.currentTimeMillis()

            val window = 10 * 60 * 1000 // 10 minutes
            val selection = "${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.DATE} < ?"
            val selectionArgs = arrayOf((timestamp - window).toString(), (timestamp + window).toString())

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val typeInt = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    
                    val type = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        else -> "UNKNOWN"
                    }
                    return CallLogInfo(number, type, name)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching call log: ${e.message}")
        }
        return null
    }

    private fun getLatestCallLogEntry(): CallLogInfo? {
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val typeInt = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val type = if (typeInt == CallLog.Calls.INCOMING_TYPE) "INCOMING" else "OUTGOING"
                    return CallLogInfo(number, type, name)
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun convertToMp3(inputFile: File): File? {
        val outputFile = File(cacheDir, "${inputFile.nameWithoutExtension}.mp3")
        if (outputFile.exists()) outputFile.delete()
        val command = "-i \"${inputFile.absolutePath}\" -vn -ar 44100 -ac 2 -b:a 128k \"${outputFile.absolutePath}\""
        val session = FFmpegKit.execute(command)
        return if (ReturnCode.isSuccess(session.returnCode)) outputFile else null
    }

    private fun sendDataToNodeServer(name: String, phone: String, type: String, parsedInfo: ParsedFile, transcript: String) {
        val client = OkHttpClient()
        val jsonBody = JSONObject().apply {
            put("contactName", name)
            put("phoneNumber", phone)
            put("callType", type)
            put("date", "${parsedInfo.date}_${parsedInfo.time}")
            put("transcript", transcript)
        }

        val jsonString = jsonBody.toString()
        Log.d(TAG, "Payload being sent: $jsonString")

        val requestBody = jsonString.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("http://193.106.55.154:3000/api/calls")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Log.d(TAG, "Backend SUCCESS: ${response.code}")
            } else {
                val responseBody = response.body?.string() ?: "No response body"
                Log.e(TAG, "Backend FAILED: ${response.code} - Error: $responseBody")
            }
        }
    }

    private data class CallLogInfo(val number: String, val type: String, val name: String?)
}
