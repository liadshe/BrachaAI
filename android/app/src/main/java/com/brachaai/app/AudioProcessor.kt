package com.brachaai.app

import android.media.MediaMetadataRetriever
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
    private val authStore: TokenStore,
    private val pendingStore: PendingUploadStore,
    private val callerLookup: CallerLookup,
    private val settingsStore: SettingsStore,
    private val tokenRefresher: TokenRefresher,
    private val baseUrl: String = BackendConfig.BASE_URL,
    private val audioDuration: AudioDuration = AudioDuration(),
    /**
     * Fallback source of call direction when the call log is unreadable. Nullable and
     * defaulted so the existing construction sites and tests that never cared about
     * direction keep compiling; a null store simply means the fallback is unavailable and
     * the direction stays unknown, which is now a value the whole stack can carry.
     */
    private val callDirectionStore: CallDirectionStore? = null
) : RecordingProcessor {

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

    /**
     * Outcome of an upload attempt, so callers can tell "retry later" from "gone".
     *
     * `internal` rather than `private` so the unit tests can assert on it — the Android
     * Gradle plugin compiles the test source set as a friend module, so this stays out of
     * the app's public surface.
     */
    internal sealed class UploadResult {
        object Success : UploadResult()
        object Unauthenticated : UploadResult()
        object Transient : UploadResult()
        /** Backend permanently rejected the payload (e.g. 400/413/422). Retrying won't help. */
        object Rejected : UploadResult()
    }

    /**
     * Result of one attempt, plus the separate question of whether the recording may go.
     *
     * These are two different questions and must not be collapsed. When the backend is
     * unreachable but the transcript is durably queued, the outcome is [ProcessOutcome.Completed]
     * — re-transcribing would spend money and enqueue the same call a second time — while
     * `mayDeleteRecording` can still be false because `queuedTranscriptIsDurable` says the
     * queue is not a trustworthy home for it.
     */
    internal data class PipelineResult(
        val outcome: ProcessOutcome,
        val mayDeleteRecording: Boolean
    )

    /**
     * Runs the full pipeline for one recording and reports how it ended.
     *
     * Never throws. Callers used to have to catch, and a throw meant "recording kept but
     * forgotten forever" — the bug this whole queue exists to fix.
     */
    override suspend fun process(audioFile: File): ProcessOutcome = withContext(Dispatchers.IO) {
        val result = try {
            runPipeline(audioFile)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure processing ${audioFile.name}", e)
            PipelineResult(ProcessOutcome.RetryLater(e.message ?: e.javaClass.simpleName), false)
        }
        applyDeletion(result, audioFile)
        result.outcome
    }

    /**
     * The single gate between a call that failed and a recording that is destroyed.
     *
     * Deletion requires *both* a terminal-success outcome and permission from the caller.
     * The outcome check is not redundant with the flag: it means a future caller that
     * miscomputes `mayDeleteRecording` still cannot destroy an unprocessed recording.
     *
     * `internal` so the unit tests can drive every row of the outcome table directly.
     */
    internal fun applyDeletion(result: PipelineResult, audioFile: File) {
        val terminalSuccess =
            result.outcome is ProcessOutcome.Completed || result.outcome is ProcessOutcome.Skipped
        if (terminalSuccess && result.mayDeleteRecording) {
            deleteOriginalIfEnabled(audioFile)
        } else {
            println("Keeping ${audioFile.name}; outcome=${result.outcome}")
        }
    }

    /**
     * Maps a transcription failure onto an outcome.
     *
     * A [WhisperHttpException] with a permanently-invalid status means this file will never
     * transcribe, no matter how good the connection gets. Anything else — a rate limit, a
     * 5xx, or a connection failure with no status at all — is worth another attempt.
     *
     * 401/403 are deliberately retryable: a bad or expired API key is fixed by shipping a
     * new build, and treating it as permanent would mark every recording stuck on its first
     * attempt with no way back.
     *
     * `internal` for the unit tests.
     */
    internal fun outcomeForWhisperFailure(e: Exception): ProcessOutcome {
        val status = (e as? WhisperHttpException)?.statusCode
        val reason = "transcription failed${status?.let { " (HTTP $it)" } ?: ""}: ${e.message}"
        return if (status != null && status in PERMANENT_TRANSCRIPTION_CODES) {
            ProcessOutcome.GiveUp(reason)
        } else {
            ProcessOutcome.RetryLater(reason)
        }
    }

    // suspend so the Success branch can call flushPending() directly. Wrapping it in
    // runBlocking instead would block an IO dispatcher thread for a whole queue drain.
    private suspend fun runPipeline(audioFile: File): PipelineResult {
        // Recordings under five seconds are deliberately not calls worth transcribing. That
        // is a terminal decision, not a failure, so the recording may go.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs in 1..4999) {
                println("Skipping ${audioFile.name}: duration too short ($durationMs ms)")
                return PipelineResult(ProcessOutcome.Skipped, mayDeleteRecording = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Duration check failed for ${audioFile.name}, proceeding anyway", e)
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }

        var mp3File: File? = null
        try {
            println("1. Starting processing for: ${audioFile.name}")

            val parsedInfo = parseFilename(audioFile.name)
            println("2. Parsed Info - Name: ${parsedInfo.contactName}, Date: ${parsedInfo.date}")

            println("3. Converting audio to true MP3 format...")
            val converted = convertToMp3(audioFile)
            if (converted == null) {
                // FFmpeg can fail on a partially-flushed recording that is fine minutes
                // later, so this is retryable rather than terminal.
                Log.e(TAG, "Audio conversion failed for ${audioFile.name}")
                return PipelineResult(ProcessOutcome.RetryLater("audio conversion failed"), false)
            }
            mp3File = converted

            println("4. Uploading MP3 to Whisper...")
            val correctedTranscript = try {
                val transcriptText = whisperClient.transcribeAudio(converted)
                println("5. Whisper Transcript: $transcriptText")
                println("6. Correcting spelling and grammar...")
                whisperClient.correctSpelling(transcriptText)
            } catch (e: Exception) {
                val outcome = outcomeForWhisperFailure(e)
                Log.e(TAG, "Transcription failed for ${audioFile.name}: $outcome", e)
                return PipelineResult(outcome, false)
            }
            println("7. Corrected Transcript: $correctedTranscript")

            if (correctedTranscript.isBlank()) {
                // A GPT-4o refusal or filtered completion returns "" without throwing.
                // Uploading it would earn a non-retryable 400, so stop here. Retryable
                // because the same audio often corrects fine on a later attempt; the
                // attempt counter stops it from running forever.
                Log.e(TAG, "Corrected transcript is blank for ${audioFile.name}; not uploading or queuing")
                return PipelineResult(ProcessOutcome.RetryLater("corrected transcript came back blank"), false)
            }

            val callStartMillis = parsedInfo.toEpochMillis()
            val callLogMatch = callStartMillis?.let { callerLookup.findNear(it) } ?: CallLogMatch.NONE
            val callerNumber = callLogMatch.number

            // Two sources, in order of authority: the call log wins whenever it is readable,
            // CallDirectionStore is the fallback for when READ_CALL_LOG was denied. When
            // neither knows, this stays null all the way to the UI. Do not add a default.
            val callType = callLogMatch.callType
                ?: callStartMillis?.let { callDirectionStore?.directionNear(it) }
            println("8. Caller number: ${callerNumber ?: "unavailable"}, type: ${callType ?: "unknown"}")

            val callLengthSeconds = callLogMatch.durationSeconds ?: audioDuration.secondsOf(audioFile)
            println("8b. Call length: ${callLengthSeconds?.let { "${it}s" } ?: "unknown"}")

            val payload = PendingUpload(
                contactName = parsedInfo.contactName,
                date = "${parsedInfo.date}_${parsedInfo.time}",
                callerNumber = callerNumber,
                transcript = correctedTranscript,
                callLengthSeconds = callLengthSeconds,
                callType = callType
            )

            println("9. Sending data to backend...")
            return when (val uploadResult = attemptUpload(payload)) {
                is UploadResult.Success -> {
                    println("SUCCESS! Data sent to backend")
                    // Network and token both just proved good — the best possible moment to
                    // also drain the transcript queue.
                    flushPending()
                    PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = true)
                }
                is UploadResult.Rejected -> {
                    // The call never reached the backend and never will, so the recording is
                    // the only copy of it. Kept forever, never retried.
                    Log.e(TAG, "Backend permanently rejected upload for ${audioFile.name}; keeping the recording")
                    PipelineResult(ProcessOutcome.GiveUp("backend rejected the payload"), false)
                }
                is UploadResult.Unauthenticated, is UploadResult.Transient -> {
                    println("Upload failed; queueing transcript for retry")
                    val queued = pendingStore.enqueue(payload)
                    if (!queued) {
                        // Nothing was persisted anywhere, so the audio is still the only
                        // copy — and re-transcribing later cannot produce a duplicate.
                        PipelineResult(ProcessOutcome.RetryLater("transcript could not be queued"), false)
                    } else {
                        // The transcript IS durably queued, so this recording is finished as
                        // far as transcription goes — hence Completed. Whether the recording
                        // may also be deleted is the separate, stricter question below.
                        PipelineResult(
                            ProcessOutcome.Completed,
                            mayDeleteRecording = queuedTranscriptIsDurable(
                                enqueued = true,
                                wasUnauthenticated = uploadResult is UploadResult.Unauthenticated
                            )
                        )
                    }
                }
            }
        } finally {
            val temp = mp3File
            try {
                if (temp != null && temp.exists() && !temp.delete()) {
                    Log.w(TAG, "Could not delete temp MP3 ${temp.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete temp MP3 ${temp?.name}", e)
            }
        }
    }

    /**
     * Decides whether a transcript that could only be *queued* — not delivered — counts as
     * durable enough to justify deleting the recording it came from.
     *
     * The recording is the only re-processable artifact; a queue entry is merely a promise
     * of a later retry. Three ways that promise breaks, each of which means the recording
     * must be kept:
     *
     * 1. **The queue write failed.** [PendingUploadStore.enqueue] bails out (and logs why)
     *    on temp-file write failure, rename failure, or any other exception — realistically
     *    a full or failing disk. Nothing was persisted anywhere.
     * 2. **The queue is at capacity.** The next [PendingUploadStore.enqueue] runs
     *    `evictOverflow()`, which permanently destroys the oldest entries once the queue
     *    passes [PendingUploadStore.MAX_ENTRIES]. Whatever it destroys will be a call whose
     *    recording is already gone, so once we are at the cap we stop trading recordings
     *    for queue slots.
     * 3. **We have never held a token.** "It will be retried after login" assumes a login
     *    that may never happen: `CallMonitorService` starts as soon as permissions are
     *    granted, independent of login, and `BootReceiver` restarts it — so an install that
     *    is never signed into would otherwise transcribe, queue and delete every call
     *    forever until the 30-day eviction wipes the lot.
     *
     * A genuinely transient failure with a token on file (network down, backend 5xx) is
     * still treated as durable, as before: the retry is realistic and the queue drains.
     *
     * `internal` (not `private`) so the unit tests in this module can drive it directly
     * with real collaborators, without a mocking framework.
     */
    internal fun queuedTranscriptIsDurable(enqueued: Boolean, wasUnauthenticated: Boolean): Boolean {
        if (!enqueued) {
            Log.w(TAG, "Failed to durably queue upload; keeping recording since no copy of the transcript was persisted")
            return false
        }
        if (wasUnauthenticated && !authStore.hasEverAuthenticated()) {
            Log.w(TAG, "Queued while no token has ever been stored; keeping recording since the login that would flush this queue may never happen")
            return false
        }
        val size = pendingStore.size()
        if (size >= PendingUploadStore.MAX_ENTRIES) {
            Log.w(TAG, "Pending queue at capacity ($size/${PendingUploadStore.MAX_ENTRIES}); keeping recording since the next queued upload will destroy a transcript")
            return false
        }
        return true
    }

    /**
     * Removes the original call recording, if the user has left "delete audio after
     * processing" on (the default).
     *
     * Never throws. By the time this runs the transcript has already been delivered or
     * queued, so failing to reclaim storage must not fail the pipeline or trigger the
     * error notification in CallMonitorService.handleNewFile. The flag read is inside the
     * try too: a corrupt/unreadable SharedPreferences backing file can surface as an
     * IllegalStateException from the getter, and that must not escape either.
     *
     * `internal` (not `private`) so the unit test in this module's test source set can
     * drive it directly without a mocking framework.
     */
    internal fun deleteOriginalIfEnabled(audioFile: File) {
        try {
            if (!settingsStore.deleteAudioAfterProcessing) {
                println("Keeping ${audioFile.name}; delete-after-processing is off")
                return
            }
            when {
                !audioFile.exists() ->
                    Log.w(TAG, "Original recording ${audioFile.name} is already gone")
                audioFile.delete() ->
                    println("Deleted original recording ${audioFile.name}")
                else ->
                    Log.w(TAG, "Could not delete original recording ${audioFile.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete original recording ${audioFile.name}", e)
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

    /** Drives one upload attempt without the transcription pipeline. For tests only. */
    internal fun uploadForTest(payload: PendingUpload): UploadResult = attemptUpload(payload)

    private fun attemptUpload(payload: PendingUpload): UploadResult {
        val token = authStore.getToken()
        if (token.isNullOrBlank()) {
            println("No auth token stored; cannot upload")
            return UploadResult.Unauthenticated
        }

        return when (val first = postCall(payload, token)) {
            is UploadResult.Unauthenticated -> {
                // This service fires long after the app was last opened, so an expired
                // access token is the normal case here, not a logout. Refresh once and
                // retry before giving up on the session.
                val refreshed = tokenRefresher.refresh(token)

                if (refreshed.isNullOrBlank()) {
                    // Compare-and-clear: only wipe the token if it's still the one we just
                    // sent. A login that raced this request may already have stored a fresh
                    // token — clearing unconditionally would wipe that instead of the
                    // expired one, right on the post-login flush path. (TokenRefresher has
                    // already cleared when the refresh token itself was rejected.)
                    if (authStore.getToken() == token) {
                        println("Refresh failed; clearing the rejected token")
                        authStore.clear()
                    } else {
                        println("Backend rejected a stale token; a newer token is already stored, leaving it")
                    }
                    UploadResult.Unauthenticated
                } else {
                    postCall(payload, refreshed)
                }
            }
            else -> first
        }
    }

    private fun postCall(payload: PendingUpload, token: String): UploadResult {
        return try {
            val jsonBody = JSONObject().apply {
                put("contactName", payload.contactName)
                put("date", payload.date)
                put("transcript", payload.transcript)
                put("callerNumber", payload.callerNumber ?: JSONObject.NULL)
                put("callLength", payload.callLengthSeconds ?: JSONObject.NULL)
                // JSON null, not "incoming": the backend leaves the field unset for a null
                // and the UI renders a neutral "Call", so an unknown direction stays
                // unknown instead of being asserted as inbound.
                put("callType", payload.callType ?: JSONObject.NULL)
            }

            val request = Request.Builder()
                .url("$baseUrl/api/calls")
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadResult.Success
                    // Deliberately does not clear here: attemptUpload owns that decision,
                    // because it is the only place that knows whether a refresh was tried.
                    response.code == 401 -> UploadResult.Unauthenticated
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
            // FFmpeg may have written a truncated file before failing. The caller gets
            // null and never sees this path, so clean it up here.
            if (outputFile.exists()) {
                outputFile.delete()
            }
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

        /**
         * OpenAI statuses that mean this particular file will never transcribe — a better
         * connection cannot help. 429 and 5xx are absent on purpose: they are about load,
         * not about the file. So are 401/403 — see [outcomeForWhisperFailure].
         */
        private val PERMANENT_TRANSCRIPTION_CODES = setOf(400, 413, 415, 422)
    }
}