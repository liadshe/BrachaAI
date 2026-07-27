# Delete Calls and Contacts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user delete calls (WhatsApp-style long-press multi-select) and delete a contact along with all of its calls and tasks.

**Architecture:** Two new backend endpoints — a bulk `POST /api/calls/bulk-delete` and a cascading `DELETE /api/contacts/:id` — both scoped by the JWT's `userId`. On the frontend, a shared `useMultiSelect` hook plus `SelectionBar` and `ConfirmDialog` components drive identical long-press selection on the Home page's recent-calls list and the Contact Details page's Calls tab. Contact deletion is a separate single-item button on Contact Details.

**Tech Stack:** Express 5 + Mongoose 9 + TypeScript (backend); React 18 + Vite 5 + TypeScript + CSS Modules + axios (frontend); Vitest for both.

**Spec:** `docs/superpowers/specs/2026-07-27-delete-calls-and-contacts-design.md`

## Global Constraints

- **No schema changes.** `Task`, `Call`, and `Contact` models are not modified. Tasks stay linked to contacts only.
- **Deletes are permanent.** No soft-delete flag, no trash, no undo.
- **Every delete query filters on `userId`** taken from `req.user.id`, alongside the document ID. Never `deleteMany({ _id: { $in: ids } })` without it.
- **Backend tests mock the Mongoose models** with `vi.mock`. No real database, no `mongodb-memory-server`.
- **Import Vitest helpers explicitly** (`import { describe, it, expect, vi } from 'vitest'`) rather than relying on globals — the frontend's `npm run build` runs `tsc` over `src/`, and globals would require editing `tsconfig.json` types.
- **Frontend `tsconfig.json` sets `noUnusedLocals` and `noUnusedParameters`.** Unused imports or parameters break `npm run build`, not just lint.
- **Destructive UI colors:** confirm-button background `#dc2626`, hover `#b91c1c`. Cards are `#ffffff`, radius `24px`, border `1px solid #f1f5f9`, primary blue `#2563eb`, heading text `#0f172a`.
- **Frontend uses `apiClient`** (axios, from `src/services/apiClient.ts`) in pages. Do not add methods to `src/services/api.ts` — that file is unused legacy.

## File Structure

**Backend — create:**
- `backend/vitest.config.ts` — test runner config, supplies `JWT_SECRET` so `config/jwt.ts` doesn't `process.exit` the worker
- `backend/src/utils/objectId.ts` — ObjectId string validation, no Mongoose dependency
- `backend/src/utils/objectId.test.ts`
- `backend/src/services/contactService.ts` — cascading contact delete
- `backend/src/services/contactService.test.ts`
- `backend/src/services/callService.test.ts`
- `backend/src/controllers/callController.test.ts`
- `backend/src/controllers/contactController.test.ts`

**Backend — modify:**
- `backend/package.json` — add Vitest and a `test` script
- `backend/tsconfig.json` — exclude test files from the `dist` build
- `backend/src/services/callService.ts` — add `deleteCallsByIds`
- `backend/src/controllers/callController.ts` — add `bulkDeleteCalls`
- `backend/src/controllers/contactController.ts` — add `deleteContact`
- `backend/src/routes/callRoute.ts` — register the bulk-delete route
- `backend/src/routes/contactRoute.ts` — register the delete route

**Frontend — create:**
- `frontend/src/hooks/useMultiSelect.ts` — long-press selection state machine
- `frontend/src/hooks/useMultiSelect.test.ts`
- `frontend/src/hooks/useSelectionBackButton.ts` — Android back button exits selection
- `frontend/src/hooks/useCallDeletion.ts` — the bulk-delete request, count-mismatch check, and error/refetch recovery, shared by both pages
- `frontend/src/hooks/useCallDeletion.test.ts`
- `frontend/src/components/SelectionBar.tsx` + `.module.css`
- `frontend/src/components/ConfirmDialog.tsx` + `.module.css`

**Frontend — modify:**
- `frontend/package.json` — add Vitest, jsdom, Testing Library, `test` script
- `frontend/vite.config.ts` — add the Vitest `test` block
- `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx` + `.module.css`
- `frontend/src/pages/HomePage/HomePage.tsx` + `.module.css`

**Responsibility boundaries:** `useMultiSelect` owns gesture timing and selection state and knows nothing about calls or HTTP. `SelectionBar` and `ConfirmDialog` are presentational — they take props and emit callbacks. The pages own the API calls and the local list state. Backend services own Mongoose queries; controllers own HTTP status codes and validation.

---

## Task 1: Backend test infrastructure and ObjectId validation

**Files:**
- Modify: `backend/package.json`
- Modify: `backend/tsconfig.json`
- Create: `backend/vitest.config.ts`
- Create: `backend/src/utils/objectId.ts`
- Test: `backend/src/utils/objectId.test.ts`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `isObjectId(value: unknown): value is string`
  - `MAX_BULK_IDS: number` (200)
  - `type IdListValidation = { ok: true; ids: string[] } | { ok: false; message: string }`
  - `validateObjectIdList(value: unknown): IdListValidation`

- [ ] **Step 1: Install Vitest in the backend**

```bash
cd backend
npm install --save-dev vitest@^3.2.4
```

- [ ] **Step 2: Add the test script to `backend/package.json`**

In the `"scripts"` block, add these two entries alongside the existing `dev`, `build`, and `start`:

```json
    "test": "vitest run",
    "test:watch": "vitest"
```

- [ ] **Step 3: Exclude test files from the production build**

`npm run build` runs `tsc`, which compiles everything matched by `include` into `dist/`. Without this, test files land in the shipped build. Add an `"exclude"` key to `backend/tsconfig.json` as a sibling of `"include"`:

```json
  "include": ["src/**/*"],
  "exclude": ["src/**/*.test.ts"]
```

- [ ] **Step 4: Add a Vitest config that supplies JWT_SECRET**

`backend/src/config/jwt.ts` calls `process.exit(1)` at import time when `JWT_SECRET` is unset, which would kill the Vitest worker with no useful error on any machine without a `backend/.env`. Set it for tests explicitly.

Create `backend/vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
    test: {
        environment: 'node',
        include: ['src/**/*.test.ts'],
        // src/config/jwt.ts calls process.exit(1) when this is missing, which
        // would kill the test worker on any machine without a backend/.env.
        env: {
            JWT_SECRET: 'test-secret',
        },
    },
});
```

- [ ] **Step 5: Write the failing test**

Create `backend/src/utils/objectId.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { isObjectId, validateObjectIdList, MAX_BULK_IDS } from './objectId';

const VALID_A = '507f1f77bcf86cd799439011';
const VALID_B = '507f191e810c19729de860ea';

describe('isObjectId', () => {
    it('accepts a 24-character hex string', () => {
        expect(isObjectId(VALID_A)).toBe(true);
    });

    it('accepts uppercase hex', () => {
        expect(isObjectId(VALID_A.toUpperCase())).toBe(true);
    });

    it('rejects a 12-character string', () => {
        // Mongoose's own ObjectId.isValid accepts any 12-character string,
        // which would let "hello world!" through. This must not.
        expect(isObjectId('hello world!')).toBe(false);
    });

    it('rejects non-hex characters', () => {
        expect(isObjectId('507f1f77bcf86cd7994390zz')).toBe(false);
    });

    it('rejects non-strings', () => {
        expect(isObjectId(42)).toBe(false);
        expect(isObjectId(null)).toBe(false);
        expect(isObjectId(undefined)).toBe(false);
        expect(isObjectId({})).toBe(false);
    });
});

describe('validateObjectIdList', () => {
    it('accepts a list of valid ids', () => {
        const result = validateObjectIdList([VALID_A, VALID_B]);
        expect(result).toEqual({ ok: true, ids: [VALID_A, VALID_B] });
    });

    it('rejects a missing value', () => {
        const result = validateObjectIdList(undefined);
        expect(result.ok).toBe(false);
    });

    it('rejects a non-array', () => {
        const result = validateObjectIdList(VALID_A);
        expect(result.ok).toBe(false);
    });

    it('rejects an empty array', () => {
        const result = validateObjectIdList([]);
        expect(result.ok).toBe(false);
    });

    it('rejects more than MAX_BULK_IDS entries', () => {
        const tooMany = Array.from({ length: MAX_BULK_IDS + 1 }, () => VALID_A);
        const result = validateObjectIdList(tooMany);
        expect(result.ok).toBe(false);
    });

    it('accepts exactly MAX_BULK_IDS entries', () => {
        const atLimit = Array.from({ length: MAX_BULK_IDS }, () => VALID_A);
        expect(validateObjectIdList(atLimit).ok).toBe(true);
    });

    it('rejects the whole list when one id is malformed', () => {
        const result = validateObjectIdList([VALID_A, 'not-an-id']);
        expect(result.ok).toBe(false);
    });
});
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd backend && npx vitest run src/utils/objectId.test.ts`
Expected: FAIL — `Failed to resolve import "./objectId"`.

- [ ] **Step 7: Write the implementation**

Create `backend/src/utils/objectId.ts`:

```ts
export const MAX_BULK_IDS = 200;

const OBJECT_ID_PATTERN = /^[0-9a-f]{24}$/i;

/**
 * Mongoose's ObjectId.isValid() returns true for any 12-character string,
 * so it cannot be used to validate untrusted input. This checks the actual
 * 24-character hex form that arrives over JSON.
 */
export const isObjectId = (value: unknown): value is string =>
    typeof value === 'string' && OBJECT_ID_PATTERN.test(value);

export type IdListValidation =
    | { ok: true; ids: string[] }
    | { ok: false; message: string };

export const validateObjectIdList = (value: unknown): IdListValidation => {
    if (!Array.isArray(value)) {
        return { ok: false, message: 'ids must be an array' };
    }
    if (value.length === 0) {
        return { ok: false, message: 'ids must not be empty' };
    }
    if (value.length > MAX_BULK_IDS) {
        return { ok: false, message: `ids must contain at most ${MAX_BULK_IDS} items` };
    }
    for (const id of value) {
        if (!isObjectId(id)) {
            return { ok: false, message: 'ids must all be valid object ids' };
        }
    }
    return { ok: true, ids: value as string[] };
};
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && npm test`
Expected: PASS — 13 tests.

- [ ] **Step 9: Verify the production build still works**

Run: `cd backend && npm run build`
Expected: exits 0, and `backend/dist/utils/objectId.test.js` does **not** exist.

- [ ] **Step 10: Commit**

```bash
git add backend/package.json backend/package-lock.json backend/tsconfig.json backend/vitest.config.ts backend/src/utils/objectId.ts backend/src/utils/objectId.test.ts
git commit -m "test: add vitest to backend with objectId validation util"
```

---

## Task 2: Bulk delete calls endpoint

**Files:**
- Modify: `backend/src/services/callService.ts`
- Modify: `backend/src/controllers/callController.ts`
- Modify: `backend/src/routes/callRoute.ts`
- Test: `backend/src/services/callService.test.ts`
- Test: `backend/src/controllers/callController.test.ts`

**Interfaces:**
- Consumes: `validateObjectIdList` from `../utils/objectId` (Task 1)
- Produces:
  - `callService.deleteCallsByIds(userId: string, ids: string[]): Promise<number>`
  - `bulkDeleteCalls(req: AuthRequest, res: Response): Promise<void>` exported from `callController`
  - Route `POST /api/calls/bulk-delete`

- [ ] **Step 1: Write the failing service test**

Create `backend/src/services/callService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Call', () => ({
    default: {
        deleteMany: vi.fn(),
    },
}));

import Call from '../models/Call';
import { deleteCallsByIds } from './callService';

const USER_ID = '507f1f77bcf86cd799439011';
const CALL_A = '507f191e810c19729de860ea';
const CALL_B = '507f191e810c19729de860eb';

describe('deleteCallsByIds', () => {
    beforeEach(() => {
        vi.mocked(Call.deleteMany).mockReset();
    });

    it('scopes the delete to the calling user', async () => {
        vi.mocked(Call.deleteMany).mockResolvedValue({ deletedCount: 2 } as any);

        await deleteCallsByIds(USER_ID, [CALL_A, CALL_B]);

        // The userId clause is what stops one user deleting another user's
        // calls by guessing ObjectIds. Assert on the exact filter.
        expect(Call.deleteMany).toHaveBeenCalledWith({
            _id: { $in: [CALL_A, CALL_B] },
            userId: USER_ID,
        });
    });

    it('returns the number of documents actually deleted', async () => {
        vi.mocked(Call.deleteMany).mockResolvedValue({ deletedCount: 2 } as any);

        const deleted = await deleteCallsByIds(USER_ID, [CALL_A, CALL_B]);

        expect(deleted).toBe(2);
    });

    it('returns 0 when the driver omits deletedCount', async () => {
        vi.mocked(Call.deleteMany).mockResolvedValue({} as any);

        const deleted = await deleteCallsByIds(USER_ID, [CALL_A]);

        expect(deleted).toBe(0);
    });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && npx vitest run src/services/callService.test.ts`
Expected: FAIL — `deleteCallsByIds is not a function`.

- [ ] **Step 3: Implement the service function**

Append to `backend/src/services/callService.ts`:

```ts
export const deleteCallsByIds = async (userId: string, ids: string[]): Promise<number> => {
    const result = await Call.deleteMany({ _id: { $in: ids }, userId });
    return result.deletedCount ?? 0;
};
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && npx vitest run src/services/callService.test.ts`
Expected: PASS — 3 tests.

- [ ] **Step 5: Write the failing controller test**

Create `backend/src/controllers/callController.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/callService', () => ({
    deleteCallsByIds: vi.fn(),
    saveRawCall: vi.fn(),
    updateCallWithAnalysis: vi.fn(),
    markAnalysisFailed: vi.fn(),
}));

import * as callService from '../services/callService';
import { bulkDeleteCalls } from './callController';

const USER_ID = '507f1f77bcf86cd799439011';
const CALL_A = '507f191e810c19729de860ea';
const CALL_B = '507f191e810c19729de860eb';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    return res;
};

const makeReq = (body: any, userId: string | undefined = USER_ID) =>
    ({ body, user: userId ? { id: userId } : undefined }) as any;

describe('bulkDeleteCalls', () => {
    beforeEach(() => {
        vi.mocked(callService.deleteCallsByIds).mockReset();
    });

    it('deletes the listed calls and returns the count', async () => {
        vi.mocked(callService.deleteCallsByIds).mockResolvedValue(2);
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A, CALL_B] }), res);

        expect(callService.deleteCallsByIds).toHaveBeenCalledWith(USER_ID, [CALL_A, CALL_B]);
        expect(res.status).toHaveBeenCalledWith(200);
        expect(res.json).toHaveBeenCalledWith({ deletedCount: 2 });
    });

    it('returns 400 when ids is missing', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({}), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 400 when ids is empty', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [] }), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 400 for a malformed id instead of throwing a CastError', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A, 'not-an-id'] }), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 400 for more than 200 ids', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: Array.from({ length: 201 }, () => CALL_A) }), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 401 when there is no authenticated user', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A] }, undefined), res);

        expect(res.status).toHaveBeenCalledWith(401);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 500 when the service throws', async () => {
        vi.mocked(callService.deleteCallsByIds).mockRejectedValue(new Error('db down'));
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A] }), res);

        expect(res.status).toHaveBeenCalledWith(500);
    });
});
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd backend && npx vitest run src/controllers/callController.test.ts`
Expected: FAIL — `bulkDeleteCalls is not a function`.

- [ ] **Step 7: Implement the controller**

Add this import near the top of `backend/src/controllers/callController.ts`, below the existing imports:

```ts
import { validateObjectIdList } from "../utils/objectId";
```

Then append this export to the same file:

```ts
export const bulkDeleteCalls = async (req: AuthRequest, res: Response) => {
  try {
    const userId = req.user?.id;
    if (!userId) {
      return res.status(401).json({ message: 'Unauthenticated' });
    }

    const validation = validateObjectIdList(req.body?.ids);
    if (!validation.ok) {
      return res.status(400).json({ message: validation.message });
    }

    const deletedCount = await callService.deleteCallsByIds(userId, validation.ids);
    res.status(200).json({ deletedCount });
  } catch (error) {
    console.error('Bulk delete calls error:', error);
    res.status(500).json({ message: 'Internal server error' });
  }
};
```

- [ ] **Step 8: Register the route**

In `backend/src/routes/callRoute.ts`, change the import line and add the route:

```ts
import { handleIncomingAndroidCall, getCalls, bulkDeleteCalls } from '../controllers/callController';
```

```ts
router.post('/calls/bulk-delete', protect, bulkDeleteCalls);
```

Place the new route after the existing `router.get('/calls', ...)` line. Express 5 matches these as distinct literal paths, so ordering relative to `/calls` does not matter.

- [ ] **Step 9: Run the full backend suite**

Run: `cd backend && npm test`
Expected: PASS — 23 tests total.

- [ ] **Step 10: Verify it compiles**

Run: `cd backend && npm run build`
Expected: exits 0.

- [ ] **Step 11: Commit**

```bash
git add backend/src/services/callService.ts backend/src/services/callService.test.ts backend/src/controllers/callController.ts backend/src/controllers/callController.test.ts backend/src/routes/callRoute.ts
git commit -m "feat: add bulk delete endpoint for calls"
```

---

## Task 3: Cascading contact delete endpoint

**Files:**
- Create: `backend/src/services/contactService.ts`
- Modify: `backend/src/controllers/contactController.ts`
- Modify: `backend/src/routes/contactRoute.ts`
- Test: `backend/src/services/contactService.test.ts`
- Test: `backend/src/controllers/contactController.test.ts`

**Interfaces:**
- Consumes: `isObjectId` from `../utils/objectId` (Task 1)
- Produces:
  - `type CascadeResult = { deletedCalls: number; deletedTasks: number }`
  - `contactService.deleteContactCascade(userId: string, contactId: string): Promise<CascadeResult | null>` — resolves `null` when no contact matches that user
  - `deleteContact(req: AuthRequest, res: Response): Promise<void>` exported from `contactController`
  - Route `DELETE /api/contacts/:id`

- [ ] **Step 1: Write the failing service test**

Create `backend/src/services/contactService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Contact', () => ({
    default: { findOne: vi.fn(), deleteOne: vi.fn() },
}));
vi.mock('../models/Call', () => ({
    default: { deleteMany: vi.fn() },
}));
vi.mock('../models/Task', () => ({
    default: { deleteMany: vi.fn() },
}));

import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';
import { deleteContactCascade } from './contactService';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';

describe('deleteContactCascade', () => {
    beforeEach(() => {
        vi.mocked(Contact.findOne).mockReset();
        vi.mocked(Contact.deleteOne).mockReset();
        vi.mocked(Call.deleteMany).mockReset();
        vi.mocked(Task.deleteMany).mockReset();

        vi.mocked(Contact.findOne).mockResolvedValue({ _id: CONTACT_ID } as any);
        vi.mocked(Contact.deleteOne).mockResolvedValue({ deletedCount: 1 } as any);
        vi.mocked(Call.deleteMany).mockResolvedValue({ deletedCount: 12 } as any);
        vi.mocked(Task.deleteMany).mockResolvedValue({ deletedCount: 4 } as any);
    });

    it('returns null and deletes nothing when the contact does not belong to the user', async () => {
        vi.mocked(Contact.findOne).mockResolvedValue(null as any);

        const result = await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(result).toBeNull();
        expect(Task.deleteMany).not.toHaveBeenCalled();
        expect(Call.deleteMany).not.toHaveBeenCalled();
        expect(Contact.deleteOne).not.toHaveBeenCalled();
    });

    it('scopes the ownership lookup to the user', async () => {
        await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(Contact.findOne).toHaveBeenCalledWith({ _id: CONTACT_ID, userId: USER_ID });
    });

    it('deletes the contact tasks, calls, and the contact itself, all scoped by user', async () => {
        await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(Task.deleteMany).toHaveBeenCalledWith({ contactId: CONTACT_ID, userId: USER_ID });
        expect(Call.deleteMany).toHaveBeenCalledWith({ contactId: CONTACT_ID, userId: USER_ID });
        expect(Contact.deleteOne).toHaveBeenCalledWith({ _id: CONTACT_ID, userId: USER_ID });
    });

    it('deletes the contact last so a mid-sequence failure never orphans calls', async () => {
        const order: string[] = [];
        vi.mocked(Task.deleteMany).mockImplementation((async () => {
            order.push('tasks');
            return { deletedCount: 4 };
        }) as any);
        vi.mocked(Call.deleteMany).mockImplementation((async () => {
            order.push('calls');
            return { deletedCount: 12 };
        }) as any);
        vi.mocked(Contact.deleteOne).mockImplementation((async () => {
            order.push('contact');
            return { deletedCount: 1 };
        }) as any);

        await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(order).toEqual(['tasks', 'calls', 'contact']);
    });

    it('reports how many calls and tasks were removed', async () => {
        const result = await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(result).toEqual({ deletedCalls: 12, deletedTasks: 4 });
    });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && npx vitest run src/services/contactService.test.ts`
Expected: FAIL — `Failed to resolve import "./contactService"`.

- [ ] **Step 3: Implement the service**

Create `backend/src/services/contactService.ts`:

```ts
import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';

export interface CascadeResult {
    deletedCalls: number;
    deletedTasks: number;
}

/**
 * Deletes a contact along with everything that points at it.
 *
 * This is deliberately not a transaction: MongoDB multi-document transactions
 * require a replica set, and DATABASE_URL may point at a standalone instance.
 * Instead the contact is deleted LAST, so a mid-sequence failure leaves the
 * contact in place and the user can safely retry — each earlier delete is
 * idempotent. Deleting the contact first would orphan its calls and tasks.
 *
 * Resolves null when no contact with that id belongs to the user.
 */
export const deleteContactCascade = async (
    userId: string,
    contactId: string,
): Promise<CascadeResult | null> => {
    const contact = await Contact.findOne({ _id: contactId, userId });
    if (!contact) {
        return null;
    }

    const taskResult = await Task.deleteMany({ contactId, userId });
    const callResult = await Call.deleteMany({ contactId, userId });
    await Contact.deleteOne({ _id: contactId, userId });

    return {
        deletedCalls: callResult.deletedCount ?? 0,
        deletedTasks: taskResult.deletedCount ?? 0,
    };
};
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && npx vitest run src/services/contactService.test.ts`
Expected: PASS — 5 tests.

- [ ] **Step 5: Write the failing controller test**

Create `backend/src/controllers/contactController.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/contactService', () => ({
    deleteContactCascade: vi.fn(),
}));
vi.mock('../models/Contact', () => ({
    default: { find: vi.fn(), findOne: vi.fn() },
}));

import * as contactService from '../services/contactService';
import { deleteContact } from './contactController';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    return res;
};

const makeReq = (id: string, userId: string | undefined = USER_ID) =>
    ({ params: { id }, user: userId ? { id: userId } : undefined }) as any;

describe('deleteContact', () => {
    beforeEach(() => {
        vi.mocked(contactService.deleteContactCascade).mockReset();
    });

    it('returns the cascade counts on success', async () => {
        vi.mocked(contactService.deleteContactCascade).mockResolvedValue({
            deletedCalls: 12,
            deletedTasks: 4,
        });
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID), res);

        expect(contactService.deleteContactCascade).toHaveBeenCalledWith(USER_ID, CONTACT_ID);
        expect(res.status).toHaveBeenCalledWith(200);
        expect(res.json).toHaveBeenCalledWith({ deletedCalls: 12, deletedTasks: 4 });
    });

    it('returns 404 when the contact belongs to another user', async () => {
        vi.mocked(contactService.deleteContactCascade).mockResolvedValue(null);
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID), res);

        expect(res.status).toHaveBeenCalledWith(404);
    });

    it('returns 400 for a malformed contact id without touching the database', async () => {
        const res = makeRes();

        await deleteContact(makeReq('not-an-id'), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(contactService.deleteContactCascade).not.toHaveBeenCalled();
    });

    it('returns 401 when there is no authenticated user', async () => {
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID, undefined), res);

        expect(res.status).toHaveBeenCalledWith(401);
        expect(contactService.deleteContactCascade).not.toHaveBeenCalled();
    });

    it('returns 500 when the service throws', async () => {
        vi.mocked(contactService.deleteContactCascade).mockRejectedValue(new Error('db down'));
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID), res);

        expect(res.status).toHaveBeenCalledWith(500);
    });
});
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd backend && npx vitest run src/controllers/contactController.test.ts`
Expected: FAIL — `deleteContact is not a function`.

- [ ] **Step 7: Implement the controller**

Add these imports below the existing imports in `backend/src/controllers/contactController.ts`:

```ts
import * as contactService from '../services/contactService';
import { isObjectId } from '../utils/objectId';
```

Then append this export to the same file:

```ts
export const deleteContact = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }

        const contactId = req.params.id;
        if (!isObjectId(contactId)) {
            return res.status(400).json({ message: 'invalid contact id' });
        }

        const result = await contactService.deleteContactCascade(userId, contactId);
        if (!result) {
            return res.status(404).json({ message: 'Contact not found' });
        }

        res.status(200).json(result);
    } catch (error) {
        console.error('Delete contact error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
```

- [ ] **Step 8: Register the route**

In `backend/src/routes/contactRoute.ts`, change the import line and add the route:

```ts
import { getContacts, getContactById, deleteContact } from '../controllers/contactController';
```

```ts
router.delete('/contacts/:id', protect, deleteContact);
```

- [ ] **Step 9: Run the full backend suite**

Run: `cd backend && npm test`
Expected: PASS — 33 tests total.

- [ ] **Step 10: Verify it compiles**

Run: `cd backend && npm run build`
Expected: exits 0.

- [ ] **Step 11: Commit**

```bash
git add backend/src/services/contactService.ts backend/src/services/contactService.test.ts backend/src/controllers/contactController.ts backend/src/controllers/contactController.test.ts backend/src/routes/contactRoute.ts
git commit -m "feat: add cascading contact delete endpoint"
```

---

## Task 4: Frontend test infrastructure and the useMultiSelect hook

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`
- Create: `frontend/src/hooks/useMultiSelect.ts`
- Test: `frontend/src/hooks/useMultiSelect.test.ts`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `LONG_PRESS_MS: number` (500), `MOVE_CANCEL_PX: number` (10)
  - `interface PressLikeEvent { clientX: number; clientY: number }`
  - `interface ItemPointerProps` with `onPointerDown`, `onPointerMove`, `onPointerUp`, `onPointerCancel`, `onClick`
  - `interface MultiSelect` with `isSelecting`, `selectedIds`, `count`, `isSelected`, `toggle`, `clear`, `getItemProps`
  - `useMultiSelect(): MultiSelect`

**Design note — selection mode is derived, not stored.** `isSelecting` is `selectedIds.size > 0`. This gives the WhatsApp auto-exit behavior (unticking the last item leaves selection mode) for free, with no second piece of state to keep in sync.

- [ ] **Step 1: Install the frontend test dependencies**

```bash
cd frontend
npm install --save-dev vitest@^3.2.4 jsdom@^26.1.0 @testing-library/react@^16.3.0 @testing-library/dom@^10.4.0
```

- [ ] **Step 2: Add the test scripts to `frontend/package.json`**

In the `"scripts"` block, add alongside the existing entries:

```json
    "test": "vitest run",
    "test:watch": "vitest"
```

- [ ] **Step 3: Add the Vitest config to `frontend/vite.config.ts`**

Change the import on line 1 from `'vite'` to `'vitest/config'` — it re-exports `defineConfig` with the `test` key typed, so no `/// <reference>` comment is needed:

```ts
import { defineConfig } from 'vitest/config'
```

Then add a `test` block as a sibling of `resolve`, after the `resolve` object:

```ts
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
```

- [ ] **Step 4: Write the failing test**

Create `frontend/src/hooks/useMultiSelect.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useMultiSelect, LONG_PRESS_MS, MOVE_CANCEL_PX } from './useMultiSelect';

// The handlers are invoked directly rather than through fireEvent because
// jsdom has no PointerEvent constructor, so synthesized pointer events lose
// their clientX/clientY and the move-cancel test would silently pass.
const press = (at = { clientX: 100, clientY: 100 }) => at;

describe('useMultiSelect', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('starts out not selecting', () => {
        const { result } = renderHook(() => useMultiSelect());

        expect(result.current.isSelecting).toBe(false);
        expect(result.current.count).toBe(0);
    });

    it('enters selection mode with the pressed item after a long press', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(true);
        expect(result.current.isSelected('call-a')).toBe(true);
        expect(result.current.count).toBe(1);
    });

    it('does not enter selection mode when released before the threshold', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS - 50);
            result.current.getItemProps('call-a').onPointerUp();
            vi.advanceTimersByTime(500);
        });

        expect(result.current.isSelecting).toBe(false);
    });

    it('cancels the pending long press when the pointer moves past the threshold', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press({ clientX: 100, clientY: 100 }));
            result.current.getItemProps('call-a').onPointerMove({
                clientX: 100,
                clientY: 100 + MOVE_CANCEL_PX + 5,
            });
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(false);
    });

    it('tolerates small movement within the threshold', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press({ clientX: 100, clientY: 100 }));
            result.current.getItemProps('call-a').onPointerMove({ clientX: 102, clientY: 103 });
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(true);
    });

    it('cancels the pending long press on pointer cancel', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            result.current.getItemProps('call-a').onPointerCancel();
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });

        expect(result.current.isSelecting).toBe(false);
    });

    it('suppresses the click that follows a long press', () => {
        const onActivate = vi.fn();
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a', onActivate).onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.getItemProps('call-a', onActivate).onPointerUp();
            result.current.getItemProps('call-a', onActivate).onClick();
        });

        // The long press already selected it; the trailing click must not
        // immediately deselect it.
        expect(onActivate).not.toHaveBeenCalled();
        expect(result.current.isSelected('call-a')).toBe(true);
    });

    it('runs the activate callback on a plain tap outside selection mode', () => {
        const onActivate = vi.fn();
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a', onActivate).onClick();
        });

        expect(onActivate).toHaveBeenCalledTimes(1);
    });

    it('toggles instead of activating while in selection mode', () => {
        const onActivate = vi.fn();
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.getItemProps('call-a').onPointerUp();
            result.current.getItemProps('call-a').onClick();
        });
        act(() => {
            result.current.getItemProps('call-b', onActivate).onClick();
        });

        expect(onActivate).not.toHaveBeenCalled();
        expect(result.current.isSelected('call-b')).toBe(true);
        expect(result.current.count).toBe(2);
    });

    it('exits selection mode when the last item is deselected', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.toggle('call-a');
        });

        expect(result.current.isSelecting).toBe(false);
        expect(result.current.count).toBe(0);
    });

    it('clears every selection', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.getItemProps('call-a').onPointerDown(press());
            vi.advanceTimersByTime(LONG_PRESS_MS);
        });
        act(() => {
            result.current.toggle('call-b');
        });
        act(() => {
            result.current.clear();
        });

        expect(result.current.isSelecting).toBe(false);
        expect(result.current.selectedIds).toEqual([]);
    });

    it('exposes the selected ids', () => {
        const { result } = renderHook(() => useMultiSelect());

        act(() => {
            result.current.toggle('call-a');
        });
        act(() => {
            result.current.toggle('call-b');
        });

        expect(result.current.selectedIds.sort()).toEqual(['call-a', 'call-b']);
    });
});
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/hooks/useMultiSelect.test.ts`
Expected: FAIL — `Failed to resolve import "./useMultiSelect"`.

- [ ] **Step 6: Write the implementation**

Create `frontend/src/hooks/useMultiSelect.ts`:

```ts
import { useCallback, useRef, useState } from 'react';

export const LONG_PRESS_MS = 500;
export const MOVE_CANCEL_PX = 10;

/** The subset of a PointerEvent this hook reads. */
export interface PressLikeEvent {
    clientX: number;
    clientY: number;
}

export interface ItemPointerProps {
    onPointerDown: (event: PressLikeEvent) => void;
    onPointerMove: (event: PressLikeEvent) => void;
    onPointerUp: () => void;
    onPointerCancel: () => void;
    onClick: () => void;
}

export interface MultiSelect {
    isSelecting: boolean;
    selectedIds: string[];
    count: number;
    isSelected: (id: string) => boolean;
    toggle: (id: string) => void;
    clear: () => void;
    getItemProps: (id: string, onActivate?: () => void) => ItemPointerProps;
}

export const useMultiSelect = (): MultiSelect => {
    const [selected, setSelected] = useState<Set<string>>(() => new Set());

    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const startRef = useRef<PressLikeEvent | null>(null);
    const suppressClickRef = useRef(false);

    const cancelPendingPress = useCallback(() => {
        if (timerRef.current !== null) {
            clearTimeout(timerRef.current);
            timerRef.current = null;
        }
        startRef.current = null;
    }, []);

    const toggle = useCallback((id: string) => {
        setSelected(prev => {
            const next = new Set(prev);
            if (next.has(id)) {
                next.delete(id);
            } else {
                next.add(id);
            }
            return next;
        });
    }, []);

    const clear = useCallback(() => setSelected(new Set()), []);

    // Deliberately not memoized: onClick has to read the current `selected`
    // set, and a stale memoized closure would activate rows instead of
    // toggling them.
    const getItemProps = (id: string, onActivate?: () => void): ItemPointerProps => ({
        onPointerDown: (event: PressLikeEvent) => {
            cancelPendingPress();
            startRef.current = { clientX: event.clientX, clientY: event.clientY };
            timerRef.current = setTimeout(() => {
                timerRef.current = null;
                startRef.current = null;
                // A click always follows the pointerup that ends a long press.
                // Without this flag it would immediately undo the selection.
                suppressClickRef.current = true;
                setSelected(prev => {
                    const next = new Set(prev);
                    next.add(id);
                    return next;
                });
            }, LONG_PRESS_MS);
        },

        onPointerMove: (event: PressLikeEvent) => {
            const start = startRef.current;
            if (!start || timerRef.current === null) return;
            const dx = event.clientX - start.clientX;
            const dy = event.clientY - start.clientY;
            if (Math.sqrt(dx * dx + dy * dy) > MOVE_CANCEL_PX) {
                cancelPendingPress();
            }
        },

        onPointerUp: cancelPendingPress,
        onPointerCancel: cancelPendingPress,

        onClick: () => {
            if (suppressClickRef.current) {
                suppressClickRef.current = false;
                return;
            }
            if (selected.size > 0) {
                toggle(id);
                return;
            }
            onActivate?.();
        },
    });

    return {
        isSelecting: selected.size > 0,
        selectedIds: Array.from(selected),
        count: selected.size,
        isSelected: (id: string) => selected.has(id),
        toggle,
        clear,
        getItemProps,
    };
};
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd frontend && npm test`
Expected: PASS — 12 tests.

- [ ] **Step 8: Verify the production build still works**

Run: `cd frontend && npm run build`
Expected: exits 0. `tsc` type-checks `src/`, including the new test file, so this catches type errors the test run alone would miss.

- [ ] **Step 9: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts frontend/src/hooks/useMultiSelect.ts frontend/src/hooks/useMultiSelect.test.ts
git commit -m "feat: add useMultiSelect long-press selection hook"
```

---

## Task 5: Back button exits selection mode

**Files:**
- Create: `frontend/src/hooks/useSelectionBackButton.ts`

**Interfaces:**
- Consumes: nothing
- Produces: `useSelectionBackButton(active: boolean, onExit: () => void): void`

**Why this is its own file.** It manipulates `window.history` underneath React Router, which is the riskiest part of the feature. Keeping it isolated means it can be reviewed, or reverted, without touching selection behavior.

**Known limitation, accepted:** if the user navigates away via the bottom nav *while* in selection mode, the pushed history entry is left behind, so one extra back press is needed to leave that page later. The alternative — consuming the entry during unmount — would cancel the user's navigation outright, which is far worse.

- [ ] **Step 1: Write the implementation**

There is no automated test for this task. jsdom's history implementation does not dispatch `popstate` for programmatic `history.back()`, so a test here would assert against a fake rather than the behavior. It is covered by the on-device checks in Task 10.

Create `frontend/src/hooks/useSelectionBackButton.ts`:

```ts
import { useEffect, useRef } from 'react';

/**
 * Makes the Android back button exit selection mode instead of leaving the
 * screen, by pushing a throwaway history entry while selecting and popping it
 * when selection ends.
 *
 * The pushed entry has the same URL as the current one, so React Router sees
 * no location change and does not navigate.
 */
export const useSelectionBackButton = (active: boolean, onExit: () => void) => {
    const unmountingRef = useRef(false);

    // Declared before the effect below so that on unmount React runs this
    // cleanup first, letting the other one tell "selection ended" apart from
    // "the page is going away".
    useEffect(() => () => {
        unmountingRef.current = true;
    }, []);

    useEffect(() => {
        if (!active) return;

        window.history.pushState({ brachaSelection: true }, '');

        const handlePop = () => onExit();
        window.addEventListener('popstate', handlePop);

        return () => {
            window.removeEventListener('popstate', handlePop);
            // On unmount the user is navigating somewhere; consuming our entry
            // here would cancel that navigation and trap them on this page.
            if (unmountingRef.current) return;
            if ((window.history.state as { brachaSelection?: boolean } | null)?.brachaSelection) {
                window.history.back();
            }
        };
        // onExit must be a stable reference (the `clear` from useMultiSelect is
        // wrapped in useCallback); an unstable one would push a duplicate entry
        // on every render.
    }, [active, onExit]);
};
```

- [ ] **Step 2: Verify it compiles**

Run: `cd frontend && npm run build`
Expected: exits 0.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/hooks/useSelectionBackButton.ts
git commit -m "feat: exit selection mode on back button"
```

---

## Task 6: Shared delete UI and the call-deletion hook

**Files:**
- Create: `frontend/src/components/SelectionBar.tsx`
- Create: `frontend/src/components/SelectionBar.module.css`
- Create: `frontend/src/components/ConfirmDialog.tsx`
- Create: `frontend/src/components/ConfirmDialog.module.css`
- Create: `frontend/src/hooks/useCallDeletion.ts`
- Test: `frontend/src/hooks/useCallDeletion.test.ts`

**Interfaces:**
- Consumes: `POST /api/calls/bulk-delete` (Task 2)
- Produces:
  - `SelectionBar` with props `{ count: number; onCancel: () => void; onDelete: () => void }`
  - `ConfirmDialog` with props `{ title: string; message: string; confirmLabel?: string; busy?: boolean; onCancel: () => void; onConfirm: () => void }` (`confirmLabel` defaults to `'Delete'`, `busy` defaults to `false`)
  - `interface CallDeletionOptions { onDeleted: (deletedIds: Set<string>) => void; onRefetch: () => Promise<void> }`
  - `interface CallDeletion { isDeleting: boolean; error: string | null; deleteCalls: (ids: string[]) => Promise<boolean> }`
  - `useCallDeletion(options: CallDeletionOptions): CallDeletion`

`SelectionBar` and `ConfirmDialog` are default exports, matching `BottomNav`. `useCallDeletion` is a named export, matching `useMultiSelect`.

**Why the hook exists.** Both the Home page and the Contact Details page need the same delete request, the same count-mismatch check, and the same error-and-refetch recovery. Only the list-update and refetch differ, so those are injected as callbacks and everything else lives here once.

- [ ] **Step 1: Create the SelectionBar styles**

Create `frontend/src/components/SelectionBar.module.css`:

```css
.bar {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    z-index: 900;
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 16px 20px;
    background-color: #2563eb;
    color: #ffffff;
    box-shadow: 0 4px 12px rgba(37, 99, 235, 0.25);
}

.iconBtn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    padding: 0;
    border: none;
    border-radius: 12px;
    background-color: transparent;
    color: #ffffff;
    cursor: pointer;
    transition: background-color 0.2s ease;
}

.iconBtn:hover {
    background-color: rgba(255, 255, 255, 0.15);
}

.count {
    flex: 1;
    font-size: 18px;
    font-weight: 700;
}
```

- [ ] **Step 2: Create the SelectionBar component**

Create `frontend/src/components/SelectionBar.tsx`:

```tsx
import styles from './SelectionBar.module.css';

interface SelectionBarProps {
    count: number;
    onCancel: () => void;
    onDelete: () => void;
}

const SelectionBar: React.FC<SelectionBarProps> = ({ count, onCancel, onDelete }) => (
    <div className={styles.bar}>
        <button type="button" className={styles.iconBtn} onClick={onCancel} aria-label="Cancel selection">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
        </button>

        <span className={styles.count}>{count} selected</span>

        <button type="button" className={styles.iconBtn} onClick={onDelete} aria-label="Delete selected">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
            </svg>
        </button>
    </div>
);

export default SelectionBar;
```

- [ ] **Step 3: Create the ConfirmDialog styles**

These mirror the modal styles already in `ContactDetailsPage.module.css` so the dialog looks native to the app, with a red confirm button for destructive intent.

Create `frontend/src/components/ConfirmDialog.module.css`:

```css
.overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-color: rgba(15, 23, 42, 0.3);
    backdrop-filter: blur(8px);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
    padding: 20px;
}

.content {
    background-color: #ffffff;
    border-radius: 32px;
    padding: 32px;
    width: 100%;
    max-width: 400px;
    box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
    border: 1px solid #f1f5f9;
}

.title {
    font-size: 24px;
    font-weight: 700;
    color: #0f172a;
    margin: 0 0 12px 0;
}

.message {
    font-size: 16px;
    line-height: 1.5;
    color: #475569;
    margin: 0 0 24px 0;
}

.actions {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
}

.cancelBtn {
    padding: 12px 20px;
    background-color: #f1f5f9;
    color: #475569;
    border: none;
    border-radius: 12px;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s ease;
}

.cancelBtn:hover {
    background-color: #e2e8f0;
}

.confirmBtn {
    padding: 12px 20px;
    background-color: #dc2626;
    color: #ffffff;
    border: none;
    border-radius: 12px;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s ease;
}

.confirmBtn:hover {
    background-color: #b91c1c;
}

.confirmBtn:disabled,
.cancelBtn:disabled {
    opacity: 0.6;
    cursor: default;
}
```

- [ ] **Step 4: Create the ConfirmDialog component**

Create `frontend/src/components/ConfirmDialog.tsx`:

```tsx
import styles from './ConfirmDialog.module.css';

interface ConfirmDialogProps {
    title: string;
    message: string;
    confirmLabel?: string;
    busy?: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}

const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
    title,
    message,
    confirmLabel = 'Delete',
    busy = false,
    onCancel,
    onConfirm,
}) => (
    <div className={styles.overlay}>
        <div className={styles.content} role="alertdialog" aria-modal="true">
            <h2 className={styles.title}>{title}</h2>
            <p className={styles.message}>{message}</p>
            <div className={styles.actions}>
                <button type="button" className={styles.cancelBtn} onClick={onCancel} disabled={busy}>
                    Cancel
                </button>
                <button type="button" className={styles.confirmBtn} onClick={onConfirm} disabled={busy}>
                    {busy ? 'Deleting…' : confirmLabel}
                </button>
            </div>
        </div>
    </div>
);

export default ConfirmDialog;
```

- [ ] **Step 5: Write the failing hook test**

Create `frontend/src/hooks/useCallDeletion.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

vi.mock('@/services/apiClient', () => ({
    default: { post: vi.fn(), get: vi.fn() },
}));

import apiClient from '@/services/apiClient';
import { useCallDeletion } from './useCallDeletion';

const IDS = ['call-a', 'call-b'];

const setup = () => {
    const onDeleted = vi.fn();
    const onRefetch = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useCallDeletion({ onDeleted, onRefetch }));
    return { result, onDeleted, onRefetch };
};

describe('useCallDeletion', () => {
    beforeEach(() => {
        vi.mocked(apiClient.post).mockReset();
    });

    it('posts the ids to the bulk-delete endpoint', async () => {
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        const { result } = setup();

        await act(async () => {
            await result.current.deleteCalls(IDS);
        });

        expect(apiClient.post).toHaveBeenCalledWith('/calls/bulk-delete', { ids: IDS });
    });

    it('reports success and hands back the deleted ids', async () => {
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        const { result, onDeleted, onRefetch } = setup();

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(true);
        expect(onDeleted).toHaveBeenCalledWith(new Set(IDS));
        expect(onRefetch).not.toHaveBeenCalled();
        expect(result.current.error).toBeNull();
    });

    it('treats a short deletedCount as a failure and resyncs', async () => {
        // A short count means some ids were not ours to delete. Reporting
        // success here would leave the UI claiming rows are gone that aren't.
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 1 } } as any);
        const { result, onDeleted, onRefetch } = setup();

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(false);
        expect(onDeleted).not.toHaveBeenCalled();
        expect(onRefetch).toHaveBeenCalledTimes(1);
        expect(result.current.error).not.toBeNull();
    });

    it('reports failure and resyncs when the request throws', async () => {
        vi.mocked(apiClient.post).mockRejectedValue(new Error('network down'));
        const { result, onDeleted, onRefetch } = setup();

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(false);
        expect(onDeleted).not.toHaveBeenCalled();
        expect(onRefetch).toHaveBeenCalledTimes(1);
        expect(result.current.error).not.toBeNull();
    });

    it('survives a refetch that also fails', async () => {
        vi.mocked(apiClient.post).mockRejectedValue(new Error('network down'));
        const onDeleted = vi.fn();
        const onRefetch = vi.fn().mockRejectedValue(new Error('still down'));
        const { result } = renderHook(() => useCallDeletion({ onDeleted, onRefetch }));

        let outcome: boolean | undefined;
        await act(async () => {
            outcome = await result.current.deleteCalls(IDS);
        });

        expect(outcome).toBe(false);
        expect(result.current.error).not.toBeNull();
    });

    it('clears a previous error when a later delete succeeds', async () => {
        const { result } = setup();

        vi.mocked(apiClient.post).mockRejectedValue(new Error('network down'));
        await act(async () => {
            await result.current.deleteCalls(IDS);
        });
        expect(result.current.error).not.toBeNull();

        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        await act(async () => {
            await result.current.deleteCalls(IDS);
        });

        expect(result.current.error).toBeNull();
    });

    it('is not deleting once the request settles', async () => {
        vi.mocked(apiClient.post).mockResolvedValue({ data: { deletedCount: 2 } } as any);
        const { result } = setup();

        await act(async () => {
            await result.current.deleteCalls(IDS);
        });

        expect(result.current.isDeleting).toBe(false);
    });
});
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/hooks/useCallDeletion.test.ts`
Expected: FAIL — `Failed to resolve import "./useCallDeletion"`.

- [ ] **Step 7: Write the hook**

Create `frontend/src/hooks/useCallDeletion.ts`:

```ts
import { useState } from 'react';
import apiClient from '@/services/apiClient';

export interface CallDeletionOptions {
    /** Called on success with the ids that were removed, so the page can drop them from its list. */
    onDeleted: (deletedIds: Set<string>) => void;
    /** Called on failure to resync the page's list with what the server actually has. */
    onRefetch: () => Promise<void>;
}

export interface CallDeletion {
    isDeleting: boolean;
    error: string | null;
    deleteCalls: (ids: string[]) => Promise<boolean>;
}

export const useCallDeletion = ({ onDeleted, onRefetch }: CallDeletionOptions): CallDeletion => {
    const [isDeleting, setIsDeleting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const deleteCalls = async (ids: string[]): Promise<boolean> => {
        setIsDeleting(true);
        setError(null);

        try {
            const response = await apiClient.post('/calls/bulk-delete', { ids });

            if (response.data?.deletedCount !== ids.length) {
                // A short count means some ids were not ours to delete. Don't
                // pretend it worked — fall through to the resync below.
                throw new Error('count mismatch');
            }

            onDeleted(new Set(ids));
            return true;
        } catch (err) {
            console.error('Error deleting calls:', err);
            setError('Could not delete those calls. Please try again.');

            try {
                await onRefetch();
            } catch (refetchError) {
                console.error('Error refreshing calls:', refetchError);
            }

            return false;
        } finally {
            setIsDeleting(false);
        }
    };

    return { isDeleting, error, deleteCalls };
};
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd frontend && npm test`
Expected: PASS — 19 tests (12 from `useMultiSelect`, 7 here).

- [ ] **Step 9: Verify it compiles**

Run: `cd frontend && npm run build`
Expected: exits 0.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/components/SelectionBar.tsx frontend/src/components/SelectionBar.module.css frontend/src/components/ConfirmDialog.tsx frontend/src/components/ConfirmDialog.module.css frontend/src/hooks/useCallDeletion.ts frontend/src/hooks/useCallDeletion.test.ts
git commit -m "feat: add SelectionBar, ConfirmDialog, and useCallDeletion hook"
```

---

## Task 7: Multi-select call delete on Contact Details

**Files:**
- Modify: `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx`
- Modify: `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.module.css`

**Interfaces:**
- Consumes: `useMultiSelect` (Task 4), `useSelectionBackButton` (Task 5), `SelectionBar`, `ConfirmDialog`, and `useCallDeletion` (Task 6)
- Produces: nothing consumed by later tasks

- [ ] **Step 1: Add the card styles**

Append to `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.module.css`:

```css
/* Long-press multi-select.
   Without the callout/user-select suppression, a long press inside the Android
   WebView raises the native "Copy / Select all" popup and the text magnifier
   instead of entering selection mode. This works in desktop Chrome without
   these properties and fails on device. */
.selectableCard {
    -webkit-touch-callout: none;
    -webkit-user-select: none;
    user-select: none;
    cursor: pointer;
}

.selectedCard {
    border-color: #2563eb;
    background-color: #eff6ff;
}

.selectionSpacer {
    height: 72px;
}
```

- [ ] **Step 2: Add the imports**

At the top of `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx`, add below the existing imports:

```tsx
import SelectionBar from '@/components/SelectionBar';
import ConfirmDialog from '@/components/ConfirmDialog';
import { useMultiSelect } from '@/hooks/useMultiSelect';
import { useSelectionBackButton } from '@/hooks/useSelectionBackButton';
import { useCallDeletion } from '@/hooks/useCallDeletion';
```

- [ ] **Step 3: Add the selection state**

Inside the `ContactDetailsPage` component, below the existing `useState` declarations and above the `useEffect`, add:

```tsx
    const callSelection = useMultiSelect();
    const [isDeleteCallsOpen, setIsDeleteCallsOpen] = useState(false);

    useSelectionBackButton(callSelection.isSelecting, callSelection.clear);

    const callDeletion = useCallDeletion({
        onDeleted: (deletedIds) => {
            setCalls(prev => prev.filter(call => !deletedIds.has(call._id)));
        },
        onRefetch: async () => {
            const callsRes = await apiClient.get('/calls');
            setCalls(callsRes.data.filter((c: any) => c.contactId?._id === id));
        },
    });
```

- [ ] **Step 4: Add the delete handler**

Add this function inside the component, next to `toggleCallTranscript`. The request, the count-mismatch check, and the error recovery all live in `useCallDeletion`; this only closes the UI around it, which happens either way.

```tsx
    const handleDeleteSelectedCalls = async () => {
        await callDeletion.deleteCalls(callSelection.selectedIds);
        callSelection.clear();
        setIsDeleteCallsOpen(false);
    };
```

- [ ] **Step 5: Render the selection bar**

Inside the returned JSX, immediately after the opening `<div className={styles.pageWrapper}>` tag, add:

```tsx
            {callSelection.isSelecting && (
                <>
                    <SelectionBar
                        count={callSelection.count}
                        onCancel={callSelection.clear}
                        onDelete={() => setIsDeleteCallsOpen(true)}
                    />
                    <div className={styles.selectionSpacer} />
                </>
            )}

            {callDeletion.error && <p className={styles.statusMessage}>{callDeletion.error}</p>}
```

The spacer keeps the fixed bar from covering the header.

- [ ] **Step 6: Wire the call cards**

In the calls tab, replace the opening tag of the call card:

```tsx
                                    <div key={call._id} className={styles.callCard}>
```

with:

```tsx
                                    <div
                                        key={call._id}
                                        className={`${styles.callCard} ${styles.selectableCard} ${callSelection.isSelected(call._id) ? styles.selectedCard : ''}`}
                                        {...callSelection.getItemProps(call._id)}
                                    >
```

`getItemProps` is called without an `onActivate` callback because a plain tap on a call card does nothing today — expanding the transcript is handled by its own button.

- [ ] **Step 7: Stop the transcript button firing while selecting**

Replace the `onClick` on the view-transcript button:

```tsx
                                            onClick={() => toggleCallTranscript(call._id)}
```

with:

```tsx
                                            onClick={(e) => {
                                                if (callSelection.isSelecting) return;
                                                e.stopPropagation();
                                                toggleCallTranscript(call._id);
                                            }}
```

Without the `stopPropagation`, tapping the button outside selection mode would also bubble to the card's `onClick`.

- [ ] **Step 8: Render the confirm dialog**

Before the closing `<BottomNav />` in the returned JSX, add:

```tsx
            {isDeleteCallsOpen && (
                <ConfirmDialog
                    title={`Delete ${callSelection.count} ${callSelection.count === 1 ? 'call' : 'calls'}?`}
                    message="This can't be undone."
                    busy={callDeletion.isDeleting}
                    onCancel={() => setIsDeleteCallsOpen(false)}
                    onConfirm={handleDeleteSelectedCalls}
                />
            )}
```

- [ ] **Step 9: Verify it builds**

Run: `cd frontend && npm run build`
Expected: exits 0.

- [ ] **Step 10: Verify by hand in the browser**

Start both servers (`cd backend && npm run dev`, and `cd frontend && npm run dev`), log in, and open a contact with at least two calls.

- Press and hold a call card for a second with the mouse — the blue selection bar appears reading "1 selected", and the card turns blue.
- Click a second card — the bar reads "2 selected".
- Click the first card again — back to "1 selected".
- Click it once more — the bar disappears.
- Select one call, click the trash icon, confirm — the card disappears; reload the page and it is still gone.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx frontend/src/pages/ContactDetailsPage/ContactDetailsPage.module.css
git commit -m "feat: multi-select call delete on contact details"
```

---

## Task 8: Delete contact button on Contact Details

**Files:**
- Modify: `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx`
- Modify: `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.module.css`

**Interfaces:**
- Consumes: `ConfirmDialog` (Task 6), `DELETE /api/contacts/:id` (Task 3). **Task 7 must be complete first** — this task edits the same page and assumes its imports and header markup are already in place.
- Produces: nothing consumed by later tasks

- [ ] **Step 1: Add the styles**

Append to `frontend/src/pages/ContactDetailsPage/ContactDetailsPage.module.css`:

```css
.headerTopRow {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.deleteContactBtn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    padding: 0;
    border: none;
    border-radius: 12px;
    background-color: #fef2f2;
    color: #dc2626;
    cursor: pointer;
    transition: background-color 0.2s ease;
}

.deleteContactBtn:hover {
    background-color: #fee2e2;
}
```

- [ ] **Step 2: Add the navigation import**

Change the `react-router-dom` import at the top of `ContactDetailsPage.tsx` from:

```tsx
import { useParams, Link } from 'react-router-dom';
```

to:

```tsx
import { useParams, Link, useNavigate } from 'react-router-dom';
```

- [ ] **Step 3: Add the state**

Inside the component, next to the selection state added in Task 7, add:

```tsx
    const navigate = useNavigate();
    const [isDeleteContactOpen, setIsDeleteContactOpen] = useState(false);
    const [isDeletingContact, setIsDeletingContact] = useState(false);
    const [contactError, setContactError] = useState<string | null>(null);
```

Then render the message next to the call-delete error added in Task 7 Step 5:

```tsx
            {contactError && <p className={styles.statusMessage}>{contactError}</p>}
```

- [ ] **Step 4: Add the message builder and handler**

Add these inside the component:

```tsx
    const contactDeleteMessage = () => {
        const parts: string[] = [];
        if (calls.length > 0) {
            parts.push(`${calls.length} ${calls.length === 1 ? 'call' : 'calls'}`);
        }
        if (tasks.length > 0) {
            parts.push(`${tasks.length} ${tasks.length === 1 ? 'task' : 'tasks'}`);
        }
        // Omit the clause entirely rather than saying "0 calls and 0 tasks".
        const damage = parts.length > 0 ? `This also deletes ${parts.join(' and ')}. ` : '';
        return `${damage}This can't be undone.`;
    };

    const handleDeleteContact = async () => {
        setIsDeletingContact(true);
        setContactError(null);

        try {
            await apiClient.delete(`/contacts/${id}`);
            navigate('/contacts');
        } catch (error) {
            console.error('Error deleting contact:', error);
            setContactError('Could not delete this contact. Please try again.');
            setIsDeleteContactOpen(false);
            setIsDeletingContact(false);
        }
    };
```

`setIsDeletingContact(false)` is deliberately only in the error branch — on success the component unmounts as it navigates away, and setting state after that logs a React warning.

- [ ] **Step 5: Add the button to the header**

In the returned JSX, replace the back link:

```tsx
                <Link to="/contacts" className={styles.backLink}>
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                        <line x1="19" y1="12" x2="5" y2="12"></line>
                        <polyline points="12 19 5 12 12 5"></polyline>
                    </svg>
                    <span>Back to Contacts</span>
                </Link>
```

with the same link wrapped in a row alongside the delete button:

```tsx
                <div className={styles.headerTopRow}>
                    <Link to="/contacts" className={styles.backLink}>
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                            <line x1="19" y1="12" x2="5" y2="12"></line>
                            <polyline points="12 19 5 12 12 5"></polyline>
                        </svg>
                        <span>Back to Contacts</span>
                    </Link>

                    <button
                        type="button"
                        className={styles.deleteContactBtn}
                        onClick={() => setIsDeleteContactOpen(true)}
                        aria-label="Delete contact"
                    >
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                            <polyline points="3 6 5 6 21 6" />
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                        </svg>
                    </button>
                </div>
```

- [ ] **Step 6: Render the dialog**

Next to the call-delete dialog added in Task 7, add:

```tsx
            {isDeleteContactOpen && (
                <ConfirmDialog
                    title={`Delete ${contact.name}?`}
                    message={contactDeleteMessage()}
                    busy={isDeletingContact}
                    onCancel={() => setIsDeleteContactOpen(false)}
                    onConfirm={handleDeleteContact}
                />
            )}
```

- [ ] **Step 7: Verify it builds**

Run: `cd frontend && npm run build`
Expected: exits 0.

- [ ] **Step 8: Verify by hand in the browser**

With both dev servers running:

- Open a contact that has calls and tasks. The dialog reads e.g. *"Delete David Cohen?" / "This also deletes 3 calls and 2 tasks. This can't be undone."*
- Cancel — nothing changes.
- Confirm — you land on the Contacts list and that contact is gone.
- Open the Tasks page — the deleted contact's tasks are gone too.
- Open a different contact and confirm its calls and tasks are untouched.
- Open a contact with no calls and no tasks — the dialog reads only *"This can't be undone."* with no "0 calls" text.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/pages/ContactDetailsPage/ContactDetailsPage.tsx frontend/src/pages/ContactDetailsPage/ContactDetailsPage.module.css
git commit -m "feat: delete contact with its calls and tasks"
```

---

## Task 9: Multi-select call delete on the Home page

**Files:**
- Modify: `frontend/src/pages/HomePage/HomePage.tsx`
- Modify: `frontend/src/pages/HomePage/HomePage.module.css`

**Interfaces:**
- Consumes: `useMultiSelect` (Task 4), `useSelectionBackButton` (Task 5), `SelectionBar`, `ConfirmDialog`, and `useCallDeletion` (Task 6)
- Produces: nothing consumed by later tasks

- [ ] **Step 1: Add the card styles**

Append to `frontend/src/pages/HomePage/HomePage.module.css`:

```css
/* Long-press multi-select. See the note in ContactDetailsPage.module.css:
   without the callout/user-select suppression the Android WebView shows its
   native text-selection popup instead of entering selection mode. */
.selectableCard {
    -webkit-touch-callout: none;
    -webkit-user-select: none;
    user-select: none;
    cursor: pointer;
}

.selectedCard {
    border-color: #2563eb;
    background-color: #eff6ff;
}

.selectionSpacer {
    height: 72px;
}
```

- [ ] **Step 2: Add the imports**

At the top of `frontend/src/pages/HomePage/HomePage.tsx`, add below the existing imports:

```tsx
import SelectionBar from '@/components/SelectionBar';
import ConfirmDialog from '@/components/ConfirmDialog';
import { useMultiSelect } from '@/hooks/useMultiSelect';
import { useSelectionBackButton } from '@/hooks/useSelectionBackButton';
import { useCallDeletion } from '@/hooks/useCallDeletion';
```

- [ ] **Step 3: Add the selection state**

Inside the `HomePage` component, below the existing `useState` declarations, add. Note the `onRefetch` differs from the Contact Details page — Home shows every contact's calls, so it does not filter.

```tsx
    const callSelection = useMultiSelect();
    const [isDeleteOpen, setIsDeleteOpen] = useState(false);

    useSelectionBackButton(callSelection.isSelecting, callSelection.clear);

    const callDeletion = useCallDeletion({
        onDeleted: (deletedIds) => {
            setCalls(prev => prev.filter(call => !deletedIds.has(call._id)));
        },
        onRefetch: async () => {
            const callsRes = await apiClient.get('/calls');
            setCalls(callsRes.data);
        },
    });
```

- [ ] **Step 4: Add the delete handler**

Add this function inside the component, below `filteredCalls`:

```tsx
    const handleDeleteSelectedCalls = async () => {
        await callDeletion.deleteCalls(callSelection.selectedIds);
        callSelection.clear();
        setIsDeleteOpen(false);
    };
```

- [ ] **Step 5: Render the selection bar**

Immediately after the opening `<div className={styles.pageWrapper}>` tag, add:

```tsx
            {callSelection.isSelecting && (
                <>
                    <SelectionBar
                        count={callSelection.count}
                        onCancel={callSelection.clear}
                        onDelete={() => setIsDeleteOpen(true)}
                    />
                    <div className={styles.selectionSpacer} />
                </>
            )}
```

- [ ] **Step 6: Show the error message**

Directly below the `<h2 className={styles.sectionTitle}>Recent Call Insights</h2>` line, add:

```tsx
                        {callDeletion.error && <p className={styles.statusMessage}>{callDeletion.error}</p>}
```

- [ ] **Step 7: Wire the call cards**

Replace the opening tag of the insight item:

```tsx
                                    <div key={call._id} className={styles.insightItem}>
```

with:

```tsx
                                    <div
                                        key={call._id}
                                        className={`${styles.insightItem} ${styles.selectableCard} ${callSelection.isSelected(call._id) ? styles.selectedCard : ''}`}
                                        {...callSelection.getItemProps(call._id)}
                                    >
```

- [ ] **Step 8: Render the confirm dialog**

Before the closing `<BottomNav />` in the returned JSX, add:

```tsx
            {isDeleteOpen && (
                <ConfirmDialog
                    title={`Delete ${callSelection.count} ${callSelection.count === 1 ? 'call' : 'calls'}?`}
                    message="This can't be undone."
                    busy={callDeletion.isDeleting}
                    onCancel={() => setIsDeleteOpen(false)}
                    onConfirm={handleDeleteSelectedCalls}
                />
            )}
```

- [ ] **Step 9: Verify the whole suite and build**

Run: `cd frontend && npm test && npm run build`
Expected: 19 tests pass, build exits 0.

- [ ] **Step 10: Verify by hand in the browser**

With both dev servers running, on the Home page:

- Long-press a recent call card — the selection bar appears.
- Select calls belonging to two different contacts and delete them — both disappear, and both are still gone after a reload.
- Open each of those two contacts and confirm the deleted calls are gone there too, and that their tasks are untouched.
- Type in the search box while nothing is selected — filtering still works normally.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/pages/HomePage/HomePage.tsx frontend/src/pages/HomePage/HomePage.module.css
git commit -m "feat: multi-select call delete on home page"
```

---

## Task 10: On-device verification

**Files:** none — this task changes no code unless a check fails.

**Interfaces:**
- Consumes: everything from Tasks 1–9
- Produces: nothing

**Why this task exists.** The long-press gesture and the back-button behavior both depend on Android WebView specifics that behave differently in desktop Chrome. Passing browser checks proves nothing about the device.

- [ ] **Step 1: Build the frontend and copy it into the Android assets**

```bash
cd frontend
npm run build
rm -rf ../android/app/src/main/assets/www/*
cp -R dist/* ../android/app/src/main/assets/www/
```

- [ ] **Step 2: Install the app on a device or emulator**

```bash
cd android
./gradlew installDebug
```

- [ ] **Step 3: Run the device checks**

Log in and go to the Home page. Confirm each of these:

1. **Long press selects.** Press and hold a call card for about a second. The blue selection bar appears. The native "Copy / Select all" popup and the text magnifier do **not** appear. *If they do, the `-webkit-touch-callout` / `-webkit-user-select` rules from Task 9 Step 1 are missing or overridden.*
2. **Scrolling does not select.** Flick the call list up and down repeatedly. Nothing gets selected. *If items get selected, `MOVE_CANCEL_PX` is too large or `onPointerMove` is not wired.*
3. **Back exits selection.** With items selected, press the Android back button. Selection mode ends and you stay on the Home page.
4. **Back still leaves the page.** With nothing selected, press back. It behaves as it did before this change.
5. **Delete works end to end.** Select two calls, tap the trash icon, confirm. Both disappear. Force-stop and reopen the app — they are still gone.
6. **The same five checks on Contact Details.** Repeat 1, 2, 3, and 5 on a contact's Calls tab.
7. **View transcript still works.** With nothing selected, tap "View transcript" — it expands. It does not enter selection mode.
8. **Contact delete works.** Open a contact, tap the red delete button, read the counts in the dialog, confirm. You land on the Contacts list, the contact is gone, and its tasks are gone from the Tasks page.

- [ ] **Step 4: Commit the rebuilt Android assets**

Only if the checks pass:

```bash
git add android/app/src/main/assets/www
git commit -m "chore: update Android assets with delete feature"
```

---

## Self-Review Notes

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| No schema changes | Global constraints; no task touches a model |
| `POST /api/calls/bulk-delete` returning `deletedCount` | Task 2 |
| `DELETE /api/contacts/:id` returning cascade counts | Task 3 |
| `userId` scoping on every delete | Tasks 2, 3 — asserted on the exact filter object |
| Contact deleted last, no transaction | Task 3, ordering test |
| 404 for another user's contact | Task 3 |
| Validation: non-array, empty, >200, bad ObjectId | Tasks 1, 2 |
| `useMultiSelect` hook | Task 4 |
| Long press ~500ms, cancel on >10px move | Task 4 |
| Tap toggles while selecting, normal behavior otherwise | Task 4 |
| Auto-exit when count reaches 0 | Task 4 |
| Back button exits selection | Task 5, verified in Task 10 |
| `SelectionBar`, `ConfirmDialog` | Task 6 |
| Confirm copy and destructive styling | Tasks 6, 7, 9 |
| Remove rows from local state on success | Task 6 (`useCallDeletion`), wired in 7 and 9 |
| Error + refetch on failure or count mismatch | Task 6 (`useCallDeletion`), wired in 7 and 9 |
| Contact delete button with damage counts | Task 8 |
| Zero-count clauses omitted | Task 8 |
| Navigate to `/contacts` after contact delete | Task 8 |
| WebView long-press CSS | Tasks 7, 9; verified Task 10 |
| Backend test list | Tasks 1, 2, 3 |
| Frontend `useMultiSelect` test list | Task 4 |
| Manual device checks | Task 10 |

**Amendment (2026-07-27, pre-flight):** the delete request, count-mismatch check, and error/refetch recovery were originally written out twice — once in Task 7 and once in Task 9. They are now extracted into `useCallDeletion` in Task 6, which both pages consume. This also made that logic directly testable; Task 6 gained 7 tests as a result.

**Deviations from the spec, both deliberate:**

1. The spec's manual check list is folded into Task 10 and expanded from three items to eight, adding the transcript-button and contact-delete checks.
2. The spec did not mention it, but `useSelectionBackButton` leaves a stale history entry when the user navigates away mid-selection. This is documented in Task 5 as an accepted limitation — the alternative cancels the user's navigation.
