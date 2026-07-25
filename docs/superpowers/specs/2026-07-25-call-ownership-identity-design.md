# Call Ownership & Device Identity — Design

**Date:** 2026-07-25
**Status:** Approved for planning

## Problem

Calls recorded on the phone are transcribed, analysed, and saved successfully, but never appear in the app.

The Android upload path is unauthenticated. `handleIncomingAndroidCall`
(`backend/src/controllers/callController.ts:52-54`) has no way to know who is
recording, so it guesses:

```ts
const firstUser = await userService.getFirstUser();   // User.findOne() — no filter, no sort
const activeUserId = firstUser ? firstUser.id : "65f1234567890abcdef12345";
```

`getFirstUser()` (`backend/src/services/userService.ts:21`) is a bare
`User.findOne()`, which returns the seeded fixture user from
`data/brachaai.users.json` — `avia1@gmail.com`, `_id 6a26957844109e397cd980d3`.

The read path (`callController.ts:35`) filters by the JWT's user:

```ts
const calls = await Call.find({ userId }).populate('contactId')...
```

So every recorded call is filed under `avia1` while the app queries as the
logged-in user. Confirmed against the live deployment: the account in use is
`benlevi8010@gmail.com`, which is not `avia1`, so the calls are invisible. The
same misfiling applies to the contacts and tasks created in that request, since
`getOrCreateContact` and `createTasksFromAi` receive the same guessed id.

The root cause is architectural: **the Android service has no notion of who is
logged in.** Login state lives in WebView `localStorage`
(`frontend/src/pages/LoginPage/LoginPage.tsx:26`), which `CallMonitorService`
and `AudioProcessor` cannot read.

The silent-fallback is what made this so hard to see. A request that could not
prove its identity still returned `200 {"success":true}`, so every layer
reported success while the data went to the wrong owner.

## Goals

- Calls are owned by the user actually logged in on the device, for any user.
- Real caller phone numbers replace the `000-000-000` placeholder on contacts.
- A call recorded while logged out or with an expired token is never lost.
- A request that cannot prove its identity fails loudly.

## Non-Goals

- Migrating the existing mis-owned records. Decision: fix forward; the data
  currently under `avia1` (including the 2026-07-25 test call) stays there.
- Refresh tokens / silent renewal. The 7-day expiry stays; the offline queue
  covers the gap instead.
- Moving login into native code.
- Full E.164 phone normalisation with country inference.
- Automated tests. See "Explicitly Deferred" below.

## Architecture

Identity flows from the WebView, where login happens, into native code, where
uploading happens:

```
LoginPage (WebView)
   │  window.BrachaNative.setAuth(token)
   ▼
AuthBridge  ──►  AuthStore (EncryptedSharedPreferences)
                     │  getToken()
                     ▼
              AudioProcessor  ──►  POST /api/calls  (Authorization: Bearer …)
                     │                    │
                     │ 401 / network      │ 201
                     ▼                    ▼
             PendingUploadStore      Call owned by req.user.id
```

### Android components

**`AuthStore`** — the single owner of token storage. Wraps
`EncryptedSharedPreferences` (new dependency: `androidx.security:security-crypto`)
behind `getToken(): String?`, `setToken(String)`, `clear()`. Every other
component goes through it, so swapping to device tokens later touches one file.

**`AuthBridge`** — the `@JavascriptInterface` object registered in
`WebViewScreen` as `BrachaNative`. Exactly two methods, `setAuth(token)` and
`clearAuth()`, both delegating to `AuthStore`. `setAuth` additionally triggers a
queue flush. No other logic.

Injection risk is acceptable here because the WebView only ever loads
`file:///android_asset/www/` (`MainActivity.kt:70`) — no remote origin can reach
the bridge.

**`CallerLookup`** — queries the `CallLog.Calls` provider for the entry nearest
the recording's start time, within ±2 minutes, returning `NUMBER`. It matches on
the timestamp parsed from the filename, not the file's mtime, because
`FileObserver.CLOSE_WRITE` fires at call *end* while the filename records call
*start*. Withheld and private numbers arrive empty or as `-1`/`-2`/`-3`; these
map to `null`.

Requires `READ_CALL_LOG` in the manifest and in `MainActivity.requiredPermissions`.

**`PendingUploadStore`** — one JSON file per pending upload under
`filesDir/pending/`, holding `contactName`, `date`, `callerNumber`, `transcript`.
It queues the transcript, never the audio, so a retry never re-pays for
transcription. Capped at 200 entries or 30 days, oldest evicted first, so a
permanently logged-out device cannot exhaust storage.

Flush triggers: `CallMonitorService.onCreate`, and `AuthBridge.setAuth` — the
moment the user logs back in.

**`AudioProcessor`** — `sendDataToNodeServer` gains an
`Authorization: Bearer <token>` header and a `callerNumber` body field. On `401`
or a network error it hands the payload to `PendingUploadStore` instead of
throwing it away. A missing token short-circuits to the queue without an HTTP
call.

### Frontend changes

Three call sites, all using optional chaining so the same bundle still runs in a
desktop browser where `BrachaNative` is undefined:

- `LoginPage.tsx` and `SignupPage.tsx` — after storing the token,
  `window.BrachaNative?.setAuth(token)`.
- `apiClient.ts:23` — the existing 401 interceptor also calls
  `window.BrachaNative?.clearAuth()`, so native and web never disagree about
  being logged out.
- `App.tsx` — on mount, push the current `localStorage` token to native. This
  covers users who are *already* logged in when the bridge ships, and app
  upgrades. Without it the bridge stays empty until the next manual logout.

A TypeScript declaration for `window.BrachaNative` accompanies these.

The frontend bundle must be rebuilt into `android/app/src/main/assets/www/`,
which is how the WebView is served.

### Backend changes

**Ownership.** `POST /api/calls` becomes authenticated:

```ts
router.post('/calls', protect, handleIncomingAndroidCall);
```

`handleIncomingAndroidCall` takes `AuthRequest` and reads `req.user.id`. The
`getFirstUser()` call and the `"65f1234567890abcdef12345"` fallback are removed,
and `getFirstUser` is deleted from `userService.ts` so it cannot be
reintroduced.

**Contact matching.** `getOrCreateContact` accepts the caller number and
resolves in order:

1. Match on normalised phone.
2. Else match on name, backfilling `phone` if it is still the `000-000-000`
   placeholder.
3. Else create.

When the number is withheld, fall back to name-only matching and keep the
placeholder, since `Contact.phone` is `required: true`
(`backend/src/models/Contact.ts:13`). Normalisation is digits-only with a
preserved leading `+`.

**Response ordering.** Today `saveRawCall` runs before `analyzeTranscript`
(`callController.ts:67-75`), so an OpenAI failure returns 500 *after* the call is
already persisted. A retrying client would then re-upload a call that was in
fact saved, so adding the queue on top of today's ordering would actively
manufacture duplicates.

The controller therefore responds `201` as soon as the call is persisted, and
runs the analysis afterwards. The UI already expects this —
`HomePage.tsx:152` renders `'Summary pending analysis...'` when `callSummary` is
absent.

**`analysisStatus`.** The `Call` model gains
`analysisStatus: 'pending' | 'done' | 'failed'`, defaulting to `'pending'`, set
to `'done'` by `updateCallWithAnalysis` and to `'failed'` in the analysis catch
block. Without it a failed analysis is indistinguishable from a slow one — the
same invisible-failure trap as the original bug.

## Error Handling

| Condition | Behaviour |
|---|---|
| No token stored | Queue locally, no HTTP call. Flushed on next login. |
| `401` from backend | Queue locally, clear the stored token. Flushed on next login. |
| Network error / `5xx` | Queue locally, retried on next service start. |
| Queue over capacity | Evict oldest; log the eviction. |
| Call log unreadable / permission denied | `callerNumber: null`; contact keeps the placeholder. |
| Number withheld or private | `callerNumber: null`; name-only contact matching. |
| OpenAI analysis fails | Call persists, `analysisStatus: 'failed'`; the `201` already went out. |
| Transcription fails | Unchanged — existing `notifyError` notification. |

## Explicitly Deferred

**Automated tests — deliberately skipped at the user's direction (2026-07-25).**

The backend has no test framework today. The regression test that would pin this
bug is a single case: *a call POSTed with user A's token is not visible to user
B, and is visible to A.* It is not being written now, so the ownership
invariant remains unguarded and could regress silently — the same failure mode
being fixed here. Recorded so the gap is a known, revisitable decision rather
than an oversight.

## Verification

Manual, after implementation:

1. Log in as `benlevi8010@gmail.com`, record a call, confirm it appears with the
   correct contact name and a real phone number.
2. Check backend logs: the `[DEBUG]` ids from the webhook and from `getCalls`
   must match.
3. Log out, record a call, log back in — the queued call uploads and appears.
