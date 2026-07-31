# Delete Calls and Contacts — Design

**Date:** 2026-07-27
**Status:** Approved, ready for planning

## Problem

There is no way to remove data from Bracha AI. Calls accumulate forever, and a contact created by mistake — or one whose history is no longer wanted — cannot be removed. No DELETE route exists anywhere in the backend: `callRoute.ts`, `contactRoute.ts`, and `taskRoute.ts` expose only GET, POST, and PATCH.

Two capabilities are needed:

1. Delete one or more calls, selected WhatsApp-style by long-pressing a call and ticking others.
2. Delete a contact along with all of its calls and tasks.

## Decisions

### Tasks are not linked to calls

`Task` carries `userId` and `contactId` only (`backend/src/models/Task.ts`). AI-generated tasks are created from a call but record only the contact (`callController.ts`, `runAnalysis`). There is no way to tell which call produced a given task.

**Decision:** leave it that way. Deleting a call removes the call record only — its transcript and summary. Tasks survive under the contact. Tasks are only deleted when their contact is deleted.

This means no schema change and no data migration. The alternative — adding a `callId` to `Task` and backfilling existing rows by guessing which call produced which task — would risk silently deleting the wrong tasks later.

### Deletes are permanent

A delete removes the document from MongoDB. There is no trash, no soft-delete flag, and no undo.

The safety mechanism is a confirmation dialog that states what will be destroyed before it happens. This matters because call transcripts cannot be recovered: the Android app uploads a transcript once and does not re-send it, so a deleted transcript is gone for good.

Soft-delete was rejected because it would require adding a `deletedAt` filter to every existing query, and a Trash screen to make the retained data reachable. An undo snackbar was rejected because restoring a call verbatim would require new endpoints that re-create records from client-held state.

### Bulk delete uses one request

A confirmed delete is a single HTTP request carrying every selected ID, not one request per item. With a dialog that promises to delete 12 calls, the user must get 12 or 0 — never 9 with three unexplained failures.

A single-item delete is a list of one, so there is one code path to build and test.

## Backend

### Endpoints

Both are registered behind the existing `protect` middleware.

| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/calls/bulk-delete` | `{ ids: string[] }` | `{ deletedCount: number }` |
| `DELETE` | `/api/contacts/:id` | — | `{ deletedCalls: number, deletedTasks: number }` |

`POST` is used for the bulk call delete rather than `DELETE` because request bodies on DELETE are inconsistently supported across clients and proxies, and a query string of IDs runs into length limits.

Controllers live in the existing `callController.ts` and `contactController.ts`. The Mongo operations are pushed down into `callService.ts` and a new `contactService.ts`, matching the existing pattern where `callService` wraps all `Call` operations.

### Ownership scoping

Every delete query filters on `userId` taken from the JWT, alongside the document ID:

```ts
Call.deleteMany({ _id: { $in: ids }, userId })
```

Without the `userId` clause, any authenticated user could delete another user's calls by guessing ObjectIds. The existing `getCalls` and `getContactById` already scope this way.

If a request mixes owned and unowned IDs, only the owned ones are deleted and `deletedCount` will be less than `ids.length`. The frontend treats that mismatch as an error rather than reporting success.

### Contact cascade

`DELETE /api/contacts/:id` performs three operations in this order:

1. `Task.deleteMany({ contactId, userId })`
2. `Call.deleteMany({ contactId, userId })`
3. `Contact.deleteOne({ _id: contactId, userId })`

If the contact is not found for that user, return 404 without deleting anything.

This is **not** wrapped in a transaction. MongoDB multi-document transactions require a replica set, and `DATABASE_URL` may point at a standalone instance (`index.ts` falls back to `mongodb://localhost:27017/brachaai`). Instead the operations run in dependency order, deleting the contact last.

The ordering is the safety property: if the sequence fails partway, the contact still exists, so the user can retry, and each earlier delete is idempotent. Deleting the contact first would risk leaving orphaned calls and tasks pointing at a contact that no longer exists.

### Input validation

`POST /api/calls/bulk-delete` returns 400 for:

- `ids` missing, or not an array
- an empty array
- more than 200 entries
- any entry that is not a valid ObjectId

The ObjectId check matters: passing a malformed string to `deleteMany` throws a `CastError`, which would surface as a 500 instead of a 400.

## Frontend

### Shared selection logic

Long-press multi-select is needed on two lists — the Home page's "Recent Call Insights" and the Contact Details page's Calls tab — so the interaction is built once:

- **`useMultiSelect(items)`** (`frontend/src/hooks/`) — owns the selection-mode flag and a `Set<string>` of selected IDs. Returns the selection state plus a props object to spread onto each row (the long-press and tap handlers).
- **`SelectionBar`** (`frontend/src/components/`) — the contextual header shown while selecting.
- **`ConfirmDialog`** (`frontend/src/components/`) — destructive confirmation, used by both the call delete and the contact delete.

Each page keeps its own card markup and CSS module. Only the interaction and the chrome are shared.

`ConfirmDialog` is built by lifting the existing `modalOverlay` / `modalContent` styles out of `ContactDetailsPage.module.css`, so the Home page gets the same visual treatment without duplicating them.

### Interaction

- **Long-press (~500ms)** on a call card enters selection mode with that card selected. Implemented with a `pointerdown` timer, cancelled on `pointerup` or on pointer movement beyond ~10px — otherwise scrolling the list would trigger selections.
- **While in selection mode**, a tap toggles selection. It does not expand the transcript and does not navigate.
- **Outside selection mode**, tap behaves exactly as it does today.
- **The page header is replaced** by `SelectionBar`: a close control, an "N selected" count, and a delete control.
- **Exiting selection mode**: the close control, the Android back button, or deselecting the last item (count reaches 0 auto-exits).

Selected cards get a visible tick and a selected background state.

### Confirmation and result

Deleting from the selection bar opens `ConfirmDialog`:

> **Delete 3 calls?**
> This can't be undone.
> [Cancel] [Delete]

Delete is styled as destructive. On success the deleted rows are removed from local component state and selection mode exits — no refetch. On failure, or when `deletedCount` does not match the number requested, the dialog closes and an error message is shown; the list is refetched so it reflects actual server state.

### Contact delete

A delete control in the Contact Details header, outside the selection flow — one contact at a time, given how destructive it is.

Its dialog names the damage using the counts already loaded on that page:

> **Delete David Cohen?**
> This also deletes 12 calls and 4 tasks. This can't be undone.
> [Cancel] [Delete]

Counts are pluralized correctly, and clauses for zero calls or zero tasks are omitted rather than reading "0 calls".

On success, navigate to `/contacts`.

### WebView long-press

The app runs inside an Android WebView (`WebViewScreen.kt`, loading from `file:///android_asset/www/`). A long press there fires the native text-selection callout and magnifier, so the user gets a "Copy / Select all" popup instead of selection mode.

Call cards therefore need:

```css
-webkit-touch-callout: none;
-webkit-user-select: none;
user-select: none;
```

This works correctly in desktop Chrome without those properties and fails on device, so it must be verified on a real device, not only in a browser.

## Testing

**Backend** — `POST /api/calls/bulk-delete`:

- deletes exactly the listed calls and returns a matching `deletedCount`
- does not delete another user's call when its ID is included
- returns 400 for a missing, non-array, empty, oversized, or malformed-ObjectId `ids` value
- leaves the contact's tasks untouched

**Backend** — `DELETE /api/contacts/:id`:

- removes the contact, its calls, and its tasks
- leaves other contacts' calls and tasks untouched
- returns 404 for a contact belonging to another user, and deletes nothing

**Frontend** — `useMultiSelect`:

- a long press enters selection mode with the pressed item selected
- a pointer move beyond the threshold cancels the pending long press
- a release before the threshold does not enter selection mode
- deselecting the last item exits selection mode

**Manual, on device:**

- long press on a call card selects it rather than raising the native text-selection popup
- scrolling the call list does not select anything
- the Android back button exits selection mode instead of leaving the screen

## Out of scope

- Bulk-deleting tasks from the Tasks page
- Multi-select on the Contacts list
- Undo, trash, or archive for any resource
- The unused `deleteTask` method in `frontend/src/services/api.ts`, which points at an endpoint that does not exist. It is unrelated to this work and stays as-is.
