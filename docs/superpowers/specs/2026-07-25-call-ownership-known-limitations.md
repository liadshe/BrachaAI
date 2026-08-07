# Call Ownership Fix — Known Limitations and Deferred Findings

**Date:** 2026-07-25
**Branch:** `ben-test-run`
**Plan:** `docs/superpowers/plans/2026-07-25-call-ownership-identity.md`
**Spec:** `docs/superpowers/specs/2026-07-25-call-ownership-identity-design.md`

Every task and the whole branch were reviewed. This records what was deliberately
**not** fixed, so none of it is a silent omission. Nothing below blocks the
outstanding manual verification (Task 11).

## Status

Tasks 1-10 are implemented, reviewed, and committed. **Task 11 — deploy and
verify end to end — has not been done.** It needs a physical device and was
left for the repo owner. No runtime testing of any kind has happened: every
task was verified by compilation and code review only.

**The backend has not been deployed.** This is deliberate and the ordering is
load-bearing: `POST /api/calls` now rejects unauthenticated uploads, so the new
APK must be installed before the backend is deployed. Deploying first means any
call recorded by the old client is lost outright — the old client has no queue.

## Decisions the owner made

- **No automated tests.** The regression test that would pin this bug is one
  case: a call POSTed with user A's token is not visible to user B, and is
  visible to A. It is not written. The ownership invariant is unguarded and can
  regress silently.
- **No migration of existing data.** Calls, contacts, and tasks already filed
  under the seeded fixture user `avia1@gmail.com` stay there, including the
  2026-07-25 test call.
- **`AuthStore` write failures stay silent.** `setToken`/`clear` return `Unit`
  and swallow their own errors, so a failed encrypted write leaves the previous
  token readable and the caller cannot tell. Reviewed twice; the realistic
  failure mode throws at initialization inside the same try, and the blast
  radius is bounded (uploads go to the previous account until the next 401).
  One cheap hardening remains available: `AuthBridge` calls `onAuthenticated()`
  unconditionally after `setToken`, so a swallowed failure still triggers a
  flush under the stale token. Gating that callback on a `Boolean` return would
  close the only path where the swallow does damage.

## Real limitations, not yet fixed

**Uploads are not idempotent.** If the request body is fully delivered and the
call is saved but the connection drops before the `201` arrives, the client
re-queues and retries, producing a duplicate call, duplicate analysis, and
duplicate tasks. Responding before the AI analysis (rather than after) shrank
this window enormously but did not close it. A client-generated UUID plus a
sparse unique index would.

**A call recorded while fully offline is lost.** The retry queue protects the
upload leg only. Transcription happens first, and `WhisperApiClient` throws on
any network failure, so nothing is ever queued — the error notification fires
and the recording is left on disk with no record that it still needs
processing. The plan's goal "a call recorded while logged out is never lost"
holds; offline is a different and more common failure mode.

**A persistently failing queue head blocks everything behind it.** Any status
outside {400, 422} is treated as transient and aborts the flush loop, so an
entry that deterministically fails — 404, 405, 500, a bad gateway during a
deploy — stalls the queue until 30-day eviction. A per-entry attempt counter
would bound it.

**A server restart mid-analysis strands a call at `analysisStatus: 'pending'`
forever.** Nothing sweeps or requeues it. The call and its transcript are safe;
only the summary is missing. Note the field is currently write-only — nothing
in the frontend reads it — so a stranded call is indistinguishable from a
finished one in the UI.

**Transcripts travel over cleartext HTTP** to a hardcoded IP, with a 7-day
bearer token. The WebView already did this before this work; the branch adds a
second always-on plaintext channel from a background service and turns the JWT
into a long-lived native credential. The base URL is also now duplicated
between native (hardcoded in `AudioProcessor`) and web (`VITE_API_URL`), so the
two can drift.

**Phone numbers are not canonicalized to E.164.** Both layers normalize to
digits with an optional leading `+`, consistently. The same person as
`0521234567` outgoing and `+972521234567` incoming still yields two contacts.
Ownership is unaffected; only deduplication suffers.

**~~`callDateTime` is built in the server's timezone~~ — fixed.** The filename's
wall clock is now interpreted in `Asia/Jerusalem` by
`backend/src/utils/callDate.ts` rather than in whatever zone the container
happens to run in, so the stored instant no longer moves with the deployment.
Two caveats remain: calls written *before* this fix are still stored at the old
offset and would need a one-off migration, and the zone is an assumption about
the user, not a fact from the device — the real fix is for the client to send an
instant (it already computes one in `FilenameParser.toEpochMillis`), which only
helps once every installed app has updated.

**Contact matching by name can mislabel.** If two different people were
recorded under the same contact name — "Mom", "Office", "Unknown" — the second
person's number can backfill the first person's placeholder contact. Inherent
to name-fallback matching. A known real number is never overwritten.

**`backend/dist/` is tracked in git.** It is currently in sync, but it will
drift on the next source change, and anyone running `node dist/index.js`
directly from a stale checkout would silently execute pre-fix code. The clean
fix is to gitignore it and stop tracking build output.

## Fixed late, worth knowing about

These were caught by the final whole-branch review, after the per-task reviews
had passed, because they only appear when the layers are viewed together:

- `READ_CALL_LOG` was in an all-or-nothing permission gate. Because the WebView
  is the only source of the auth token, denying that one permission would have
  blocked the token entirely while the recorder kept queueing doomed uploads.
  It is also a hard-restricted permission on API 29+, so on some install paths
  it is denied with no dialog and no way to grant it.
- Express's default 100 kB body limit combined with the client treating `413`
  as permanent meant hour-long transcripts were silently deleted on-device.
- The 401 interceptor redirected to `/login`, which under `HashRouter` on a
  `file://` URL resolves to `file:///login` — a blank page, requiring a
  force-stop to recover. Tokens expire weekly.
- A GPT-4o refusal returns an empty string without throwing; that empty
  transcript would be rejected with 400 and permanently deleted.
