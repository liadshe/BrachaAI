# Contact Task Badge and Call Duration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show each contact's open-task count on the Contacts list, and record and display a real call duration instead of the fabricated "0 min".

**Architecture:** Two independent slices. Part 1 (Tasks 1–4) is backend + frontend only: a shared open-task filter, a two-query count on `GET /api/contacts`, and a pill on the contact card. Part 2 (Tasks 5–9) threads a duration from the Android call log — falling back to measuring the recording — through the upload payload, the durable retry queue, the backend, and finally the call row on the contact details page.

**Tech Stack:** TypeScript/Express/Mongoose backend (Vitest), React 18 + Vite frontend (Vitest + Testing Library + axios-mock-adapter), Kotlin/Android app (JUnit + Robolectric).

## Global Constraints

- Branch is already created: `feature/contact-task-badge-and-call-duration`. Do not create another.
- Design spec: `docs/superpowers/specs/2026-08-02-contact-task-badge-and-call-duration-design.md`. It is authoritative; this plan implements it.
- An open task is `{ completed: false, status: { $ne: 'done' } }` — both conditions, always, everywhere. Never redefine it locally.
- A bad or missing duration must NEVER fail an upload, and must NEVER produce an HTTP 400 from `POST /api/calls`. `AudioProcessor.NON_RETRYABLE_CODES` treats 400 as permanent and drops the transcript.
- `PendingUploadStore` entries written by the current shipped build have no `callLengthSeconds` key. Reading one must yield `null`, never a parse failure — a parse failure quarantines the file and strands a transcript whose recording is already deleted.
- Duration is measured in whole seconds. `0` and negative mean "unknown", not "a zero-second call".
- Backend tests: `cd backend && npm test`. Frontend tests: `cd frontend && npm test`. Android tests: `cd android && ./gradlew test`.
- Backend and frontend tests mock Mongoose models / axios; there is no test database and no emulator. Do not add one.
- Commit after every task. Do not squash tasks together.

---

### Task 1: Extract the shared open-task filter

`briefingService.ts` keeps the definition of "open" in a private const. The contacts badge needs the same definition, and two copies would eventually disagree — a card promising two tasks while the Android call overlay shows three.

**Files:**
- Create: `backend/src/services/taskFilters.ts`
- Modify: `backend/src/services/briefingService.ts:31-36`
- Test: `backend/src/services/briefingService.test.ts` (existing — must keep passing, unchanged)

**Interfaces:**
- Consumes: nothing.
- Produces: `OPEN_TASK_FILTER` — `{ completed: false, status: { $ne: 'done' } }`, exported from `backend/src/services/taskFilters.ts`. Task 2 imports it.

- [ ] **Step 1: Run the existing briefing tests to establish a green baseline**

```bash
cd backend && npm test -- src/services/briefingService.test.ts
```

Expected: PASS. `briefingService.test.ts` already asserts the filter shape ("treats a task as open only when completed is false and status is not done"), so it is the regression test for this refactor. If it does not pass before you start, stop and report.

- [ ] **Step 2: Create the shared filter module**

Create `backend/src/services/taskFilters.ts`:

```ts
/**
 * A task is open only when both fields agree. `createTasksFromAi` writes `status` and lets
 * `completed` default; `updateTask` writes both. On disagreement we drop the task rather
 * than risk showing a finished one — during a call, or as a count on a contact card.
 *
 * Shared rather than duplicated: the Contacts page badge and the Android call overlay
 * describe the same tasks to the same user minutes apart, and a drift between two copies
 * would surface as a card and an overlay disagreeing, with no obvious cause.
 */
export const OPEN_TASK_FILTER = { completed: false, status: { $ne: 'done' } };
```

- [ ] **Step 3: Import it in briefingService instead of declaring it**

In `backend/src/services/briefingService.ts`, add to the imports at the top:

```ts
import { OPEN_TASK_FILTER } from './taskFilters';
```

Then delete the local declaration — these seven lines, comment included:

```ts
/**
 * A task is open only when both fields agree. `createTasksFromAi` writes `status` and lets
 * `completed` default; `updateTask` writes both. On disagreement we drop the task rather
 * than risk showing a finished one on screen during a call.
 */
const OPEN_TASK_FILTER = { completed: false, status: { $ne: 'done' } };
```

Nothing else in the file changes — the usage in `fetchRelated` already reads `...OPEN_TASK_FILTER`.

- [ ] **Step 4: Run the tests to verify nothing broke**

```bash
cd backend && npm test
```

Expected: PASS, same count as Step 1's baseline for the briefing suite.

- [ ] **Step 5: Commit**

```bash
git add backend/src/services/taskFilters.ts backend/src/services/briefingService.ts
git commit -m "refactor(backend): share the open-task filter between services"
```

---

### Task 2: Count open tasks per contact

**Files:**
- Modify: `backend/src/services/contactService.ts`
- Test: `backend/src/services/contactService.test.ts:1-11` (extend the mocks), plus a new `describe` block

**Interfaces:**
- Consumes: `OPEN_TASK_FILTER` from `./taskFilters` (Task 1).
- Produces: `getContactsWithOpenTaskCounts(userId: string): Promise<any[]>` in `backend/src/services/contactService.ts`. Each element is the lean contact document plus `openTaskCount: number`. Contacts with no open tasks are included with `openTaskCount: 0`. Task 3 calls this.

- [ ] **Step 1: Extend the model mocks in the existing test file**

The existing `vi.mock` calls at `backend/src/services/contactService.test.ts:3-11` only stub the methods `deleteContactCascade` uses. They are hoisted and file-wide, so extend them in place — replace lines 3–11 with:

```ts
vi.mock('../models/Contact', () => ({
    default: { find: vi.fn(), findOne: vi.fn(), deleteOne: vi.fn() },
}));
vi.mock('../models/Call', () => ({
    default: { deleteMany: vi.fn() },
}));
vi.mock('../models/Task', () => ({
    default: { find: vi.fn(), deleteMany: vi.fn() },
}));
```

Then extend the import on line 16 to bring in the new function:

```ts
import { deleteContactCascade, getContactsWithOpenTaskCounts } from './contactService';
```

- [ ] **Step 2: Write the failing tests**

Append to `backend/src/services/contactService.test.ts`. The `chain` helper mirrors the one in `briefingService.test.ts` — Mongoose query builders are chainable and resolve at `.lean()`.

```ts
/** Mongoose query builders are chainable; resolve at .lean(). */
const chain = (result: any) => ({
    sort: vi.fn().mockReturnThis(),
    select: vi.fn().mockReturnThis(),
    lean: vi.fn().mockResolvedValue(result),
});

const OTHER_CONTACT_ID = '507f191e810c19729de860eb';

describe('getContactsWithOpenTaskCounts', () => {
    beforeEach(() => {
        vi.mocked(Contact.find).mockReset();
        vi.mocked(Task.find).mockReset();

        vi.mocked(Contact.find).mockReturnValue(chain([
            { _id: CONTACT_ID, name: 'David Cohen', phone: '+972541234567' },
            { _id: OTHER_CONTACT_ID, name: 'Noa Levi', phone: '+972541234568' },
        ]) as any);
        vi.mocked(Task.find).mockReturnValue(chain([]) as any);
    });

    it('returns an empty array without querying tasks when the user has no contacts', async () => {
        vi.mocked(Contact.find).mockReturnValue(chain([]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result).toEqual([]);
        expect(Task.find).not.toHaveBeenCalled();
    });

    it('scopes both queries to the user', async () => {
        await getContactsWithOpenTaskCounts(USER_ID);

        expect(Contact.find).toHaveBeenCalledWith({ userId: USER_ID });
        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({ userId: USER_ID });
    });

    it('counts a task as open only when completed is false and status is not done', async () => {
        await getContactsWithOpenTaskCounts(USER_ID);

        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({
            completed: false,
            status: { $ne: 'done' },
        });
    });

    it('counts each contact open tasks', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([
            { contactId: CONTACT_ID },
            { contactId: CONTACT_ID },
            { contactId: OTHER_CONTACT_ID },
        ]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result[0]).toMatchObject({ name: 'David Cohen', openTaskCount: 2 });
        expect(result[1]).toMatchObject({ name: 'Noa Levi', openTaskCount: 1 });
    });

    it('reports zero rather than omitting a contact with no open tasks', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([{ contactId: CONTACT_ID }]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result).toHaveLength(2);
        expect(result[1]).toMatchObject({ name: 'Noa Levi', openTaskCount: 0 });
    });

    it('matches contacts to tasks by id even when the ids arrive as ObjectId-like objects', async () => {
        // .lean() returns real ObjectIds, not strings; a === comparison would silently
        // count zero for every contact.
        const asObjectId = (hex: string) => ({ toString: () => hex });
        vi.mocked(Contact.find).mockReturnValue(chain([
            { _id: asObjectId(CONTACT_ID), name: 'David Cohen' },
        ]) as any);
        vi.mocked(Task.find).mockReturnValue(chain([
            { contactId: asObjectId(CONTACT_ID) },
        ]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result[0]).toMatchObject({ openTaskCount: 1 });
    });

    it('preserves the contact fields alongside the count', async () => {
        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result[0]).toMatchObject({
            _id: CONTACT_ID,
            name: 'David Cohen',
            phone: '+972541234567',
        });
    });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd backend && npm test -- src/services/contactService.test.ts
```

Expected: FAIL — `getContactsWithOpenTaskCounts is not a function`. The pre-existing `deleteContactCascade` tests must still pass.

- [ ] **Step 4: Implement**

In `backend/src/services/contactService.ts`, add the import below the existing model imports:

```ts
import { OPEN_TASK_FILTER } from './taskFilters';
```

and append:

```ts
/**
 * Every contact the user owns, each carrying how many open tasks point at it.
 *
 * Two queries for the whole list rather than two per contact: the Contacts page loads the
 * entire address book at once, so an N+1 here would scale with it. Same shape as
 * `briefingService.fetchRelated`, and deliberately not a `$group` aggregation — the
 * aggregation pipeline skips Mongoose's string-to-ObjectId coercion, and a miscast
 * `userId` there matches nothing silently, which would look like every badge missing
 * rather than like an error.
 */
export const getContactsWithOpenTaskCounts = async (userId: string): Promise<any[]> => {
    const contacts = (await Contact.find({ userId }).sort({ name: 1 }).lean()) as any[];
    if (contacts.length === 0) {
        return [];
    }

    const openTasks = (await Task.find({ userId, ...OPEN_TASK_FILTER })
        .select('contactId')
        .lean()) as any[];

    // Keyed on String(...) throughout: .lean() hands back ObjectIds, and two ObjectIds for
    // the same document are not === each other.
    const counts = new Map<string, number>();
    for (const task of openTasks) {
        const key = String(task.contactId);
        counts.set(key, (counts.get(key) ?? 0) + 1);
    }

    return contacts.map(contact => ({
        ...contact,
        openTaskCount: counts.get(String(contact._id)) ?? 0,
    }));
};
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd backend && npm test
```

Expected: PASS, all suites.

- [ ] **Step 6: Commit**

```bash
git add backend/src/services/contactService.ts backend/src/services/contactService.test.ts
git commit -m "feat(backend): count open tasks per contact"
```

---

### Task 3: Serve the count from GET /api/contacts

**Files:**
- Modify: `backend/src/controllers/contactController.ts:7-16`
- Test: `backend/src/controllers/contactController.test.ts:3-8` (extend mocks), plus a new `describe` block

**Interfaces:**
- Consumes: `getContactsWithOpenTaskCounts(userId)` from `../services/contactService` (Task 2).
- Produces: `GET /api/contacts` responds `200` with an array of contacts, each with an added `openTaskCount: number`. Task 4 consumes this shape.

- [ ] **Step 1: Extend the controller test mocks**

Replace `backend/src/controllers/contactController.test.ts:3-8` with:

```ts
vi.mock('../services/contactService', () => ({
    deleteContactCascade: vi.fn(),
    getContactsWithOpenTaskCounts: vi.fn(),
}));
vi.mock('../models/Contact', () => ({
    default: { find: vi.fn(), findOne: vi.fn() },
}));
```

and extend the import on line 11:

```ts
import { deleteContact, getContacts } from './contactController';
```

- [ ] **Step 2: Write the failing tests**

Append to `backend/src/controllers/contactController.test.ts`:

```ts
describe('getContacts', () => {
    beforeEach(() => {
        vi.mocked(contactService.getContactsWithOpenTaskCounts).mockReset();
        vi.mocked(contactService.getContactsWithOpenTaskCounts).mockResolvedValue([
            { _id: CONTACT_ID, name: 'David Cohen', openTaskCount: 2 },
        ]);
    });

    it('returns the contacts with their open-task counts', async () => {
        const res = makeRes();

        await getContacts({ user: { id: USER_ID } } as any, res);

        expect(contactService.getContactsWithOpenTaskCounts).toHaveBeenCalledWith(USER_ID);
        expect(res.status).toHaveBeenCalledWith(200);
        expect(res.json).toHaveBeenCalledWith([
            { _id: CONTACT_ID, name: 'David Cohen', openTaskCount: 2 },
        ]);
    });

    it('returns 500 when the service throws', async () => {
        vi.mocked(contactService.getContactsWithOpenTaskCounts).mockRejectedValue(new Error('db down'));
        const res = makeRes();

        await getContacts({ user: { id: USER_ID } } as any, res);

        expect(res.status).toHaveBeenCalledWith(500);
    });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd backend && npm test -- src/controllers/contactController.test.ts
```

Expected: FAIL — `getContactsWithOpenTaskCounts` is not called (the controller still queries `Contact.find` directly).

- [ ] **Step 4: Implement**

Replace `getContacts` in `backend/src/controllers/contactController.ts` (lines 7–16) with:

```ts
export const getContacts = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        const contacts = await contactService.getContactsWithOpenTaskCounts(userId as string);
        res.status(200).json(contacts);
    } catch (error) {
        console.error('Get contacts error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
```

`contactService` is already imported at the top of the file. Leave the `Contact` model import alone — `getContactById` still uses it.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd backend && npm test
```

Expected: PASS, all suites.

- [ ] **Step 6: Commit**

```bash
git add backend/src/controllers/contactController.ts backend/src/controllers/contactController.test.ts
git commit -m "feat(backend): return open-task counts from GET /api/contacts"
```

---

### Task 4: Render the badge on the Contacts page

**Files:**
- Modify: `frontend/src/pages/ContactsPage/ContactsPage.tsx:7-14` (interface) and `:93-95` (the header block)
- Modify: `frontend/src/pages/ContactsPage/ContactsPage.module.css` (add `.taskBadge` after `.clientName`, around line 152)
- Test: create `frontend/src/pages/ContactsPage/ContactsPage.test.tsx`

**Interfaces:**
- Consumes: `GET /api/contacts` returning `{ _id, name, phone, openTaskCount, ... }[]` (Task 3).
- Produces: nothing consumed by later tasks. Part 1 is complete after this task.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/ContactsPage/ContactsPage.test.tsx`. The render pattern matches `frontend/src/components/AuthLanding.test.tsx`; `MemoryRouter` is required because the page uses `useNavigate` and renders `BottomNav`.

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import ContactsPage from './ContactsPage';

const clientMock = new MockAdapter(apiClient);

const renderPage = () =>
    render(
        <MemoryRouter>
            <ContactsPage />
        </MemoryRouter>
    );

const contact = (overrides: Record<string, unknown> = {}) => ({
    _id: '507f191e810c19729de860ea',
    name: 'David Cohen',
    phone: '+972541234567',
    openTaskCount: 0,
    ...overrides,
});

beforeEach(() => {
    clientMock.reset();
});

describe('ContactsPage task badge', () => {
    it('shows the open-task count on the card', async () => {
        clientMock.onGet('/contacts').reply(200, [contact({ openTaskCount: 2 })]);

        renderPage();

        expect(await screen.findByText('2 tasks')).toBeTruthy();
    });

    it('uses the singular for one task', async () => {
        clientMock.onGet('/contacts').reply(200, [contact({ openTaskCount: 1 })]);

        renderPage();

        expect(await screen.findByText('1 task')).toBeTruthy();
    });

    it('shows no badge when the contact has no open tasks', async () => {
        clientMock.onGet('/contacts').reply(200, [contact({ openTaskCount: 0 })]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.queryByText(/task/i)).toBeNull();
    });

    it('shows no badge when the backend omits the count entirely', async () => {
        // An older backend, or a deploy where only the frontend has shipped.
        const { openTaskCount, ...withoutCount } = contact();
        clientMock.onGet('/contacts').reply(200, [withoutCount]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.queryByText(/task/i)).toBeNull();
    });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npm test -- src/pages/ContactsPage/ContactsPage.test.tsx
```

Expected: FAIL — "Unable to find an element with the text: 2 tasks".

- [ ] **Step 3: Add the count to the Contact interface**

In `frontend/src/pages/ContactsPage/ContactsPage.tsx`, add one field to the interface at lines 7–14:

```tsx
interface Contact {
    _id: string;
    name: string;
    phone: string;
    email?: string;
    lastNote?: string;
    initials?: string;
    openTaskCount?: number;
}
```

Optional, so a response without the field renders no badge rather than crashing.

- [ ] **Step 4: Render the badge**

Replace the `clientHeader` block at `ContactsPage.tsx:93-95`:

```tsx
                                            <div className={styles.clientHeader}>
                                                <h3 className={styles.clientName}>{contact.name}</h3>
                                                {contact.openTaskCount ? (
                                                    <span className={styles.taskBadge}>
                                                        {contact.openTaskCount} {contact.openTaskCount === 1 ? 'task' : 'tasks'}
                                                    </span>
                                                ) : null}
                                            </div>
```

`contact.openTaskCount ?` (not `> 0`) covers `0` and `undefined` in one condition, and `null` on the false branch keeps React from rendering a stray `0`.

- [ ] **Step 5: Add the badge styles**

In `frontend/src/pages/ContactsPage/ContactsPage.module.css`, insert after the `.clientName` rule (which ends at line 152):

```css
.taskBadge {
    flex-shrink: 0;
    margin-left: 8px;
    padding: 3px 10px;
    border-radius: 999px;
    background: #fef3c7;
    color: #92400e;
    font-size: 12px;
    font-weight: 600;
    white-space: nowrap;
}
```

`.clientHeader` is already `display: flex; justify-content: space-between; align-items: center` (line 140), so the badge lands top-right with no change there. `flex-shrink: 0` keeps a long contact name from squeezing it.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd frontend && npm test
```

Expected: PASS, all suites.

- [ ] **Step 7: Verify the build type-checks**

```bash
cd frontend && npm run build
```

Expected: exit 0. `npm run build` runs `tsc` before Vite, so this is the type check.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/ContactsPage/
git commit -m "feat(frontend): show open-task count on contact cards"
```

---

### Task 5: Read call duration from the Android call log

`CallerLookup` already queries the call log for the number nearest the recording's start time. The duration is a column on that same row, so it costs one extra projection entry and no extra query.

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/CallerLookup.kt` (whole file)
- Modify: `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt:102-103` (the one call site)
- Test: create `android/app/src/test/java/com/brachaai/app/CallerLookupTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, all in `CallerLookup.kt`:
  - `data class CallLogMatch(val number: String?, val durationSeconds: Int?)` with `CallLogMatch.NONE` in its companion.
  - `internal data class CallLogEntry(val number: String?, val dateMillis: Long, val durationSeconds: Int)`.
  - `CallerLookup.findNear(callStartMillis: Long): CallLogMatch` — replaces `findNumberNear`, which is deleted.
  - `internal fun CallerLookup.selectBest(entries: List<CallLogEntry>, callStartMillis: Long): CallLogMatch` — the pure selection logic, driven directly by tests.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/CallerLookupTest.kt`. It drives `selectBest` directly rather than the ContentResolver, matching `OverlayDeciderTest`'s approach of testing decision logic without the Android plumbing around it — Robolectric ships no CallLog provider, so a query-level test would be testing a fake.

```kotlin
package com.brachaai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the selection half of [CallerLookup] — which log row wins, and what survives
 * normalization. The ContentResolver query itself has no JVM seam (Robolectric ships no
 * CallLog provider), so it is exercised on device instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallerLookupTest {

    private val lookup = CallerLookup(RuntimeEnvironment.getApplication() as Context)

    private val start = 1_754_000_000_000L

    @Test
    fun returnsNothingWhenNoEntriesAreNear() {
        assertEquals(CallLogMatch.NONE, lookup.selectBest(emptyList(), start))
    }

    @Test
    fun picksTheEntryClosestInTimeToTheRecording() {
        val entries = listOf(
            CallLogEntry("0501111111", start + 90_000, 30),
            CallLogEntry("0502222222", start + 5_000, 272),
        )

        val match = lookup.selectBest(entries, start)

        assertEquals("0502222222", match.number)
        assertEquals(272, match.durationSeconds)
    }

    @Test
    fun readsTheDurationOfTheWinningEntry() {
        val match = lookup.selectBest(listOf(CallLogEntry("0501234567", start, 272)), start)

        assertEquals(272, match.durationSeconds)
    }

    @Test
    fun treatsAZeroDurationAsUnknown() {
        // Missed and unanswered calls are logged with DURATION = 0. That is not a
        // zero-second call, it is the absence of one.
        val match = lookup.selectBest(listOf(CallLogEntry("0501234567", start, 0)), start)

        assertNull(match.durationSeconds)
        assertEquals("0501234567", match.number)
    }

    @Test
    fun treatsANegativeDurationAsUnknown() {
        val match = lookup.selectBest(listOf(CallLogEntry("0501234567", start, -1)), start)

        assertNull(match.durationSeconds)
    }

    @Test
    fun keepsTheDurationWhenTheNumberIsWithheld() {
        // The two fields are independently nullable: a withheld caller still had a call
        // of some length, and that length is worth showing.
        val match = lookup.selectBest(listOf(CallLogEntry("-1", start, 272)), start)

        assertNull(match.number)
        assertEquals(272, match.durationSeconds)
    }

    @Test
    fun normalizesTheWinningNumber() {
        val match = lookup.selectBest(listOf(CallLogEntry("+972 (54) 123-4567", start, 60)), start)

        assertEquals("+972541234567", match.number)
    }

    @Test
    fun treatsABlankNumberAsUnknownWithoutLosingTheDuration() {
        val match = lookup.selectBest(listOf(CallLogEntry("   ", start, 60)), start)

        assertNull(match.number)
        assertEquals(60, match.durationSeconds)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.CallerLookupTest"
```

Expected: FAIL to compile — `Unresolved reference: CallLogMatch`.

- [ ] **Step 3: Implement**

Replace the whole of `android/app/src/main/java/com/brachaai/app/CallerLookup.kt`:

```kotlin
package com.brachaai.app

import android.content.Context
import android.provider.CallLog
import android.util.Log
import kotlin.math.abs

/**
 * What the call log knew about a call. The two fields are independently nullable on
 * purpose: a withheld number carries a perfectly good duration, and a missed-call entry
 * carries a valid number with `DURATION = 0`.
 */
data class CallLogMatch(val number: String?, val durationSeconds: Int?) {
    companion object {
        /** Nothing usable: no permission, no matching entry, or a failed query. */
        val NONE = CallLogMatch(null, null)
    }
}

/** One raw call log row, before normalization. Internal so the tests can build them. */
internal data class CallLogEntry(
    val number: String?,
    val dateMillis: Long,
    val durationSeconds: Int,
)

/**
 * Finds the other party's number, and how long the call lasted, by matching the call log
 * entry closest to the recording's start time.
 */
class CallerLookup(context: Context) {

    private val appContext = context.applicationContext

    fun findNear(callStartMillis: Long): CallLogMatch {
        val from = callStartMillis - TOLERANCE_MS
        val to = callStartMillis + TOLERANCE_MS

        return try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf(from.toString(), to.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                val entries = mutableListOf<CallLogEntry>()
                while (cursor.moveToNext()) {
                    entries += CallLogEntry(
                        number = cursor.getString(numberIdx),
                        dateMillis = cursor.getLong(dateIdx),
                        durationSeconds = cursor.getInt(durationIdx),
                    )
                }

                selectBest(entries, callStartMillis).also {
                    if (it.number == null) Log.d(TAG, "No usable caller number near $callStartMillis")
                }
            } ?: CallLogMatch.NONE
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG not granted; caller number and duration unavailable")
            CallLogMatch.NONE
        } catch (e: Exception) {
            Log.w(TAG, "Call log lookup failed", e)
            CallLogMatch.NONE
        }
    }

    /**
     * Picks the entry nearest [callStartMillis] and reduces it to what callers can use.
     *
     * Pure, and `internal` rather than private, so every branch is unit-testable without a
     * ContentResolver — Robolectric ships no CallLog provider, so a query-level test would
     * only be asserting against a fake of our own making.
     */
    internal fun selectBest(entries: List<CallLogEntry>, callStartMillis: Long): CallLogMatch {
        // minByOrNull keeps the first on a tie, and the query is ordered DATE DESC, so a
        // tie resolves to the newer entry — the same behaviour as the older loop.
        val best = entries.minByOrNull { abs(it.dateMillis - callStartMillis) }
            ?: return CallLogMatch.NONE

        return CallLogMatch(
            number = normalize(best.number),
            // A missed or unanswered call is logged with 0. Never report that as a call
            // that lasted no time; it is a call whose length we do not know.
            durationSeconds = best.durationSeconds.takeIf { it > 0 },
        )
    }

    /** Digits only, preserving a leading '+'. Withheld/private numbers become null. */
    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed in WITHHELD) return null
        val prefix = if (trimmed.startsWith("+")) "+" else ""
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.isEmpty()) null else prefix + digits
    }

    companion object {
        private const val TAG = "CallerLookup"
        const val TOLERANCE_MS = 2L * 60 * 1000
        private val WITHHELD = setOf("-1", "-2", "-3")
    }
}
```

- [ ] **Step 4: Update the one call site so the module compiles**

In `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt`, replace lines 102–103:

```kotlin
                val callLogMatch = parsedInfo.toEpochMillis()?.let { callerLookup.findNear(it) }
                    ?: CallLogMatch.NONE
                val callerNumber = callLogMatch.number
                println("8. Caller number: ${callerNumber ?: "unavailable"}")
```

Task 7 uses `callLogMatch.durationSeconds`; leaving the local in place now keeps that change to one line.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd android && ./gradlew test
```

Expected: PASS, all suites including the pre-existing ones.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/CallerLookup.kt android/app/src/main/java/com/brachaai/app/AudioProcessor.kt android/app/src/test/java/com/brachaai/app/CallerLookupTest.kt
git commit -m "feat(android): read call duration from the call log"
```

---

### Task 6: Measure the recording when the call log cannot help

`READ_CALL_LOG` is optional and never gates the app, so an install can be missing it indefinitely. Without a fallback those users would never see a duration.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/AudioDuration.kt`
- Test: create `android/app/src/test/java/com/brachaai/app/AudioDurationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class AudioDuration { fun secondsOf(file: File): Int? }` in `android/app/src/main/java/com/brachaai/app/AudioDuration.kt`. Returns whole seconds, or `null` for anything unreadable, absent, or zero-length. Never throws. Task 7 injects it into `AudioProcessor`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/AudioDurationTest.kt`:

```kotlin
package com.brachaai.app

import android.media.MediaMetadataRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioDurationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val duration = AudioDuration()

    private fun recordingWithDurationMs(name: String, ms: String?): java.io.File {
        val file = tempFolder.newFile(name)
        if (ms != null) {
            ShadowMediaMetadataRetriever.addMetadata(
                file.absolutePath,
                MediaMetadataRetriever.METADATA_KEY_DURATION,
                ms
            )
        }
        return file
    }

    @Test
    fun readsTheDurationInWholeSeconds() {
        val file = recordingWithDurationMs("call.m4a", "272000")

        assertEquals(272, duration.secondsOf(file))
    }

    @Test
    fun roundsToTheNearestSecond() {
        val file = recordingWithDurationMs("call.m4a", "272600")

        assertEquals(273, duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownWhenTheFileCarriesNoDuration() {
        val file = recordingWithDurationMs("silent.m4a", null)

        assertNull(duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownForAZeroLengthRecording() {
        val file = recordingWithDurationMs("empty.m4a", "0")

        assertNull(duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownForUnparseableMetadata() {
        val file = recordingWithDurationMs("weird.m4a", "not-a-number")

        assertNull(duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownForAMissingFileRatherThanThrowing() {
        // Must never throw: this runs on the upload path, and a duration we cannot
        // measure must not cost a transcript.
        assertNull(duration.secondsOf(java.io.File(tempFolder.root, "gone.m4a")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.AudioDurationTest"
```

Expected: FAIL to compile — `Unresolved reference: AudioDuration`.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/com/brachaai/app/AudioDuration.kt`:

```kotlin
package com.brachaai.app

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * How long a recording runs, in whole seconds.
 *
 * The fallback for [CallerLookup] — the call log is the truth about the *call*, this is
 * only the truth about the *file* — but `READ_CALL_LOG` is optional and never gates the
 * app, so without this an install missing that permission would never show a duration.
 *
 * Uses the platform's [MediaMetadataRetriever] rather than the already-linked FFmpegKit:
 * one metadata read needs no transcoder, and this way the fallback adds no dependency.
 */
class AudioDuration {

    /**
     * Whole seconds, or `null` for anything we cannot measure — missing file, unreadable
     * container, absent or nonsensical metadata, or a zero-length recording.
     *
     * Never throws. This runs on the upload path, where the transcript is the thing worth
     * protecting; a duration is not worth failing a call over.
     */
    fun secondsOf(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()

            if (millis == null || millis <= 0) {
                Log.d(TAG, "No usable duration in ${file.name}")
                null
            } else {
                ((millis + 500) / 1000).toInt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read duration from ${file.name}", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not release the metadata retriever", e)
            }
        }
    }

    companion object {
        private const val TAG = "AudioDuration"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd android && ./gradlew test
```

Expected: PASS, all suites.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/AudioDuration.kt android/app/src/test/java/com/brachaai/app/AudioDurationTest.kt
git commit -m "feat(android): measure a recording's length as a duration fallback"
```

---

### Task 7: Send the duration with the upload

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/PendingUploadStore.kt:10-15` (the data class), `:43-49` (enqueue), `:83-99` (peekAll)
- Modify: `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt:18-27` (constructor), `:102-110` (measurement + payload), `:320-334` (postCall body)
- Modify: `android/CLAUDE.md` (the `AudioProcessor.kt` bullet in the Architecture section)
- Test: `android/app/src/test/java/com/brachaai/app/PendingUploadStoreTest.kt`, `android/app/src/test/java/com/brachaai/app/AudioProcessorUploadTest.kt`

**Interfaces:**
- Consumes: `CallLogMatch.durationSeconds` (Task 5), `AudioDuration.secondsOf(file)` (Task 6).
- Produces: `PendingUpload.callLengthSeconds: Int?` (defaulted to `null`, so existing construction sites keep compiling), persisted as the JSON key `callLengthSeconds` and sent to the backend as the body field `callLength`. Task 8 consumes that field.

- [ ] **Step 1: Write the failing queue tests**

Append inside the existing `PendingUploadStoreTest` class in `android/app/src/test/java/com/brachaai/app/PendingUploadStoreTest.kt`, reusing its `tempFolder` rule and its `quarantined(dir)` helper (declared at line 36):

```kotlin
    // ------------------------------------------------------------- call length

    @Test
    fun roundTripsAnUploadCarryingItsCallLength() {
        val store = PendingUploadStore(tempFolder.newFolder("queue-with-length"))
        store.enqueue(sample().copy(callLengthSeconds = 272))

        assertEquals(272, store.peekAll().single().second.callLengthSeconds)
    }

    @Test
    fun roundTripsAnUploadWithNoCallLength() {
        val store = PendingUploadStore(tempFolder.newFolder("queue-no-length"))
        store.enqueue(sample().copy(callLengthSeconds = null))

        assertNull(store.peekAll().single().second.callLengthSeconds)
    }

    @Test
    fun readsAPreUpdateEntryThatHasNoCallLengthKeyInsteadOfQuarantiningIt() {
        // Entries queued by the shipped build have no such key. Treating that as a parse
        // failure would rename every one of them to *.corrupt on the first flush after
        // the update — and their recordings are already deleted, so those transcripts
        // would be stranded.
        val dir = tempFolder.newFolder("queue-legacy")
        val store = PendingUploadStore(dir)
        File(dir, "1754000000000-000.json").writeText(
            """{"contactName":"Dana","date":"250101_120000","callerNumber":"0501234567","transcript":"shalom"}"""
        )

        val entries = store.peekAll()

        assertEquals(1, entries.size)
        assertEquals("shalom", entries.single().second.transcript)
        assertNull(entries.single().second.callLengthSeconds)
        assertTrue("the legacy entry must not have been quarantined", quarantined(dir).isEmpty())
    }
```

`sample()` (line 26) does not set `callLengthSeconds`, so `.copy(...)` is what varies it — which also proves the default keeps existing construction sites compiling. Add the one missing import to the file: `org.junit.Assert.assertNull`. (`assertEquals`, `assertTrue`, `File` and the rule are already imported.)

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PendingUploadStoreTest"
```

Expected: FAIL to compile — `PendingUpload` has no parameter `callLengthSeconds`.

- [ ] **Step 3: Add the field to the payload and persist it**

In `android/app/src/main/java/com/brachaai/app/PendingUploadStore.kt`, replace the data class at lines 10–15:

```kotlin
data class PendingUpload(
    val contactName: String,
    val date: String,
    val callerNumber: String?,
    val transcript: String,
    /** Whole seconds, or null when neither the call log nor the recording could tell us. */
    val callLengthSeconds: Int? = null
)
```

The default keeps every existing construction site — including the ones in the test sources — compiling untouched.

In `enqueue`, add one line to the JSON block at lines 44–49:

```kotlin
        val json = JSONObject().apply {
            put("contactName", upload.contactName)
            put("date", upload.date)
            put("callerNumber", upload.callerNumber ?: JSONObject.NULL)
            put("transcript", upload.transcript)
            put("callLengthSeconds", upload.callLengthSeconds ?: JSONObject.NULL)
        }
```

In `peekAll`, read it back (lines 86–93):

```kotlin
                val json = JSONObject(file.readText())
                val number = if (json.isNull("callerNumber")) null else json.getString("callerNumber")
                // isNull() is true for a missing key as well as an explicit null, which is
                // exactly what an entry queued before this field existed needs — anything
                // stricter would quarantine it and strand its transcript.
                val callLength = if (json.isNull("callLengthSeconds")) null else json.getInt("callLengthSeconds")
                file to PendingUpload(
                    contactName = json.getString("contactName"),
                    date = json.getString("date"),
                    callerNumber = number,
                    transcript = json.getString("transcript"),
                    callLengthSeconds = callLength
                )
```

- [ ] **Step 4: Run the queue tests to verify they pass**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PendingUploadStoreTest"
```

Expected: PASS.

- [ ] **Step 5: Write the failing upload-body test**

Append to `android/app/src/test/java/com/brachaai/app/AudioProcessorUploadTest.kt`:

```kotlin
    @Test
    fun sendsTheCallLengthInTheUploadBody() {
        val store = FakeTokenStore(accessToken = "good", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(200))

        processorFor(store).uploadForTest(payload.copy(callLengthSeconds = 272))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(272, body.getInt("callLength"))
    }

    @Test
    fun sendsAnExplicitNullCallLengthWhenTheDurationIsUnknown() {
        val store = FakeTokenStore(accessToken = "good", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(200))

        processorFor(store).uploadForTest(payload.copy(callLengthSeconds = null))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue("callLength should be JSON null, not absent", body.isNull("callLength"))
    }
```

Add `org.junit.Assert.assertTrue` to the imports if it is not already there.

- [ ] **Step 6: Run it to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.AudioProcessorUploadTest"
```

Expected: FAIL — `JSONObject["callLength"] not found`.

- [ ] **Step 7: Send the field, and measure it on the pipeline**

In `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt`, add one line to the JSON body in `postCall` (after the `callerNumber` line, around line 326):

```kotlin
                put("callLength", payload.callLengthSeconds ?: JSONObject.NULL)
```

Add the collaborator to the constructor (lines 18–27), keeping a default so `CallMonitorService` needs no change:

```kotlin
class AudioProcessor(
    private val openAiApiKey: String,
    private val cacheDir: File,
    private val authStore: TokenStore,
    private val pendingStore: PendingUploadStore,
    private val callerLookup: CallerLookup,
    private val settingsStore: SettingsStore,
    private val tokenRefresher: TokenRefresher,
    private val baseUrl: String = BackendConfig.BASE_URL,
    private val audioDuration: AudioDuration = AudioDuration()
) {
```

Then extend the block Task 5 left at lines 102–110 to measure and attach the duration:

```kotlin
                val callLogMatch = parsedInfo.toEpochMillis()?.let { callerLookup.findNear(it) }
                    ?: CallLogMatch.NONE
                val callerNumber = callLogMatch.number
                println("8. Caller number: ${callerNumber ?: "unavailable"}")

                // The call log is the truth about the call; the recording is only the
                // truth about the file. Prefer the former, fall back to the latter, and
                // accept "unknown" over blocking an upload. Measured off the original
                // recording, which still exists here — deleteOriginalIfEnabled runs much
                // later, and the converted MP3 is gone on the conversion-failure path.
                val callLengthSeconds = callLogMatch.durationSeconds
                    ?: audioDuration.secondsOf(audioFile)
                println("8b. Call length: ${callLengthSeconds?.let { "${it}s" } ?: "unknown"}")

                val payload = PendingUpload(
                    contactName = parsedInfo.contactName,
                    date = "${parsedInfo.date}_${parsedInfo.time}",
                    callerNumber = callerNumber,
                    transcript = correctedTranscript,
                    callLengthSeconds = callLengthSeconds
                )
```

The `?:` choosing between the two sources is not unit-tested, deliberately: it sits inside `processAndSendToBackend`, which runs FFmpeg, Whisper, and a live upload and has no JVM seam — the same reason `AudioProcessorTest`'s own header says it covers `deleteOriginalIfEnabled` only. Both sources are tested directly (Tasks 5 and 6) and the body field is tested here; the branch itself is verified on device.

- [ ] **Step 8: Run the whole Android suite**

```bash
cd android && ./gradlew test
```

Expected: PASS, all suites.

- [ ] **Step 9: Update the Android architecture notes**

In `android/CLAUDE.md`, in the numbered `AudioProcessor.kt` bullet under Architecture, add a line after the existing `CallerLookup` line ("Looks up the caller's number via `CallerLookup` ..."):

```markdown
   - Resolves the call's length: `CallerLookup` now returns the call log's `DURATION` alongside the number, and `AudioDuration` measures the recording itself when the call log has nothing (no `READ_CALL_LOG`, no matching entry, or a missed call logged as 0). Either may be unknown, which is sent as a JSON null and never fails the upload
```

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/ android/app/src/test/java/com/brachaai/app/ android/CLAUDE.md
git commit -m "feat(android): send the call duration with each upload"
```

---

### Task 8: Accept and store the duration on the backend

**Files:**
- Modify: `backend/src/services/callService.ts:4-16` (`saveRawCall`)
- Modify: `backend/src/controllers/callController.ts:55-69` (validation and the save call)
- Modify: `ARCHITECTURE.md:208` (the `callLength` schema note)
- Test: `backend/src/services/callService.test.ts`, plus a new `backend/src/controllers/callController.test.ts`

**Interfaces:**
- Consumes: the request body field `callLength` (Task 7).
- Produces: `Call.callLength` persisted in seconds, surfaced by the existing `GET /api/calls`. Task 9 renders it.

- [ ] **Step 1: Write the failing service test**

`backend/src/services/callService.test.ts` currently mocks only `Call.deleteMany` and imports only `deleteCallsByIds`. Extend both — replace lines 3–10 with:

```ts
vi.mock('../models/Call', () => ({
    default: {
        create: vi.fn(),
        deleteMany: vi.fn(),
    },
}));

import Call from '../models/Call';
import { deleteCallsByIds, saveRawCall } from './callService';
```

and add a contact id alongside the existing constants at line 13:

```ts
const CONTACT_ID = '507f191e810c19729de860ec';
```

Then append the new suite:

```ts
describe('saveRawCall call length', () => {
    beforeEach(() => {
        vi.mocked(Call.create).mockReset();
        vi.mocked(Call.create).mockResolvedValue({ id: 'call-1' } as any);
    });

    it('stores the duration when one is given', async () => {
        await saveRawCall(USER_ID, CONTACT_ID, 'shalom', new Date('2026-08-02T10:15:00Z'), 272);

        expect(vi.mocked(Call.create).mock.calls[0][0]).toMatchObject({ callLength: 272 });
    });

    it('omits the field entirely when the duration is unknown', async () => {
        await saveRawCall(USER_ID, CONTACT_ID, 'shalom', new Date('2026-08-02T10:15:00Z'));

        // Absent, not null: an explicit null would be a stored claim that we measured
        // nothing, and it would defeat the `callLength?: number` optionality downstream.
        expect(vi.mocked(Call.create).mock.calls[0][0]).not.toHaveProperty('callLength');
    });
});
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && npm test -- src/services/callService.test.ts
```

Expected: FAIL — `callLength` is not on the created document.

- [ ] **Step 3: Implement the service change**

Replace `saveRawCall` in `backend/src/services/callService.ts`:

```ts
export const saveRawCall = async (
    userId: string,
    contactId: string | mongoose.Types.ObjectId,
    transcript: string,
    callDate: Date,
    callLengthSeconds?: number
) => {
    return await Call.create({
        userId,
        contactId,
        fullTranscript: transcript,
        callDateTime: callDate,
        // Spread rather than assigned: an unknown duration must leave the field unset, not
        // store an explicit null that later reads as a measured zero.
        ...(callLengthSeconds === undefined ? {} : { callLength: callLengthSeconds }),
    });
};
```

- [ ] **Step 4: Run it to verify it passes**

```bash
cd backend && npm test -- src/services/callService.test.ts
```

Expected: PASS.

- [ ] **Step 5: Write the failing controller test**

Create `backend/src/controllers/callController.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Call', () => ({ default: { find: vi.fn(), deleteMany: vi.fn() } }));
vi.mock('../services/userService', () => ({ getOrCreateContact: vi.fn() }));
vi.mock('../services/callService', () => ({
    saveRawCall: vi.fn(),
    updateCallWithAnalysis: vi.fn(),
    markAnalysisFailed: vi.fn(),
    deleteCallsByIds: vi.fn(),
}));
vi.mock('../services/aiService', () => ({ analyzeTranscript: vi.fn() }));
vi.mock('../services/taskService', () => ({ createTasksFromAi: vi.fn() }));

import * as userService from '../services/userService';
import * as callService from '../services/callService';
import * as aiService from '../services/aiService';
import { handleIncomingAndroidCall } from './callController';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    res.headersSent = false;
    return res;
};

const makeReq = (body: Record<string, unknown>) =>
    ({ body, user: { id: USER_ID } }) as any;

const validBody = (overrides: Record<string, unknown> = {}) => ({
    contactName: 'David Cohen',
    date: '260802_101500',
    transcript: 'shalom',
    callerNumber: '0541234567',
    ...overrides,
});

/** The 5th positional argument of saveRawCall. */
const savedCallLength = () => vi.mocked(callService.saveRawCall).mock.calls[0][4];

beforeEach(() => {
    vi.mocked(userService.getOrCreateContact).mockReset();
    vi.mocked(callService.saveRawCall).mockReset();
    vi.mocked(aiService.analyzeTranscript).mockReset();

    vi.mocked(userService.getOrCreateContact).mockResolvedValue({ id: CONTACT_ID } as any);
    vi.mocked(callService.saveRawCall).mockResolvedValue({ id: 'call-1' } as any);
    // Analysis is fire-and-forget after the response; resolve it so nothing dangles.
    vi.mocked(aiService.analyzeTranscript).mockResolvedValue({ summary: 's', tasks: [] } as any);
});

describe('handleIncomingAndroidCall call length', () => {
    it('persists a valid duration', async () => {
        await handleIncomingAndroidCall(makeReq(validBody({ callLength: 272 })), makeRes());

        expect(savedCallLength()).toBe(272);
    });

    it('rounds a fractional duration to whole seconds', async () => {
        await handleIncomingAndroidCall(makeReq(validBody({ callLength: 272.6 })), makeRes());

        expect(savedCallLength()).toBe(273);
    });

    it('accepts a call with no duration at all', async () => {
        const res = makeRes();

        await handleIncomingAndroidCall(makeReq(validBody()), res);

        expect(savedCallLength()).toBeUndefined();
        expect(res.status).toHaveBeenCalledWith(201);
    });

    // Each of these must still save the call. A 400 here is destructive: the Android
    // client treats 400 as permanent, drops the payload and never retries it, so a
    // malformed duration would cost the whole transcript.
    it.each([
        ['null', null],
        ['a string', '272'],
        ['a negative number', -5],
        ['NaN', NaN],
        ['Infinity', Infinity],
        ['an object', { seconds: 272 }],
    ])('saves the call without a duration when callLength is %s', async (_label, callLength) => {
        const res = makeRes();

        await handleIncomingAndroidCall(makeReq(validBody({ callLength })), res);

        expect(callService.saveRawCall).toHaveBeenCalled();
        expect(savedCallLength()).toBeUndefined();
        expect(res.status).toHaveBeenCalledWith(201);
        expect(res.status).not.toHaveBeenCalledWith(400);
    });

    it('still rejects a call with no transcript', async () => {
        const res = makeRes();

        await handleIncomingAndroidCall(makeReq(validBody({ transcript: '' })), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.saveRawCall).not.toHaveBeenCalled();
    });
});
```

- [ ] **Step 6: Run it to verify it fails**

```bash
cd backend && npm test -- src/controllers/callController.test.ts
```

Expected: FAIL — `saveRawCall` is called with only 4 arguments, so `savedCallLength()` is `undefined` where 272 is expected.

- [ ] **Step 7: Implement the controller change**

In `backend/src/controllers/callController.ts`, add above `handleIncomingAndroidCall`:

```ts
/**
 * Whole seconds, or undefined for anything we will not stand behind.
 *
 * Deliberately never an error. `AudioProcessor.NON_RETRYABLE_CODES` treats a 400 as
 * permanent — the client drops the payload and never retries it — so rejecting a call
 * over a malformed duration would destroy the transcript to protect an integer.
 */
const parseCallLength = (raw: unknown): number | undefined => {
  if (typeof raw !== 'number' || !Number.isFinite(raw) || raw < 0) {
    return undefined;
  }
  return Math.round(raw);
};
```

Then in the handler, extend the destructuring at line 55 and the `saveRawCall` call at lines 64–69:

```ts
    const { contactName, date, transcript, callerNumber, callLength } = req.body;
    if (!transcript) {
      return res.status(400).json({ success: false, message: 'transcript is required' });
    }

    console.log(`[DEBUG] Android call webhook for userId: ${userId}`);

    const actualCallDate = parseFilenameDate(date);
    const contact = await userService.getOrCreateContact(userId, contactName, callerNumber ?? null);
    const call = await callService.saveRawCall(
      userId,
      contact.id,
      transcript,
      actualCallDate,
      parseCallLength(callLength)
    );
```

- [ ] **Step 8: Run the whole backend suite**

```bash
cd backend && npm test
```

Expected: PASS, all suites.

- [ ] **Step 9: Update the architecture note**

In `ARCHITECTURE.md`, replace the `callLength` line (line 208):

```markdown
*   **`callLength`**: `Number` - The duration of the call in seconds. Sent by the Android app, which prefers the call log's `DURATION` and falls back to measuring the recording. Unset when neither source could tell — including for every call recorded before this field was populated, which is not backfillable.
```

- [ ] **Step 10: Commit**

```bash
git add backend/src/services/callService.ts backend/src/services/callService.test.ts backend/src/controllers/callController.ts backend/src/controllers/callController.test.ts ARCHITECTURE.md
git commit -m "feat(backend): accept and store the call duration"
```

---

### Task 9: Show the real duration on the call row

**Files:**
- Create: `frontend/src/utils/formatDuration.ts`
- Create: `frontend/src/utils/formatDuration.test.ts`
- Modify: `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx:224-228` (delete the local helper), `:1-10` (import), `:389` (the call row)

**Interfaces:**
- Consumes: `Call.callLength` in seconds from `GET /api/calls` (Task 8).
- Produces: `formatDuration(seconds?: number): string | null` from `frontend/src/utils/formatDuration.ts`. `null` means unknown.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/utils/formatDuration.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { formatDuration } from './formatDuration';

describe('formatDuration', () => {
    it('formats under a minute without collapsing to zero', () => {
        // The bug this replaces: Math.round(45 / 60) rendered "0 min".
        expect(formatDuration(45)).toBe('0:45');
    });

    it('zero-pads the seconds', () => {
        expect(formatDuration(65)).toBe('1:05');
    });

    it('formats minutes and seconds', () => {
        expect(formatDuration(272)).toBe('4:32');
    });

    it('switches to hours at exactly an hour', () => {
        expect(formatDuration(3600)).toBe('1:00:00');
    });

    it('stays in minutes just under an hour', () => {
        expect(formatDuration(3599)).toBe('59:59');
    });

    it('zero-pads the minutes in the hours form', () => {
        expect(formatDuration(4360)).toBe('1:12:40');
    });

    it.each([
        ['undefined', undefined],
        ['zero', 0],
        ['a negative number', -5],
        ['NaN', NaN],
        ['Infinity', Infinity],
    ])('reports unknown for %s', (_label, seconds) => {
        expect(formatDuration(seconds as number | undefined)).toBeNull();
    });

    it('reports unknown for null, which is what the API sends for an unmeasured call', () => {
        expect(formatDuration(null as unknown as undefined)).toBeNull();
    });

    it('truncates a fractional value rather than rendering a decimal', () => {
        expect(formatDuration(90.7)).toBe('1:30');
    });
});
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd frontend && npm test -- src/utils/formatDuration.test.ts
```

Expected: FAIL — cannot resolve `./formatDuration`.

- [ ] **Step 3: Implement**

Create `frontend/src/utils/formatDuration.ts`:

```ts
/**
 * A call's length as a clock reading — `0:45`, `4:32`, `1:12:40`.
 *
 * Returns null for a duration we do not have, which callers must render as *nothing*
 * rather than as a zero. Every call recorded before the Android app started measuring
 * duration lands here, and the previous implementation turned that missing value into a
 * confident "0 min" for all of them.
 *
 * Clock style rather than rounded minutes so a 45-second call reads as 45 seconds.
 */
export const formatDuration = (seconds?: number): string | null => {
    if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) {
        return null;
    }

    const total = Math.floor(seconds);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const secs = total % 60;
    const padded = String(secs).padStart(2, '0');

    return hours > 0
        ? `${hours}:${String(minutes).padStart(2, '0')}:${padded}`
        : `${minutes}:${padded}`;
};
```

- [ ] **Step 4: Run it to verify it passes**

```bash
cd frontend && npm test -- src/utils/formatDuration.test.ts
```

Expected: PASS.

- [ ] **Step 5: Use it on the call row**

In `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx`, add the import after the other `@/` imports (around line 9):

```tsx
import { formatDuration } from '@/utils/formatDuration';
```

Delete the local helper at lines 224–228 entirely:

```tsx
    const formatDuration = (seconds?: number) => {
        if (!seconds) return '0 min';
        const mins = Math.round(seconds / 60);
        return `${mins} min`;
    };
```

Replace the call-time line at line 389:

```tsx
                                                    <p className={styles.callTime}>
                                                        {formatTime(call.callDateTime)}
                                                        {formatDuration(call.callLength) ? ` • ${formatDuration(call.callLength)}` : ''}
                                                    </p>
```

An unknown duration takes the separator with it, leaving the timestamp alone — no dash, no placeholder, no fabricated zero.

- [ ] **Step 6: Run the frontend suite and type-check**

```bash
cd frontend && npm test
```

Expected: PASS, all suites.

```bash
cd frontend && npm run build
```

Expected: exit 0.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/utils/ frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx
git commit -m "feat(frontend): show the real call duration instead of 0 min"
```

---

## Final verification

After Task 9, run every suite from a clean state and confirm all three are green before reporting completion:

```bash
cd backend && npm test
```

```bash
cd frontend && npm test && npm run build
```

```bash
cd android && ./gradlew test
```

Note for whoever reports this work: the duration only appears for calls uploaded by a **rebuilt Android app**. Calls already in the database have no duration and will render without one — permanently, since the recordings they could have been measured from are deleted after processing. Do not claim the duration feature is verified end-to-end on the strength of unit tests alone; that requires an on-device run.
