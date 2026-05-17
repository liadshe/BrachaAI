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

## Local Setup

The app requires an OpenAI API key. Add to `local.properties` (not committed):

```
OPENAI_API_KEY=sk-...
```

This is injected at build time into `BuildConfig.OPENAI_API_KEY` via `app/build.gradle.kts`.

## Architecture

Single-module Android app (`app`). The core pipeline is:

1. **`FilenameParser.kt`** — Parses audio filenames in the format `ContactName_YYMMDD_HHMMSS.ext` into a `ParsedFile` data class. Splits from right to safely handle names containing underscores.

2. **`AudioProcessor.kt`** — Orchestrates the full pipeline:
   - Parses the filename
   - Converts audio to standard MP3 (128k, 44100Hz, stereo) using FFmpeg
   - Sends the MP3 to OpenAI Whisper for Hebrew transcription
   - POSTs the result to the Node.js backend at `http://10.0.2.2:3000/api/calls`

3. **`WhisperApiClient.kt`** — Sends audio to OpenAI Whisper API (`whisper-1`, `language=he`). Sends the file as `audio.mp3` in the multipart form to avoid encoding issues with Hebrew filenames in HTTP headers.

4. **`MainActivity.kt`** — Compose UI with a single button that triggers `AudioProcessor.processAndSendToBackend()` on a coroutine. Currently hardcoded to a test `.m4a` file in `cacheDir`.

## Key Details

- **Backend URL**: `http://10.0.2.2:3000/api/calls` — this is the Android emulator's alias for `localhost`. `usesCleartextTraffic=true` is set in the manifest to allow plain HTTP.
- **FFmpeg**: Uses `ffmpeg-kit-audio` (not full FFmpeg) — audio-only variant. Runs synchronously via `FFmpegKit.execute()`.
- **Coroutines**: `processAndSendToBackend` is a `suspend` function running on `Dispatchers.IO`. The OkHttp call inside `WhisperApiClient` and the backend call in `AudioProcessor.sendDataToNodeServer` are blocking (not suspend), which is fine on IO dispatcher.
- **Min SDK 26** (Android 8.0).
