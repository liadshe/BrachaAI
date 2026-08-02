# Call Ownership & Device Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every recorded call belong to the user actually logged in on the device, capture the real caller phone number, and never lose a call recorded while logged out.

**Architecture:** The WebView owns login, so it pushes its JWT across a `@JavascriptInterface` bridge into `EncryptedSharedPreferences`. The background recorder reads that token and sends it as a bearer header. `POST /api/calls` becomes an authenticated route that reads `req.user.id` instead of guessing the first user in the database. Uploads that cannot authenticate are queued on disk as transcripts and flushed on next login.

**Tech Stack:** Kotlin / Android (minSdk 26, Compose, OkHttp, FFmpegKit), React 18 + Vite + TypeScript (bundled into `android/app/src/main/assets/www/`), Express 5 + Mongoose 9 + TypeScript.

**Spec:** `docs/superpowers/specs/2026-07-25-call-ownership-identity-design.md`

## Global Constraints

- **No automated tests.** Explicitly deferred by the user on 2026-07-25. Every task below verifies via compile/build plus a stated manual check. Do not add jest, ts-jest, supertest, or new JVM test classes.
- **Deployment order is load-bearing.** Tasks 1-7 (client) ship BEFORE tasks 8-10 (backend). Task 9 makes `POST /api/calls` reject unauthenticated requests; deploying it before the new APK is installed would 401 every upload from the old client, which has no queue to catch them. Do not reorder.
- Android: `minSdk = 26`, `compileSdk = 36`, `jvmTarget = "11"`.
- Android dependencies are declared as literal coordinate strings in `android/app/build.gradle.kts` (the `libs.versions.toml` catalog is only used for the pre-existing AndroidX/Compose entries). Follow the literal-string pattern for new additions.
- Backend URL used by the device is `http://193.106.55.154:3000/api/calls`, hardcoded in `AudioProcessor.kt:102`. Leave it as-is; it is out of scope.
- The frontend bundle must be rebuilt into `android/app/src/main/assets/www/` for any frontend change to reach the device. `file:///android_asset/www/index.html` is what `MainActivity.kt:70` loads.
- Do not migrate existing mis-owned records. Data currently under `avia1` stays there.
- Commit after every task.

---

### Task 1: PendingUploadStore

The on-disk queue for uploads that could not be sent. It stores the **transcript**, never the audio — transcription has already been paid for by the time an upload is attempted, so a retry must never redo it.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/PendingUploadStore.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class PendingUpload(contactName: String, date: String, callerNumber: String?, transcript: String)`; `class PendingUploadStore(dir: File)` with `enqueue(upload: PendingUpload)`, `peekAll(): List<Pair<File, PendingUpload>>`, `remove(file: File)`, `size(): Int`.

- [ ] **Step 1: Create the store**

```kotlin
package com.brachaai.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class PendingUpload(
    val contactName: String,
    val date: String,
    val callerNumber: String?,
    val transcript: String
)

/**
 * Durable queue of uploads that could not be delivered (no token, 401, or network failure).
 * One JSON file per entry, named so lexical order == chronological order.
 */
class PendingUploadStore(private val dir: File) {

    private val counter = AtomicInteger(0)

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create pending upload directory: ${dir.absolutePath}")
        }
    }

    fun enqueue(upload: PendingUpload) {
        val json = JSONObject().apply {
            put("contactName", upload.contactName)
            put("date", upload.date)
            put("callerNumber", upload.callerNumber ?: JSONObject.NULL)
            put("transcript", upload.transcript)
        }

        val name = String.format("%013d-%03d.json", System.currentTimeMillis(), counter.getAndIncrement() % 1000)
        try {
            File(dir, name).writeText(json.toString())
            Log.d(TAG, "Queued upload $name; queue size = ${size()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue upload", e)
            return
        }
        evictOverflow()
    }

    fun peekAll(): List<Pair<File, PendingUpload>> =
        listFiles().mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val number = if (json.isNull("callerNumber")) null else json.getString("callerNumber")
                file to PendingUpload(
                    contactName = json.getString("contactName"),
                    date = json.getString("date"),
                    callerNumber = number,
                    transcript = json.getString("transcript")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Discarding unreadable queue entry ${file.name}", e)
                file.delete()
                null
            }
        }

    fun remove(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete queue entry ${file.name}")
        }
    }

    fun size(): Int = listFiles().size

    private fun listFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    private fun evictOverflow() {
        val files = listFiles()
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS

        files.filter { it.lastModified() < cutoff }.forEach {
            Log.w(TAG, "Evicting queue entry ${it.name}: older than 30 days")
            it.delete()
        }

        val remaining = listFiles()
        if (remaining.size > MAX_ENTRIES) {
            remaining.take(remaining.size - MAX_ENTRIES).forEach {
                Log.w(TAG, "Evicting queue entry ${it.name}: queue over capacity")
                it.delete()
            }
        }
    }

    companion object {
        private const val TAG = "PendingUploadStore"
        const val MAX_ENTRIES = 200
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/PendingUploadStore.kt
git commit -m "Add on-disk queue for undeliverable call uploads"
```

---

### Task 2: AuthStore

The single owner of token storage. Everything else reads the token through this, so a future switch to device tokens touches one file.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/AuthStore.kt`
- Modify: `android/app/build.gradle.kts:55-78` (dependencies block)

**Interfaces:**
- Consumes: nothing.
- Produces: `class AuthStore(context: Context)` with `getToken(): String?`, `setToken(token: String)`, `clear()`.

- [ ] **Step 1: Add the security-crypto dependency**

In `android/app/build.gradle.kts`, inside the `dependencies { }` block, after the existing `androidx.webkit` line:

```kotlin
    // for using webview
    implementation("androidx.webkit:webkit:1.11.0")
    // encrypted storage for the auth token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

- [ ] **Step 2: Create the store**

```kotlin
package com.brachaai.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sole owner of auth token persistence. The token originates in the WebView's
 * localStorage and is pushed here via AuthBridge.
 */
class AuthStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(): String? = try {
        prefs.getString(KEY_TOKEN, null)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read auth token", e)
        null
    }

    fun setToken(token: String) {
        try {
            prefs.edit().putString(KEY_TOKEN, token).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist auth token", e)
        }
    }

    fun clear() {
        try {
            prefs.edit().remove(KEY_TOKEN).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear auth token", e)
        }
    }

    companion object {
        private const val TAG = "AuthStore"
        private const val PREFS_NAME = "bracha_auth"
        private const val KEY_TOKEN = "jwt"
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/AuthStore.kt android/app/build.gradle.kts
git commit -m "Add encrypted auth token storage"
```

---

### Task 3: AuthBridge and WebView registration

Exposes `window.BrachaNative` to the WebView. Two methods, no logic of its own.

Injection risk is acceptable because the WebView only loads `file:///android_asset/www/` (`MainActivity.kt:70`) — no remote origin can reach the bridge.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/AuthBridge.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/WebViewScreen.kt:15-47`
- Modify: `android/app/src/main/java/com/brachaai/app/MainActivity.kt:70-72`

**Interfaces:**
- Consumes: `AuthStore` (Task 2).
- Produces: `class AuthBridge(context: Context, onAuthenticated: () -> Unit)` with `@JavascriptInterface setAuth(token: String)` and `@JavascriptInterface clearAuth()`; `WebViewScreen(url: String, onAuthenticated: () -> Unit, onWebViewCreated: (WebView) -> Unit)`.

The `onAuthenticated` lambda is wired to a real queue flush in Task 5. Here it only logs.

- [ ] **Step 1: Create the bridge**

```kotlin
package com.brachaai.app

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Bridge exposed to the WebView as `BrachaNative`. The web app owns login, so it
 * hands the JWT to native code here; the background recorder has no other way to
 * learn who is logged in.
 */
class AuthBridge(
    context: Context,
    private val onAuthenticated: () -> Unit
) {
    private val authStore = AuthStore(context.applicationContext)

    @JavascriptInterface
    fun setAuth(token: String?) {
        if (token.isNullOrBlank()) {
            authStore.clear()
            Log.d(TAG, "setAuth called with empty token; cleared")
            return
        }
        authStore.setToken(token)
        Log.d(TAG, "Auth token stored from WebView")
        onAuthenticated()
    }

    @JavascriptInterface
    fun clearAuth() {
        authStore.clear()
        Log.d(TAG, "Auth token cleared")
    }

    companion object {
        private const val TAG = "AuthBridge"
        const val JS_NAME = "BrachaNative"
    }
}
```

- [ ] **Step 2: Register the bridge in WebViewScreen**

Replace the whole of `WebViewScreen.kt` with:

```kotlin
package com.brachaai.app

import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebViewScreen(
    url: String,
    onAuthenticated: () -> Unit = {},
    onWebViewCreated: (WebView) -> Unit = {}
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                }
                addJavascriptInterface(AuthBridge(context, onAuthenticated), AuthBridge.JS_NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d("WebView", "Page loaded: $url")
                    }
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        Log.e("WebView", "Error: ${error.description} for ${request.url}")
                    }
                }
                onWebViewCreated(this)
                loadUrl(url)
            }
        }
    )
}
```

- [ ] **Step 3: Pass the callback from MainActivity**

The real callback triggers a queue flush via `CallMonitorService.requestFlush`, which does not exist until Task 5. Use the logging placeholder below now; Task 5 Step 3 replaces it with the real call.

In `MainActivity.kt`, replace the `WebViewScreen(...)` call at line 70:

```kotlin
                    WebViewScreen(
                        url = "file:///android_asset/www/index.html",
                        onAuthenticated = { android.util.Log.d("MainActivity", "Auth token received") }
                    ) { wv ->
                        webView = wv
                    }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/AuthBridge.kt android/app/src/main/java/com/brachaai/app/WebViewScreen.kt android/app/src/main/java/com/brachaai/app/MainActivity.kt
git commit -m "Expose BrachaNative auth bridge to the WebView"
```

---

### Task 4: CallerLookup and READ_CALL_LOG

Resolves the other party's phone number from the Android call log.

The recording filename carries the call **start** time, while `FileObserver.CLOSE_WRITE` fires at call **end**. Matching must therefore use the parsed filename timestamp, not the file's mtime, or every lookup will be off by the call's duration.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/CallerLookup.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/FilenameParser.kt`
- Modify: `android/app/src/main/AndroidManifest.xml:5-14`
- Modify: `android/app/src/main/java/com/brachaai/app/MainActivity.kt:32-40`

**Interfaces:**
- Consumes: `ParsedFile` (existing, `FilenameParser.kt:4`).
- Produces: `fun ParsedFile.toEpochMillis(): Long?`; `class CallerLookup(context: Context)` with `findNumberNear(callStartMillis: Long): String?`.

- [ ] **Step 1: Add the permission to the manifest**

In `AndroidManifest.xml`, after the `POST_NOTIFICATIONS` line:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_CALL_LOG" />
```

- [ ] **Step 2: Add timestamp parsing to FilenameParser**

Append to `FilenameParser.kt`:

```kotlin
/**
 * Converts the filename's date + time (YYMMDD + HHMMSS, device local time) into
 * epoch millis. Returns null when the filename carried an unparseable stamp.
 */
fun ParsedFile.toEpochMillis(): Long? = try {
    java.text.SimpleDateFormat("yyMMddHHmmss", java.util.Locale.US)
        .apply { isLenient = false }
        .parse(date + time)
        ?.time
} catch (e: Exception) {
    null
}
```

- [ ] **Step 3: Create CallerLookup**

```kotlin
package com.brachaai.app

import android.content.Context
import android.provider.CallLog
import android.util.Log
import kotlin.math.abs

/**
 * Finds the phone number of the other party by matching the call log entry
 * closest to the recording's start time.
 */
class CallerLookup(context: Context) {

    private val appContext = context.applicationContext

    fun findNumberNear(callStartMillis: Long): String? {
        val from = callStartMillis - TOLERANCE_MS
        val to = callStartMillis + TOLERANCE_MS

        return try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf(from.toString(), to.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

                var best: String? = null
                var bestDelta = Long.MAX_VALUE

                while (cursor.moveToNext()) {
                    val delta = abs(cursor.getLong(dateIdx) - callStartMillis)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        best = cursor.getString(numberIdx)
                    }
                }

                normalize(best).also {
                    if (it == null) Log.d(TAG, "No usable caller number near $callStartMillis")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG not granted; caller number unavailable")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Call log lookup failed", e)
            null
        }
    }

    /** Digits only, preserving a leading '+'. Withheld/private numbers become null. */
    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed in WITHHELD) return null
        val prefix = if (trimmed.startsWith("+")) "+" else ""
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.isEmpty()) null else prefix + digits
    }

    companion object {
        private const val TAG = "CallerLookup"
        const val TOLERANCE_MS = 2L * 60 * 1000
        private val WITHHELD = setOf("-1", "-2", "-3")
    }
}
```

- [ ] **Step 4: Request the permission at runtime**

In `MainActivity.kt`, add `READ_CALL_LOG` to `requiredPermissions` so it is requested on every supported version:

```kotlin
    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
```

- [ ] **Step 5: Verify it compiles**

Run: `cd android && ./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/CallerLookup.kt android/app/src/main/java/com/brachaai/app/FilenameParser.kt android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/brachaai/app/MainActivity.kt
git commit -m "Resolve caller phone number from the call log"
```

---

### Task 5: Wire auth, caller number, and the queue into the upload path

This is the task that makes the client actually behave differently.

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt` (whole file)
- Modify: `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt:25-47,132-139`
- Modify: `android/app/src/main/java/com/brachaai/app/MainActivity.kt` (replace the Task 3 placeholder)

**Interfaces:**
- Consumes: `AuthStore` (Task 2), `PendingUploadStore` / `PendingUpload` (Task 1), `CallerLookup` + `ParsedFile.toEpochMillis()` (Task 4).
- Produces: `AudioProcessor(openAiApiKey: String, cacheDir: File, authStore: AuthStore, pendingStore: PendingUploadStore, callerLookup: CallerLookup)` with `suspend fun processAndSendToBackend(audioFile: File)` and `suspend fun flushPending()`; `CallMonitorService.requestFlush(context: Context)`.

- [ ] **Step 1: Rewrite AudioProcessor**

```kotlin
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
```

- [ ] **Step 2: Construct the new dependencies in CallMonitorService and add the flush entry point**

In `CallMonitorService.kt`, replace the `onCreate` construction line and add flush handling:

```kotlin
    override fun onCreate() {
        super.onCreate()
        audioProcessor = AudioProcessor(
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            cacheDir = cacheDir,
            authStore = AuthStore(this),
            pendingStore = PendingUploadStore(File(filesDir, "pending")),
            callerLookup = CallerLookup(this)
        )
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()

        val notification = buildMonitoringNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startWatching()
        isRunning = true
        flushPending()
    }
```

Replace `onStartCommand` so an explicit flush intent is honoured:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FLUSH) flushPending()
        return START_STICKY
    }

    private fun flushPending() {
        serviceScope.launch {
            try {
                audioProcessor.flushPending()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush pending uploads", e)
            }
        }
    }
```

Extend the companion object:

```kotlin
    companion object {
        const val WATCH_PATH = "/storage/emulated/0/Recordings/Call"
        const val ACTION_FLUSH = "com.brachaai.app.action.FLUSH"
        @Volatile var isRunning = false
        private const val NOTIFICATION_ID = 1
        private const val MONITOR_CHANNEL_ID = "call_monitor"
        private const val ERROR_CHANNEL_ID = "call_monitor_errors"
        private const val TAG = "CallMonitorService"

        /** Asks the running service to retry queued uploads — called right after login. */
        fun requestFlush(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java).apply { action = ACTION_FLUSH }
            context.startForegroundService(intent)
        }
    }
```

Add the imports `android.content.Context` and `java.io.File` to the existing import block.

- [ ] **Step 3: Replace the MainActivity placeholder from Task 3**

```kotlin
                    WebViewScreen(
                        url = "file:///android_asset/www/index.html",
                        onAuthenticated = { CallMonitorService.requestFlush(this@MainActivity) }
                    ) { wv ->
                        webView = wv
                    }
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/AudioProcessor.kt android/app/src/main/java/com/brachaai/app/CallMonitorService.kt android/app/src/main/java/com/brachaai/app/MainActivity.kt
git commit -m "Send authenticated uploads with caller number, queue on failure"
```

---

### Task 6: Push the token from the web app into native

Three call sites plus a type declaration. All use optional chaining so the same bundle still runs in a desktop browser where `BrachaNative` is undefined.

**Files:**
- Create: `frontend/src/types/native.d.ts`
- Modify: `frontend/src/pages/LoginPage/LoginPage.tsx:25-29`
- Modify: `frontend/src/pages/SignupPage/SignupPage.tsx:34-38`
- Modify: `frontend/src/services/apiClient.ts:19-29`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `AuthBridge` JS surface (Task 3) — `setAuth(token)`, `clearAuth()`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Declare the bridge type**

Create `frontend/src/types/native.d.ts`:

```typescript
export {};

declare global {
  interface BrachaNativeBridge {
    setAuth(token: string): void;
    clearAuth(): void;
  }

  interface Window {
    /** Injected by the Android host. Undefined in a plain browser. */
    BrachaNative?: BrachaNativeBridge;
  }
}
```

- [ ] **Step 2: Push the token on login**

In `LoginPage.tsx`, replace the success block inside `handleSubmit`:

```typescript
      const { token, user } = response.data;
      localStorage.setItem('token', token);
      localStorage.setItem('user', JSON.stringify(user));
      window.BrachaNative?.setAuth(token);

      navigate('/home');
```

- [ ] **Step 3: Push the token on signup**

In `SignupPage.tsx`, replace the equivalent block:

```typescript
            const { token, user } = response.data;
            localStorage.setItem('token', token);
            localStorage.setItem('user', JSON.stringify(user));
            window.BrachaNative?.setAuth(token);

            navigate('/home');
```

- [ ] **Step 4: Clear native auth on 401**

In `apiClient.ts`, extend the existing response interceptor:

```typescript
apiClient.interceptors.response.use((response) => {
    return response;
}, (error) => {
    if (error.response && error.response.status === 401) {
        console.warn('Unauthorized request. Logging out user.');
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.BrachaNative?.clearAuth();
        window.location.href = '/login';
    }
    return Promise.reject(error);
});
```

- [ ] **Step 5: Sync the existing token on app start**

This is the step that covers users who are *already* logged in when the bridge ships — without it the bridge stays empty until the next manual logout.

In `App.tsx`, add the import and effect:

```typescript
import { useEffect } from 'react';
import { HashRouter , Routes, Route, Navigate } from 'react-router-dom';
```

and inside `App`, before the `return`:

```typescript
function App() {
  useEffect(() => {
    const token = localStorage.getItem('token');
    if (token) {
      window.BrachaNative?.setAuth(token);
    }
  }, []);

  return (
```

- [ ] **Step 6: Verify it type-checks and builds**

Run: `cd frontend && npm run build`
Expected: `tsc` passes with no errors, then `vite build` writes `dist/`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/native.d.ts frontend/src/pages/LoginPage/LoginPage.tsx frontend/src/pages/SignupPage/SignupPage.tsx frontend/src/services/apiClient.ts frontend/src/App.tsx
git commit -m "Hand the auth token to the Android host on login"
```

---

### Task 7: Rebuild the Android web assets

Frontend source changes do not reach the device until the bundle is rebuilt — `MainActivity.kt:70` loads `file:///android_asset/www/index.html`.

**Files:**
- Modify: `android/app/src/main/assets/www/**` (generated output)

**Interfaces:**
- Consumes: the built `frontend/dist` (Task 6).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Build the frontend**

Run: `cd frontend && npm run build`
Expected: `dist/` contains `index.html` and a fresh `assets/` directory.

- [ ] **Step 2: Replace the bundled assets**

The old hashed bundles must be deleted, not merged, or stale JS lingers in the APK.

```bash
rm -rf android/app/src/main/assets/www
cp -r frontend/dist android/app/src/main/assets/www
```

- [ ] **Step 3: Confirm the bridge call made it into the bundle**

Run: `grep -c "BrachaNative" android/app/src/main/assets/www/assets/*.js`
Expected: at least one file reports a non-zero count.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/assets/www
git commit -m "Update Android assets from frontend build"
```

---

### Task 8: Analysis status and respond-before-analyze

Reordering the response ahead of the AI call is what makes the retry queue safe. Today `saveRawCall` runs before `analyzeTranscript` (`callController.ts:67-75`), so an OpenAI failure returns 500 *after* the call is persisted — a retrying client would duplicate a call that actually saved.

**Files:**
- Modify: `backend/src/models/Call.ts:3-10`
- Modify: `backend/src/services/callService.ts:18-19`
- Modify: `backend/src/controllers/callController.ts:44-97`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `callService.updateCallWithAnalysis(callId: string, summary: string)` (now also sets `analysisStatus: 'done'`); `callService.markAnalysisFailed(callId: string)`.

- [ ] **Step 1: Add analysisStatus to the Call model**

```typescript
import mongoose, { Schema, Document } from 'mongoose';

const CallSchema = new Schema({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    contactId: { type: Schema.Types.ObjectId, ref: 'Contact', required: true },
    fullTranscript: { type: String, required: true },
    callSummary: { type: String },
    analysisStatus: {
        type: String,
        enum: ['pending', 'done', 'failed'],
        default: 'pending',
    },
    callDateTime: { type: Date, default: Date.now },
    callLength: { type: Number }, // in seconds
});

export default mongoose.model('Call', CallSchema);
```

- [ ] **Step 2: Update callService**

```typescript
export const updateCallWithAnalysis = async (callId: string, summary: string) => {
    return await Call.findByIdAndUpdate(
        callId,
        { callSummary: summary, analysisStatus: 'done' },
        { returnDocument: 'after' }
    );
};

export const markAnalysisFailed = async (callId: string) => {
    return await Call.findByIdAndUpdate(
        callId,
        { analysisStatus: 'failed' },
        { returnDocument: 'after' }
    );
};
```

- [ ] **Step 3: Respond before analysing**

In `callController.ts`, replace `handleIncomingAndroidCall` and add the helper below it. (Ownership still uses `getFirstUser` at this point — Task 9 removes it. Keeping the two changes separate keeps each reviewable.)

```typescript
export const handleIncomingAndroidCall = async (
  req: Request,
  res: Response,
) => {
  try {
    const { contactName, date, transcript } = req.body;

    const firstUser = await userService.getFirstUser();
    const activeUserId = firstUser ? firstUser.id : "65f1234567890abcdef12345";

    const actualCallDate = parseFilenameDate(date);
    const contact = await userService.getOrCreateContact(activeUserId, contactName);
    const call = await callService.saveRawCall(
      activeUserId,
      contact.id,
      transcript,
      actualCallDate
    );

    // Respond as soon as the call is durable. Analysis is slow and may fail;
    // making the client wait on it would turn AI errors into duplicate uploads.
    res.status(201).json({ success: true, callId: call.id, analysisStatus: 'pending' });

    void runAnalysis(call.id, activeUserId, contact.id, transcript);
  } catch (error) {
    console.error("Controller Error:", error);
    if (!res.headersSent) res.status(500).json({ success: false });
  }
};

const runAnalysis = async (
  callId: string,
  userId: string,
  contactId: string,
  transcript: string,
) => {
  try {
    const analysis = await aiService.analyzeTranscript(transcript);
    await callService.updateCallWithAnalysis(callId, analysis.summary);
    console.log(`Processed: ${analysis.summary}`);

    if (
      analysis?.tasks &&
      Array.isArray(analysis.tasks) &&
      analysis.tasks.length > 0
    ) {
      await createTasksFromAi(userId, contactId, analysis.tasks);
      console.log(`Tasks created: ${analysis.tasks.length}`);
    }
  } catch (error) {
    console.error(`Analysis failed for call ${callId}:`, error);
    await callService.markAnalysisFailed(callId);
  }
};
```

- [ ] **Step 4: Verify it compiles**

Run: `cd backend && npm run build`
Expected: `tsc` exits 0.

- [ ] **Step 5: Commit**

```bash
git add backend/src/models/Call.ts backend/src/services/callService.ts backend/src/controllers/callController.ts
git commit -m "Respond before analysis and track analysis status"
```

---

### Task 9: Authenticate call uploads — the fix

**Files:**
- Modify: `backend/src/routes/callRoute.ts:10`
- Modify: `backend/src/controllers/callController.ts` (`handleIncomingAndroidCall`)
- Modify: `backend/src/services/userService.ts:21-24`

**Interfaces:**
- Consumes: `protect` / `AuthRequest` (existing, `backend/src/middleware/authMiddleware.ts`).
- Produces: `POST /api/calls` requiring `Authorization: Bearer <jwt>`.

- [ ] **Step 1: Protect the route**

In `callRoute.ts`:

```typescript
router.post('/calls', protect, handleIncomingAndroidCall);
router.get('/calls', protect, getCalls);
```

- [ ] **Step 2: Read the owner from the token**

Replace the top of `handleIncomingAndroidCall` — signature and the ownership lines:

```typescript
export const handleIncomingAndroidCall = async (
  req: AuthRequest,
  res: Response,
) => {
  try {
    const userId = req.user?.id;
    if (!userId) {
      return res.status(401).json({ success: false, message: 'Unauthenticated' });
    }

    const { contactName, date, transcript } = req.body;
    if (!transcript) {
      return res.status(400).json({ success: false, message: 'transcript is required' });
    }

    console.log(`[DEBUG] Android call webhook for userId: ${userId}`);

    const actualCallDate = parseFilenameDate(date);
    const contact = await userService.getOrCreateContact(userId, contactName);
    const call = await callService.saveRawCall(
      userId,
      contact.id,
      transcript,
      actualCallDate
    );

    res.status(201).json({ success: true, callId: call.id, analysisStatus: 'pending' });

    void runAnalysis(call.id, userId, contact.id, transcript);
  } catch (error) {
    console.error("Controller Error:", error);
    if (!res.headersSent) res.status(500).json({ success: false });
  }
};
```

- [ ] **Step 3: Delete getFirstUser**

Remove this entire function from `userService.ts` so the guess cannot be reintroduced:

```typescript
export const getFirstUser = async () => {
    const user = await User.findOne();
    return user;
};
```

Also remove the now-unused `import User from '../models/User';` at the top of `userService.ts` if nothing else references it.

In `callController.ts`, `handleIncomingAndroidCall` no longer takes a bare `Request`. If `tsc` reports `Request` as unused, narrow the import to `import { Response } from "express";` — `getCalls` and `handleIncomingAndroidCall` both use `AuthRequest`.

- [ ] **Step 4: Verify it compiles and that nothing still calls getFirstUser**

Run: `cd backend && npm run build`
Expected: `tsc` exits 0.

Run: `grep -rn "getFirstUser" backend/src`
Expected: no matches.

- [ ] **Step 5: Commit**

```bash
git add backend/src/routes/callRoute.ts backend/src/controllers/callController.ts backend/src/services/userService.ts
git commit -m "Own uploaded calls by the authenticated user"
```

---

### Task 10: Match contacts by phone number

Now that a real number arrives, name-only matching (`userService.ts:7`) would create a second contact for every spelling variant.

**Files:**
- Modify: `backend/src/services/userService.ts:4-19`
- Modify: `backend/src/controllers/callController.ts` (the `getOrCreateContact` call site)

**Interfaces:**
- Consumes: `callerNumber` from the request body (Task 5).
- Produces: `getOrCreateContact(userId: string, contactName: string, callerNumber?: string | null)`.

- [ ] **Step 1: Rewrite getOrCreateContact**

```typescript
import Contact from '../models/Contact';

export const PLACEHOLDER_PHONE = '000-000-000';

/** Digits only, preserving a leading '+'. Returns null for unusable input. */
const normalizePhone = (raw?: string | null): string | null => {
    if (!raw) return null;
    const trimmed = String(raw).trim();
    const prefix = trimmed.startsWith('+') ? '+' : '';
    const digits = trimmed.replace(/\D/g, '');
    return digits ? prefix + digits : null;
};

export const getOrCreateContact = async (
    userId: string,
    contactName: string,
    callerNumber: string | null = null,
) => {
    const phone = normalizePhone(callerNumber);

    // Phone is the strongest identifier — prefer it over the recorded name.
    if (phone) {
        const byPhone = await Contact.findOne({ userId, phone });
        if (byPhone) return byPhone;
    }

    const byName = await Contact.findOne({ userId, name: contactName });
    if (byName) {
        // Backfill a real number over the placeholder, but never overwrite a known one.
        if (phone && byName.phone === PLACEHOLDER_PHONE) {
            byName.phone = phone;
            await byName.save();
            console.log(`Backfilled phone for contact ${contactName}`);
        }
        return byName;
    }

    const created = await Contact.create({
        userId,
        name: contactName,
        phone: phone ?? PLACEHOLDER_PHONE, // schema requires a phone
    });
    console.log(`Created new contact: ${contactName}`);
    return created;
};
```

- [ ] **Step 2: Pass the caller number through the controller**

In `handleIncomingAndroidCall`, destructure and forward it:

```typescript
    const { contactName, date, transcript, callerNumber } = req.body;
```

```typescript
    const contact = await userService.getOrCreateContact(userId, contactName, callerNumber ?? null);
```

- [ ] **Step 3: Verify it compiles**

Run: `cd backend && npm run build`
Expected: `tsc` exits 0.

- [ ] **Step 4: Commit**

```bash
git add backend/src/services/userService.ts backend/src/controllers/callController.ts
git commit -m "Match contacts by caller phone number"
```

---

### Task 11: Deploy and verify end to end

No automated tests exist, so this manual pass is the only proof the fix works. Do not skip it.

**Files:** none modified.

**Interfaces:** none.

- [ ] **Step 1: Install the new APK first**

Deployment order matters — the client must be able to send a token before the backend starts demanding one.

Run: `cd android && ./gradlew installDebug`
Expected: `BUILD SUCCESSFUL`, app installed.

- [ ] **Step 2: Open the app and grant the new permission**

Launch the app and accept the call log permission prompt. Confirm in logcat that the token reached native:

Run: `adb logcat -s AuthBridge`
Expected: `Auth token stored from WebView` — this comes from the `App.tsx` startup sync, without needing to log out and back in.

- [ ] **Step 3: Deploy the backend**

Run: `cd devops && docker compose up -d --build backend`
Expected: container restarts healthy; `🍃 Connected to MongoDB Successfully` in the logs.

- [ ] **Step 4: Record a call and confirm ownership**

Make a short call, then:

Run: `docker logs bracha-backend --tail 100 | grep DEBUG`
Expected: `[DEBUG] Android call webhook for userId: X` and `[DEBUG] Fetching calls for userId: Y` where **X == Y**. This ID comparison is the original bug; matching IDs is the proof.

- [ ] **Step 5: Confirm it renders**

Open the app's home screen.
Expected: the call appears with the correct contact name, a real phone number rather than `000-000-000`, and a summary (briefly `Summary pending analysis...` until the analysis lands).

- [ ] **Step 6: Verify the offline queue**

Log out, record a short call, confirm in logcat that it queued:

Run: `adb logcat -s PendingUploadStore`
Expected: `Queued upload …; queue size = 1`

Log back in.
Expected: `Flushed …` in logcat, and the call appears on the home screen.

- [ ] **Step 7: Commit any deployment notes**

No code changes expected. If deployment revealed fixes, commit them individually.

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| `AuthStore` / EncryptedSharedPreferences | 2 |
| `AuthBridge` / `BrachaNative` registration | 3 |
| `CallerLookup`, ±2min, withheld numbers, `READ_CALL_LOG` | 4 |
| `PendingUploadStore`, transcript-not-audio, 200/30d cap | 1 |
| Flush on service start and on `setAuth` | 5 |
| `AudioProcessor` bearer token + `callerNumber` | 5 |
| Frontend `setAuth`/`clearAuth`/startup sync, TS declaration | 6 |
| Asset rebuild | 7 |
| `protect` on `POST /api/calls`, `req.user.id`, delete `getFirstUser` | 9 |
| Contact matching by phone with placeholder backfill | 10 |
| `201`-then-analyze, `analysisStatus` | 8 |
| Error handling table | 5 (client), 8 (analysis), 10 (withheld number) |
| Verification steps | 11 |
| No automated tests | Global Constraints |

**Known gap carried from the spec:** the ownership invariant has no regression test, by explicit decision. It can regress silently.
