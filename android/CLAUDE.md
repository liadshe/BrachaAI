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

7. **Overlay pipeline** — `PhoneStateReceiver` is manifest-registered rather than a `TelephonyCallback` started from a service, because `ACTION_PHONE_STATE_CHANGED` is exempt from Android 8's implicit-broadcast restrictions and so still fires when the app has been force-stopped. On RINGING it hands the incoming number to `OverlayDecider` — pure decision logic with no Android imports, so every branch is unit-testable without the service around it. `OverlayDecider` normalizes the number through `PhoneNormalizer` (which reduces every spelling of a number — with or without country code, a leading zero, withheld-call sentinels — to one lookup key; it's the only place phone formats are interpreted, and the backend never sees a raw number), looks the key up in `BriefingStore` (a single `briefings.json` snapshot in app-private storage), and treats a miss, or a match with no summary and no open tasks, as "show nothing" — which is why phone matching never has to happen on the backend. A hit renders `CallOverlayService` (a non-foreground `WindowManager` card, gated on `SYSTEM_ALERT_WINDOW`) or, without that permission, `BriefingNotifier`'s high-priority notification; both renderers cap the visible task list at `MAX_TASKS_SHOWN` (a top-level constant in `Briefing.kt` that both read as peers, so they can't disagree on the count) and compute "+N more" off the same untruncated total. EXTRA_STATE_IDLE dismisses both, unconditionally. `BriefingSync` refreshes the snapshot from the backend's `GET /api/briefings` via `BriefingClient`, on a 6-hour tick, right after an upload, and when the app resumes to the foreground; `syncNow()` is `@Synchronized` because those three triggers share one IO coroutine scope and `BriefingStore.replaceAll` writes through a single fixed temp-file path, so unsynchronized concurrent syncs could race on that file. `replaceAll` itself writes to a temp file and then does `Files.move(..., REPLACE_EXISTING)`, so a failed write leaves the previous snapshot exactly as it was rather than destroying it.

## Key Details

- **Backend URL**: `http://193.106.55.154:3000/api/calls` — the deployed backend, over plain HTTP. `usesCleartextTraffic=true` is set in the manifest to allow it. (This base URL is currently duplicated between native and the web frontend rather than shared.)
- **FFmpeg**: Uses `ffmpeg-kit-audio` (not full FFmpeg) — audio-only variant. Runs synchronously via `FFmpegKit.execute()`.
- **Coroutines**: `processAndSendToBackend` is a `suspend` function running on `Dispatchers.IO`. The OkHttp calls inside `WhisperApiClient` and `AudioProcessor` are blocking (not suspend), which is fine on IO dispatcher.
- **Min SDK 26** (Android 8.0).
- **Overlay permission**: `SYSTEM_ALERT_WINDOW` is optional and never gates the WebView. Whenever it isn't held, `MainActivity` overlays a dismissible `Card` prompt on top of the WebView (it doesn't block login or navigation) that deep-links to `ACTION_MANAGE_OVERLAY_PERMISSION`. Dismissal is in-memory only, so the prompt reappears on the next launch until the permission is actually granted. Its bottom padding, `MainActivity.BOTTOM_NAV_CLEARANCE`, is a fixed 96dp sized to clear the web frontend's fixed bottom-nav bar (`frontend/src/components/BottomNav.module.css`) and has to be updated by hand if that CSS's height changes.
- **Shared `TokenRefresher`**: `CallMonitorService.onCreate` builds exactly one `AuthStore` and one `TokenRefresher` and passes both into `AudioProcessor` and `BriefingClient`. `TokenRefresher.refresh()` is `@Synchronized` on the instance, not on the token store, and refresh tokens are single-use — if the uploader and the briefing sync each held their own `TokenRefresher`, they'd hold two separate locks and could both redeem the same refresh token in parallel (e.g. an upload retry and a briefing sync hitting a 401 around the same time), rotating each other out and logging the user out. One shared instance is what makes the single-flight guarantee actually hold.
