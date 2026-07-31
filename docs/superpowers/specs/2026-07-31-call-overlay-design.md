# Incoming-Call Briefing Overlay — Design

**Date:** 2026-07-31
**Branch:** `overlay`
**Status:** Approved for planning

## Problem

When a known contact calls, the user has no idea what was last discussed with them or what
they owe them. That context already exists in BrachaAI — call summaries and AI-extracted
tasks — but it is locked inside the app, and nobody opens an app while their phone is
ringing.

## What we are building

When a known contact calls, a floating card appears over the incoming-call screen showing
the contact's name, the summary of the last call with them, and their open tasks. It stays
visible through the conversation and disappears when the call ends.

## Decisions

| Decision | Choice |
|---|---|
| Trigger | Incoming calls only, and only for contacts already known to BrachaAI |
| Unknown numbers | Nothing shows — no card, no network call |
| Data source | Local cache, refreshed in place during the ring if the network is quick |
| Lifecycle | Persists through the answered call; auto-dismisses when the call ends |
| Interaction | Read-only. X to dismiss, tap anywhere else to open the contact in the app |
| Mechanism | `SYSTEM_ALERT_WINDOW` overlay, degrading to a notification when not granted |

### Rejected alternatives

- **`CallScreeningService`** — only one app on a device can hold the call-screening role, so
  it competes with spam blockers, and the role request is high-friction. It also does not
  draw anything, so the overlay would still be needed. Strictly more work for less.
- **Notification only** — cannot match the intended card, collapses after seconds, stacks
  awkwardly against the system's own incoming-call notification, and will not persist
  through the call. Retained as the *fallback* renderer, not the primary path.
- **Live fetch on ring, no cache** — a backend round trip plus a possible token refresh can
  easily miss the ring window, and yields a blank card on a bad network. Cache-first is what
  makes the card feel instant.
- **Cache only, no live refresh** — simple, but shows stale data with no path to correct it
  when the network is in fact fine.

## Architecture

```
  Incoming call
        │
        ▼
  PhoneStateReceiver ──RINGING(number)──▶ CallOverlayService
   (manifest)                                   │
        │                                       ├─▶ PhoneNormalizer ─▶ phoneKey
        └──IDLE──────────────────────────▶      ├─▶ BriefingStore.lookup(phoneKey)
                                     (dismiss)  │      │
                                                │      ├─ miss ─▶ stop, show nothing
                                                │      └─ hit  ─▶ OverlayDecider
                                                │                     │
                                                │        ┌────────────┴────────────┐
                                                │   canDrawOverlays?          otherwise
                                                │        │                         │
                                                │   CallOverlayView          Notification
                                                │   (WindowManager)            (fallback)
                                                │
                                                └─▶ GET /api/briefings/:contactId
                                                        (live refresh, swap if in time)

  CallMonitorService ──periodic tick──┐
  Successful call upload ─────────────┼──▶ BriefingSync ──▶ GET /api/briefings ──▶ BriefingStore
  App foreground ─────────────────────┘
```

## Backend

Two endpoints in a new `briefingRoute.ts` / `briefingController.ts` / `briefingService.ts`
trio, matching the existing route/controller/service split. Both are behind `protect` and
scoped to the authenticated user.

- `GET /api/briefings` — every contact for the user, with their briefing. Consumed by the
  background sync.
- `GET /api/briefings/:contactId` — a single contact. Consumed by the live refresh during a
  ring, after the device has already matched the number against its cache. Returns 404 if
  the contact does not exist or belongs to another user.

Response shape (a single object for `:contactId`, an array of the same for the collection):

```json
{
  "contactId": "665f1a...",
  "name": "David Cohen",
  "phone": "+972501234567",
  "lastCall": {
    "summary": "Promised to send price quote for website redesign.",
    "dateTime": "2026-07-28T10:04:00.000Z"
  },
  "openTasks": [
    { "id": "665f2b...", "title": "Send contract by Tuesday", "priority": "HIGH" },
    { "id": "665f2c...", "title": "Check availability for meeting", "priority": "MEDIUM" }
  ],
  "openTaskCount": 2
}
```

Rules:

- `lastCall` is the most recent `Call` for the contact **with a non-empty `callSummary`**,
  so a call still awaiting AI analysis does not mask the last useful one. `null` when the
  contact has no summarised calls.
- `openTasks` is `status != 'done'`, sorted `HIGH → MEDIUM → LOW` then newest first,
  **capped at 5 server-side**. The cap keeps the sync payload bounded for a contact with a
  long backlog; the card shows fewer still (see below).
- `openTaskCount` is the **untruncated** total of open tasks. Without it the card's
  `+N more` would be computed from the capped list and would lie — a contact with 40 open
  tasks would read `+2 more`.
- No phone-number handling anywhere on the backend. See below for why.

### Why matching is device-side only

Because unknown numbers show nothing, a cache miss is a complete answer — the device never
needs to ask the server "who is this number?". So the backend returns `phone` exactly as
stored and never normalizes or queries by it. This removes the need for two number-matching
implementations (Kotlin and TypeScript) that must agree forever, and it means existing
`Contact` records need no migration or backfill.

## Phone normalization

The single riskiest piece of logic in the feature. One pure Kotlin class, `PhoneNormalizer`,
used for both sides of every comparison — the incoming call's number and each cached
contact's `phone`.

Algorithm:

1. If the input is a withheld-number sentinel (`-1`, `-2`, `-3`), blank, or contains no
   digits, return `null` — no match, no card.
2. Strip everything that is not a digit, remembering nothing about formatting.
3. If the result starts with the country code (`972` by default) **and** is longer than 9
   digits, drop the country code.
4. Drop a single leading `0`.
5. If more than 9 digits remain, keep the last 9. This is the fallback that makes foreign
   numbers match consistently without teaching the app every country's dialling plan.
6. If fewer than 4 digits remain, return `null` — too short to identify anyone.

The country code is a constant with a named default rather than a magic number inline, so a
non-Israeli deployment is a one-line change.

### Test vectors

Both the incoming-number path and the cached-contact path run through this table.

| Input | Normalized | Note |
|---|---|---|
| `+972501234567` | `501234567` | mobile, international |
| `0501234567` | `501234567` | mobile, national |
| `050-123-4567` | `501234567` | mobile, punctuated |
| `050 123 4567` | `501234567` | mobile, spaced |
| `+972-50-123-4567` | `501234567` | international, punctuated |
| `+97231234567` | `31234567` | landline, international |
| `031234567` | `31234567` | landline, national |
| `03-123-4567` | `31234567` | landline, punctuated |
| `+14155552671` | `155552671` | foreign number, last-9 fallback |
| `-1` / `-2` / `-3` | `null` | withheld / private / payphone |
| `""` / `null` | `null` | absent |
| `abc` | `null` | no digits |

The first eight rows are the contract that matters: every spelling of the same Israeli
number must collapse to one key.

## Android components

### `PhoneStateReceiver`

Manifest-registered receiver for `android.intent.action.PHONE_STATE`. This broadcast is on
the exemption list for Android 8's implicit-broadcast restrictions, so a manifest receiver
still fires with the app closed — more robust than registering a `TelephonyCallback` from a
service that may have been killed.

- `EXTRA_STATE_RINGING` with `EXTRA_INCOMING_NUMBER` → start `CallOverlayService` with the
  number.
- `EXTRA_STATE_IDLE` → tell `CallOverlayService` to tear down.
- `EXTRA_STATE_OFFHOOK` → ignored. The card is meant to persist through the answered call.

Permissions: `READ_PHONE_STATE` (new, normal runtime prompt). On Android 9+ the number is
only populated if `READ_CALL_LOG` is also held — the app already requests it for
`CallerLookup`, so no new user-facing ask there.

### `BriefingStore`

A single JSON snapshot in app-private storage, replaced wholesale on each sync, indexed by
normalized phone key. Deliberately **simpler than `PendingUploadStore`**: that store uses
one durable file per entry because losing an entry means losing a transcript forever. This
is disposable derived data — a corrupt or missing snapshot means "no card until the next
sync", never data loss. So: read failures are swallowed and treated as an empty cache, and
there is no `.corrupt` quarantine dance.

Writes are atomic (temp file + rename) so a call arriving mid-sync can never read a
half-written snapshot.

Interface is one method the ring path cares about: `lookup(phoneKey: String): Briefing?`.

### `BriefingSync`

Fetches `GET /api/briefings` with the stored JWT, reusing `AuthStore` and `TokenRefresher`
exactly as the upload path does, and rewrites the snapshot. Triggered on:

- a successful call upload — the data just changed;
- `MainActivity.onResume` — the user may have edited tasks in the WebView;
- a periodic tick, **every 6 hours**.

The tick is a coroutine inside the existing `CallMonitorService` rather than a new
WorkManager dependency. That service is already kept alive for `FileObserver`, so the
reliability is already paid for; and if it is dead, the app is not recording calls either,
so there is nothing new to sync.

A sync failure is logged and leaves the previous snapshot in place. There is no retry
storm — the next trigger will pick it up.

### `OverlayDecider`

Pure Kotlin, no Android imports, so every branch is unit-testable:

- number normalizes to `null` → do nothing;
- cache miss → do nothing;
- cache hit and `canDrawOverlays()` → render overlay;
- cache hit and no overlay permission → render notification;
- a card is already showing → replace its content rather than stacking a second window.

### `CallOverlayService`

A thin shell around `OverlayDecider`, holding the parts that cannot be unit-tested. Started
service; the `SYSTEM_ALERT_WINDOW` grant exempts it from Android 12's background
foreground-service-start restriction, which is the same grant the overlay itself needs.

- Stops immediately on a cache miss, so unknown callers cost nothing.
- Fires `GET /api/briefings/:contactId` in parallel with showing the card, and swaps in the
  fresher content **only if it returns while the card is still up**.
- Tears down on `IDLE`.
- A **30-minute** hard timeout backstops teardown, so a missed or dropped `IDLE` broadcast
  can never strand a card on screen indefinitely. It is set well past any plausible call
  length precisely because it is a backstop, not a display policy — `IDLE` is what normally
  ends the card.

### `CallOverlayView`

Plain XML layout inflated into a `WindowManager` view of type `TYPE_APPLICATION_OVERLAY`.

Not Compose: attaching a `ComposeView` outside an Activity requires hand-wiring lifecycle
and saved-state owners onto a raw window, which is fiddly and a known crash source. The card
is static, read-only text. The accepted tradeoff is that it does not share the Compose theme
`MainActivity` uses, so its colors and type are defined once in resources to match.

Layout, following the reference mockup: avatar circle, contact name, a "Last Interaction"
block with the last call summary, then "Open Tasks" as a bulleted list.

- **Three tasks maximum**, with a `+N more` line derived from `openTaskCount` (not from the
  length of the returned list). The card sits over a live call screen and must not grow tall
  enough to obscure the answer control.
- Positioned top-centre.
- Non-focusable (`FLAG_NOT_FOCUSABLE`) so it never steals touches from the dialer's
  answer/reject buttons.
- `lastCall == null` → the "Last Interaction" block is omitted, not shown empty.
- No open tasks → the "Open Tasks" block is omitted.
- Both absent → no card at all; there is nothing to say.
- X dismisses. Tapping anywhere else opens `MainActivity` with a `contactId` extra, which
  loads the WebView at `#/contacts/<id>` — a route that already exists in the frontend.

### Notification fallback

When `Settings.canDrawOverlays()` is false, the identical briefing renders as a
high-priority notification instead of a window. Same trigger, same cache, same data — a
branch inside the service, not a parallel subsystem. This also covers OEM skins that grant
overlay rights and later revoke them quietly.

### Permission onboarding

`SYSTEM_ALERT_WINDOW` cannot be granted by a runtime prompt; it is a Settings toggle reached
via `ACTION_MANAGE_OVERLAY_PERMISSION`.

`MainActivity` currently blocks the WebView until core permissions are granted. **The
overlay must not join that gate** — the app is fully usable without it, and blocking login
on an optional feature would be a regression. Instead:

- a one-time dismissible card in the app explaining what the permission unlocks, with a
  button that deep-links to the settings screen;
- a re-entry point in the app's Settings page for anyone who declines and later changes
  their mind.

## Testing

JVM unit tests, alongside the existing suite in `android/app/src/test/`:

- `PhoneNormalizerTest` — the full vector table above.
- `BriefingStoreTest` — round-trip, empty cache, corrupt file recovers as empty, atomic
  replace leaves no partial state.
- `OverlayDeciderTest` — every branch: null number, cache miss, hit with permission, hit
  without permission, replace-while-showing.
- `BriefingSyncTest` — success writes the snapshot; failure leaves the previous one intact.

Backend, following the existing `.test.ts`-beside-the-service convention:

- `briefingService.test.ts` — last summarised call wins over a newer unsummarised one; task
  ordering and the cap at 5; `openTaskCount` reports the untruncated total when the list is
  capped; contact with no calls; contact scoping by user.

### Manual test checklist

These cannot be meaningfully automated and must be walked through on a real device:

1. Known contact calls → card appears while ringing, with correct name, summary, tasks.
2. Answer the call → card persists through the conversation.
3. End the call → card disappears.
4. X during ring → card disappears, answer/reject still work.
5. Tap the card → app opens on that contact's page.
6. Unknown number calls → nothing appears.
7. Contact with no open tasks → card shows summary only, no empty section.
8. Airplane mode → card still appears from cache.
9. Overlay permission revoked → notification appears instead.
10. App force-stopped, then a call arrives → receiver still fires and the card appears.
11. Second call immediately after the first → content replaces, no stacked windows.
12. Rapid ring → reject → ring again → no stranded card.

## Known considerations

**Summaries are visible without unlocking.** The card renders over the ringing screen, which
on most devices is reachable without authenticating. Anyone glancing at the phone sees what
was last discussed with that contact and what is owed to them. This design does **not** gate
the card behind an unlocked device. If that becomes a concern, the natural fix is a setting
that suppresses content (name only) while the keyguard is up — deliberately deferred rather
than built speculatively.

**OEM overlay restrictions.** Xiaomi, Huawei, Oppo and others layer an extra, separately
managed background-overlay permission on top of `SYSTEM_ALERT_WINDOW`. The notification
fallback is what keeps the feature alive on those devices; we are not going to chase
per-vendor settings deep links.

**Recording pipeline untouched.** This feature reads from the same data the existing
transcription pipeline writes, and shares `AuthStore`/`TokenRefresher`, but adds no coupling
to `AudioProcessor` beyond one sync trigger on successful upload.

## Out of scope

- Outgoing-call briefings.
- Marking tasks done from the card.
- Cards for unknown or new numbers.
- Any change to how calls are recorded, transcribed, or analysed.
