# Incoming-Call Briefing Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a known contact calls, show a floating card over the incoming-call screen with the last call summary and their open tasks.

**Architecture:** A new backend `/api/briefings` pair serves per-contact briefings. Android syncs them into a local JSON snapshot, so a manifest-registered `PHONE_STATE` receiver can match the ringing number against the cache with no network in the path and render a `WindowManager` overlay instantly. All matching logic lives on the device; the backend never sees a phone number.

**Tech Stack:** Backend — Express 5, Mongoose 9, TypeScript, Vitest. Android — Kotlin, OkHttp 4.12, `org.json`, JUnit 4 + Robolectric 4.14.1 + MockWebServer.

**Spec:** [2026-07-31-call-overlay-design.md](../specs/2026-07-31-call-overlay-design.md)

## Global Constraints

- Android `minSdk = 26`, `targetSdk = 36`, `jvmTarget = "11"`.
- **No new Gradle or npm dependencies.** Everything needed is already on the classpath (OkHttp, `org.json`, Robolectric, MockWebServer, Vitest).
- Backend base URL comes from `BackendConfig.BASE_URL` — never inline a URL.
- All backend queries are scoped by `userId`. No endpoint may return another user's data.
- Backend test files live beside their subject as `<name>.test.ts` and use Vitest with `vi.mock` of the Mongoose models (see `contactService.test.ts`).
- Android unit tests live in `android/app/src/test/java/com/brachaai/app/`.
- Overlay features must **never** gate the WebView in `MainActivity`. The app stays fully usable without the overlay permission.
- Card copy is English, matching the reference mockup. Layouts use `start`/`end`, never `left`/`right` — the app sets `supportsRtl="true"`.

### Definition of "open task"

`Task` carries **both** `status` and `completed`, written by different code paths: `createTasksFromAi` sets `status: 'todo'` and lets `completed` default to `false`, while `updateTask` writes both. `getTasksSummary` treats `completed: false` as open.

This plan queries `{ completed: false, status: { $ne: 'done' } }` — open only when both fields agree. If they ever disagree, the card omits the task rather than showing a done one on screen during a call. Do not "simplify" this to one field.

---

### Task 1: Backend briefing service

**Files:**
- Create: `backend/src/services/briefingService.ts`
- Test: `backend/src/services/briefingService.test.ts`

**Interfaces:**
- Consumes: `Contact`, `Call`, `Task` models (existing).
- Produces:
  - `interface BriefingTask { id: string; title: string; priority: 'LOW' | 'MEDIUM' | 'HIGH' }`
  - `interface BriefingLastCall { summary: string; dateTime: Date }`
  - `interface Briefing { contactId: string; name: string; phone: string; lastCall: BriefingLastCall | null; openTasks: BriefingTask[]; openTaskCount: number }`
  - `const MAX_OPEN_TASKS = 5`
  - `getBriefings(userId: string): Promise<Briefing[]>`
  - `getBriefing(userId: string, contactId: string): Promise<Briefing | null>`

- [ ] **Step 1: Write the failing test**

Create `backend/src/services/briefingService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Contact', () => ({ default: { find: vi.fn(), findOne: vi.fn() } }));
vi.mock('../models/Call', () => ({ default: { find: vi.fn() } }));
vi.mock('../models/Task', () => ({ default: { find: vi.fn() } }));

import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';
import { getBriefings, getBriefing, MAX_OPEN_TASKS } from './briefingService';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';
const OTHER_CONTACT_ID = '507f191e810c19729de860eb';

/** Mongoose query builders are chainable; resolve at .lean(). */
const chain = (result: any) => ({
    sort: vi.fn().mockReturnThis(),
    select: vi.fn().mockReturnThis(),
    lean: vi.fn().mockResolvedValue(result),
});

const contact = (id: string, name: string) => ({ _id: id, name, phone: '+972501234567' });

beforeEach(() => {
    vi.mocked(Contact.find).mockReset();
    vi.mocked(Contact.findOne).mockReset();
    vi.mocked(Call.find).mockReset();
    vi.mocked(Task.find).mockReset();

    vi.mocked(Contact.find).mockReturnValue(chain([contact(CONTACT_ID, 'David Cohen')]) as any);
    vi.mocked(Contact.findOne).mockReturnValue(chain(contact(CONTACT_ID, 'David Cohen')) as any);
    vi.mocked(Call.find).mockReturnValue(chain([]) as any);
    vi.mocked(Task.find).mockReturnValue(chain([]) as any);
});

describe('getBriefings', () => {
    it('returns an empty array without querying calls or tasks when the user has no contacts', async () => {
        vi.mocked(Contact.find).mockReturnValue(chain([]) as any);

        const result = await getBriefings(USER_ID);

        expect(result).toEqual([]);
        expect(Call.find).not.toHaveBeenCalled();
        expect(Task.find).not.toHaveBeenCalled();
    });

    it('scopes every query to the user', async () => {
        await getBriefings(USER_ID);

        expect(Contact.find).toHaveBeenCalledWith({ userId: USER_ID });
        expect(vi.mocked(Call.find).mock.calls[0][0]).toMatchObject({ userId: USER_ID });
        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({ userId: USER_ID });
    });

    it('treats a task as open only when completed is false and status is not done', async () => {
        await getBriefings(USER_ID);

        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({
            completed: false,
            status: { $ne: 'done' },
        });
    });

    it('picks the most recent call that actually has a summary', async () => {
        vi.mocked(Call.find).mockReturnValue(chain([
            { contactId: CONTACT_ID, callSummary: 'newest with summary', callDateTime: new Date('2026-07-28T10:00:00Z') },
            { contactId: CONTACT_ID, callSummary: 'older', callDateTime: new Date('2026-07-20T10:00:00Z') },
        ]) as any);

        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.lastCall).toEqual({
            summary: 'newest with summary',
            dateTime: new Date('2026-07-28T10:00:00Z'),
        });
    });

    it('excludes calls with no summary from the query, so a pending analysis cannot mask the last useful call', async () => {
        await getBriefings(USER_ID);

        expect(vi.mocked(Call.find).mock.calls[0][0]).toMatchObject({
            callSummary: { $nin: [null, ''] },
        });
    });

    it('reports lastCall as null when the contact has no summarised calls', async () => {
        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.lastCall).toBeNull();
    });

    it('sorts open tasks HIGH before MEDIUM before LOW', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([
            { _id: 't1', contactId: CONTACT_ID, title: 'low', priority: 'LOW' },
            { _id: 't2', contactId: CONTACT_ID, title: 'high', priority: 'HIGH' },
            { _id: 't3', contactId: CONTACT_ID, title: 'medium', priority: 'MEDIUM' },
        ]) as any);

        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.openTasks.map(t => t.title)).toEqual(['high', 'medium', 'low']);
    });

    it('caps the returned tasks but reports the untruncated count', async () => {
        const many = Array.from({ length: 40 }, (_, i) => ({
            _id: `t${i}`, contactId: CONTACT_ID, title: `task ${i}`, priority: 'LOW',
        }));
        vi.mocked(Task.find).mockReturnValue(chain(many) as any);

        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.openTasks).toHaveLength(MAX_OPEN_TASKS);
        expect(briefing.openTaskCount).toBe(40);
    });

    it('does not leak one contact calls or tasks onto another', async () => {
        vi.mocked(Contact.find).mockReturnValue(chain([
            contact(CONTACT_ID, 'David Cohen'),
            contact(OTHER_CONTACT_ID, 'Ruth Levi'),
        ]) as any);
        vi.mocked(Call.find).mockReturnValue(chain([
            { contactId: CONTACT_ID, callSummary: 'davids call', callDateTime: new Date('2026-07-28T10:00:00Z') },
        ]) as any);
        vi.mocked(Task.find).mockReturnValue(chain([
            { _id: 't1', contactId: CONTACT_ID, title: 'davids task', priority: 'HIGH' },
        ]) as any);

        const [david, ruth] = await getBriefings(USER_ID);

        expect(david.openTasks).toHaveLength(1);
        expect(ruth.openTasks).toEqual([]);
        expect(ruth.lastCall).toBeNull();
    });

    it('exposes the contact identity the card needs', async () => {
        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.contactId).toBe(CONTACT_ID);
        expect(briefing.name).toBe('David Cohen');
        expect(briefing.phone).toBe('+972501234567');
    });
});

describe('getBriefing', () => {
    it('returns null when the contact does not belong to the user', async () => {
        vi.mocked(Contact.findOne).mockReturnValue(chain(null) as any);

        expect(await getBriefing(USER_ID, CONTACT_ID)).toBeNull();
    });

    it('scopes the contact lookup to the user', async () => {
        await getBriefing(USER_ID, CONTACT_ID);

        expect(Contact.findOne).toHaveBeenCalledWith({ _id: CONTACT_ID, userId: USER_ID });
    });

    it('builds the same shape as the collection endpoint', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([
            { _id: 't1', contactId: CONTACT_ID, title: 'Send contract', priority: 'HIGH' },
        ]) as any);

        const briefing = await getBriefing(USER_ID, CONTACT_ID);

        expect(briefing).toEqual({
            contactId: CONTACT_ID,
            name: 'David Cohen',
            phone: '+972501234567',
            lastCall: null,
            openTasks: [{ id: 't1', title: 'Send contract', priority: 'HIGH' }],
            openTaskCount: 1,
        });
    });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend && npx vitest run src/services/briefingService.test.ts
```

Expected: FAIL — `Failed to resolve import "./briefingService"`.

- [ ] **Step 3: Write the implementation**

Create `backend/src/services/briefingService.ts`:

```ts
import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';

export interface BriefingTask {
    id: string;
    title: string;
    priority: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface BriefingLastCall {
    summary: string;
    dateTime: Date;
}

export interface Briefing {
    contactId: string;
    name: string;
    phone: string;
    lastCall: BriefingLastCall | null;
    openTasks: BriefingTask[];
    openTaskCount: number;
}

/**
 * Sent to the device per contact. The card shows fewer still; this bound only keeps the
 * sync payload from ballooning for a contact with a long backlog.
 */
export const MAX_OPEN_TASKS = 5;

/**
 * A task is open only when both fields agree. `createTasksFromAi` writes `status` and lets
 * `completed` default; `updateTask` writes both. On disagreement we drop the task rather
 * than risk showing a finished one on screen during a call.
 */
const OPEN_TASK_FILTER = { completed: false, status: { $ne: 'done' } };

/** Excludes calls still awaiting AI analysis, so they cannot mask the last useful summary. */
const SUMMARISED_CALL_FILTER = { callSummary: { $nin: [null, ''] } };

const PRIORITY_RANK: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };

const assemble = (contact: any, calls: any[], tasks: any[]): Briefing => {
    const id = String(contact._id);

    // `calls` arrives sorted newest-first, so the first hit for a contact is its latest.
    const latest = calls.find(call => String(call.contactId) === id);

    const open = tasks
        .filter(task => String(task.contactId) === id)
        .sort((a, b) => (PRIORITY_RANK[a.priority] ?? 99) - (PRIORITY_RANK[b.priority] ?? 99));

    return {
        contactId: id,
        name: contact.name,
        phone: contact.phone,
        lastCall: latest
            ? { summary: latest.callSummary, dateTime: latest.callDateTime }
            : null,
        openTasks: open.slice(0, MAX_OPEN_TASKS).map(task => ({
            id: String(task._id),
            title: task.title,
            priority: task.priority,
        })),
        openTaskCount: open.length,
    };
};

/**
 * Two queries for the whole contact list rather than two per contact — the device syncs
 * every contact at once, and an N+1 here would scale with the address book.
 */
const fetchRelated = async (userId: string, contactIds: any[]) => {
    const [calls, tasks] = await Promise.all([
        Call.find({ userId, contactId: { $in: contactIds }, ...SUMMARISED_CALL_FILTER })
            .sort({ callDateTime: -1 })
            .select('contactId callSummary callDateTime')
            .lean(),
        Task.find({ userId, contactId: { $in: contactIds }, ...OPEN_TASK_FILTER })
            .sort({ createdAt: -1 })
            .select('contactId title priority')
            .lean(),
    ]);
    return { calls: calls as any[], tasks: tasks as any[] };
};

export const getBriefings = async (userId: string): Promise<Briefing[]> => {
    const contacts = (await Contact.find({ userId }).sort({ name: 1 }).lean()) as any[];
    if (contacts.length === 0) {
        return [];
    }

    const { calls, tasks } = await fetchRelated(userId, contacts.map(c => c._id));
    return contacts.map(contact => assemble(contact, calls, tasks));
};

export const getBriefing = async (
    userId: string,
    contactId: string,
): Promise<Briefing | null> => {
    const contact = await Contact.findOne({ _id: contactId, userId }).lean();
    if (!contact) {
        return null;
    }

    const { calls, tasks } = await fetchRelated(userId, [(contact as any)._id]);
    return assemble(contact, calls, tasks);
};
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend && npx vitest run src/services/briefingService.test.ts
```

Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/services/briefingService.ts backend/src/services/briefingService.test.ts
git commit -m "feat(backend): add briefing service for per-contact call summaries and open tasks"
```

---

### Task 2: Backend briefing endpoints

**Files:**
- Create: `backend/src/controllers/briefingController.ts`
- Create: `backend/src/routes/briefingRoute.ts`
- Modify: `backend/src/index.ts` (imports near line 5-8, `app.use` near line 38-41)

**Interfaces:**
- Consumes: `getBriefings`, `getBriefing` from Task 1; `protect` from `../middleware/authMiddleware`; `isObjectId` from `../utils/objectId`.
- Produces: `GET /api/briefings` → `Briefing[]`; `GET /api/briefings/:contactId` → `Briefing` or 404.

- [ ] **Step 1: Write the controller**

Create `backend/src/controllers/briefingController.ts`. This mirrors `contactController.ts` — same `AuthRequest`, same error shape, same status codes:

```ts
import { Response } from 'express';
import { AuthRequest } from '../middleware/authMiddleware';
import * as briefingService from '../services/briefingService';
import { isObjectId } from '../utils/objectId';

export const getBriefings = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }

        const briefings = await briefingService.getBriefings(userId);
        res.status(200).json(briefings);
    } catch (error) {
        console.error('Get briefings error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const getBriefingByContactId = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }

        const contactId = req.params.contactId;
        if (!isObjectId(contactId)) {
            return res.status(400).json({ message: 'invalid contact id' });
        }

        const briefing = await briefingService.getBriefing(userId, contactId);
        if (!briefing) {
            return res.status(404).json({ message: 'Contact not found' });
        }

        res.status(200).json(briefing);
    } catch (error) {
        console.error('Get briefing error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
```

- [ ] **Step 2: Write the route**

Create `backend/src/routes/briefingRoute.ts`:

```ts
import { Router } from 'express';
import { getBriefings, getBriefingByContactId } from '../controllers/briefingController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.get('/briefings', protect, getBriefings);
router.get('/briefings/:contactId', protect, getBriefingByContactId);

export default router;
```

- [ ] **Step 3: Register the route**

In `backend/src/index.ts`, add to the import block (alongside the other route imports around line 5-8):

```ts
import briefingRoutes from './routes/briefingRoute';
```

And in the routes section (alongside the other `app.use` calls around line 38-41):

```ts
app.use('/api', briefingRoutes);
```

- [ ] **Step 4: Verify it compiles and nothing regressed**

```bash
cd backend && npx tsc --noEmit && npx vitest run
```

Expected: no TypeScript errors; the full suite passes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/controllers/briefingController.ts backend/src/routes/briefingRoute.ts backend/src/index.ts
git commit -m "feat(backend): expose GET /api/briefings and /api/briefings/:contactId"
```

---

### Task 3: Phone number normalization

The riskiest logic in the feature: every spelling of a number must collapse to one key, on both sides of every comparison.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/PhoneNormalizer.kt`
- Test: `android/app/src/test/java/com/brachaai/app/PhoneNormalizerTest.kt`

**Interfaces:**
- Produces: `PhoneNormalizer.key(raw: String?, countryCode: String = DEFAULT_COUNTRY_CODE): String?` and `PhoneNormalizer.DEFAULT_COUNTRY_CODE = "972"`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/PhoneNormalizerTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun `every spelling of the same mobile number collapses to one key`() {
        val expected = "501234567"
        assertEquals(expected, PhoneNormalizer.key("+972501234567"))
        assertEquals(expected, PhoneNormalizer.key("0501234567"))
        assertEquals(expected, PhoneNormalizer.key("050-123-4567"))
        assertEquals(expected, PhoneNormalizer.key("050 123 4567"))
        assertEquals(expected, PhoneNormalizer.key("+972-50-123-4567"))
        assertEquals(expected, PhoneNormalizer.key("(050) 123-4567"))
    }

    @Test
    fun `every spelling of the same landline collapses to one key`() {
        val expected = "31234567"
        assertEquals(expected, PhoneNormalizer.key("+97231234567"))
        assertEquals(expected, PhoneNormalizer.key("031234567"))
        assertEquals(expected, PhoneNormalizer.key("03-123-4567"))
    }

    @Test
    fun `foreign numbers fall back to the last nine digits rather than failing`() {
        assertEquals("155552671", PhoneNormalizer.key("+14155552671"))
    }

    @Test
    fun `withheld and private numbers have no key`() {
        assertNull(PhoneNormalizer.key("-1"))
        assertNull(PhoneNormalizer.key("-2"))
        assertNull(PhoneNormalizer.key("-3"))
    }

    @Test
    fun `absent or digitless input has no key`() {
        assertNull(PhoneNormalizer.key(null))
        assertNull(PhoneNormalizer.key(""))
        assertNull(PhoneNormalizer.key("   "))
        assertNull(PhoneNormalizer.key("abc"))
    }

    @Test
    fun `numbers too short to identify anyone have no key`() {
        assertNull(PhoneNormalizer.key("123"))
    }

    @Test
    fun `a nine digit number starting with the country code is treated as national, not stripped`() {
        // 972123456 is only 9 digits — stripping "972" would leave a 6-digit stub that
        // could collide with an unrelated contact.
        assertEquals("972123456", PhoneNormalizer.key("972123456"))
    }

    @Test
    fun `the country code is configurable`() {
        assertEquals("2071234567".takeLast(9), PhoneNormalizer.key("+442071234567", countryCode = "44"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PhoneNormalizerTest"
```

Expected: FAIL — unresolved reference `PhoneNormalizer`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/PhoneNormalizer.kt`:

```kotlin
package com.brachaai.app

/**
 * Reduces any spelling of a phone number to a single comparison key.
 *
 * Used on BOTH sides of every comparison — the ringing number from the telephony broadcast
 * and the `phone` string stored on each cached contact — so the two can only match if they
 * agree here. This is the only place number formats are interpreted; the backend never sees
 * a phone number.
 */
object PhoneNormalizer {

    /** Israel. A different deployment changes this one constant. */
    const val DEFAULT_COUNTRY_CODE = "972"

    /** Length of an Israeli national significant number, and the fallback truncation width. */
    private const val KEY_LENGTH = 9

    /** Below this, a "number" identifies nobody — short codes, mis-parses. */
    private const val MIN_KEY_LENGTH = 4

    /** Telephony sentinels for withheld, unavailable, and payphone callers. */
    private val WITHHELD = setOf("-1", "-2", "-3")

    fun key(raw: String?, countryCode: String = DEFAULT_COUNTRY_CODE): String? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim()
        if (trimmed in WITHHELD) return null

        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // Only strip the country code when what remains is still a plausible number —
        // a bare 9-digit string starting with "972" is a national number, not a prefixed one.
        if (digits.length > KEY_LENGTH && digits.startsWith(countryCode)) {
            digits = digits.removePrefix(countryCode)
        }

        if (digits.startsWith("0")) {
            digits = digits.substring(1)
        }

        // Anything still over-length is foreign or oddly formatted. Truncating from the right
        // keeps the subscriber portion, which is what actually identifies the caller.
        if (digits.length > KEY_LENGTH) {
            digits = digits.takeLast(KEY_LENGTH)
        }

        return if (digits.length < MIN_KEY_LENGTH) null else digits
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PhoneNormalizerTest"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/PhoneNormalizer.kt android/app/src/test/java/com/brachaai/app/PhoneNormalizerTest.kt
git commit -m "feat(android): add phone number normalization for caller matching"
```

---

### Task 4: Briefing model and local cache

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/Briefing.kt`
- Create: `android/app/src/main/java/com/brachaai/app/BriefingStore.kt`
- Test: `android/app/src/test/java/com/brachaai/app/BriefingStoreTest.kt`

**Interfaces:**
- Consumes: `PhoneNormalizer.key` (Task 3).
- Produces:
  - `data class BriefingTask(val id: String, val title: String, val priority: String)`
  - `data class Briefing(val contactId: String, val name: String, val phone: String, val lastCallSummary: String?, val openTasks: List<BriefingTask>, val openTaskCount: Int)`
  - `Briefing.toJson(): JSONObject` and `Briefing.fromJson(json: JSONObject): Briefing?`
  - `class BriefingStore(file: File)` with `replaceAll(briefings: List<Briefing>)`, `lookup(phoneKey: String): Briefing?`, `readAll(): List<Briefing>`

> **Note on `lastCallDateTime`:** the backend sends it, but the card never displays it (the mockup shows the summary text only). It is deliberately **not** parsed into the Android model — adding an unused field means an unused date parser and a timezone bug waiting to happen. If the card ever shows "3 days ago", add it then.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/BriefingStoreTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BriefingStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): Pair<BriefingStore, File> {
        val file = File(temp.newFolder("cache"), "briefings.json")
        return BriefingStore(file) to file
    }

    private fun briefing(
        contactId: String = "c1",
        name: String = "David Cohen",
        phone: String = "+972501234567",
        summary: String? = "Promised to send price quote.",
        tasks: List<BriefingTask> = listOf(BriefingTask("t1", "Send contract by Tuesday", "HIGH")),
        openTaskCount: Int = 1,
    ) = Briefing(contactId, name, phone, summary, tasks, openTaskCount)

    @Test
    fun `round trips a briefing through disk`() {
        val (subject, _) = store()

        subject.replaceAll(listOf(briefing()))

        val restored = subject.readAll().single()
        assertEquals("c1", restored.contactId)
        assertEquals("David Cohen", restored.name)
        assertEquals("+972501234567", restored.phone)
        assertEquals("Promised to send price quote.", restored.lastCallSummary)
        assertEquals(1, restored.openTaskCount)
        assertEquals(listOf(BriefingTask("t1", "Send contract by Tuesday", "HIGH")), restored.openTasks)
    }

    @Test
    fun `round trips a briefing with no summary and no tasks`() {
        val (subject, _) = store()

        subject.replaceAll(listOf(briefing(summary = null, tasks = emptyList(), openTaskCount = 0)))

        val restored = subject.readAll().single()
        assertNull(restored.lastCallSummary)
        assertTrue(restored.openTasks.isEmpty())
        assertEquals(0, restored.openTaskCount)
    }

    @Test
    fun `looks a contact up by any spelling of their number`() {
        val (subject, _) = store()
        subject.replaceAll(listOf(briefing(phone = "+972501234567")))

        val key = PhoneNormalizer.key("050-123-4567")!!

        assertEquals("David Cohen", subject.lookup(key)?.name)
    }

    @Test
    fun `returns null for a number that is not a known contact`() {
        val (subject, _) = store()
        subject.replaceAll(listOf(briefing(phone = "+972501234567")))

        assertNull(subject.lookup(PhoneNormalizer.key("+972529999999")!!))
    }

    @Test
    fun `an absent cache reads as empty rather than throwing`() {
        val (subject, _) = store()

        assertTrue(subject.readAll().isEmpty())
        assertNull(subject.lookup("501234567"))
    }

    @Test
    fun `a corrupt cache reads as empty rather than throwing`() {
        val (subject, file) = store()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")

        assertTrue(subject.readAll().isEmpty())
        assertNull(subject.lookup("501234567"))
    }

    @Test
    fun `replaceAll fully replaces the previous snapshot`() {
        val (subject, _) = store()
        subject.replaceAll(listOf(briefing(contactId = "c1", name = "David Cohen")))

        subject.replaceAll(listOf(briefing(contactId = "c2", name = "Ruth Levi", phone = "+972529999999")))

        assertEquals(listOf("Ruth Levi"), subject.readAll().map { it.name })
    }

    @Test
    fun `leaves no temp file behind after a write`() {
        val (subject, file) = store()

        subject.replaceAll(listOf(briefing()))

        val leftovers = file.parentFile!!.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("temp file left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `skips entries missing required fields instead of failing the whole snapshot`() {
        val (subject, file) = store()
        file.parentFile?.mkdirs()
        file.writeText(
            """{"contacts":[{"name":"No id"},{"contactId":"c2","name":"Ruth Levi","phone":"+972529999999","openTasks":[],"openTaskCount":0}]}"""
        )

        assertEquals(listOf("Ruth Levi"), subject.readAll().map { it.name })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.BriefingStoreTest"
```

Expected: FAIL — unresolved references `BriefingStore`, `Briefing`, `BriefingTask`.

- [ ] **Step 3: Write the model**

Create `android/app/src/main/java/com/brachaai/app/Briefing.kt`:

```kotlin
package com.brachaai.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class BriefingTask(
    val id: String,
    val title: String,
    val priority: String,
)

/**
 * What the overlay shows for one contact: who they are, what was last discussed, what is
 * still open.
 *
 * [openTaskCount] is the untruncated total. [openTasks] is capped by the backend and capped
 * again by the card, so counting the list would understate "+N more".
 */
data class Briefing(
    val contactId: String,
    val name: String,
    val phone: String,
    val lastCallSummary: String?,
    val openTasks: List<BriefingTask>,
    val openTaskCount: Int,
) {

    fun toJson(): JSONObject {
        val tasks = JSONArray()
        openTasks.forEach { task ->
            tasks.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("priority", task.priority)
            )
        }
        return JSONObject()
            .put("contactId", contactId)
            .put("name", name)
            .put("phone", phone)
            .put("lastCallSummary", lastCallSummary ?: JSONObject.NULL)
            .put("openTasks", tasks)
            .put("openTaskCount", openTaskCount)
    }

    companion object {
        private const val TAG = "Briefing"

        /**
         * Parses one briefing, from either the backend response or the on-disk cache — the
         * two use the same shape deliberately, so a synced payload can be written straight
         * back out.
         *
         * Returns null for an entry missing the fields that identify a contact, so one bad
         * record cannot take the whole snapshot down with it.
         */
        fun fromJson(json: JSONObject): Briefing? {
            val contactId = json.optString("contactId")
            val phone = json.optString("phone")
            if (contactId.isBlank() || phone.isBlank()) {
                Log.w(TAG, "Skipping briefing entry with no contact id or phone")
                return null
            }

            val summary = if (json.isNull("lastCallSummary")) {
                null
            } else {
                json.optString("lastCallSummary").ifBlank { null }
            }

            val tasksJson = json.optJSONArray("openTasks") ?: JSONArray()
            val tasks = (0 until tasksJson.length()).mapNotNull { index ->
                val task = tasksJson.optJSONObject(index) ?: return@mapNotNull null
                val title = task.optString("title")
                if (title.isBlank()) null
                else BriefingTask(
                    id = task.optString("id"),
                    title = title,
                    priority = task.optString("priority").ifBlank { "LOW" },
                )
            }

            return Briefing(
                contactId = contactId,
                name = json.optString("name").ifBlank { "Unknown" },
                phone = phone,
                lastCallSummary = summary,
                openTasks = tasks,
                openTaskCount = json.optInt("openTaskCount", tasks.size),
            )
        }
    }
}

/**
 * The backend sends `lastCall: { summary, dateTime }`; the card only ever shows the summary.
 * Flattening here keeps the date out of the device model entirely rather than carrying an
 * unused timestamp through the cache.
 */
fun briefingFromBackendJson(json: JSONObject): Briefing? {
    val lastCall = json.optJSONObject("lastCall")
    val flattened = JSONObject(json.toString())
        .put("lastCallSummary", lastCall?.optString("summary")?.ifBlank { null } ?: JSONObject.NULL)
    return Briefing.fromJson(flattened)
}
```

- [ ] **Step 4: Write the store**

Create `android/app/src/main/java/com/brachaai/app/BriefingStore.kt`:

```kotlin
package com.brachaai.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The local snapshot the overlay reads when the phone rings.
 *
 * Deliberately simpler than [PendingUploadStore], which uses one durable file per entry
 * because losing an entry loses a transcript forever. This is disposable derived data: a
 * corrupt or missing snapshot means "no card until the next sync", never data loss. So every
 * read failure degrades to an empty cache instead of throwing, and there is no quarantine.
 *
 * Writes go through a temp file and a rename so a call arriving mid-sync can never read a
 * half-written snapshot.
 */
class BriefingStore(private val file: File) {

    fun replaceAll(briefings: List<Briefing>) {
        val array = JSONArray()
        briefings.forEach { array.put(it.toJson()) }
        val payload = JSONObject().put(KEY_CONTACTS, array).toString()

        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(payload)
            if (!temp.renameTo(file)) {
                // Some filesystems refuse a rename onto an existing path.
                file.delete()
                if (!temp.renameTo(file)) {
                    Log.e(TAG, "Could not replace the briefing snapshot")
                    temp.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not write the briefing snapshot", e)
            temp.delete()
        }
    }

    /** The cached briefing for a normalized phone key, or null if this caller is unknown. */
    fun lookup(phoneKey: String): Briefing? =
        readAll().firstOrNull { PhoneNormalizer.key(it.phone) == phoneKey }

    fun readAll(): List<Briefing> {
        if (!file.exists()) return emptyList()

        return try {
            val contacts = JSONObject(file.readText()).optJSONArray(KEY_CONTACTS) ?: JSONArray()
            (0 until contacts.length()).mapNotNull { index ->
                contacts.optJSONObject(index)?.let(Briefing::fromJson)
            }
        } catch (e: Exception) {
            // Nothing here is irreplaceable — the next sync rewrites it.
            Log.w(TAG, "Briefing snapshot unreadable; treating as empty", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "BriefingStore"
        private const val KEY_CONTACTS = "contacts"

        /** The snapshot lives in app-private storage, beside the pending-upload queue. */
        fun default(filesDir: File): BriefingStore = BriefingStore(File(filesDir, "briefings.json"))
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.BriefingStoreTest"
```

Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/Briefing.kt android/app/src/main/java/com/brachaai/app/BriefingStore.kt android/app/src/test/java/com/brachaai/app/BriefingStoreTest.kt
git commit -m "feat(android): add briefing model and local snapshot cache"
```

---

### Task 5: Briefing API client

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/BriefingClient.kt`
- Test: `android/app/src/test/java/com/brachaai/app/BriefingClientTest.kt`

**Interfaces:**
- Consumes: `Briefing`, `briefingFromBackendJson` (Task 4); `TokenStore` and `TokenRefresher` (existing); `BackendConfig.BASE_URL`; `FakeTokenStore` (existing test helper).
- Produces: `class BriefingClient(tokenStore, tokenRefresher, baseUrl, client)` with `fetchAll(): List<Briefing>?` and `fetchOne(contactId: String): Briefing?` — **null means the fetch failed**, empty list means the user genuinely has no contacts.

> Read `android/app/src/test/java/com/brachaai/app/FakeTokenStore.kt` before writing the test — reuse it rather than writing another fake.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/BriefingClientTest.kt`:

```kotlin
package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric because Briefing/TokenRefresher log through android.util.Log and parse with
// Android's org.json — both throw "not mocked" under AGP's stub jar. Same reason as
// PendingUploadStoreTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BriefingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore

    private val briefingJson = """
        {
          "contactId": "c1",
          "name": "David Cohen",
          "phone": "+972501234567",
          "lastCall": { "summary": "Promised to send price quote.", "dateTime": "2026-07-28T10:00:00.000Z" },
          "openTasks": [ { "id": "t1", "title": "Send contract by Tuesday", "priority": "HIGH" } ],
          "openTaskCount": 1
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeTokenStore().apply { setTokens("access-1", "refresh-1") }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = BriefingClient(
        tokenStore = tokenStore,
        tokenRefresher = TokenRefresher(tokenStore, baseUrl = server.url("/").toString().trimEnd('/')),
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `fetchAll parses the briefing list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$briefingJson]"))

        val result = client().fetchAll()

        assertEquals(1, result?.size)
        val briefing = result!!.single()
        assertEquals("David Cohen", briefing.name)
        assertEquals("Promised to send price quote.", briefing.lastCallSummary)
        assertEquals(1, briefing.openTaskCount)
        assertEquals("Send contract by Tuesday", briefing.openTasks.single().title)
    }

    @Test
    fun `fetchAll sends the stored access token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        client().fetchAll()

        val request = server.takeRequest()
        assertEquals("/api/briefings", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
    }

    @Test
    fun `fetchAll distinguishes an empty address book from a failure`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val result = client().fetchAll()

        assertNotNull("empty list, not null", result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `fetchAll refreshes the token once on 401 and retries`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"token":"access-2","refreshToken":"refresh-2"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$briefingJson]"))

        val result = client().fetchAll()

        assertEquals(1, result?.size)
        server.takeRequest()
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `fetchAll gives up rather than looping when the refreshed token is also rejected`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"token":"access-2","refreshToken":"refresh-2"}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        assertNull(client().fetchAll())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `fetchAll returns null when there is no token to send`() {
        tokenStore.clear()

        assertNull(client().fetchAll())
        assertEquals("no request attempted", 0, server.requestCount)
    }

    @Test
    fun `fetchAll returns null on a server error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(client().fetchAll())
    }

    @Test
    fun `fetchAll returns null on an unparseable body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        assertNull(client().fetchAll())
    }

    @Test
    fun `fetchOne requests the contact and parses a single briefing`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(briefingJson))

        val result = client().fetchOne("c1")

        assertEquals("David Cohen", result?.name)
        assertEquals("/api/briefings/c1", server.takeRequest().path)
    }

    @Test
    fun `fetchOne returns null when the contact is gone`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(client().fetchOne("c1"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.BriefingClientTest"
```

Expected: FAIL — unresolved reference `BriefingClient`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/BriefingClient.kt`:

```kotlin
package com.brachaai.app

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads briefings from the backend.
 *
 * Mirrors the upload path's auth handling: send the stored access token, and on a 401 ask
 * [TokenRefresher] once for a fresh one and retry exactly once. One retry, never a loop — a
 * refreshed token that is also rejected means the session is genuinely over.
 *
 * Every failure returns null. Callers must distinguish that from an empty list, which is the
 * legitimate answer for a user with no contacts.
 */
class BriefingClient(
    private val tokenStore: TokenStore,
    private val tokenRefresher: TokenRefresher,
    private val baseUrl: String = BackendConfig.BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    /** Every contact for the signed-in user. Null on any failure. */
    fun fetchAll(): List<Briefing>? {
        val body = get("$baseUrl/api/briefings") ?: return null
        return try {
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::briefingFromBackendJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not parse the briefing list", e)
            null
        }
    }

    /** One contact, for the live refresh while a card is on screen. Null on any failure. */
    fun fetchOne(contactId: String): Briefing? {
        val body = get("$baseUrl/api/briefings/$contactId") ?: return null
        return try {
            briefingFromBackendJson(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "Could not parse the briefing for $contactId", e)
            null
        }
    }

    private fun get(url: String): String? {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "No access token stored; skipping briefing fetch")
            return null
        }

        val first = execute(url, token) ?: return null
        if (first.code != 401) {
            return first.bodyOrNull()
        }

        val refreshed = tokenRefresher.refresh(token)
        if (refreshed.isNullOrBlank()) {
            Log.w(TAG, "Briefing fetch unauthorized and refresh failed")
            return null
        }

        val second = execute(url, refreshed) ?: return null
        return second.bodyOrNull()
    }

    private class Result(val code: Int, val body: String?) {
        fun bodyOrNull(): String? =
            if (code in 200..299) body
            else {
                Log.w(TAG, "Briefing fetch failed with HTTP $code")
                null
            }
    }

    private fun execute(url: String, token: String): Result? = try {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            Result(response.code, response.body?.string())
        }
    } catch (e: Exception) {
        Log.e(TAG, "Briefing request failed", e)
        null
    }

    companion object {
        private const val TAG = "BriefingClient"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.BriefingClientTest"
```

Expected: PASS, 10 tests.

If `FakeTokenStore` lacks a `clear()` or `setTokens()` that the test uses, extend it — do not create a second fake.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/BriefingClient.kt android/app/src/test/java/com/brachaai/app/BriefingClientTest.kt
git commit -m "feat(android): add briefing API client with token refresh"
```

---

### Task 6: Briefing sync and its triggers

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/BriefingSync.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/MainActivity.kt` (`onResume`, line 105-108)
- Test: `android/app/src/test/java/com/brachaai/app/BriefingSyncTest.kt`

**Interfaces:**
- Consumes: `BriefingClient` (Task 5), `BriefingStore` (Task 4).
- Produces:
  - `class BriefingSync(client: BriefingClient, store: BriefingStore)` with `syncNow(): Boolean`
  - `CallMonitorService.ACTION_SYNC_BRIEFINGS` and `CallMonitorService.requestBriefingSync(context: Context)`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/BriefingSyncTest.kt`:

```kotlin
package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Robolectric: BriefingStore/Briefing use android.util.Log and org.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BriefingSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: BriefingStore
    private lateinit var tokenStore: FakeTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = BriefingStore(File(temp.newFolder("files"), "briefings.json"))
        tokenStore = FakeTokenStore().apply { setTokens("access-1", "refresh-1") }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sync(): BriefingSync {
        val base = server.url("/").toString().trimEnd('/')
        return BriefingSync(
            client = BriefingClient(tokenStore, TokenRefresher(tokenStore, baseUrl = base), baseUrl = base),
            store = store,
        )
    }

    private val payload = """
        [{"contactId":"c1","name":"David Cohen","phone":"+972501234567",
          "lastCall":{"summary":"Promised a quote.","dateTime":"2026-07-28T10:00:00.000Z"},
          "openTasks":[],"openTaskCount":0}]
    """.trimIndent()

    @Test
    fun `a successful sync writes the snapshot`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        assertTrue(sync().syncNow())

        assertEquals("David Cohen", store.readAll().single().name)
    }

    @Test
    fun `a failed sync leaves the previous snapshot intact`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        sync().syncNow()

        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(sync().syncNow())

        assertEquals("David Cohen", store.readAll().single().name)
    }

    @Test
    fun `an empty address book clears the snapshot`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        sync().syncNow()

        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(sync().syncNow())

        assertTrue(store.readAll().isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.BriefingSyncTest"
```

Expected: FAIL — unresolved reference `BriefingSync`.

- [ ] **Step 3: Write the sync**

Create `android/app/src/main/java/com/brachaai/app/BriefingSync.kt`:

```kotlin
package com.brachaai.app

import android.util.Log

/**
 * Refreshes the local snapshot from the backend.
 *
 * A failure is logged and leaves the previous snapshot in place — a stale card beats no card
 * when the phone is ringing. There is no retry here; the next trigger picks it up.
 */
class BriefingSync(
    private val client: BriefingClient,
    private val store: BriefingStore,
) {

    /**
     * @return true if the snapshot was refreshed.
     *
     * Synchronized: the periodic tick, the post-upload trigger and `ACTION_SYNC_BRIEFINGS`
     * all launch into the same IO-dispatched scope, and [BriefingStore.replaceAll] writes
     * through one fixed temp path. Concurrent writers would race on that file.
     */
    @Synchronized
    fun syncNow(): Boolean {
        // Null means the fetch failed. An empty list is a real answer — the user deleted
        // their last contact — and must clear the snapshot rather than preserve it.
        val briefings = client.fetchAll()
        if (briefings == null) {
            Log.w(TAG, "Briefing sync failed; keeping the previous snapshot")
            return false
        }

        store.replaceAll(briefings)
        Log.d(TAG, "Briefing snapshot refreshed (${briefings.size} contacts)")
        return true
    }

    companion object {
        private const val TAG = "BriefingSync"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.BriefingSyncTest"
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Wire the triggers into `CallMonitorService`**

In `CallMonitorService.kt`, add these imports:

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
```

Add a field beside `audioProcessor` (near line 26):

```kotlin
private lateinit var briefingSync: BriefingSync
```

In `onCreate`, after the `audioProcessor` assignment (the block ending at line 43), add:

```kotlin
        briefingSync = BriefingSync(
            client = BriefingClient(authStore, tokenRefresher),
            store = BriefingStore.default(filesDir),
        )
```

**Share the refresher, not just the store.** `TokenRefresher.refresh()` is `@Synchronized` on
the *instance*, so two instances hold two different locks and do not serialize with each
other. Refresh tokens are single-use: if an upload retry and a briefing sync both hit 401
near-simultaneously with separate refreshers, both POST the same refresh token, one rotates
it, and the other gets a 401 — which `refresh()` treats as a real logout and clears the
session. So hoist the existing `TokenRefresher(authStore)` out of the `AudioProcessor`
constructor call into a local `val tokenRefresher` and pass that one instance to both. This
is the same reasoning that already makes `onCreate` share a single `AuthStore`.

At the end of `onCreate`, after `flushPending()` (line 57), add:

```kotlin
        startBriefingSyncLoop()
```

Extend `onStartCommand` (lines 60-63) to handle the new action:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FLUSH -> flushPending()
            ACTION_SYNC_BRIEFINGS -> syncBriefings()
        }
        return START_STICKY
    }
```

Add these two methods beside `flushPending`:

```kotlin
    private fun syncBriefings() {
        serviceScope.launch {
            try {
                briefingSync.syncNow()
            } catch (e: Exception) {
                Log.e(TAG, "Briefing sync failed", e)
            }
        }
    }

    /**
     * Periodic refresh, riding on this already-persistent service rather than adding
     * WorkManager for one job. If this service is dead the app is not recording calls
     * either, so there is nothing new to sync.
     */
    private fun startBriefingSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    briefingSync.syncNow()
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic briefing sync failed", e)
                }
                delay(BRIEFING_SYNC_INTERVAL_MS)
            }
        }
    }
```

In `handleNewFile` (lines 108-117), sync after a successful upload — the contact's summary and tasks have just changed. Replace the method body with:

```kotlin
    private fun handleNewFile(file: File) {
        serviceScope.launch {
            try {
                audioProcessor.processAndSendToBackend(file)
                // The call that just uploaded produces a new summary and new tasks.
                briefingSync.syncNow()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process ${file.name}", e)
                notifyError(file.name, e.message ?: "Unknown error")
            }
        }
    }
```

Add to the `companion object` (beside `ACTION_FLUSH`, line 158):

```kotlin
        const val ACTION_SYNC_BRIEFINGS = "com.brachaai.app.action.SYNC_BRIEFINGS"
        private val BRIEFING_SYNC_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)
```

Add the import for that constant:

```kotlin
import java.util.concurrent.TimeUnit
```

And add the request helper beside `requestFlush` (lines 165-169):

```kotlin
        /** Asks the running service to refresh the overlay's briefing snapshot. */
        fun requestBriefingSync(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_SYNC_BRIEFINGS
            }
            context.startForegroundService(intent)
        }
```

- [ ] **Step 6: Wire the foreground trigger into `MainActivity`**

In `MainActivity.kt`, replace `onResume` (lines 105-108) with:

```kotlin
    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        // The user may have edited tasks in the WebView; keep the overlay's snapshot honest.
        if (CallMonitorService.isRunning) {
            CallMonitorService.requestBriefingSync(this)
        }
    }
```

- [ ] **Step 7: Verify the whole suite still passes**

```bash
cd android && ./gradlew testDebugUnitTest
```

Expected: PASS, including the pre-existing `CallMonitorServiceTest`.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/BriefingSync.kt android/app/src/test/java/com/brachaai/app/BriefingSyncTest.kt android/app/src/main/java/com/brachaai/app/CallMonitorService.kt android/app/src/main/java/com/brachaai/app/MainActivity.kt
git commit -m "feat(android): sync briefings on upload, foreground, and a 6-hour tick"
```

---

### Task 7: Overlay decision logic

The pure core of the ring path: given a number and whether the overlay permission is held, decide what to render. No Android imports, so every branch is testable.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/OverlayDecider.kt`
- Test: `android/app/src/test/java/com/brachaai/app/OverlayDeciderTest.kt`

**Interfaces:**
- Consumes: `PhoneNormalizer` (Task 3), `BriefingStore`, `Briefing` (Task 4).
- Produces:
  - `sealed interface OverlayAction` with `object DoNothing : OverlayAction` and `data class Show(val briefing: Briefing, val asNotification: Boolean) : OverlayAction`
  - `class OverlayDecider(store: BriefingStore)` with `decide(rawNumber: String?, canDrawOverlays: Boolean): OverlayAction`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/OverlayDeciderTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Robolectric: OverlayDecider reads through BriefingStore, which uses android.util.Log
// and org.json. The decider itself is pure, but its collaborator is not.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayDeciderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: BriefingStore

    @Before
    fun setUp() {
        store = BriefingStore(File(temp.newFolder("files"), "briefings.json"))
    }

    private fun seed(
        phone: String = "+972501234567",
        summary: String? = "Promised to send price quote.",
        tasks: List<BriefingTask> = listOf(BriefingTask("t1", "Send contract", "HIGH")),
    ) {
        store.replaceAll(listOf(Briefing("c1", "David Cohen", phone, summary, tasks, tasks.size)))
    }

    private fun decide(number: String?, canDrawOverlays: Boolean = true) =
        OverlayDecider(store).decide(number, canDrawOverlays)

    @Test
    fun `shows an overlay for a known contact`() {
        seed()

        val action = decide("+972501234567")

        assertTrue(action is OverlayAction.Show)
        assertEquals("David Cohen", (action as OverlayAction.Show).briefing.name)
        assertFalse(action.asNotification)
    }

    @Test
    fun `matches a known contact regardless of how the number is formatted`() {
        seed(phone = "+972501234567")

        assertTrue(decide("050-123-4567") is OverlayAction.Show)
    }

    @Test
    fun `does nothing for a number that is not a known contact`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide("+972529999999"))
    }

    @Test
    fun `does nothing for a withheld number`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide("-1"))
    }

    @Test
    fun `does nothing when there is no number at all`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide(null))
    }

    @Test
    fun `does nothing when the cache is empty`() {
        assertEquals(OverlayAction.DoNothing, decide("+972501234567"))
    }

    @Test
    fun `does nothing for a known contact with nothing to say`() {
        seed(summary = null, tasks = emptyList())

        assertEquals(OverlayAction.DoNothing, decide("+972501234567"))
    }

    @Test
    fun `shows a contact with tasks but no call summary`() {
        seed(summary = null)

        assertTrue(decide("+972501234567") is OverlayAction.Show)
    }

    @Test
    fun `shows a contact with a call summary but no tasks`() {
        seed(tasks = emptyList())

        assertTrue(decide("+972501234567") is OverlayAction.Show)
    }

    @Test
    fun `falls back to a notification when the overlay permission is not held`() {
        seed()

        val action = decide("+972501234567", canDrawOverlays = false)

        assertTrue((action as OverlayAction.Show).asNotification)
    }

    @Test
    fun `stays silent for an unknown caller even without the overlay permission`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide("+972529999999", canDrawOverlays = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.OverlayDeciderTest"
```

Expected: FAIL — unresolved references `OverlayDecider`, `OverlayAction`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/OverlayDecider.kt`:

```kotlin
package com.brachaai.app

sealed interface OverlayAction {

    /** Unknown caller, withheld number, or a contact with nothing worth saying. */
    object DoNothing : OverlayAction

    /** @param asNotification true when the overlay permission is not held. */
    data class Show(val briefing: Briefing, val asNotification: Boolean) : OverlayAction
}

/**
 * Decides what a ringing number should put on screen.
 *
 * Pure by design — no Android imports — so every branch of the ring path is unit-testable.
 * The service around it only performs the decision.
 */
class OverlayDecider(private val store: BriefingStore) {

    fun decide(rawNumber: String?, canDrawOverlays: Boolean): OverlayAction {
        val phoneKey = PhoneNormalizer.key(rawNumber) ?: return OverlayAction.DoNothing

        // A miss is a complete answer: unknown callers show nothing, so no network is needed.
        val briefing = store.lookup(phoneKey) ?: return OverlayAction.DoNothing

        // A known contact with no summary and no open tasks would render an empty card.
        if (briefing.lastCallSummary.isNullOrBlank() && briefing.openTasks.isEmpty()) {
            return OverlayAction.DoNothing
        }

        return OverlayAction.Show(briefing, asNotification = !canDrawOverlays)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.OverlayDeciderTest"
```

Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/OverlayDecider.kt android/app/src/test/java/com/brachaai/app/OverlayDeciderTest.kt
git commit -m "feat(android): add overlay decision logic for ringing numbers"
```

---

### Task 8: The card layout

Resources only — no logic. Build this before the service so the service has something to inflate.

**Files:**
- Create: `android/app/src/main/res/layout/overlay_call_briefing.xml`
- Create: `android/app/src/main/res/layout/overlay_task_item.xml`
- Create: `android/app/src/main/res/drawable/overlay_card_background.xml`
- Create: `android/app/src/main/res/drawable/overlay_avatar_background.xml`
- Modify: `android/app/src/main/res/values/colors.xml`
- Modify: `android/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the colors**

Append inside the `<resources>` element of `android/app/src/main/res/values/colors.xml`:

```xml
    <color name="overlay_card_background">#FF1C2333</color>
    <color name="overlay_card_stroke">#FF2E3950</color>
    <color name="overlay_primary_text">#FFF2F5FA</color>
    <color name="overlay_secondary_text">#FF9AA7BD</color>
    <color name="overlay_accent">#FF2F6BFF</color>
    <color name="overlay_task_bullet">#FFFF7A45</color>
    <color name="overlay_divider">#FF2A3346</color>
```

- [ ] **Step 2: Add the strings**

Append inside the `<resources>` element of `android/app/src/main/res/values/strings.xml`:

```xml
    <string name="overlay_last_interaction">Last Interaction</string>
    <string name="overlay_open_tasks">Open Tasks</string>
    <string name="overlay_more_tasks">+%1$d more</string>
    <string name="overlay_close">Close</string>
    <string name="overlay_permission_title">Show caller briefings</string>
    <string name="overlay_permission_body">Allow BrachaAI to draw over other apps and you\'ll see the last summary and open tasks for a contact while they\'re calling.</string>
    <string name="overlay_permission_enable">Enable</string>
    <string name="overlay_permission_dismiss">Not now</string>
```

- [ ] **Step 3: Add the drawables**

Create `android/app/src/main/res/drawable/overlay_card_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/overlay_card_background" />
    <corners android:radius="20dp" />
    <stroke
        android:width="1dp"
        android:color="@color/overlay_card_stroke" />
</shape>
```

Create `android/app/src/main/res/drawable/overlay_avatar_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/overlay_accent" />
</shape>
```

- [ ] **Step 4: Add the task row layout**

Create `android/app/src/main/res/layout/overlay_task_item.xml`. One row per task; the bullet is a separate view so it keeps its accent colour without span juggling:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingTop="6dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="•"
        android:textColor="@color/overlay_task_bullet"
        android:textSize="15sp"
        android:paddingEnd="8dp"
        android:paddingStart="0dp" />

    <TextView
        android:id="@+id/overlay_task_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="@color/overlay_task_bullet"
        android:textSize="15sp"
        android:maxLines="2"
        android:ellipsize="end" />
</LinearLayout>
```

- [ ] **Step 5: Add the card layout**

Create `android/app/src/main/res/layout/overlay_call_briefing.xml`. Sections carry ids so the service can hide the ones with no content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="12dp">

    <LinearLayout
        android:id="@+id/overlay_card"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="@drawable/overlay_card_background"
        android:padding="20dp"
        android:elevation="8dp">

        <View
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:layout_gravity="center_horizontal"
            android:background="@drawable/overlay_avatar_background" />

        <TextView
            android:id="@+id/overlay_name"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:gravity="center_horizontal"
            android:textColor="@color/overlay_primary_text"
            android:textSize="17sp"
            android:maxLines="1"
            android:ellipsize="end" />

        <LinearLayout
            android:id="@+id/overlay_summary_section"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="18dp"
                android:background="@color/overlay_divider" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="14dp"
                android:text="@string/overlay_last_interaction"
                android:textColor="@color/overlay_secondary_text"
                android:textSize="13sp" />

            <TextView
                android:id="@+id/overlay_summary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:textColor="@color/overlay_primary_text"
                android:textSize="15sp"
                android:maxLines="3"
                android:ellipsize="end" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/overlay_tasks_section"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="18dp"
                android:background="@color/overlay_divider" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="14dp"
                android:text="@string/overlay_open_tasks"
                android:textColor="@color/overlay_secondary_text"
                android:textSize="13sp" />

            <LinearLayout
                android:id="@+id/overlay_tasks"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:orientation="vertical" />

            <TextView
                android:id="@+id/overlay_more_tasks"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:textColor="@color/overlay_secondary_text"
                android:textSize="13sp"
                android:visibility="gone" />
        </LinearLayout>
    </LinearLayout>

    <TextView
        android:id="@+id/overlay_close"
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:layout_gravity="top|end"
        android:layout_marginTop="20dp"
        android:layout_marginEnd="20dp"
        android:gravity="center"
        android:text="✕"
        android:textColor="@color/overlay_secondary_text"
        android:textSize="16sp"
        android:contentDescription="@string/overlay_close" />
</FrameLayout>
```

- [ ] **Step 6: Verify the resources compile**

```bash
cd android && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/layout/overlay_call_briefing.xml android/app/src/main/res/layout/overlay_task_item.xml android/app/src/main/res/drawable/overlay_card_background.xml android/app/src/main/res/drawable/overlay_avatar_background.xml android/app/src/main/res/values/colors.xml android/app/src/main/res/values/strings.xml
git commit -m "feat(android): add call briefing card layout and resources"
```

---

### Task 9: Overlay window and notification renderers

Two ways to put a briefing on screen, behind one small interface so the receiver does not care which it gets.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/CallOverlayService.kt`
- Create: `android/app/src/main/java/com/brachaai/app/BriefingNotifier.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `Briefing`, `BriefingStore`, `BriefingClient`, `PhoneNormalizer`, the Task 8 layouts.
- Produces:
  - `CallOverlayService.show(context: Context, phoneKey: String)` and `CallOverlayService.dismiss(context: Context)`
  - `BriefingNotifier.show(context: Context, briefing: Briefing)` and `BriefingNotifier.dismiss(context: Context)`
  - `MainActivity.EXTRA_CONTACT_ID = "com.brachaai.app.extra.CONTACT_ID"` (consumed in Task 11)

> **Deviation from the spec, deliberate:** the spec put the decision inside `CallOverlayService`. This plan moves it into the receiver (Task 10) and keeps the service as the overlay renderer only. Reason: starting a *service* to post a notification would hit Android 12's background-start restriction on exactly the devices where the overlay permission is missing — the one case the fallback exists to serve. Posting the notification straight from the receiver avoids that entirely. Consequence: the live refresh applies to the overlay path only; the notification path shows cached data. That is an acceptable trade for a fallback.

- [ ] **Step 1: Write the notifier**

Create `android/app/src/main/java/com/brachaai/app/BriefingNotifier.kt`:

```kotlin
package com.brachaai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The fallback renderer, used when the overlay permission is not held.
 *
 * Posted directly from the receiver rather than via a service: starting a service from the
 * background is restricted on Android 12+, and the exemption we rely on for the overlay is
 * the overlay permission itself — precisely what is missing here.
 */
object BriefingNotifier {

    fun show(context: Context, briefing: Briefing) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager)

        val lines = buildList {
            briefing.lastCallSummary?.takeIf { it.isNotBlank() }?.let(::add)
            val shown = briefing.openTasks.take(CallOverlayService.MAX_TASKS_SHOWN)
            shown.forEach { add("• ${it.title}") }
            // Subtract what was actually shown, not the cap — the two renderers must not
            // disagree about the same number if the list is ever shorter than the cap.
            val hidden = briefing.openTaskCount - shown.size
            if (hidden > 0) add(context.getString(R.string.overlay_more_tasks, hidden))
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(briefing.name)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(contactIntent(context, briefing.contactId))
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun contactIntent(context: Context, contactId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CONTACT_ID, contactId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Caller Briefings", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private const val CHANNEL_ID = "caller_briefing"

    /** Fixed id: a second call replaces the first card rather than stacking beside it. */
    private const val NOTIFICATION_ID = 2
}
```

- [ ] **Step 2: Write the overlay service**

Create `android/app/src/main/java/com/brachaai/app/CallOverlayService.kt`:

```kotlin
package com.brachaai.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Holds the floating briefing card for the duration of a call.
 *
 * Not a foreground service: a second persistent notification on every call would be worse
 * than the card itself, and the process is already kept alive by [CallMonitorService]. The
 * SYSTEM_ALERT_WINDOW grant is what exempts this from Android 12's background-start rules —
 * which is why the notification fallback never comes through here (see [BriefingNotifier]).
 */
class CallOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        Log.w(TAG, "Overlay timed out; a call-ended broadcast was probably missed")
        stopSelf()
    }

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var store: BriefingStore? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        store = BriefingStore.default(filesDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val phoneKey = intent.getStringExtra(EXTRA_PHONE_KEY)
                if (phoneKey.isNullOrBlank()) stopSelf() else showFor(phoneKey)
            }
            ACTION_DISMISS -> stopSelf()
            else -> stopSelf()
        }
        // START_NOT_STICKY: a card resurrected after a process death would have no call to
        // belong to.
        return START_NOT_STICKY
    }

    private fun showFor(phoneKey: String) {
        val briefing = store?.lookup(phoneKey)
        if (briefing == null) {
            Log.d(TAG, "No cached briefing for the ringing number")
            stopSelf()
            return
        }

        render(briefing)

        mainHandler.removeCallbacks(timeout)
        mainHandler.postDelayed(timeout, MAX_LIFETIME_MS)

        refreshInBackground(briefing.contactId)
    }

    /**
     * Live refresh. The result is discarded unless the card is still on screen — a response
     * that arrives after the call ended has nowhere to go.
     */
    private fun refreshInBackground(contactId: String) {
        scope.launch {
            val authStore = AuthStore(this@CallOverlayService)
            val fresh = BriefingClient(authStore, TokenRefresher(authStore)).fetchOne(contactId)
                ?: return@launch

            withContext(Dispatchers.Main) {
                if (view != null) render(fresh)
            }
        }
    }

    private fun render(briefing: Briefing) {
        val root = view ?: inflate() ?: return
        bind(root, briefing)
    }

    private fun inflate(): View? {
        val manager = windowManager ?: return null
        val root = LayoutInflater.from(this).inflate(R.layout.overlay_call_briefing, null)

        root.findViewById<View>(R.id.overlay_close).setOnClickListener { stopSelf() }
        root.findViewById<View>(R.id.overlay_card).setOnClickListener {
            openContact(root.tag as? String)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE keeps key events with the dialer; the card is still clickable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // LayoutParams.y is in PIXELS, unlike every dp value in the layout XML. Convert
            // explicitly — a bare integer here means a different offset on every density.
            y = (OVERLAY_TOP_MARGIN_DP * resources.displayMetrics.density).toInt()
        }

        return try {
            manager.addView(root, params)
            view = root
            root
        } catch (e: Exception) {
            // Permission revoked between the check and here, or an OEM refusing the window.
            Log.e(TAG, "Could not attach the overlay", e)
            stopSelf()
            null
        }
    }

    private fun bind(root: View, briefing: Briefing) {
        root.tag = briefing.contactId
        root.findViewById<TextView>(R.id.overlay_name).text = briefing.name

        val summary = briefing.lastCallSummary?.takeIf { it.isNotBlank() }
        root.findViewById<View>(R.id.overlay_summary_section).visibility =
            if (summary == null) View.GONE else View.VISIBLE
        summary?.let { root.findViewById<TextView>(R.id.overlay_summary).text = it }

        val shown = briefing.openTasks.take(MAX_TASKS_SHOWN)
        root.findViewById<View>(R.id.overlay_tasks_section).visibility =
            if (shown.isEmpty()) View.GONE else View.VISIBLE

        val container = root.findViewById<LinearLayout>(R.id.overlay_tasks)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        shown.forEach { task ->
            val row = inflater.inflate(R.layout.overlay_task_item, container, false)
            row.findViewById<TextView>(R.id.overlay_task_title).text = task.title
            container.addView(row)
        }

        // Counted from the untruncated total, not the list, which is capped twice over.
        val hidden = briefing.openTaskCount - shown.size
        val more = root.findViewById<TextView>(R.id.overlay_more_tasks)
        if (hidden > 0) {
            more.text = getString(R.string.overlay_more_tasks, hidden)
            more.visibility = View.VISIBLE
        } else {
            more.visibility = View.GONE
        }
    }

    private fun openContact(contactId: String?) {
        if (contactId.isNullOrBlank()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CONTACT_ID, contactId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeout)
        view?.let { attached ->
            try {
                windowManager?.removeView(attached)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay was already detached", e)
            }
        }
        view = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallOverlayService"
        private const val ACTION_SHOW = "com.brachaai.app.action.SHOW_OVERLAY"
        private const val ACTION_DISMISS = "com.brachaai.app.action.DISMISS_OVERLAY"
        private const val EXTRA_PHONE_KEY = "com.brachaai.app.extra.PHONE_KEY"

        /** Kept small so the card cannot grow tall enough to cover the answer control. */
        const val MAX_TASKS_SHOWN = 3

        /** Gap below the status bar, in dp — converted to px at attach time. */
        private const val OVERLAY_TOP_MARGIN_DP = 48f

        /**
         * Backstop only — a dropped call-ended broadcast must not strand a card forever.
         * Set well past any plausible call length; IDLE is what normally ends the card.
         */
        private val MAX_LIFETIME_MS = TimeUnit.MINUTES.toMillis(30)

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun show(context: Context, phoneKey: String) {
            val intent = Intent(context, CallOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_PHONE_KEY, phoneKey)
            }
            context.startService(intent)
        }

        fun dismiss(context: Context) {
            val intent = Intent(context, CallOverlayService::class.java).apply {
                action = ACTION_DISMISS
            }
            context.startService(intent)
        }
    }
}
```

- [ ] **Step 3: Add the contact-id extra to `MainActivity`**

Both renderers above build an Intent carrying `MainActivity.EXTRA_CONTACT_ID`, so it must exist before this task compiles. Task 11 wires up what the activity *does* with it; this step only declares the key.

In `android/app/src/main/java/com/brachaai/app/MainActivity.kt`, add a companion object at the end of the class:

```kotlin
    companion object {
        /** Set by the caller-briefing card; opens the app on that contact. See Task 11. */
        const val EXTRA_CONTACT_ID = "com.brachaai.app.extra.CONTACT_ID"
    }
```

- [ ] **Step 4: Declare the permission and service**

In `android/app/src/main/AndroidManifest.xml`, add beside the other `uses-permission` elements (after line 13):

```xml
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

And add inside `<application>`, after the `CallMonitorService` declaration (line 42):

```xml
        <service
            android:name=".CallOverlayService"
            android:exported="false" />
```

- [ ] **Step 5: Verify it compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/CallOverlayService.kt android/app/src/main/java/com/brachaai/app/BriefingNotifier.kt android/app/src/main/java/com/brachaai/app/MainActivity.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): render the caller briefing as an overlay or notification"
```

---

### Task 10: Phone state receiver

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/PhoneStateReceiver.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/com/brachaai/app/PhoneStateReceiverTest.kt`

**Interfaces:**
- Consumes: `OverlayDecider` (Task 7), `CallOverlayService.show/dismiss/canDrawOverlays`, `BriefingNotifier.show/dismiss` (Task 9), `PhoneNormalizer` (Task 3).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/PhoneStateReceiverTest.kt`. Robolectric gives a real `Context` and records started services:

```kotlin
package com.brachaai.app

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneStateReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).clearStartedServices()
        BriefingStore.default(context.filesDir).replaceAll(
            listOf(
                Briefing(
                    contactId = "c1",
                    name = "David Cohen",
                    phone = "+972501234567",
                    lastCallSummary = "Promised to send price quote.",
                    openTasks = listOf(BriefingTask("t1", "Send contract", "HIGH")),
                    openTaskCount = 1,
                )
            )
        )
    }

    private fun broadcast(state: String, number: String? = null) {
        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, state)
            number?.let { putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, it) }
        }
        PhoneStateReceiver().onReceive(context, intent)
    }

    private fun nextService(): Intent? =
        shadowOf(context as android.app.Application).nextStartedService

    @Test
    fun `a ringing known contact starts the overlay service`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")

        val started = nextService()
        assertEquals(CallOverlayService::class.java.name, started?.component?.className)
    }

    @Test
    fun `a ringing unknown number starts nothing`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972529999999")

        assertNull(nextService())
    }

    @Test
    fun `a withheld number starts nothing`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "-1")

        assertNull(nextService())
    }

    @Test
    fun `an unrelated broadcast is ignored`() {
        PhoneStateReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(nextService())
    }

    @Test
    fun `an idle broadcast dismisses the overlay`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")
        shadowOf(context as android.app.Application).clearStartedServices()

        broadcast(TelephonyManager.EXTRA_STATE_IDLE)

        assertEquals(CallOverlayService::class.java.name, nextService()?.component?.className)
    }

    @Test
    fun `an offhook broadcast leaves the card alone`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")
        shadowOf(context as android.app.Application).clearStartedServices()

        broadcast(TelephonyManager.EXTRA_STATE_OFFHOOK)

        assertNull("answering must not tear the card down", nextService())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PhoneStateReceiverTest"
```

Expected: FAIL — unresolved reference `PhoneStateReceiver`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/PhoneStateReceiver.kt`:

```kotlin
package com.brachaai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Turns a ringing phone into a briefing card.
 *
 * Manifest-registered rather than a TelephonyCallback registered from a service:
 * ACTION_PHONE_STATE_CHANGED is exempt from Android 8's implicit-broadcast restrictions, so
 * this still fires when the app has been closed.
 *
 * The decision runs synchronously on the main thread. It reads one small JSON file and does
 * no network, which is well inside a receiver's budget — and the whole point is to decide
 * before the ring feels stale.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> onRinging(context, intent)

            // The card is meant to persist through the answered call, so OFFHOOK is
            // deliberately not handled. IDLE is the only thing that ends it.
            TelephonyManager.EXTRA_STATE_IDLE -> dismiss(context)
        }
    }

    private fun onRinging(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        val decider = OverlayDecider(BriefingStore.default(context.filesDir))
        when (val action = decider.decide(number, CallOverlayService.canDrawOverlays(context))) {
            is OverlayAction.DoNothing -> Log.d(TAG, "Nothing to show for this caller")

            is OverlayAction.Show -> if (action.asNotification) {
                BriefingNotifier.show(context, action.briefing)
            } else {
                // The service re-reads the briefing itself, so no model crosses the Intent.
                CallOverlayService.show(context, PhoneNormalizer.key(number)!!)
            }
        }
    }

    /** Both renderers are cleared: whichever is up, the call is over. */
    private fun dismiss(context: Context) {
        CallOverlayService.dismiss(context)
        BriefingNotifier.dismiss(context)
    }

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }
}
```

- [ ] **Step 4: Register the receiver**

In `android/app/src/main/AndroidManifest.xml`, add inside `<application>` after the `BootReceiver` element:

```xml
        <receiver
            android:name=".PhoneStateReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.PHONE_STATE" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PhoneStateReceiverTest"
```

Expected: PASS, 6 tests.

If `nextStartedService` returns null for the RINGING case, check that Robolectric reports `Settings.canDrawOverlays` as true by default — if not, the decider correctly chose the notification path, and the test should assert on the notification instead via `shadowOf(notificationManager).allNotifications`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/PhoneStateReceiver.kt android/app/src/test/java/com/brachaai/app/PhoneStateReceiverTest.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): show a briefing card when a known contact calls"
```

---

### Task 11: Permission onboarding and contact deep link

The last wiring: let the user grant the overlay permission, and make tapping the card land on the right page.

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/MainActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `CallOverlayService.canDrawOverlays` (Task 9), `MainActivity.EXTRA_CONTACT_ID` (declared in Task 9 Step 3).
- Produces: nothing consumed by later tasks.

> **Deviation from the spec, deliberate:** the spec asked for a re-entry point "in the app's Settings page" for users who decline. That page lives in the React frontend, so honouring it literally would mean a `NativeBridge` method, a frontend change, and an `assets/www` rebuild — for one button. Instead `overlayPromptDismissed` is **in-memory only**: dismissing hides the prompt for the session, and it returns on the next app launch. That is a re-entry point with no new surface. If it proves annoying, persist it in `SettingsStore` and add the bridge method then.

- [ ] **Step 1: Make the activity single-top**

In `android/app/src/main/AndroidManifest.xml`, add to the `MainActivity` element (line 28-32) so a tap while the app is already open reuses the instance instead of stacking a second one:

```xml
            android:launchMode="singleTop"
```

- [ ] **Step 2: Add the deep link and the permission prompt to `MainActivity`**

Add these imports. `Box`, `Row`, `Card`, and `TextButton` are already covered by the existing `androidx.compose.foundation.layout.*` and `androidx.compose.material3.*` wildcards (lines 16-17) — do not add them again:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
```

Add state beside the existing `by mutableStateOf` fields (lines 28-30):

```kotlin
    private var overlayPromptDismissed by mutableStateOf(false)
    private var canDrawOverlays by mutableStateOf(false)
    private var startUrl by mutableStateOf(WEB_URL)
```

In `onCreate`, before `refreshPermissionState()` (line 71), set the initial URL:

```kotlin
        startUrl = urlFor(intent)
```

Add `onNewIntent`, so a tap while the app is already running navigates instead of doing nothing:

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = urlFor(intent)
        startUrl = target
        webView?.loadUrl(target)
    }

    /**
     * The web app uses HashRouter, so a route is a fragment on the bundled index.html.
     */
    private fun urlFor(intent: Intent?): String {
        val contactId = intent?.getStringExtra(EXTRA_CONTACT_ID)
        return if (contactId.isNullOrBlank()) WEB_URL else "$WEB_URL#/contacts/$contactId"
    }
```

Replace the `WebViewScreen` call inside `setContent` (lines 80-85) to use the state-held URL:

```kotlin
                if (permissionsGranted && allFilesGranted) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        WebViewScreen(
                            url = startUrl,
                            onAuthenticated = { CallMonitorService.requestFlush(this@MainActivity) }
                        ) { wv ->
                            webView = wv
                        }
                        if (!canDrawOverlays && !overlayPromptDismissed) {
                            OverlayPermissionPrompt(
                                onEnable = { openOverlaySettings() },
                                onDismiss = { overlayPromptDismissed = true },
                            )
                        }
                    }
                } else if (!allFilesGranted) {
```

Add the prompt composable and the settings deep link at the bottom of the class. It is a `BoxScope` extension so `Modifier.align` resolves — it is called from inside the `Box` added above:

```kotlin
    @Composable
    private fun BoxScope.OverlayPermissionPrompt(onEnable: () -> Unit, onDismiss: () -> Unit) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.overlay_permission_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.overlay_permission_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.overlay_permission_dismiss))
                    }
                    Button(onClick = onEnable) {
                        Text(stringResource(R.string.overlay_permission_enable))
                    }
                }
            }
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }
```

`BoxScope` is covered by the `androidx.compose.foundation.layout.*` wildcard already present.

Finally, keep `canDrawOverlays` current — the user grants it in Settings and comes back to the app, and `onResume` already calls this. In `refreshPermissionState()` (line 110), add as the first line:

```kotlin
        canDrawOverlays = CallOverlayService.canDrawOverlays(this)
```

Extend the `companion object` added in Task 9 Step 3 with the base URL:

```kotlin
    companion object {
        /** Set by the caller-briefing card; opens the app on that contact. */
        const val EXTRA_CONTACT_ID = "com.brachaai.app.extra.CONTACT_ID"
        private const val WEB_URL = "file:///android_asset/www/index.html"
    }
```

- [ ] **Step 3: Verify it compiles and all tests pass**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/MainActivity.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): prompt for overlay permission and deep-link the card to the contact"
```

---

### Task 12: Documentation

**Files:**
- Modify: `android/CLAUDE.md`
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: Document the Android side**

In `android/CLAUDE.md`, add a numbered entry to the Architecture list after the `PendingUploadStore` item:

```markdown
7. **Overlay pipeline** — `PhoneStateReceiver` fires on `PHONE_STATE`; on RINGING it asks
   `OverlayDecider` what to do with the number. `PhoneNormalizer` reduces every spelling of a
   number to one key, and `BriefingStore` (a single JSON snapshot in app-private storage,
   refreshed by `BriefingSync` via `BriefingClient` from `GET /api/briefings`) answers
   "is this a known contact?" with no network in the path. A hit renders `CallOverlayService`
   (a `WindowManager` card, requiring SYSTEM_ALERT_WINDOW) or, without that permission,
   `BriefingNotifier`. A miss shows nothing, which is why phone matching never has to happen
   on the backend.
```

Add to Key Details:

```markdown
- **Overlay permission**: `SYSTEM_ALERT_WINDOW` is optional and never gates the WebView.
  `MainActivity` shows a dismissible prompt deep-linking to the settings screen.
```

- [ ] **Step 2: Document the backend endpoints**

In `ARCHITECTURE.md`, add to the `backend/` component list under `services/`:

```markdown
    *   `briefingService.ts`: Assembles the per-contact briefing (last summarised call plus open tasks, capped) that the Android overlay shows when a known contact calls.
```

- [ ] **Step 3: Commit**

```bash
git add android/CLAUDE.md ARCHITECTURE.md
git commit -m "docs: describe the incoming-call briefing overlay"
```

---

## Manual verification

Unit tests cannot cover `WindowManager` attachment, real telephony broadcasts, or OEM behaviour. Walk these on a physical device after Task 11.

Build and install:

```bash
cd android && ./gradlew installDebug
```

1. Known contact calls → card appears while ringing with the right name, summary, and tasks.
2. Answer → card persists through the conversation.
3. End the call → card disappears.
4. Tap ✕ during the ring → card disappears; answer and reject still work.
5. Tap the card → app opens on that contact's page.
6. Unknown number calls → nothing appears.
7. Contact with tasks but no summary → no empty "Last Interaction" section.
8. Contact with a summary but no tasks → no empty "Open Tasks" section.
9. Contact with more than 3 open tasks → "+N more" shows the untruncated count.
10. Airplane mode → card still appears from cache.
11. Revoke "Display over other apps" → a high-priority notification appears instead.
12. App force-stopped, then a call arrives → the receiver still fires and the card appears.
13. Two calls back to back → content replaces; no stacked cards.
14. Ring → reject → ring again → no stranded card.
15. Reinstall, log in, call before any sync → nothing appears, and no crash.
16. Without the overlay permission → the prompt shows over the WebView but does **not** block login or navigation. "Not now" hides it; relaunching the app brings it back.
17. Grant the permission from the prompt and return to the app → the prompt is gone without a restart.

---

## Notes for the implementer

**The card must never block the answer button.** `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL` and the 3-task cap both exist for this. If you change the layout, re-run manual check 4 before committing.

**Do not move phone matching to the backend.** It looks like a natural fit for a `?phone=` query, but the entire reason it lives on the device is that a cache miss is already a complete answer. Moving it creates two normalizers that must agree forever.

**Do not make `CallOverlayService` a foreground service.** A second persistent notification on every call is worse than the problem it solves, and the process is already held alive by `CallMonitorService`.

**`assets/www/` is a build artifact.** This feature does not change the frontend, so no rebuild is needed. If you do touch `frontend/`, follow the process in `android/CLAUDE.md` or the app ships the previous bundle.
