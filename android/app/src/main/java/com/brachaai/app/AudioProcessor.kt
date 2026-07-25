package com.brachaai.app

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

class AudioProcessor(
    private val openAiApiKey: String,
    private val cacheDir: File,
    private val authStore: AuthStore,
    private val pendingStore: PendingUploadStore,
    private val callerLookup: CallerLookup
) {

    private val whisperClient = WhisperApiClient(openAiApiKey)
    private val client = OkHttpClient()

    /** Outcome of an upload attempt, so callers can tell "retry later" from "gone". */
    private sealed class UploadResult {
        object Success : UploadResult()
        object Unauthenticated : UploadResult()
        object Transient : UploadResult()
    }

    suspend fun processAndSendToBackend(audioFile: File) {
        withContext(Dispatchers.IO) {
            try {
                println("1. Starting processing for: ${audioFile.name}")

                val parsedInfo = parseFilename(audioFile.name)
                println("2. Parsed Info - Name: ${parsedInfo.contactName}, Date: ${parsedInfo.date}")

                println("3. Converting audio to true MP3 format...")
                val mp3File = convertToMp3(audioFile)

                if (mp3File == null) {
                    println("ERROR: Audio conversion failed. Stopping process.")
                    return@withContext
                }

                println("4. Uploading MP3 to Whisper...")
                val transcriptText = whisperClient.transcribeAudio(mp3File)
                println("5. Whisper Transcript: $transcriptText")

                println("6. Correcting spelling and grammar...")
                val correctedTranscript = whisperClient.correctSpelling(transcriptText)
                println("7. Corrected Transcript: $correctedTranscript")

                val callerNumber = parsedInfo.toEpochMillis()?.let { callerLookup.findNumberNear(it) }
                println("8. Caller number: ${callerNumber ?: "unavailable"}")

                val payload = PendingUpload(
                    contactName = parsedInfo.contactName,
                    date = "${parsedInfo.date}_${parsedInfo.time}",
                    callerNumber = callerNumber,
                    transcript = correctedTranscript
                )

                println("9. Sending data to backend...")
                when (attemptUpload(payload)) {
                    is UploadResult.Success -> println("SUCCESS! Data sent to backend")
                    else -> {
                        println("Upload failed; queued for retry")
                        pendingStore.enqueue(payload)
                    }
                }

                if (mp3File.exists()) {
                    mp3File.delete()
                }

            } catch (e: Exception) {
                println("Error during processing: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    /** Retries everything queued. Stops early on 401 — waiting for a fresh login. */
    suspend fun flushPending() {
        withContext(Dispatchers.IO) {
            val queued = pendingStore.peekAll()
            if (queued.isEmpty()) return@withContext

            println("Flushing ${queued.size} pending upload(s)")
            for ((file, payload) in queued) {
                when (attemptUpload(payload)) {
                    is UploadResult.Success -> {
                        pendingStore.remove(file)
                        println("Flushed ${file.name}")
                    }
                    is UploadResult.Unauthenticated -> {
                        println("Still unauthenticated; keeping ${pendingStore.size()} queued")
                        return@withContext
                    }
                    is UploadResult.Transient -> {
                        println("Transient failure on ${file.name}; will retry later")
                        return@withContext
                    }
                }
            }
        }
    }

    private fun attemptUpload(payload: PendingUpload): UploadResult {
        val token = authStore.getToken()
        if (token.isNullOrBlank()) {
            println("No auth token stored; cannot upload")
            return UploadResult.Unauthenticated
        }

        val jsonBody = JSONObject().apply {
            put("contactName", payload.contactName)
            put("date", payload.date)
            put("transcript", payload.transcript)
            put("callerNumber", payload.callerNumber ?: JSONObject.NULL)
        }

        val request = Request.Builder()
            .url("http://193.106.55.154:3000/api/calls")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadResult.Success
                    response.code == 401 -> {
                        println("Backend rejected the token; clearing it")
                        authStore.clear()
                        UploadResult.Unauthenticated
                    }
                    else -> {
                        println("FAILED to send to backend. Code: ${response.code}")
                        UploadResult.Transient
                    }
                }
            }
        } catch (e: Exception) {
            println("FAILED to connect to backend: ${e.message}")
            UploadResult.Transient
        }
    }

    /**
     * Uses FFmpeg to convert ANY audio file into a standard 128k MP3.
     */
    private fun convertToMp3(inputFile: File): File? {
        val outputFile = File(this.cacheDir, "${inputFile.nameWithoutExtension}.mp3")

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val command = "-i \"${inputFile.absolutePath}\" -vn -ar 44100 -ac 2 -b:a 128k \"${outputFile.absolutePath}\""

        val session = FFmpegKit.execute(command)

        return if (ReturnCode.isSuccess(session.returnCode)) {
            println("Conversion Success! Saved to: ${outputFile.name}")
            outputFile
        } else {
            println("Conversion Failed! FFmpeg logs: ${session.failStackTrace}")
            null
        }
    }
}
