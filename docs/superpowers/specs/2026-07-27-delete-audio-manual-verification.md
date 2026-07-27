# Delete Audio After Processing — Manual Verification Checklist

The cases that need a **real phone call** and **real OpenAI spend**. Each run costs one
Whisper transcription plus one GPT-4o correction.

The automated checks (transcription failure keeps the recording, no leaked MP3, toggle
persistence, row hidden in a browser) were run separately on the emulator — see
`.superpowers/sdd/2026-07-27-delete-audio-after-processing/task-5-report.md`.

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

| Case | Result | Notes |
|---|---|---|
| 1 — ON deletes | | |
| 2 — OFF keeps | | |
| 3 — offline queues | | |
| 5 — fresh-install default | | |
| 6 — no leaked MP3 | | |

Anything that fails: capture the logcat around it and the folder listing before/after. Those
two together are enough to diagnose it.
