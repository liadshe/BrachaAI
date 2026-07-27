# Delete Audio After Processing — Manual Verification Checklist

The complete on-device verification for this feature. Cases 1, 3, and 5 cost one Whisper
transcription plus one GPT-4o correction each; cases A, 4, and 6 are free.

Emulator verification was attempted and abandoned: the AVD's `/data` partition had 466 MB
free against a 95 MB APK plus Android's low-storage headroom, and the install could not
proceed. Rather than uninstall unrelated apps to make room, the three checks that were going
to run there (A, 4, and the browser check) were folded into this list — they take under a
minute each on a real phone.

Case D from that plan — the toggle row must not render in a plain desktop browser — was
verified by code inspection instead: `SettingsPage.tsx` gates the row on
`audioSettingSupported`, which is only set when both bridge methods exist. No runtime check
was performed.

## Prerequisites

- A physical Android phone with call recording enabled (the emulator can't place real calls).
- The app installed from this branch: `cd android && ./gradlew installDebug`.
- All permissions granted, **including "All files access"** — without it the delete silently
  fails, the recording survives, and you'd misread that as case 2 passing.
- **Logged in inside the app.** Without a JWT the upload can't succeed, so cases 1 and 5
  would exercise the queued path instead of the success path.
- The backend at `http://193.106.55.154:3000` reachable from the phone.

## Setup

```bash
export PATH="$PATH:/d/AndroidStudioSDK/platform-tools"
adb devices                       # confirm your phone, not an emulator
```

Watch the pipeline in one terminal while you test. The pipeline logs with `println`, so
`System.out` is required — without it you'll see almost nothing:

```bash
adb logcat -c    # clear first
adb logcat -s AudioProcessor:* CallMonitorService:* NativeBridge:* System.out:*
```

Paths you'll check:

| What | Where |
|---|---|
| Recordings | `/storage/emulated/0/Recordings/Call` |
| The setting | `adb shell run-as com.brachaai.app cat /data/data/com.brachaai.app/shared_prefs/bracha_settings.xml` |
| Temp MP3s | `adb shell run-as com.brachaai.app ls -la /data/data/com.brachaai.app/cache` |
| Retry queue | `adb shell run-as com.brachaai.app ls -la /data/data/com.brachaai.app/files/pending` |

List recordings before and after each call:

```bash
adb shell ls -la /storage/emulated/0/Recordings/Call
```

---

## Case A — The toggle exists and defaults ON (free, do this first)

No call needed. Do this before anything else — it's the cheapest way to catch a broken build.

1. Open the app → Settings → **Call Settings**.
2. Confirm a row titled **Delete Audio After Processing**, description
   *"Free up storage by removing recordings once transcribed"*, sitting directly below
   *Automatic Call Recording*.
3. Confirm its switch is **ON** without you having touched it.
4. Flip it OFF, force-stop the app (`adb shell am force-stop com.brachaai.app`), reopen it.

**Pass:** the row is present, starts ON, and still reads OFF after the restart — proving the
value lives in native storage, not just React state.

**Fail:** row missing entirely → the web bundle in the APK is stale; rebuild the frontend and
copy `frontend/dist/` into `android/app/src/main/assets/www/` before continuing. Row present
but reverts to ON after restart → the bridge write isn't landing.

Set it back **ON** before moving on.

---

## Case 1 — Toggle ON: recording is deleted after a successful transcript

1. Settings → Call Settings → **Delete Audio After Processing** = **ON**.
2. List the recordings folder. Note what's there.
3. Make a short call (20-30s, say a few sentences so the transcript isn't blank — a blank
   transcript deliberately aborts before deletion and would look like a failure here). End it.
4. Wait for the log to reach `SUCCESS! Data sent to backend`.

**Pass:** the new recording is **gone** from the folder, and the log shows
`Deleted original recording <filename>`. The call appears in the app with a transcript.

**Fail:** recording still present after a successful upload, or no deletion line in the log.

---

## Case 2 — Toggle OFF: recording is kept

1. Settings → **Delete Audio After Processing** = **OFF**.
2. Confirm it actually reached native before testing — this is the step that catches a
   toggle that only moved in React:
   ```bash
   adb shell run-as com.brachaai.app cat /data/data/com.brachaai.app/shared_prefs/bracha_settings.xml
   ```
   Expect `delete_audio_after_processing` = `false`.
3. Make another short call. End it. Wait for `SUCCESS! Data sent to backend`.

**Pass:** the recording is **still there**, and the log shows
`Keeping <filename>; delete-after-processing is off`. Transcript still arrives.

**Fail:** recording deleted despite the setting being off. This one is severe — stop and
report it.

Set the toggle back **ON** before continuing.

---

## Case 3 — Offline: recording deleted, transcript queued and delivered later

This is the case where the design is doing something non-obvious: the recording is deleted
even though the upload failed, because the transcript is already safely on disk. Whisper
still needs network, so put the phone offline only *after* transcription.

Simpler equivalent that tests the same branch — make the **backend** unreachable while
leaving internet up:

1. Toggle **ON**.
2. Turn the phone's Wi-Fi/data **off after the call ends but before the upload**, or block
   the backend host. If that timing is awkward, an easier variant: log out of the app, then
   make a call. With no JWT the upload can't authenticate and takes the same queued path.
3. Make a short call. End it.

**Pass:** log shows `Upload failed; queued for retry`; the recording is **gone**; the retry
queue directory is **non-empty**. Then restore connectivity (and log back in if you logged
out) — the queued transcript should upload on the next successful call, and the call appears
in the app.

**Fail:** the recording is deleted but the queue directory is empty. That would mean the
transcript was lost along with the audio — the exact failure the queue-write guard exists to
prevent. Report it immediately.

---

## Case 4 — Failed transcription KEEPS the recording (free — no OpenAI spend)

The safety-critical case. Whisper never gets called, so this costs nothing. Run it with the
toggle **ON** — that's the worst case, where a misplaced deletion would actually fire.

1. Toggle **ON**.
2. Put the phone in **airplane mode** (or otherwise kill all connectivity) *before* the call
   finishes, so the Whisper request fails outright.
3. Make a short call and end it. FFmpeg conversion runs locally and succeeds; the Whisper
   call then fails with a network error.
4. Watch for the error notification the app raises on a failed pipeline.

**Pass:** the recording is **still present**, and the app cache holds **no leftover `.mp3`**:

```bash
adb shell ls -la /storage/emulated/0/Recordings/Call
adb shell run-as com.brachaai.app ls -la /data/data/com.brachaai.app/cache
```

**Fail:** recording deleted after a failed transcription. That is the most serious possible
bug in this feature — the call is unrecoverable. Stop and report it.

Turn airplane mode off afterwards.

---

## Case 5 — Fresh install: default really is ON

1. `adb uninstall com.brachaai.app`
2. `cd android && ./gradlew installDebug`
3. Grant permissions and log in, but **do not open the Settings page at all.** The point is
   that the default holds with no first-run write.
4. Make a short call. End it.

**Pass:** recording deleted. Confirms the default comes from the code path, not from a value
written when Settings is first viewed.

---

## Case 6 — No leaked temp MP3

Rides along with case 1 — no separate call needed.

```bash
adb shell run-as com.brachaai.app ls -la /data/data/com.brachaai.app/cache
```

**Pass:** no `.mp3` files. The converted file is deleted on every exit path, including
failures.

---

## Results

| Case | Cost | Result | Notes |
|---|---|---|---|
| A — toggle present, defaults ON, persists | free | | |
| 1 — ON deletes | 1 call | | |
| 2 — OFF keeps | 1 call | | |
| 3 — offline queues | 1 call | | |
| 4 — failed transcription keeps | free | | |
| 5 — fresh-install default | 1 call | | |
| 6 — no leaked MP3 | free (rides on 1) | | |
| D — row hidden in browser | — | verified by code inspection only | |

If you only have time for two: **Case 4** (nothing is destroyed when transcription fails) and
**Case 2** (OFF is honoured). Those are the two where a bug loses a user's recording.

Anything that fails: capture the logcat around it and the folder listing before/after. Those
two together are enough to diagnose it.
