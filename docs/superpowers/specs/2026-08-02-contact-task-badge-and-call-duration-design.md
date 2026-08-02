# Contact Task Badge and Call Duration — Design

**Date:** 2026-08-02
**Status:** Approved, ready for planning

## Problem

Two unrelated gaps in the same corner of the product, both about a number that should be on screen and isn't.

1. **The Contacts list says nothing about outstanding work.** `ContactsPage` shows a contact's name, phone, and last note. Whether that contact has three tasks waiting or none is invisible until you open them. The Android call overlay already surfaces exactly this number when the contact rings — the web UI is the one place it's missing.

2. **Every call reads "0 min".** `Call.callLength` exists in the schema (`backend/src/models/Call.ts:14`) and `ContactDetailsPage` renders it, but nothing has ever written it. The Android app doesn't measure duration, doesn't send it, and `callService.saveRawCall` doesn't accept it. `formatDuration` then turns the resulting `undefined` into the string `'0 min'`, so the UI has been confidently reporting a fabricated zero for every call in the database.

The second is not a formatting bug. Fixing the display alone would change "0 min" into "unknown" — correct, but still empty. The number has to be measured at the source.

## Part 1 — Open-task badge

### One definition of "open"

`briefingService.ts:36` defines an open task as `{ completed: false, status: { $ne: 'done' } }` — both fields must agree, because `createTasksFromAi` writes `status` and lets `completed` default while `updateTask` writes both. That constant is currently private to `briefingService`.

**Decision:** move it to a new `backend/src/services/taskFilters.ts`, exported as `OPEN_TASK_FILTER`, and have `briefingService` import it rather than declare it.

The badge on a contact card and the count on the ringing-call overlay describe the same thing to the same user, minutes apart. Two copies of the filter would eventually drift, and the symptom — a card promising two tasks and an overlay showing three — would be near-impossible to attribute. The comment explaining *why* both fields are checked moves with the constant.

### Counting query

`contactService.getContactsWithOpenTaskCounts(userId)` runs two queries for the whole list, never one per contact:

```ts
Contact.find({ userId }).sort({ name: 1 }).lean()
Task.find({ userId, ...OPEN_TASK_FILTER }).select('contactId').lean()
```

The open tasks are tallied by `contactId` in memory and merged onto the contacts. This mirrors `briefingService.fetchRelated`, which made the same two-query call for the same reason: an N+1 here would scale with the size of the address book, and the Contacts page loads the whole thing.

A `$group` aggregation would move the tally into MongoDB and return one row per contact instead of one per task. It is rejected because the aggregation pipeline does not apply Mongoose's schema-based string-to-ObjectId coercion the way `find` does — `userId` would have to be cast by hand, and getting that wrong matches nothing silently, presenting as every badge missing rather than as an error. The volume this saves is negligible: the projection is a single ObjectId per open task, and `briefingService` already ships the same tasks with their titles and priorities attached.

### Response shape

`GET /api/contacts` returns the same array it does now, with one field added per contact:

| Field | Type | Meaning |
|---|---|---|
| `openTaskCount` | `number` | Open tasks for this contact. `0` when there are none. |

Additive, so existing consumers are unaffected. `GET /api/contacts/:id` is left alone — `ContactDetailsPage` loads that contact's tasks in full and has no use for a count.

Contacts with no open tasks are present in the response with `openTaskCount: 0`, not omitted. Absence would be indistinguishable from a failed merge.

### UI

The badge renders inside `clientHeader` on each card, only when `openTaskCount > 0`:

- `1 task` (singular), `2 tasks` (plural).
- `clientHeader` is already `display: flex; justify-content: space-between; align-items: center` (`ContactsPage.module.css:140`), so a second child lands at the card's top-right beside the name with no layout change.
- New `.taskBadge` class in `ContactsPage.module.css`: rounded pill, soft amber background, small semibold text, `flex-shrink: 0` so a long contact name truncates rather than squeezing the badge.

A contact with zero open tasks gets no badge at all. The list stays quiet, and a badge that appears means there is something to act on.

## Part 2 — Call duration

### Where the number comes from

Two sources, tried in order:

1. **The Android call log** (`CallLog.Calls.DURATION`) — the exact talk time, as the OS recorded it.
2. **The recording itself**, measured with `MediaMetadataRetriever` — used when the call log is unavailable.

The call log is preferred because it is the truth about the call rather than about the file. The fallback exists because `READ_CALL_LOG` is an optional permission that `MainActivity` requests opportunistically and never gates on, so a working install can be missing it indefinitely; without a fallback those users would see no duration at all, forever.

`MediaMetadataRetriever` is a platform API, so the fallback adds no dependency. FFprobeKit was rejected on that basis alone — `ffmpeg-kit-audio` is already linked for conversion, but the retriever is cheaper and simpler for a single metadata read.

When both fail, the duration is unknown. That is a first-class outcome, not an error, and never blocks or fails an upload.

### `CallerLookup` returns a match, not a string

`findNumberNear` currently returns `String?`. It becomes:

```kotlin
data class CallLogMatch(val number: String?, val durationSeconds: Int?)
```

The same single query, with `CallLog.Calls.DURATION` added to the projection and the closest-entry-by-time selection unchanged. Both fields are taken from that one best entry.

The two fields are independently nullable, on purpose. A withheld number normalizes to `null` (`CallerLookup.normalize`) while its duration is perfectly good, and a missed-call log entry records `DURATION = 0` alongside a valid number. A duration of `0` or less reads as unknown rather than as a zero-second call.

The existing failure paths — `SecurityException` when the permission is absent, any other exception, no cursor — return `CallLogMatch(null, null)`. Callers keep the behaviour they have today for the number.

### `AudioDuration`

A new single-purpose class wrapping `MediaMetadataRetriever`:

```kotlin
fun secondsOf(file: File): Int?
```

Reads `METADATA_KEY_DURATION` (milliseconds) off the **original** recording and rounds to whole seconds. The original is used rather than the converted MP3 because it is the file that exists on every path, including the ones where conversion failed and the MP3 was cleaned up.

Never throws — an unreadable, truncated, or exotic container yields `null`. The retriever is always released. A duration we cannot measure must not cost a transcript.

### Wiring through the upload

In `AudioProcessor.processAndSendToBackend`:

```kotlin
val callLengthSeconds = match.durationSeconds ?: audioDuration.secondsOf(audioFile)
```

`PendingUpload` gains `callLengthSeconds: Int?`, and `postCall` sends it as `callLength` (JSON null when unknown).

`PendingUploadStore` serializes the field. **The reader must treat a missing key as `null`, not as a parse failure.** Entries queued by the current build have no such key; a strict read would rename every one of them to `*.corrupt` on the first flush after the update, stranding transcripts whose recordings have already been deleted.

The measurement happens before the upload attempt, so a queued call carries its duration through the retry rather than losing it.

### Backend acceptance

`handleIncomingAndroidCall` reads `callLength` from the body and validates it: a finite number, `>= 0`. Anything else — a string, `NaN`, negative, absent — is treated as unknown and dropped.

**A bad duration never produces a 400.** `AudioProcessor.NON_RETRYABLE_CODES` contains 400, so a 400 makes the app drop the payload permanently and delete nothing further — the transcript would be destroyed over a malformed integer. The call is saved without a duration instead.

`saveRawCall` takes an optional trailing `callLengthSeconds?: number` and includes `callLength` in the created document only when it is defined, so unknown durations leave the field unset rather than storing an explicit `null`.

### Display

`formatDuration` moves out of `ContactDetailsPage` into `frontend/src/utils/formatDuration.ts` — it is pure, it needs its own tests, and the page component is already long.

```
formatDuration(seconds?: number): string | null
```

| Input | Output |
|---|---|
| `undefined`, `null`, `NaN`, `Infinity`, `<= 0` | `null` |
| `45` | `0:45` |
| `272` | `4:32` |
| `3600` | `1:00:00` |
| `4360` | `1:12:40` |

Seconds are always two digits; minutes are two digits only in the hours form. Clock style was chosen because it never collapses a short call to zero the way the current `Math.round(seconds / 60)` does, and stays narrow enough to sit beside the timestamp on a phone.

`null` means unknown, and the call row renders the timestamp alone — no separator, no dash, no placeholder:

```
14:03 • 4:32     (known)
14:03            (unknown)
```

Every call currently in the database is unknown, and will stay that way. There is no backfill: the durations were never recorded anywhere, and the recordings they could have been measured from are deleted after processing by default. Showing nothing is honest about that; showing "0 min" was not.

## Testing

Vitest on backend and frontend, JUnit on Android, following the patterns already in each directory.

**Backend**
- Open-task aggregation: a contact with a mix of open and done tasks counts only the open ones; a contact with no tasks reports `0`; another user's tasks never contribute; a task where `completed` and `status` disagree is excluded.
- `callLength` acceptance: a valid number persists; a string, a negative, and `NaN` are each dropped without failing the request; a call with no `callLength` saves as it does today.

**Android**
- `CallerLookup`: duration is read from the closest entry; `DURATION = 0` reads as unknown; a withheld number still yields its duration; a blank number does the same.
- `AudioDuration`: reads whole seconds; rounds to the nearest; reports unknown for absent, unparseable, and zero-length metadata; returns null rather than throwing on a missing file.
- `AudioProcessor`: the upload body carries `callLength`, and carries an explicit JSON null when the duration is unknown.
- `PendingUploadStore`: round-trips an entry with a duration and one without; an entry written without the key (a pre-update queue file) parses with `callLengthSeconds = null` rather than being quarantined.

The *choice* between the two duration sources is one expression inside `processAndSendToBackend`, which has no JVM seam — it runs FFmpeg, Whisper, and a live upload, which is why `AudioProcessorTest` already covers only the methods that can be driven directly. That branch is verified on device rather than in a unit test, as the rest of that pipeline is.

**Frontend**
- `formatDuration`: each row of the table above, plus the hour boundary at exactly `3600`.
- `ContactsPage`: the badge renders `2 tasks` at 2, `1 task` at 1, and is absent at 0.

## Out of scope

- Backfilling durations for existing calls. Impossible — the source data no longer exists.
- Showing a task badge on `ContactDetailsPage` or `HomePage`.
- Showing duration anywhere other than the call rows on `ContactDetailsPage`.
- Making duration searchable, sortable, or filterable.
