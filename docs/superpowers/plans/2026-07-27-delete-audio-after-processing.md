# Delete Audio After Processing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Settings toggle, ON by default, that deletes the original call recording from the device once its transcript has been secured.

**Architecture:** The setting is owned natively in Android `SharedPreferences` (`SettingsStore`), not on the backend, because the code that deletes runs in a background service that may execute before the user has ever logged in or opened the WebView. The existing JS bridge — registered to JavaScript as `BrachaNative` — is renamed from `AuthBridge` to `NativeBridge` and grows a getter/setter pair so the React Settings page can read and write the native flag. `AudioProcessor` consults the flag after the transcript is durable.

**Tech Stack:** Kotlin / Jetpack Compose / Android SDK 26–36, Robolectric (new test dependency), React 18 + TypeScript + Vite, CSS Modules.

**Design spec:** `docs/superpowers/specs/2026-07-27-delete-audio-after-processing-design.md`

## Global Constraints

- Default value is **`true`** (ON). It must be correct from first boot with no first-run write and no migration — implemented as the default argument of `getBoolean`.
- `SettingsStore` is the **only** component that touches settings storage. `AudioProcessor` and `NativeBridge` go through it; neither calls `getSharedPreferences` itself.
- The prefs file is named exactly `bracha_settings`; the key is exactly `delete_audio_after_processing`. Both `NativeBridge` and `AudioProcessor` must read the same file/key or the toggle will silently not affect the pipeline.
- The JS-visible bridge name stays `BrachaNative` (`NativeBridge.JS_NAME`). Renaming it would break `SettingsPage.tsx:183` and every existing install.
- Settings-page copy, exactly: name `Delete Audio After Processing`, description `Free up storage by removing recordings once transcribed`.
- The new toggle must **not** be routed through `handleToggle` in `SettingsPage.tsx`. That function PUTs to `/auth/profile`; this setting is native-only and never touches the backend.
- Deletion of the original recording must never throw. By the time it runs, the transcript is already delivered or queued; a storage-reclaim failure must not fail the pipeline or fire the error notification in `CallMonitorService.handleNewFile`.
- Do **not** fix `backend/src/controllers/authController.ts:104` (it ignores `settings`, which is why the two existing toggles reset on login). Out of scope per the spec; a separate change.
- Do **not** add a backfill sweep for recordings that already exist on the device.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `android/app/src/main/java/com/brachaai/app/SettingsStore.kt` | Create | Sole owner of device-local settings persistence |
| `android/app/src/test/java/com/brachaai/app/SettingsStoreTest.kt` | Create | Proves the default is ON and both directions round-trip |
| `android/app/src/test/java/com/brachaai/app/NativeBridgeTest.kt` | Create | Proves the bridge and the store agree on the same prefs file |
| `android/app/build.gradle.kts` | Modify | Robolectric test dep + `testOptions` |
| `android/app/src/main/java/com/brachaai/app/AuthBridge.kt` | Delete (renamed) | — |
| `android/app/src/main/java/com/brachaai/app/NativeBridge.kt` | Create (renamed from above) | JS-facing bridge: auth + device settings |
| `android/app/src/main/java/com/brachaai/app/WebViewScreen.kt:34` | Modify | Register the renamed bridge |
| `android/app/src/main/java/com/brachaai/app/AuthStore.kt:11` | Modify | Stale comment referencing `AuthBridge` |
| `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt` | Modify | MP3 `finally` cleanup + original-recording deletion |
| `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt:32-38` | Modify | Pass `SettingsStore` into `AudioProcessor` |
| `frontend/src/types/native.d.ts` | Modify | Type the two new bridge methods |
| `frontend/src/pages/SettingsPage/SettingsPage.tsx` | Modify | The toggle row + its handler |
| `frontend/src/pages/SettingsPage/SettingsPage.module.css` | Modify | `.amberIcon` + row divider |

**Deviation from the spec, noted deliberately:** the spec says Robolectric goes in `android/gradle/libs.versions.toml`. This plan declares it as a direct coordinate string in `build.gradle.kts` instead, matching how every dependency added since the catalog was generated is declared there (`okhttp`, `ffmpeg-kit-audio`, `webkit`, `security-crypto` at lines 71-79). Same outcome, one file touched instead of two.

---

### Task 1: `SettingsStore` — the native flag

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/SettingsStore.kt`
- Create: `android/app/src/test/java/com/brachaai/app/SettingsStoreTest.kt`
- Modify: `android/app/build.gradle.kts` (add `testOptions` block; add Robolectric dep)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `class SettingsStore(context: Context)` with the mutable property `var deleteAudioAfterProcessing: Boolean`. Tasks 2 and 3 both construct it and read/write that property.

**Background for the implementer:** the repo's Android unit tests are plain JUnit running on the JVM (`android/app/src/test/`), where `SharedPreferences` does not exist — every Android framework call returns a "not mocked" stub. Robolectric provides a real in-JVM implementation of the framework, which is what lets us assert the default. It needs `isIncludeAndroidResources = true` or it fails at startup complaining about missing resources. `compileSdk`/`targetSdk` here are 36, which Robolectric has no runtime jar for, so the SDK is pinned to 34 via `@Config`.

- [ ] **Step 1: Add the Robolectric test dependency and test options**

In `android/app/build.gradle.kts`, add a `testOptions` block inside the `android { }` block, immediately after the `buildFeatures` block that ends at line 52:

```kotlin
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources/manifest on the unit-test classpath.
            isIncludeAndroidResources = true
        }
    }
}
```

Then in the `dependencies { }` block, directly below `testImplementation(libs.junit)` (line 64):

```kotlin
    testImplementation(libs.junit)
    // Real in-JVM Android framework, so SharedPreferences-backed settings are testable.
    testImplementation("org.robolectric:robolectric:4.14.1")
```

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/SettingsStoreTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    // A fresh instance each time, so the assertions go through SharedPreferences
    // rather than through an in-memory field on one object.
    private fun newStore() = SettingsStore(RuntimeEnvironment.getApplication())

    @Test
    fun deleteAudioDefaultsToTrueBeforeAnyWrite() {
        assertTrue(newStore().deleteAudioAfterProcessing)
    }

    @Test
    fun deleteAudioPersistsFalse() {
        newStore().deleteAudioAfterProcessing = false
        assertFalse(newStore().deleteAudioAfterProcessing)
    }

    @Test
    fun deleteAudioPersistsBackToTrue() {
        newStore().deleteAudioAfterProcessing = false
        newStore().deleteAudioAfterProcessing = true
        assertTrue(newStore().deleteAudioAfterProcessing)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run from `android/`: `./gradlew testDebugUnitTest --tests "com.brachaai.app.SettingsStoreTest"`

Expected: FAIL — compilation error, `Unresolved reference: SettingsStore`.

- [ ] **Step 4: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/SettingsStore.kt`:

```kotlin
package com.brachaai.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Sole owner of device-local user settings.
 *
 * Deliberately backed by plain SharedPreferences rather than the
 * EncryptedSharedPreferences that AuthStore uses: there is no secret here, and
 * AuthStore swallows read failures by design — acceptable for a token that can be
 * re-fetched, wrong for a flag that decides whether to destroy a recording.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * When true, the original call recording is deleted once its transcript is secured.
     *
     * Defaults to true so storage is reclaimed from first boot — CallMonitorService can
     * process a call before the user has ever opened Settings, so there is no opportunity
     * for a first-run write.
     */
    var deleteAudioAfterProcessing: Boolean
        get() = prefs.getBoolean(KEY_DELETE_AUDIO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DELETE_AUDIO, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "bracha_settings"
        private const val KEY_DELETE_AUDIO = "delete_audio_after_processing"
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run from `android/`: `./gradlew testDebugUnitTest --tests "com.brachaai.app.SettingsStoreTest"`

Expected: PASS, 3 tests.

If it fails with `NoClassDefFoundError` or a resources error, the `testOptions` block from Step 1 is missing or misplaced — it belongs inside `android { }`, not inside `defaultConfig { }`.

- [ ] **Step 6: Run the whole unit-test suite to confirm nothing regressed**

Run from `android/`: `./gradlew testDebugUnitTest`

Expected: PASS, including the pre-existing `CallMonitorServiceTest` and `ExampleUnitTest`.

- [ ] **Step 7: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/brachaai/app/SettingsStore.kt android/app/src/test/java/com/brachaai/app/SettingsStoreTest.kt
git commit -m "feat: add SettingsStore with delete-audio-after-processing flag, default on"
```

---

### Task 2: Rename `AuthBridge` → `NativeBridge` and expose the setting to JS

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/NativeBridge.kt`
- Delete: `android/app/src/main/java/com/brachaai/app/AuthBridge.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/WebViewScreen.kt:34`
- Modify: `android/app/src/main/java/com/brachaai/app/AuthStore.kt:11` (stale comment)
- Create: `android/app/src/test/java/com/brachaai/app/NativeBridgeTest.kt`

**Interfaces:**
- Consumes: `SettingsStore(context)` and its `deleteAudioAfterProcessing` property from Task 1.
- Produces: `class NativeBridge(context: Context, onAuthenticated: () -> Unit)` with `companion object { const val JS_NAME = "BrachaNative" }` and four `@JavascriptInterface` methods — `setAuth(token: String?)`, `clearAuth()`, `getDeleteAudioAfterProcessing(): Boolean`, `setDeleteAudioAfterProcessing(enabled: Boolean)`. Task 4 calls the last two from TypeScript.

**Why the rename:** the class is already registered to JavaScript under the name `BrachaNative`; only the Kotlin class name says "Auth". Adding a storage setting to something called `AuthBridge` is how files stop meaning what their name says. There is exactly one call site (`WebViewScreen.kt:34`), so the rename is cheap. The JS-visible name does not change.

**Note on Robolectric safety:** `NativeBridge` constructs an `AuthStore`, whose `EncryptedSharedPreferences` would need a real Android keystore. That is safe under Robolectric only because `AuthStore.prefs` is declared `by lazy` (`AuthStore.kt:17`) and the test never calls `setAuth`/`clearAuth`. Keep it lazy.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/NativeBridgeTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBridgeTest {

    private fun newBridge() = NativeBridge(RuntimeEnvironment.getApplication()) {}

    @Test
    fun deleteAudioReadsAsOnByDefaultOverTheBridge() {
        assertTrue(newBridge().getDeleteAudioAfterProcessing())
    }

    // The bridge and the store must resolve to the same prefs file and key. If they
    // drift apart the toggle appears to work and silently changes nothing in the
    // background pipeline, which is the worst possible failure for this feature.
    @Test
    fun writingOverTheBridgeIsVisibleToSettingsStore() {
        val app = RuntimeEnvironment.getApplication()
        newBridge().setDeleteAudioAfterProcessing(false)
        assertFalse(SettingsStore(app).deleteAudioAfterProcessing)
    }

    @Test
    fun writingOverTheBridgeRoundTripsBackToTrue() {
        val app = RuntimeEnvironment.getApplication()
        newBridge().setDeleteAudioAfterProcessing(false)
        newBridge().setDeleteAudioAfterProcessing(true)
        assertTrue(SettingsStore(app).deleteAudioAfterProcessing)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `android/`: `./gradlew testDebugUnitTest --tests "com.brachaai.app.NativeBridgeTest"`

Expected: FAIL — compilation error, `Unresolved reference: NativeBridge`.

- [ ] **Step 3: Create `NativeBridge.kt` and delete `AuthBridge.kt`**

Create `android/app/src/main/java/com/brachaai/app/NativeBridge.kt`:

```kotlin
package com.brachaai.app

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Bridge exposed to the WebView as `BrachaNative`.
 *
 * Auth: the web app owns login, so it hands the JWT to native code here; the
 * background recorder has no other way to learn who is logged in.
 *
 * Settings: device-local settings that the background recorder must be able to read
 * offline are owned natively and edited from the web Settings page through here.
 */
class NativeBridge(
    context: Context,
    private val onAuthenticated: () -> Unit
) {
    private val appContext = context.applicationContext
    private val authStore = AuthStore(appContext)
    private val settingsStore = SettingsStore(appContext)

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

    @JavascriptInterface
    fun getDeleteAudioAfterProcessing(): Boolean = settingsStore.deleteAudioAfterProcessing

    @JavascriptInterface
    fun setDeleteAudioAfterProcessing(enabled: Boolean) {
        settingsStore.deleteAudioAfterProcessing = enabled
        Log.d(TAG, "deleteAudioAfterProcessing set to $enabled")
    }

    companion object {
        private const val TAG = "NativeBridge"
        const val JS_NAME = "BrachaNative"
    }
}
```

Then delete the old file:

```bash
git rm android/app/src/main/java/com/brachaai/app/AuthBridge.kt
```

- [ ] **Step 4: Update the single call site**

In `android/app/src/main/java/com/brachaai/app/WebViewScreen.kt`, line 34, replace:

```kotlin
                addJavascriptInterface(AuthBridge(context, onAuthenticated), AuthBridge.JS_NAME)
```

with:

```kotlin
                addJavascriptInterface(NativeBridge(context, onAuthenticated), NativeBridge.JS_NAME)
```

- [ ] **Step 5: Fix the stale comment in `AuthStore.kt`**

In `android/app/src/main/java/com/brachaai/app/AuthStore.kt`, line 11, replace:

```kotlin
 * localStorage and is pushed here via AuthBridge.
```

with:

```kotlin
 * localStorage and is pushed here via NativeBridge.
```

- [ ] **Step 6: Confirm no references to `AuthBridge` remain**

Run from the repo root: `grep -rn "AuthBridge" android/app/src frontend/src`

Expected: no output. Any hit is a missed rename.

- [ ] **Step 7: Run the tests**

Run from `android/`: `./gradlew testDebugUnitTest`

Expected: PASS — 3 `NativeBridgeTest` + 3 `SettingsStoreTest` + the 2 pre-existing tests.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/NativeBridge.kt android/app/src/main/java/com/brachaai/app/AuthBridge.kt android/app/src/main/java/com/brachaai/app/WebViewScreen.kt android/app/src/main/java/com/brachaai/app/AuthStore.kt android/app/src/test/java/com/brachaai/app/NativeBridgeTest.kt
git commit -m "refactor: rename AuthBridge to NativeBridge and expose delete-audio setting to JS"
```

---

### Task 3: `AudioProcessor` — delete the original recording, and stop leaking MP3s

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt:18-24` (constructor), `:52-124` (`processAndSendToBackend`), `:216-234` (`convertToMp3`)
- Modify: `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt:32-38`

**Interfaces:**
- Consumes: `SettingsStore(context)` and `deleteAudioAfterProcessing` from Task 1.
- Produces: `AudioProcessor` constructor gains a sixth named parameter `settingsStore: SettingsStore`. No later task consumes this.

**No unit test in this task, deliberately.** `AudioProcessor` depends on `FFmpegKit`, OkHttp, and live network calls to OpenAI and the backend; there is no seam to test the deletion branch on the JVM without restructuring the class, which is out of scope. Per the spec, its behaviour is covered by the manual verification matrix in Task 5. Tasks 1 and 2 already lock down the flag's storage and transport, which is where a silent failure would actually hide.

**Where deletion goes and why:** immediately after the `when (attemptUpload(payload))` block. That is the first line at which the transcript is durable in every branch — the backend accepted it, or `PendingUploadStore.enqueue` wrote it to disk. Everything that can throw earlier (`convertToMp3` returning null, a Whisper or GPT-4o error, the blank-transcript guard at `:76-84`) exits before this point, so a recording whose transcription failed is never deleted.

- [ ] **Step 1: Add the constructor parameter**

In `AudioProcessor.kt`, replace lines 18-24:

```kotlin
class AudioProcessor(
    private val openAiApiKey: String,
    private val cacheDir: File,
    private val authStore: AuthStore,
    private val pendingStore: PendingUploadStore,
    private val callerLookup: CallerLookup
) {
```

with:

```kotlin
class AudioProcessor(
    private val openAiApiKey: String,
    private val cacheDir: File,
    private val authStore: AuthStore,
    private val pendingStore: PendingUploadStore,
    private val callerLookup: CallerLookup,
    private val settingsStore: SettingsStore
) {
```

- [ ] **Step 2: Restructure `processAndSendToBackend` for guaranteed MP3 cleanup and original deletion**

In `AudioProcessor.kt`, replace the whole of `processAndSendToBackend` (lines 52-124) with:

```kotlin
    suspend fun processAndSendToBackend(audioFile: File) {
        withContext(Dispatchers.IO) {
            // Held outside the try so the finally can always clean it up. Previously the
            // delete sat on the happy path only, so any throw below leaked the MP3 in
            // cacheDir permanently.
            var mp3File: File? = null
            try {
                println("1. Starting processing for: ${audioFile.name}")

                val parsedInfo = parseFilename(audioFile.name)
                println("2. Parsed Info - Name: ${parsedInfo.contactName}, Date: ${parsedInfo.date}")

                println("3. Converting audio to true MP3 format...")
                val converted = convertToMp3(audioFile)

                if (converted == null) {
                    println("ERROR: Audio conversion failed. Stopping process.")
                    return@withContext
                }
                mp3File = converted

                println("4. Uploading MP3 to Whisper...")
                val transcriptText = whisperClient.transcribeAudio(converted)
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
                    // Throwing here also means the recording survives, since the deletion
                    // below is never reached.
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

                // The transcript is durable in every branch above: the backend took it, or
                // PendingUploadStore wrote it to disk for retry, or it was permanently
                // rejected (in which case re-processing the same audio would produce the
                // same transcript and the same rejection). The recording has no further
                // use, so honour the user's storage setting.
                deleteOriginalIfEnabled(audioFile)

            } catch (e: Exception) {
                println("Error during processing: ${e.message}")
                e.printStackTrace()
                throw e
            } finally {
                val temp = mp3File
                if (temp != null && temp.exists() && !temp.delete()) {
                    Log.w(TAG, "Could not delete temp MP3 ${temp.name}")
                }
            }
        }
    }

    /**
     * Removes the original call recording, if the user has left "delete audio after
     * processing" on (the default).
     *
     * Never throws. By the time this runs the transcript has already been delivered or
     * queued, so failing to reclaim storage must not fail the pipeline or trigger the
     * error notification in CallMonitorService.handleNewFile.
     */
    private fun deleteOriginalIfEnabled(audioFile: File) {
        if (!settingsStore.deleteAudioAfterProcessing) {
            println("Keeping ${audioFile.name}; delete-after-processing is off")
            return
        }
        try {
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
```

- [ ] **Step 3: Clean up partial output when conversion fails**

A failed FFmpeg run can leave a truncated MP3 behind, and `convertToMp3` returning null means the `finally` above never learns about it. In `AudioProcessor.kt`, in `convertToMp3`, replace the `else` branch (lines 230-233):

```kotlin
        } else {
            println("Conversion Failed! FFmpeg logs: ${session.failStackTrace}")
            null
        }
```

with:

```kotlin
        } else {
            println("Conversion Failed! FFmpeg logs: ${session.failStackTrace}")
            // FFmpeg may have written a truncated file before failing. The caller gets
            // null and never sees this path, so clean it up here.
            if (outputFile.exists()) {
                outputFile.delete()
            }
            null
        }
```

- [ ] **Step 4: Wire `SettingsStore` into the service**

In `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt`, replace lines 32-38:

```kotlin
        audioProcessor = AudioProcessor(
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            cacheDir = cacheDir,
            authStore = AuthStore(this),
            pendingStore = PendingUploadStore(File(filesDir, "pending")),
            callerLookup = CallerLookup(this)
        )
```

with:

```kotlin
        audioProcessor = AudioProcessor(
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            cacheDir = cacheDir,
            authStore = AuthStore(this),
            pendingStore = PendingUploadStore(File(filesDir, "pending")),
            callerLookup = CallerLookup(this),
            settingsStore = SettingsStore(this)
        )
```

- [ ] **Step 5: Build and run the tests**

Run from `android/`: `./gradlew assembleDebug testDebugUnitTest`

Expected: BUILD SUCCESSFUL, all 8 unit tests pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/AudioProcessor.kt android/app/src/main/java/com/brachaai/app/CallMonitorService.kt
git commit -m "feat: delete original recording after transcript is secured, per user setting"
```

---

### Task 4: Settings page toggle

**Files:**
- Modify: `frontend/src/types/native.d.ts`
- Modify: `frontend/src/pages/SettingsPage/SettingsPage.tsx:1-18` (state + effect), `:20-38` (new handler alongside `handleToggle`), `:124-151` (Call Settings section)
- Modify: `frontend/src/pages/SettingsPage/SettingsPage.module.css:145-147` (add `.amberIcon` and a row divider)

**Interfaces:**
- Consumes: `window.BrachaNative.getDeleteAudioAfterProcessing()` and `.setDeleteAudioAfterProcessing(enabled)` from Task 2.
- Produces: nothing consumed by later tasks.

**Background for the implementer:** the frontend has no test runner — `frontend/package.json` has only `dev`, `build`, `preview`, `lint`. Verification here is `npm run build` (which runs `tsc` first, so type errors fail the build) plus the manual matrix in Task 5. Do not add a test framework for this.

The bridge methods are typed **optional** because the web bundle is committed into `android/app/src/main/assets/www/` by CI and can run inside an older APK that has no `getDeleteAudioAfterProcessing`. Optional typing forces the call sites to guard, which is what makes the row correctly disappear on an old host instead of throwing.

- [ ] **Step 1: Type the new bridge methods**

Replace the whole of `frontend/src/types/native.d.ts` with:

```ts
export {};

declare global {
  interface BrachaNativeBridge {
    setAuth(token: string): void;
    clearAuth(): void;
    /**
     * Device-local storage settings. Optional because the committed web bundle can run
     * inside an older APK whose bridge predates them.
     */
    getDeleteAudioAfterProcessing?(): boolean;
    setDeleteAudioAfterProcessing?(enabled: boolean): void;
  }

  interface Window {
    /** Injected by the Android host. Undefined in a plain browser. */
    BrachaNative?: BrachaNativeBridge;
  }
}
```

- [ ] **Step 2: Add the CSS for the new row**

In `frontend/src/pages/SettingsPage/SettingsPage.module.css`, immediately after the `.purpleIcon` block that ends at line 147, add:

```css
.amberIcon {
    background-color: #fffbeb;
    color: #d97706;
}

/* Call Settings is the first card with more than one row; without this the two rows
   run together. Only applies where a sibling exists, so single-row cards are unchanged. */
.settingItem + .settingItem {
    border-top: 1px solid #f1f5f9;
}
```

- [ ] **Step 3: Add state and read the native value on mount**

In `frontend/src/pages/SettingsPage/SettingsPage.tsx`, replace lines 9-18:

```tsx
    const [user, setUser] = useState<any>(JSON.parse(localStorage.getItem('user') || '{}'));
    const [googleCalendarSync, setGoogleCalendarSync] = useState(user.settings?.googleCalendarSync || false);
    const [autoCallRecording, setAutoCallRecording] = useState(user.settings?.autoCallRecording || false);

    useEffect(() => {
        const storedUser = JSON.parse(localStorage.getItem('user') || '{}');
        setUser(storedUser);
        setGoogleCalendarSync(storedUser.settings?.googleCalendarSync || false);
        setAutoCallRecording(storedUser.settings?.autoCallRecording || false);
    }, []);
```

with:

```tsx
    const [user, setUser] = useState<any>(JSON.parse(localStorage.getItem('user') || '{}'));
    const [googleCalendarSync, setGoogleCalendarSync] = useState(user.settings?.googleCalendarSync || false);
    const [autoCallRecording, setAutoCallRecording] = useState(user.settings?.autoCallRecording || false);
    const [deleteAudioAfterProcessing, setDeleteAudioAfterProcessing] = useState(true);
    const [audioSettingSupported, setAudioSettingSupported] = useState(false);

    useEffect(() => {
        const storedUser = JSON.parse(localStorage.getItem('user') || '{}');
        setUser(storedUser);
        setGoogleCalendarSync(storedUser.settings?.googleCalendarSync || false);
        setAutoCallRecording(storedUser.settings?.autoCallRecording || false);

        // Native-only setting: the Android host owns it so the background call-processing
        // service can read it offline and before login. Absent in a plain browser, where
        // there are no device recordings to delete.
        const bridge = window.BrachaNative;
        if (bridge?.getDeleteAudioAfterProcessing && bridge.setDeleteAudioAfterProcessing) {
            setAudioSettingSupported(true);
            setDeleteAudioAfterProcessing(bridge.getDeleteAudioAfterProcessing());
        }
    }, []);
```

- [ ] **Step 4: Add the handler**

In `frontend/src/pages/SettingsPage/SettingsPage.tsx`, directly after the closing brace of `handleToggle` (line 38), add:

```tsx
    // Deliberately not routed through handleToggle: this setting lives in Android
    // SharedPreferences, not in the backend user record, so it must not PUT /auth/profile.
    const handleDeleteAudioToggle = (value: boolean) => {
        setDeleteAudioAfterProcessing(value);
        window.BrachaNative?.setDeleteAudioAfterProcessing?.(value);
    };
```

- [ ] **Step 5: Add the toggle row**

In `frontend/src/pages/SettingsPage/SettingsPage.tsx`, inside the Call Settings `<div className={styles.settingsCard}>`, directly after the closing `</div>` of the existing Automatic Call Recording `settingItem` (line 149) and before the card's closing `</div>` (line 150), add:

```tsx
                        {audioSettingSupported && (
                            <div className={styles.settingItem}>
                                <div className={styles.settingInfo}>
                                    <div className={`${styles.iconBox} ${styles.amberIcon}`}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <path d="M3 6h18" />
                                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
                                            <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                                            <line x1="10" x2="10" y1="11" y2="17" />
                                            <line x1="14" x2="14" y1="11" y2="17" />
                                        </svg>
                                    </div>
                                    <div className={styles.settingText}>
                                        <span className={styles.settingName}>Delete Audio After Processing</span>
                                        <span className={styles.settingDescription}>Free up storage by removing recordings once transcribed</span>
                                    </div>
                                </div>
                                <label className={styles.switch}>
                                    <input
                                        type="checkbox"
                                        checked={deleteAudioAfterProcessing}
                                        onChange={(e) => handleDeleteAudioToggle(e.target.checked)}
                                    />
                                    <span className={styles.slider}></span>
                                </label>
                            </div>
                        )}
```

- [ ] **Step 6: Type-check and build**

Run from `frontend/`: `npm run build`

Expected: `tsc` reports no errors and Vite writes `dist/`. A `Property 'getDeleteAudioAfterProcessing' does not exist` error means Step 1 was skipped or saved to the wrong path.

- [ ] **Step 7: Lint**

Run from `frontend/`: `npm run lint`

Expected: no new errors. (`--max-warnings 0` — if the pre-existing codebase already fails this, note the baseline and confirm your change adds nothing new.)

- [ ] **Step 8: Commit**

```bash
git add frontend/src/types/native.d.ts frontend/src/pages/SettingsPage/SettingsPage.tsx frontend/src/pages/SettingsPage/SettingsPage.module.css
git commit -m "feat: add delete-audio-after-processing toggle to settings page"
```

---

### Task 5: End-to-end verification on device

**Files:** none modified. This task produces a verification record, not code.

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: nothing.

**Setup:** CI normally copies `frontend/dist/` into `android/app/src/main/assets/www/`. To test locally before CI runs, build the frontend and copy it yourself, then build and install the app.

- [ ] **Step 1: Build the web bundle into the Android assets**

```bash
cd frontend && npm run build && cd ..
cp -r frontend/dist/. android/app/src/main/assets/www/
```

- [ ] **Step 2: Install on a device with call recording enabled**

Run from `android/`: `./gradlew installDebug`

Grant all permissions when prompted, including "all files access" — without it the delete will fail with a logged warning and the recording will survive.

- [ ] **Step 3: Verify the toggle appears and defaults to ON**

Open the app → Settings → Call Settings. Expected: a "Delete Audio After Processing" row below "Automatic Call Recording", switch **on**, amber trash icon, with a divider line between the two rows.

- [ ] **Step 4: Run the verification matrix**

For each case, watch `adb logcat -s AudioProcessor CallMonitorService NativeBridge` and check the recordings folder afterwards with `adb shell ls /storage/emulated/0/Recordings/Call`.

| # | Setup | Expected |
|---|---|---|
| 1 | Toggle ON, make and end a call | Recording gone; transcript appears in the app |
| 2 | Toggle OFF, make and end a call | Recording still present; transcript appears |
| 3 | Toggle ON, airplane mode on, make a call | Recording gone; log shows "Upload failed; queued for retry"; transcript arrives after reconnecting |
| 4 | Toggle ON, force a transcription failure (set an invalid `OPENAI_API_KEY` in `local.properties` and rebuild) | Recording **kept**; error notification fires |
| 5 | Fresh install (`adb uninstall com.brachaai.app`, reinstall), never open Settings, make a call | Recording gone — the default is ON |
| 6 | After case 4, check `adb shell run-as com.brachaai.app ls cache` | No leftover `.mp3` — the `finally` cleanup ran despite the failure |
| 7 | Open the frontend in a desktop browser (`cd frontend && npm run dev`) → Settings | The "Delete Audio After Processing" row is **not** rendered |

- [ ] **Step 5: Restore the real API key**

If case 4 was run, restore the valid `OPENAI_API_KEY` in `android/local.properties` and rebuild. Do not commit `local.properties` — it is untracked by design.

- [ ] **Step 6: Record the results**

Report which of the 7 cases passed. Any failure is a blocker — do not report the feature complete with a case unverified or skipped. If a case cannot be run (e.g. no physical device with call recording), say so explicitly rather than marking it passed.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| `SettingsStore`, plain SharedPreferences, default true, sole owner | Task 1 |
| `AuthBridge` → `NativeBridge` rename + two `@JavascriptInterface` methods | Task 2 |
| `AudioProcessor`: `settingsStore` param, MP3 `finally`, deletion after `when` block | Task 3 |
| Deletion outcome matrix (success / queued / rejected → delete; failures → keep) | Task 3, Step 2 (comments + control flow); verified in Task 5 cases 1-4 |
| Wiring in `CallMonitorService` | Task 3, Step 4 |
| `FileObserver` mask already `CLOSE_WRITE`-only, no change needed | No task — verified during design |
| `native.d.ts` optional methods | Task 4, Step 1 |
| Settings row, exact copy, Call Settings section, native-only handler, browser guard | Task 4, Steps 3-5 |
| Robolectric + `SettingsStoreTest` default/round-trip assertions | Task 1 |
| Manual verification matrix (7 cases) | Task 5 |
| Out of scope: `authController` settings bug, backfill sweep | Global Constraints |

No gaps.

**Placeholder scan:** no TBD/TODO, no "add error handling", no "similar to Task N", no bare prose where code is required. Every code step contains the literal content to write.

**Type consistency:** `SettingsStore.deleteAudioAfterProcessing` (property) is used identically in Tasks 1, 2, and 3. `NativeBridge.JS_NAME` matches its use in `WebViewScreen.kt`. `getDeleteAudioAfterProcessing` / `setDeleteAudioAfterProcessing` are spelled identically in the Kotlin bridge (Task 2), the TypeScript declaration (Task 4 Step 1), and both call sites (Task 4 Steps 3-4). The `AudioProcessor` constructor gains `settingsStore` in Task 3 Step 1 and it is passed by that exact name in Step 4.
