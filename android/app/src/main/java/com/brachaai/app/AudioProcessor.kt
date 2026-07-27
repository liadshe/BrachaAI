package com.brachaai.app

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
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

    // Explicit timeouts: the backend persists the call AND runs an OpenAI analysis
    // round-trip before responding, which routinely exceeds OkHttp's stock 10s default.
    // A timeout here must not be mistaken for the backend never having received the call.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Serializes flushPending() so two triggers landing close together (e.g. onCreate's
    // startup flush and an ACTION_FLUSH intent delivered moments later by the same
    // startForegroundService() call) can't both walk the same peekAll() snapshot and
    // double-POST every queued entry.
    private val flushMutex = Mutex()

    /** Outcome of an upload attempt, so callers can tell "retry later" from "gone". */
    private sealed class UploadResult {
        object Success : UploadResult()
        object Unauthenticated : UploadResult()
        object Transient : UploadResult()
        /** Backend permanently rejected the payload (e.g. 400/413/422). Retrying won't help. */
        object Rejected : UploadResult()
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

                if (correctedTranscript.isBlank()) {
                    // A GPT-4o refusal or filtered completion can return "" without throwing.
                    // Uploading it would get a 400 from the backend (transcript required),
                    // which is non-retryable and gets permanently deleted. Stop here instead
                    // so nothing is ever uploaded or queued, and surface it via the existing
                    // error-notification path (handleNewFile's catch in CallMonitorService).
                    Log.e(TAG, "Corrected transcript is blank for ${audioFile.name}; not uploading or queuing")
                    throw IllegalStateException("Transcript came back blank for ${audioFile.name}; not uploaded")
                }

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
                    is UploadResult.Success -> {
                        println("SUCCESS! Data sent to backend")
                        // Network and token both just proved good — this is the best
                        // possible moment to also retry anything sitting in the queue,
                        // since a background recorder may never be reopened by the user.
                        flushPending()
                    }
                    is UploadResult.Rejected -> {
                        Log.e(TAG, "Backend permanently rejected upload for ${audioFile.name}; dropping, will not retry")
                    }
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

    /** Retries everything queued. Stops early on Unauthenticated/Transient — waiting for a fresh login or network. */
    suspend fun flushPending() {
        flushMutex.withLock {
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
                        is UploadResult.Rejected -> {
                            // Permanent failure (e.g. empty-transcript 400). Must not sit at
                            // the head of the queue blocking every entry behind it.
                            pendingStore.remove(file)
                            Log.e(TAG, "Backend permanently rejected queued upload ${file.name}; dropping, will not retry")
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
    }

    private fun attemptUpload(payload: PendingUpload): UploadResult {
        val token = authStore.getToken()
        if (token.isNullOrBlank()) {
            println("No auth token stored; cannot upload")
            return UploadResult.Unauthenticated
        }

        return try {
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

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadResult.Success
                    response.code == 401 -> {
                        // Compare-and-clear: only wipe the token if it's still the one we
                        // just sent. A login that raced this request may already have
                        // stored a fresh token — clearing unconditionally would wipe that
                        // instead of the expired one, right on the post-login flush path.
                        if (authStore.getToken() == token) {
                            println("Backend rejected the token; clearing it")
                            authStore.clear()
                        } else {
                            println("Backend rejected a stale token; a newer token is already stored, leaving it")
                        }
                        UploadResult.Unauthenticated
                    }
                    response.code in NON_RETRYABLE_CODES -> {
                        Log.e(TAG, "Backend rejected upload with non-retryable HTTP ${response.code}: ${payload.contactName}")
                        UploadResult.Rejected
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

    companion object {
        private const val TAG = "AudioProcessor"
        /**
         * HTTP statuses the backend uses for permanently-invalid payloads — retrying never
         * helps. 413 is deliberately NOT included: it reflects server body-size configuration,
         * not a permanent property of the payload, so a 413 should be retried (e.g. after the
         * backend limit is raised) rather than treated as a reason to destroy the transcript.
         */
        private val NON_RETRYABLE_CODES = setOf(400, 422)
    }
}
