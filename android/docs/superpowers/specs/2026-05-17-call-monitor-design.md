# Call Recording Monitor — Design Spec

**Date:** 2026-05-17  
**Status:** Approved

## Goal

Automatically detect new call recordings written to `/storage/emulated/0/Recordings/Call/` and run the existing BrachaAI processing pipeline (filename parse → FFmpeg MP3 conversion → OpenAI Whisper transcription → POST to Node.js backend) without any user interaction.

## Requirements

- Monitoring runs always — survives app close
- Restarts automatically after device reboot
- On processing failure: show an error notification and log the exception
- No in-app stop button required

## Approach

**FileObserver in a ForegroundService + BootReceiver.**  
`CLOSE_WRITE` events give instant, zero-polling detection the moment the recording app finishes writing. A foreground service keeps the observer alive. A boot receiver restarts it after reboot.

## Components

### `CallMonitorService` (new)

A `ForegroundService` that owns the `FileObserver`.

- `onCreate`: creates notification channels, calls `startForeground()`, starts `FileObserver` on `CLOSE_WRITE` for `/storage/emulated/0/Recordings/Call`
- `onStartCommand`: returns `START_STICKY`
- On `CLOSE_WRITE` event: launches a coroutine on a `SupervisorJob` scope (so one failure doesn't cancel concurrent processing), calls `AudioProcessor.processAndSendToBackend(file)`
- On exception: posts error notification ("Failed to process call: [filename]") and logs stack trace
- `onDestroy`: calls `fileObserver.stopWatching()` and cancels the coroutine scope
- Two notification channels:
  - `call_monitor` — `IMPORTANCE_LOW`, persistent "Monitoring call recordings…" (silent)
  - `call_monitor_errors` — `IMPORTANCE_DEFAULT`, error alerts

`FileObserver` instantiation:
- API 29+: `FileObserver(File(watchDir), CLOSE_WRITE)`
- API 26–28: `@Suppress("DEPRECATION") FileObserver(watchPath, CLOSE_WRITE)`

Android 14+ (`UPSIDE_DOWN_CAKE`): `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`.

### `BootReceiver` (new)

A `BroadcastReceiver` listening for `ACTION_BOOT_COMPLETED`. Calls `ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))`.

### `MainActivity` (updated)

- Requests runtime permissions on launch via `ActivityResultContracts.RequestMultiplePermissions()`
  - Android 13+ (`TIRAMISU`): `READ_MEDIA_AUDIO` + `POST_NOTIFICATIONS`
  - Android 12 and below: `READ_EXTERNAL_STORAGE`
- If all permissions granted → starts `CallMonitorService`
- If denied → shows inline message: "Storage permission required for monitoring to work"
- UI updated from test button to status screen: "Monitoring active — watching …/Call/"

### `AndroidManifest.xml` (updated)

New permissions:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

New declarations:
```xml
<service
    android:name=".CallMonitorService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />

<receiver
    android:name=".BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

## Data Flow

```
Call ends → recording app closes file → CLOSE_WRITE fires
  → CallMonitorService.onEvent(path)
    → coroutine (Dispatchers.IO, SupervisorJob)
      → AudioProcessor.processAndSendToBackend(File(watchDir, path))
          parse → convert MP3 → Whisper → POST to backend
          └─ success: temp mp3 deleted
          └─ failure: error notification + log
```

## Error Handling

- All processing errors are caught by the existing `try/catch` in `AudioProcessor.processAndSendToBackend`
- `CallMonitorService` wraps the coroutine in an additional `try/catch` to post the error notification
- Missed files during service downtime (e.g., killed then restarted) are not retried — acceptable for v1

## Files Changed

| File | Change |
|------|--------|
| `app/src/main/java/com/brachaai/app/CallMonitorService.kt` | New |
| `app/src/main/java/com/brachaai/app/BootReceiver.kt` | New |
| `app/src/main/java/com/brachaai/app/MainActivity.kt` | Updated |
| `app/src/main/AndroidManifest.xml` | Updated |
