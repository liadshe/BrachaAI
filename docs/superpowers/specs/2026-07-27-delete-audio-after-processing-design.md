# Delete Audio After Processing — Design

**Date:** 2026-07-27
**Status:** Approved, ready for planning

## Problem

Call recordings accumulate on the device forever. `AudioProcessor` deletes the temporary MP3
it generates but never touches the original recording that the OS dialer wrote to
`/storage/emulated/0/Recordings/Call`. On a phone that records every call, this is the
dominant storage cost, and nothing in the app ever reclaims it.

Users need a setting to opt out of that accumulation. It defaults to ON, because saving
storage is the point.

## Goal

A toggle in the Settings page: when ON, the original call recording is deleted once its
transcript has been secured. When OFF, the recording is kept. Default ON.

## Non-goals

- Deleting recordings that already exist on the device from before this feature ships.
  The toggle governs future processing only; there is no backfill sweep.
- Any UI for browsing, playing, or manually deleting recordings.
- Uploading or archiving audio anywhere. The backend stores transcripts only
  (`backend/src/models/Call.ts` has no audio field) and that does not change.
- Syncing this setting across devices. See "Storage" below.

## Key constraint

The code that deletes runs in `CallMonitorService`, a background foreground-service started
on boot by `BootReceiver`. It can process a call when the WebView has never been opened and
the user has never logged in. Therefore the setting must be readable natively, offline, with
a correct default from first boot — it cannot live only in React state or only on the backend.

## Design

### 1. `SettingsStore` (new)

`android/app/src/main/java/com/brachaai/app/SettingsStore.kt`

Wraps a plain `SharedPreferences` file named `bracha_settings`, exposing:

```kotlin
var deleteAudioAfterProcessing: Boolean   // getBoolean(KEY, true) / putBoolean
```

Reading with a default of `true` means the setting is ON from install with no migration
and no first-run write.

Deliberately **not** `EncryptedSharedPreferences`, which `AuthStore` uses. There is no secret
here, and `AuthStore` swallows read/write failures by design — acceptable for a token that can
be re-fetched, wrong for a flag that decides whether to destroy a file. A swallowed failure
here must not silently flip the behaviour.

Following the `AuthStore` convention, `SettingsStore` is the only component that touches
settings storage.

### 2. Bridge: `AuthBridge` → `NativeBridge`

The class is already registered to JS as `BrachaNative` (`AuthBridge.JS_NAME`), so the file and
class are renamed to `NativeBridge` — it is no longer auth-specific. Existing `setAuth` /
`clearAuth` behaviour is unchanged. Two methods are added:

```kotlin
@JavascriptInterface fun getDeleteAudioAfterProcessing(): Boolean
@JavascriptInterface fun setDeleteAudioAfterProcessing(enabled: Boolean)
```

Both delegate to `SettingsStore`. `@JavascriptInterface` marshals `Boolean` natively, so JS
receives a real boolean, not a string.

Call sites to update: `MainActivity.kt`, `WebViewScreen.kt`.

### 3. `AudioProcessor` changes

Constructor gains a `settingsStore: SettingsStore` parameter.

**MP3 cleanup moves into a `finally`.** Today the delete at `AudioProcessor.kt:114-116` sits
inside the `try`, *after* the upload branch. Any earlier throw — the blank-transcript guard at
`:83`, a Whisper or GPT-4o network error — leaks the MP3 in `cacheDir` permanently. Since this
feature exists to reclaim storage, that leak is fixed here rather than left in place.

**Original-recording deletion** happens immediately after the `when (attemptUpload(payload))`
block. That is the first point at which the transcript is durable: either the backend accepted
it, or `PendingUploadStore.enqueue` wrote it to disk for retry.

Deletion semantics, by outcome:

| Outcome | Original recording |
|---|---|
| Upload succeeded | Deleted if flag ON |
| Upload failed transiently → queued in `PendingUploadStore` | Deleted if flag ON — the transcript is on disk and will be retried; re-processing the audio is never attempted by the flush path |
| Upload `Rejected` (400/422) | Deleted if flag ON — re-processing produces an identical transcript and an identical rejection, so keeping the file only accumulates garbage nothing will clean up. Logged at `Log.e`. |
| Conversion failed, Whisper/GPT threw, or transcript came back blank | **Kept**, always — these throw before this point and the call can still be recovered |

A failed `File.delete()` is logged and otherwise ignored; it must never fail the pipeline,
since the transcript has already been delivered by that point.

### 4. Wiring

- `CallMonitorService` constructs `AudioProcessor` — it builds and passes a `SettingsStore`.
- `MainActivity` / `WebViewScreen` construct the bridge — they pass `Context` through as they
  already do for `AuthBridge`.
- `CallMonitorService`'s `FileObserver` is registered with `FileObserver.CLOSE_WRITE` only
  (`CallMonitorService.kt:87` and `:94`, verified). Deleting a file emits `DELETE`, which is
  outside the mask, so our own delete cannot re-trigger `handleNewFile`. No change needed.

### 5. Frontend

**`frontend/src/types/native.d.ts`** — add both methods to the `BrachaNative` interface as
optional, matching how the bridge is already treated as possibly-absent
(`window.BrachaNative?.clearAuth()` at `SettingsPage.tsx:183`).

**`frontend/src/pages/SettingsPage/SettingsPage.tsx`** — a new row in the existing
**Call Settings** section, directly below "Automatic Call Recording":

- Name: `Delete Audio After Processing`
- Description: `Free up storage by removing recordings once transcribed`
- Icon: trash icon in the existing `redIcon` box, matching sibling rows
- Switch markup reuses `styles.switch` / `styles.slider` verbatim — no CSS changes needed

State is initialised in the existing `useEffect` from
`window.BrachaNative?.getDeleteAudioAfterProcessing?.() ?? true`.

Its `onChange` handler is **separate from `handleToggle`**. `handleToggle` PUTs to
`/auth/profile`; this setting is native-only and must not go near the backend.

The row renders only when `window.BrachaNative?.setDeleteAudioAfterProcessing` is present, so
it is absent in a plain browser — where there are no device recordings to delete and the
toggle would be a lie.

## Storage decision: native-only

The value lives in `SharedPreferences`, not in `User.settings` on the backend.

- It is a device-storage setting — "which files exist on *this* phone" does not belong in a
  shared account record.
- It works offline and before first login, which the backend-backed alternative cannot: a call
  processed before login+sync would fall back to a default anyway.
- It avoids a network dependency in a pipeline explicitly built to survive being offline.

Consequence, accepted: the setting does not follow the user across devices or survive a
reinstall. It reverts to the default (ON) in both cases.

### Related pre-existing bug, explicitly out of scope

This design routes around that bug rather than fixing it. Fixing it is a separate change and
should not be bundled here. **Noted so it is not mistaken for something this work introduced.**

## Risks

**Default-ON is destructive for existing users.** Users updating the app begin losing call
recordings without having opted in, and the deletion is irreversible. This is a deliberate,
user-requested choice — saving storage is the stated goal — but it is the single riskiest
property of this design and is recorded here as such.

**The recordings folder is shared.** The file being deleted is the phone's own call recording,
visible to the user's file manager and recorder app. The app has `MANAGE_EXTERNAL_STORAGE`
("all files access", gated in `MainActivity`), so it can delete it. This is user-visible
destruction of a file the app did not create.

## Verification

**Automated.** The repo's Android tests are plain JUnit (`CallMonitorServiceTest.kt`,
`ExampleUnitTest.kt`); there is no Robolectric or mocking library in
`android/gradle/libs.versions.toml`, so `SharedPreferences` cannot be exercised on the JVM as
things stand.

The single highest-value assertion in this feature is that the default is ON. Robolectric is
therefore **added** to `libs.versions.toml` as a `testImplementation` dependency, and
`SettingsStoreTest` asserts:

- `deleteAudioAfterProcessing` is `true` on a fresh `SharedPreferences` with no prior write
- setting it to `false` persists and reads back `false`
- setting it back to `true` persists and reads back `true`

This is one new test dependency and one new test file. `AudioProcessor` itself stays outside
JVM test coverage — it depends on `FFmpegKit`, OkHttp, and live network calls — so its
deletion semantics are covered by the manual matrix below.

**Manual, on device.** Each case checks the recording file in
`/storage/emulated/0/Recordings/Call` after processing completes:

1. Toggle ON, normal call → recording deleted, transcript appears in the app.
2. Toggle OFF, normal call → recording still present, transcript appears.
3. Toggle ON, airplane mode → recording deleted, upload queued in `PendingUploadStore`,
   transcript arrives after reconnect.
4. Toggle ON, forced transcription failure → recording **kept**.
5. Fresh install, never opened Settings, call arrives → recording deleted (default is ON).
6. MP3 cleanup: after a forced Whisper failure, `cacheDir` contains no leftover `.mp3`.
7. Web browser (not the Android app) → the toggle row is not rendered.
