# Pending Audio Queue — Design

**Date:** 2026-08-07
**Status:** Approved, ready for planning

## Problem

The retry story is text-only. `AudioProcessor.processAndSendToBackend` runs two network
stages back to back:

1. **Transcription** — the recording goes to Whisper, then the transcript to GPT-4o for
   correction.
2. **Delivery** — the corrected transcript is POSTed to the backend.

Only stage 2 has a failover. When delivery fails, `PendingUploadStore` durably queues the
payload and something later flushes it.

Stage 1 has none. With no internet, `WhisperApiClient.transcribeAudio` throws, the
exception propagates out of `processAndSendToBackend`, and `CallMonitorService.handleNewFile`
catches it and posts an error notification. The recording does survive — the delete sits
below the throw and is never reached — but nothing ever looks at it again. `FileObserver`
only fires on `CLOSE_WRITE` for newly written files, so a recording stranded by an offline
transcription attempt sits in `/storage/emulated/0/Recordings/Call` forever and that call
never reaches the app.

The text queue cannot help here, because with no internet there is no transcript to queue.

## Requirements

1. A recording that could not be transcribed is retried later, from the audio.
2. A recording is **never** deleted unless the call actually landed. The
   "delete audio after processing" setting decides what happens *after* success; it never
   decides whether a failure loses data.
3. Permanently-failing recordings stop retrying, but are still never deleted.

## Approach

Split "make one attempt" from "decide whether to attempt again", and keep a small durable
record of which recordings are finished.

The recording stays where the recorder wrote it. Nothing is copied or moved — no double
disk usage, and the file stays visible in the phone's normal Recordings folder. The
trade-off accepted: if the user or another app clears that folder, the call is gone. An
index in app-private storage tracks which recordings are done or stuck.

### Components

**`RecordingIndex`** *(new)*

A single JSON snapshot in app-private storage, written to a temp file and then renamed over
the previous one — the same atomic-replace pattern as `BriefingStore.replaceAll`, so a
failed write leaves the previous snapshot intact.

Maps recording filename → `{ attempts, done, stuck, lastError }`.

- **No entry** = never attempted. A fresh recording needs no write to be picked up, so the
  common path costs nothing.
- **`done`** = the call landed in the backend, or was deliberately skipped (under 5s).
- **`stuck`** = gave up. The file is kept indefinitely.
- Every sweep prunes entries whose file is no longer present in the watch directory, so the
  index cannot grow beyond the number of files in that folder.

A corrupt or unreadable snapshot degrades to "nothing is done". That is the safe direction:
the worst case is re-transcribing a call, never deleting one.

**`AudioProcessor`** *(modified)*

Same job — one file, one attempt — but it stops throwing and returns a `ProcessOutcome`:
`Completed` / `Skipped` / `RetryLater` / `GiveUp`. It no longer makes any retry decision.

**`PendingAudioQueue`** *(new)*

Owns the retry policy and is the single path by which any recording gets processed. It
lists the watch directory, skips anything marked `done` or `stuck`, runs each remaining
file through `AudioProcessor`, and writes the outcome back to the index.

All processing runs under one mutex, so a sweep and a freshly recorded call cannot process
the same file concurrently. `CallMonitorService.handleNewFile` routes through this class
too rather than calling `AudioProcessor` directly.

On `RetryLater` it increments `attempts`; at `MAX_ATTEMPTS` (5) consecutive failures it
flips the entry to `stuck`. A `GiveUp` outcome flips the entry to `stuck` immediately,
regardless of the attempt count. A `Completed` or `Skipped` outcome marks the entry `done`.

**`WhisperApiClient`** *(modified)*

`transcribeAudio` and `correctSpelling` currently throw a bare
`IOException("Unexpected code $response")` for every non-2xx response, which loses the
status code that `AudioProcessor` now needs to tell `RetryLater` from `GiveUp`. Both throw
a typed exception carrying the HTTP status instead, so a 413/400 (file too large, corrupt)
can be separated from a 429/5xx (rate limit, outage). A network-level exception with no
status remains `RetryLater`.

**`NetworkWatcher`** *(new)*

Wraps `ConnectivityManager.registerDefaultNetworkCallback` and invokes a callback when a
network becomes available **and validated** (`NET_CAPABILITY_VALIDATED`) — not merely
connected, since a captive-portal Wi-Fi would otherwise trigger a sweep that is guaranteed
to fail. Debounced, because Wi-Fi↔cellular handover fires the callback repeatedly.

Registered in `CallMonitorService.onCreate`. Android delivers an immediate callback for the
already-connected network at registration time, so this covers service start and boot
without a separate trigger.

**`CallMonitorService`** *(modified)*

Constructs and wires the above, registers the watcher, unregisters it in `onDestroy`, and
posts the stuck notification.

**Manifest** gains `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`
— a normal permission, no runtime prompt.

### Retry triggers

1. **A validated network becomes available.** The primary trigger, and the direct answer to
   "it failed because I was offline".
2. **After any successful call upload.** A success proves both network and token are good.
   This mirrors the existing `flushPending()` call on the upload success path.

3. **The existing six-hour loop.** Added after review, which showed triggers 1 and 2 left a
   real hole: a transcription that fails *while online* — an OpenAI 429 or 5xx — on a phone
   that stays on one network and takes no further calls is never retried, never reaches the
   attempt cap, never marked stuck, and never reported. That is the original "kept but
   forgotten" bug in a narrower window. This rides the briefing-sync loop that already runs
   in this service, so it adds no timer and no wakeup. The sweep is placed *after* the loop's
   delay rather than before: the first pass runs at service start, before `NetworkWatcher`
   has established whether the network is usable, and `BootReceiver` restarts the service on
   every boot, so sweeping there would burn an attempt off every pending recording on each
   offline reboot.

   This sweep is gated on the device currently having a validated connection, checked at call
   time rather than from a cached flag. Ungated, it was itself a stranding bug: a phone
   offline for around thirty hours would get five sweeps, burn all five attempts against a
   network that was never there, and mark every pending recording `stuck` — kept and
   notified, but never retried again. When connectivity cannot be determined at all, the
   sweep runs anyway, because an unknown state must not silently disable the only backstop:
   burning one attempt is recoverable, never retrying is not.

   Triggers 1 and 2 need no such gate — the network callback fires only for a validated
   network, and the post-upload sweep only runs after a call has actually landed.

### Deletion rule

One gate, in one place. `AudioProcessor` deletes the recording only when the outcome is
`Completed` or `Skipped`, and only then consults `settingsStore.deleteAudioAfterProcessing`.
Every other outcome keeps the file unconditionally.

| Situation | Outcome | File | Retries |
|---|---|---|---|
| Backend accepted the call | `Completed` | deleted if setting on | — |
| Whisper OK, backend down, transcript durably queued | `Completed` | deleted if setting on | text queue handles it (unchanged) |
| Under 5 seconds — deliberately skipped | `Skipped` | deleted if setting on | — |
| No internet / Whisper timeout / 5xx / 429 | `RetryLater` | kept | attempts + 1 |
| Corrected transcript came back blank | `RetryLater` | kept | attempts + 1 |
| FFmpeg conversion failed | `RetryLater` | kept | attempts + 1 |
| Whisper rejects the file (too large, corrupt) | `GiveUp` | kept | none — `stuck` |
| Backend 400/422 rejects the payload | `GiveUp` | kept | none — `stuck` |
| 5 consecutive `RetryLater` failures | → `stuck` | kept | none |

### Deliberate behaviour changes

- **Backend 400/422 no longer deletes the recording.** Today the `Rejected` branch sets
  `transcriptIsDurable = true` and deletes. The call never reached the backend, so under
  requirement 2 the file is now kept and the entry marked `stuck`.
- **A blank corrected transcript now counts attempts.** Today it throws with no bookkeeping,
  so every reconnect would re-run Whisper on it forever. It now goes `stuck` after 5
  attempts and stops burning quota.

`AudioProcessor.queuedTranscriptIsDurable` and its three guards are untouched. They govern
the *transcript* queue's durability, which remains a separate concern from the audio queue.

### Notifications

`stuck` posts one notification on the existing error channel, once, at the moment the entry
flips — not on every sweep. There is no retry action and no UI: the file stays on disk and
the reason is recorded in the index and the log. Recovery is by hand, off the phone's
storage.

`RetryLater` is silent. Being offline is normal and must not notify.

## Testing

All JVM unit tests; no device required.

- **`RecordingIndex`** — round-trip, atomic replace, corrupt snapshot degrading to "nothing
  is done", pruning of entries whose file has vanished.
- **`PendingAudioQueue`** — a fake `AudioProcessor` driving each outcome: attempts
  increment, `stuck` at exactly 5, `done` and `stuck` files skipped on later sweeps, mutex
  prevents double-processing when a sweep and a new-file event overlap.
- **`AudioProcessor`** — the deletion gate for every row of the table above, extending the
  existing `AudioProcessorTest` and `AudioProcessorUploadTest`.

Gradle cannot run on the development machine (loopback error on every task), so these run
in CI only. No claim that the tests pass may be made from local execution.

## Out of scope

- Any frontend/WebView surface for viewing or retrying pending recordings.
- A manual retry action on the notification.
- Copying or moving recordings into app-private storage.
- Changes to `PendingUploadStore` or the transcript queue's semantics.
