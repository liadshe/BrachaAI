# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK
./gradlew installDebug           # build + install on connected device/emulator

# Test
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests (requires device/emulator)
./gradlew testDebugUnitTest --tests "com.brachaai.app.ExampleUnitTest"  # single test

# Other
./gradlew clean
```

### Web UI assets

`app/src/main/assets/www/` is a **checked-in build artifact** of `frontend/`, not source. To
change the WebView UI: edit `frontend/`, run `npm run build` there, copy the contents of
`frontend/dist/` over `android/app/src/main/assets/www/`, and commit the result — otherwise
the app ships the previous bundle. `.github/workflows/build-and-copy.yml` does the same copy
on push. There is deliberately no Gradle task for it.

## Local Setup

The app requires an OpenAI API key. Add to `local.properties` (not committed):

```
OPENAI_API_KEY=sk-...
```

This is injected at build time into `BuildConfig.OPENAI_API_KEY` via `app/build.gradle.kts`.

## Architecture

Single-module Android app (`app`). `MainActivity` hosts a WebView (`WebViewScreen.kt`) that loads the bundled web frontend from `file:///android_asset/www/index.html`; login happens there. A background `CallMonitorService` (started once permissions/auth are in place, restarted on boot by `BootReceiver`) watches the recordings directory and runs the transcription pipeline on each new file:

1. **`FilenameParser.kt`** — Parses audio filenames in the format `ContactName_YYMMDD_HHMMSS.ext` into a `ParsedFile` data class. Splits from right to safely handle names containing underscores.

2. **`AudioProcessor.kt`** — Orchestrates the full pipeline:
   - Parses the filename
   - Converts audio to standard MP3 (128k, 44100Hz, stereo) using FFmpeg
   - Sends the MP3 to OpenAI Whisper for Hebrew transcription, then to GPT-4o for spelling/grammar correction
   - Looks up the caller's number via `CallerLookup` (matches the call log against the recording's start time; returns `null` if `READ_CALL_LOG` wasn't granted or nothing matches)
   - POSTs the result, authenticated with the stored JWT, to the backend at `http://193.106.55.154:3000/api/calls`
   - On failure (no token, network error, or a retryable HTTP status) the payload is durably queued via `PendingUploadStore` instead of being lost; non-retryable statuses (400/422 — e.g. an empty transcript) are logged and dropped instead of retried forever

3. **`WhisperApiClient.kt`** — Sends audio to OpenAI Whisper API (`whisper-1`, `language=he`), then the transcript to GPT-4o for correction. Sends the file as `audio.mp3` in the multipart form to avoid encoding issues with Hebrew filenames in HTTP headers.

4. **`MainActivity.kt`** — Hosts the WebView and gates it on runtime permissions (media/notifications, plus "all files access" on API 30+) and "all files access". `READ_CALL_LOG` is requested opportunistically for caller-number lookups but does not gate the WebView, since the WebView is the only source of the auth token.

5. **`NativeBridge.kt`** / **`AuthStore.kt`** / **`SettingsStore.kt`** — The web app owns login; `NativeBridge` is exposed to the WebView as `window.BrachaNative` so JS can push the JWT (`setAuth`) or clear it (`clearAuth`) into native code, and read/write the "delete audio after processing" setting. `AuthStore` persists the token in `EncryptedSharedPreferences` — it's the only component that touches token storage, and it swallows read/write failures rather than throwing; it also records (in plain prefs) whether a token has *ever* been stored, which the pipeline uses to decide whether deleting a recording is safe. `SettingsStore` owns device-local settings in plain SharedPreferences.

6. **`PendingUploadStore.kt`** — Durable on-disk queue (one JSON file per entry under app-private storage) for uploads that couldn't be delivered. Flushed on successful upload, on fresh auth, and via `CallMonitorService.requestFlush()`. Entries older than 30 days or beyond 200 entries are evicted — which now means permanent data loss, since the recording is deleted once its transcript is queued, so `AudioProcessor.queuedTranscriptIsDurable` keeps the recording whenever the queue is at capacity, the queue write failed, or no token has ever been stored. Unparseable entries are renamed aside to `*.corrupt` rather than deleted, so the transcript stays recoverable by hand.

## Key Details

- **Backend URL**: `http://193.106.55.154:3000/api/calls` — the deployed backend, over plain HTTP. `usesCleartextTraffic=true` is set in the manifest to allow it. (This base URL is currently duplicated between native and the web frontend rather than shared.)
- **FFmpeg**: Uses `ffmpeg-kit-audio` (not full FFmpeg) — audio-only variant. Runs synchronously via `FFmpegKit.execute()`.
- **Coroutines**: `processAndSendToBackend` is a `suspend` function running on `Dispatchers.IO`. The OkHttp calls inside `WhisperApiClient` and `AudioProcessor` are blocking (not suspend), which is fine on IO dispatcher.
- **Min SDK 26** (Android 8.0).
