# Persistent Login (Refresh Tokens) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opening the app with an existing session goes straight to the home screen, and that session survives indefinitely without breaking background transcript uploads.

**Architecture:** Fix the root route that unconditionally renders the login page, then add per-client rotating refresh tokens so the session outlives the (now 15-minute) access token. Two clients hold independent refresh tokens — the WebView (`web`) and the native background uploader (`native`) — so neither can invalidate the other on rotation.

**Tech Stack:** Express + Mongoose + jsonwebtoken (backend, vitest), React + react-router `HashRouter` + axios (frontend, vitest + @testing-library/react), Kotlin + OkHttp (Android, JUnit + Robolectric).

**Design doc:** `docs/superpowers/specs/2026-07-31-persistent-login-refresh-tokens-design.md`

---

## STATUS as of 2026-07-31 — partially executed, stopped deliberately

| Tasks | State |
| --- | --- |
| 1-4 (backend) | ✅ Complete, tested, reviewed |
| 5-8 (frontend) | ✅ Complete, 41 tests passing, reviewed |
| 9 (native storage) | ⚠️ Committed (`52a57fd`) but **never compiled** — static review only |
| 10-13 (native refresher, uploader, wiring, bundle) | ❌ Not started |
| 14 (deploy) | ⏸ Backend ready; user runs it |

**Why it stopped:** Gradle cannot run in the execution environment —
`java.io.IOException: Unable to establish loopback connection`, reproduced on
`./gradlew help` both with and without `--no-daemon` (Gradle forks a single-use daemon
regardless). Java 21 is present; there is no `kotlinc`. Tasks 10-13 are all Kotlin and
cannot be compiled or tested, and they change the background-upload auth path where a
failure is silent. Writing them unverified was judged worse than stopping.

**Two decisions that override the task text below — read these first:**

1. **`ACCESS_TOKEN_TTL` is `'7d'`, not `'15m'`** (`backend/src/config/jwt.ts`, commit
   `d74aebc`). Task 4 specifies `'15m'`; that was staged back deliberately. The refresh
   endpoints are inert until a client calls them and deploy safely alone, but the shipped
   Android uploader holds this token and cannot refresh yet — 15 minutes would 401 every
   upload happening more than 15 minutes after the app was last opened. **Drop it to
   `'15m'` in the same commit that completes Task 12**, not before. That is the single
   change that arms the whole feature.

2. **Task 12's `NativeBridge.setAuth` change was folded into Task 9.** `Task 9` removes
   `AuthStore.setToken`, which `NativeBridge.kt:31` called, so leaving the bridge for
   later broke main-source compilation. `NativeBridge.setAuth(token, refreshToken)` is
   already done. Task 12 is therefore reduced to the `MainActivity` /
   `CallMonitorService` construction sites only. `NativeBridgeTest` needed no change — it
   never exercised `setAuth`.

**First step when resuming:** run `./gradlew testDebugUnitTest` and `./gradlew
assembleDebug` on a machine where Gradle works, to verify the unverified Task 9 commit
before building anything on top of it. Static review found no compile-blocking issues and
confirmed all five existing test files stay source-compatible, but that is not a compiler.

**Note on CI:** `.github/workflows/build-and-copy.yml` runs `on: [push]` and rebuilds
`android/app/src/main/assets/www` from `frontend/`, committing it back to the branch. Task
13 therefore happens automatically on push and does not need to be run by hand.

## Global Constraints

- Access token lifetime is exactly `'15m'`. Refresh token lifetime is exactly 90 days.
- Refresh tokens are `crypto.randomBytes(32).toString('hex')` — opaque handles, never JWTs.
- **Only the SHA-256 hash of a refresh token is ever persisted server-side.** The raw value is returned to the client once and never stored.
- `client` is always one of the string literals `'web'` or `'native'`. No other values.
- Backend tests mock the service layer (`vi.mock`) rather than connecting to Mongo — follow the existing pattern in `src/controllers/callController.test.ts`.
- `EncryptedSharedPreferences` cannot run on the JVM (Android keystore unavailable under Robolectric). Native tests must never call `AuthStore.setToken`/`setTokens` directly — use the `FakeTokenStore` introduced in Task 8.
- `android/app/src/main/assets/www/` is a checked-in build artifact, never edited by hand. It is regenerated in Task 12.
- Backend base URL is `http://193.106.55.154:3000` (external IP of the same box reached over SSH at `10.10.248.154`).
- Task order is deployment order: Tasks 1–4 (backend) must ship and be running before the rebuilt app in Tasks 12–13 reaches a device.

---

### Task 1: Refresh token primitives

**Files:**
- Create: `backend/src/utils/refreshToken.ts`
- Create: `backend/src/utils/refreshToken.test.ts`

**Interfaces:**
- Consumes: nothing
- Produces: `generateRefreshToken(): string`, `hashRefreshToken(raw: string): string`, `refreshTokenExpiry(now?: Date): Date`, `REFRESH_TOKEN_TTL_DAYS: number`

- [ ] **Step 1: Write the failing test**

Create `backend/src/utils/refreshToken.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import {
    generateRefreshToken,
    hashRefreshToken,
    refreshTokenExpiry,
    REFRESH_TOKEN_TTL_DAYS,
} from './refreshToken';

describe('generateRefreshToken', () => {
    it('returns 64 hex characters (256 bits)', () => {
        expect(generateRefreshToken()).toMatch(/^[0-9a-f]{64}$/);
    });

    it('does not repeat', () => {
        const tokens = new Set(Array.from({ length: 100 }, generateRefreshToken));
        expect(tokens.size).toBe(100);
    });
});

describe('hashRefreshToken', () => {
    it('is deterministic', () => {
        expect(hashRefreshToken('abc')).toBe(hashRefreshToken('abc'));
    });

    it('differs for different inputs', () => {
        expect(hashRefreshToken('abc')).not.toBe(hashRefreshToken('abd'));
    });

    // The whole point of hashing: a database dump must not yield usable tokens.
    it('never returns the raw token', () => {
        const raw = generateRefreshToken();
        expect(hashRefreshToken(raw)).not.toBe(raw);
    });
});

describe('refreshTokenExpiry', () => {
    it('is REFRESH_TOKEN_TTL_DAYS in the future', () => {
        const now = new Date('2026-01-01T00:00:00Z');
        const expected = new Date('2026-01-01T00:00:00Z');
        expected.setDate(expected.getDate() + REFRESH_TOKEN_TTL_DAYS);
        expect(refreshTokenExpiry(now).toISOString()).toBe(expected.toISOString());
    });

    it('uses a 90 day window', () => {
        expect(REFRESH_TOKEN_TTL_DAYS).toBe(90);
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && npx vitest run src/utils/refreshToken.test.ts`
Expected: FAIL — `Failed to resolve import "./refreshToken"`

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/utils/refreshToken.ts`:

```ts
import crypto from 'crypto';

export const REFRESH_TOKEN_TTL_DAYS = 90;

/**
 * An opaque 256-bit handle, deliberately not a JWT: it must be revocable by
 * deleting the stored row, which a self-validating token could not be.
 */
export const generateRefreshToken = (): string =>
    crypto.randomBytes(32).toString('hex');

/** Only this value is persisted — a database leak must not yield live sessions. */
export const hashRefreshToken = (raw: string): string =>
    crypto.createHash('sha256').update(raw).digest('hex');

export const refreshTokenExpiry = (now: Date = new Date()): Date => {
    const expiry = new Date(now.getTime());
    expiry.setDate(expiry.getDate() + REFRESH_TOKEN_TTL_DAYS);
    return expiry;
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && npx vitest run src/utils/refreshToken.test.ts`
Expected: PASS — 7 tests

- [ ] **Step 5: Commit**

```bash
git add backend/src/utils/refreshToken.ts backend/src/utils/refreshToken.test.ts
git commit -m "feat(auth): add refresh token generation and hashing primitives"
```

---

### Task 2: RefreshToken model

**Files:**
- Create: `backend/src/models/RefreshToken.ts`

**Interfaces:**
- Consumes: nothing
- Produces: default export `RefreshToken` (Mongoose model), `IRefreshToken`, `RefreshClient = 'web' | 'native'`

This task has no unit test of its own — it is a schema declaration whose behaviour (upsert, uniqueness, TTL) is only observable against a live Mongo. Its guarantees are exercised through the service tests in Task 3 and the manual verification in Task 13.

- [ ] **Step 1: Create the model**

Create `backend/src/models/RefreshToken.ts`:

```ts
import mongoose, { Schema, Document } from 'mongoose';

export type RefreshClient = 'web' | 'native';

export interface IRefreshToken extends Document {
    userId: mongoose.Types.ObjectId;
    tokenHash: string;
    client: RefreshClient;
    expiresAt: Date;
    createdAt: Date;
}

const RefreshTokenSchema = new Schema<IRefreshToken>({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    tokenHash: { type: String, required: true },
    client: { type: String, required: true, enum: ['web', 'native'] },
    expiresAt: { type: Date, required: true },
    createdAt: { type: Date, default: Date.now },
});

/**
 * One row per (user, client). This is what makes rotation safe with two clients:
 * a web refresh upserts only the web row and cannot invalidate native's, so the
 * background uploader and the WebView never race each other into a spurious logout.
 */
RefreshTokenSchema.index({ userId: 1, client: 1 }, { unique: true });

/** Lookup path for the refresh endpoint. */
RefreshTokenSchema.index({ tokenHash: 1 });

/** Mongo reaps expired rows on its own; nothing in app code sweeps this collection. */
RefreshTokenSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });

export default mongoose.model<IRefreshToken>('RefreshToken', RefreshTokenSchema);
```

- [ ] **Step 2: Verify it compiles**

Run: `cd backend && npx tsc --noEmit`
Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add backend/src/models/RefreshToken.ts
git commit -m "feat(auth): add RefreshToken model with per-client uniqueness and TTL"
```

---

### Task 3: Refresh token service

**Files:**
- Create: `backend/src/services/refreshTokenService.ts`
- Create: `backend/src/services/refreshTokenService.test.ts`

**Interfaces:**
- Consumes: `RefreshToken` model (Task 2), `generateRefreshToken`/`hashRefreshToken`/`refreshTokenExpiry` (Task 1)
- Produces:
  - `issueRefreshToken(userId: string, client: RefreshClient): Promise<string>` — returns the raw token
  - `rotateRefreshToken(raw: string, client: RefreshClient): Promise<{ userId: string; refreshToken: string } | null>` — `null` means reject
  - `revokeRefreshToken(raw: string): Promise<void>`

- [ ] **Step 1: Write the failing test**

Create `backend/src/services/refreshTokenService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/RefreshToken', () => ({
    default: {
        findOneAndUpdate: vi.fn(),
        findOne: vi.fn(),
        deleteOne: vi.fn(),
    },
}));

import RefreshToken from '../models/RefreshToken';
import { hashRefreshToken } from '../utils/refreshToken';
import { issueRefreshToken, rotateRefreshToken, revokeRefreshToken } from './refreshTokenService';

const USER_ID = '507f1f77bcf86cd799439011';

beforeEach(() => {
    vi.mocked(RefreshToken.findOneAndUpdate).mockReset().mockResolvedValue({} as any);
    vi.mocked(RefreshToken.findOne).mockReset();
    vi.mocked(RefreshToken.deleteOne).mockReset().mockResolvedValue({} as any);
});

describe('issueRefreshToken', () => {
    it('returns a raw token and stores only its hash', async () => {
        const raw = await issueRefreshToken(USER_ID, 'web');

        expect(raw).toMatch(/^[0-9a-f]{64}$/);
        const [filter, update] = vi.mocked(RefreshToken.findOneAndUpdate).mock.calls[0];
        expect(filter).toEqual({ userId: USER_ID, client: 'web' });
        expect((update as any).tokenHash).toBe(hashRefreshToken(raw));
        expect(JSON.stringify(update)).not.toContain(raw);
    });

    it('upserts so a client only ever holds one row', async () => {
        await issueRefreshToken(USER_ID, 'native');
        const options = vi.mocked(RefreshToken.findOneAndUpdate).mock.calls[0][2];
        expect(options).toMatchObject({ upsert: true });
    });
});

describe('rotateRefreshToken', () => {
    const future = () => new Date(Date.now() + 60_000);

    it('issues a new token and returns the owning user', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue({
            _id: 'row1',
            userId: { toString: () => USER_ID },
            expiresAt: future(),
        } as any);

        const result = await rotateRefreshToken('old-raw-token', 'web');

        expect(result?.userId).toBe(USER_ID);
        expect(result?.refreshToken).toMatch(/^[0-9a-f]{64}$/);
        expect(result?.refreshToken).not.toBe('old-raw-token');
    });

    it('looks the token up by hash, never by raw value', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue(null);

        await rotateRefreshToken('old-raw-token', 'web');

        expect(RefreshToken.findOne).toHaveBeenCalledWith({
            tokenHash: hashRefreshToken('old-raw-token'),
            client: 'web',
        });
    });

    it('rejects an unknown token', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue(null);
        expect(await rotateRefreshToken('nope', 'web')).toBeNull();
    });

    it('rejects and deletes an expired token', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue({
            _id: 'row1',
            userId: { toString: () => USER_ID },
            expiresAt: new Date(Date.now() - 60_000),
        } as any);

        expect(await rotateRefreshToken('stale', 'web')).toBeNull();
        expect(RefreshToken.deleteOne).toHaveBeenCalledWith({ _id: 'row1' });
    });

    // The per-client isolation guarantee: a web rotation must only touch the web row.
    it('only writes the row for the presented client', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue({
            _id: 'row1',
            userId: { toString: () => USER_ID },
            expiresAt: future(),
        } as any);

        await rotateRefreshToken('old-raw-token', 'web');

        for (const call of vi.mocked(RefreshToken.findOneAndUpdate).mock.calls) {
            expect(call[0]).toEqual({ userId: USER_ID, client: 'web' });
        }
        expect(RefreshToken.deleteOne).not.toHaveBeenCalled();
    });
});

describe('revokeRefreshToken', () => {
    it('deletes by hash', async () => {
        await revokeRefreshToken('some-token');
        expect(RefreshToken.deleteOne).toHaveBeenCalledWith({
            tokenHash: hashRefreshToken('some-token'),
        });
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && npx vitest run src/services/refreshTokenService.test.ts`
Expected: FAIL — `Failed to resolve import "./refreshTokenService"`

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/services/refreshTokenService.ts`:

```ts
import RefreshToken, { RefreshClient } from '../models/RefreshToken';
import {
    generateRefreshToken,
    hashRefreshToken,
    refreshTokenExpiry,
} from '../utils/refreshToken';

/**
 * Issues a fresh refresh token for one client, replacing whatever that client held.
 * The upsert on (userId, client) is what keeps web and native independent.
 */
export const issueRefreshToken = async (
    userId: string,
    client: RefreshClient
): Promise<string> => {
    const raw = generateRefreshToken();

    await RefreshToken.findOneAndUpdate(
        { userId, client },
        {
            userId,
            client,
            tokenHash: hashRefreshToken(raw),
            expiresAt: refreshTokenExpiry(),
            createdAt: new Date(),
        },
        { upsert: true, new: true }
    );

    return raw;
};

/**
 * Verifies and rotates. Returns null for anything the caller should treat as a real
 * logout — unknown token, wrong client, or expired.
 */
export const rotateRefreshToken = async (
    raw: string,
    client: RefreshClient
): Promise<{ userId: string; refreshToken: string } | null> => {
    const existing = await RefreshToken.findOne({
        tokenHash: hashRefreshToken(raw),
        client,
    });

    if (!existing) {
        return null;
    }

    if (existing.expiresAt.getTime() <= Date.now()) {
        await RefreshToken.deleteOne({ _id: existing._id });
        return null;
    }

    const userId = existing.userId.toString();
    const refreshToken = await issueRefreshToken(userId, client);

    return { userId, refreshToken };
};

/** Used by logout. Deletes only the row for the presented token. */
export const revokeRefreshToken = async (raw: string): Promise<void> => {
    await RefreshToken.deleteOne({ tokenHash: hashRefreshToken(raw) });
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && npx vitest run src/services/refreshTokenService.test.ts`
Expected: PASS — 8 tests

- [ ] **Step 5: Commit**

```bash
git add backend/src/services/refreshTokenService.ts backend/src/services/refreshTokenService.test.ts
git commit -m "feat(auth): add refresh token service with per-client rotation"
```

---

### Task 4: Auth endpoints

**Files:**
- Modify: `backend/src/config/jwt.ts`
- Modify: `backend/src/controllers/authController.ts:43`, `:79`
- Modify: `backend/src/routes/authRoute.ts`
- Create: `backend/src/controllers/authController.test.ts`

**Interfaces:**
- Consumes: `issueRefreshToken`, `rotateRefreshToken`, `revokeRefreshToken` (Task 3); `protect` (existing `middleware/authMiddleware`)
- Produces: `refresh`, `logout`, `deviceToken` request handlers; `ACCESS_TOKEN_TTL` exported from `config/jwt`; `login`/`signup` responses gain a `refreshToken` field

- [ ] **Step 1: Write the failing test**

Create `backend/src/controllers/authController.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/refreshTokenService', () => ({
    issueRefreshToken: vi.fn(),
    rotateRefreshToken: vi.fn(),
    revokeRefreshToken: vi.fn(),
}));

import * as refreshTokenService from '../services/refreshTokenService';
import { refresh, logout, deviceToken } from './authController';

const USER_ID = '507f1f77bcf86cd799439011';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    res.send = vi.fn().mockReturnValue(res);
    return res;
};

beforeEach(() => {
    vi.mocked(refreshTokenService.rotateRefreshToken).mockReset();
    vi.mocked(refreshTokenService.revokeRefreshToken).mockReset().mockResolvedValue(undefined);
    vi.mocked(refreshTokenService.issueRefreshToken).mockReset().mockResolvedValue('new-refresh');
});

describe('refresh', () => {
    it('returns a new access token and the rotated refresh token', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue({
            userId: USER_ID,
            refreshToken: 'rotated-refresh',
        });
        const res = makeRes();

        await refresh({ body: { refreshToken: 'old', client: 'web' } } as any, res);

        expect(refreshTokenService.rotateRefreshToken).toHaveBeenCalledWith('old', 'web');
        expect(res.status).toHaveBeenCalledWith(200);
        const payload = res.json.mock.calls[0][0];
        expect(payload.refreshToken).toBe('rotated-refresh');
        expect(typeof payload.token).toBe('string');
    });

    it('defaults the client to web when omitted', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue({
            userId: USER_ID,
            refreshToken: 'rotated-refresh',
        });

        await refresh({ body: { refreshToken: 'old' } } as any, makeRes());

        expect(refreshTokenService.rotateRefreshToken).toHaveBeenCalledWith('old', 'web');
    });

    it('passes the native client through', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue({
            userId: USER_ID,
            refreshToken: 'rotated-refresh',
        });

        await refresh({ body: { refreshToken: 'old', client: 'native' } } as any, makeRes());

        expect(refreshTokenService.rotateRefreshToken).toHaveBeenCalledWith('old', 'native');
    });

    it('returns 400 when no refresh token is supplied', async () => {
        const res = makeRes();
        await refresh({ body: {} } as any, res);
        expect(res.status).toHaveBeenCalledWith(400);
        expect(refreshTokenService.rotateRefreshToken).not.toHaveBeenCalled();
    });

    it('returns 401 when the token is rejected', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue(null);
        const res = makeRes();

        await refresh({ body: { refreshToken: 'bad' } } as any, res);

        expect(res.status).toHaveBeenCalledWith(401);
    });

    it('rejects an unknown client value without hitting the service', async () => {
        const res = makeRes();
        await refresh({ body: { refreshToken: 'x', client: 'evil' } } as any, res);
        expect(res.status).toHaveBeenCalledWith(400);
        expect(refreshTokenService.rotateRefreshToken).not.toHaveBeenCalled();
    });
});

describe('logout', () => {
    it('revokes the presented token and returns 204', async () => {
        const res = makeRes();
        await logout({ body: { refreshToken: 'tok' } } as any, res);
        expect(refreshTokenService.revokeRefreshToken).toHaveBeenCalledWith('tok');
        expect(res.status).toHaveBeenCalledWith(204);
    });

    // Logging out without a stored token must still succeed, or the UI can get stuck.
    it('succeeds when no token is supplied', async () => {
        const res = makeRes();
        await logout({ body: {} } as any, res);
        expect(refreshTokenService.revokeRefreshToken).not.toHaveBeenCalled();
        expect(res.status).toHaveBeenCalledWith(204);
    });
});

describe('deviceToken', () => {
    it('issues a native pair for the authenticated user', async () => {
        const res = makeRes();

        await deviceToken({ user: { id: USER_ID } } as any, res);

        expect(refreshTokenService.issueRefreshToken).toHaveBeenCalledWith(USER_ID, 'native');
        expect(res.status).toHaveBeenCalledWith(200);
        const payload = res.json.mock.calls[0][0];
        expect(payload.refreshToken).toBe('new-refresh');
        expect(typeof payload.token).toBe('string');
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && npx vitest run src/controllers/authController.test.ts`
Expected: FAIL — `refresh is not a function` (no such export yet)

- [ ] **Step 3: Export the access token TTL**

In `backend/src/config/jwt.ts`, append after the `JWT_SECRET` export:

```ts
/**
 * Short by design: the session is kept alive by refresh tokens, not by a long-lived
 * access token. Both the WebView and the native uploader refresh on 401.
 */
export const ACCESS_TOKEN_TTL = '15m';
```

- [ ] **Step 4: Add the handlers**

In `backend/src/controllers/authController.ts`, change the import on line 5 to:

```ts
import { JWT_SECRET, ACCESS_TOKEN_TTL } from '../config/jwt';
import { RefreshClient } from '../models/RefreshToken';
import {
    issueRefreshToken,
    rotateRefreshToken,
    revokeRefreshToken,
} from '../services/refreshTokenService';
```

Replace the token line in `signup` (line 43) with:

```ts
        const token = jwt.sign({ id: newUser._id }, JWT_SECRET, { expiresIn: ACCESS_TOKEN_TTL });
        const refreshToken = await issueRefreshToken(
            String(newUser._id),
            parseClient(req.body.client)
        );
```

and add `refreshToken,` immediately after `token,` in that response's `res.status(201).json({ ... })`.

Replace the token line in `login` (line 79) with:

```ts
        const token = jwt.sign({ id: user._id }, JWT_SECRET, { expiresIn: ACCESS_TOKEN_TTL });
        const refreshToken = await issueRefreshToken(
            String(user._id),
            parseClient(req.body.client)
        );
```

and add `refreshToken,` immediately after `token,` in that response's `res.status(200).json({ ... })`.

Add at the end of the file:

```ts
/** Anything other than the two known clients is a bad request, not a silent default. */
const parseClient = (value: unknown): RefreshClient => {
    if (value === undefined || value === null || value === 'web') return 'web';
    if (value === 'native') return 'native';
    throw new InvalidClientError();
};

class InvalidClientError extends Error {}

export const refresh = async (req: Request, res: Response) => {
    try {
        const { refreshToken } = req.body ?? {};

        if (!refreshToken || typeof refreshToken !== 'string') {
            return res.status(400).json({ message: 'refreshToken is required' });
        }

        let client: RefreshClient;
        try {
            client = parseClient(req.body.client);
        } catch {
            return res.status(400).json({ message: 'Invalid client' });
        }

        const rotated = await rotateRefreshToken(refreshToken, client);
        if (!rotated) {
            return res.status(401).json({ message: 'Invalid refresh token' });
        }

        const token = jwt.sign({ id: rotated.userId }, JWT_SECRET, {
            expiresIn: ACCESS_TOKEN_TTL,
        });

        return res.status(200).json({ token, refreshToken: rotated.refreshToken });
    } catch (error) {
        console.error('Refresh error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};

export const logout = async (req: Request, res: Response) => {
    try {
        const { refreshToken } = req.body ?? {};

        if (refreshToken && typeof refreshToken === 'string') {
            await revokeRefreshToken(refreshToken);
        }

        // Always 204: a logout with nothing to revoke has still achieved its goal, and
        // failing it would strand the UI on a session it has already discarded.
        return res.status(204).send();
    } catch (error) {
        console.error('Logout error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};

/**
 * Mints the native client's pair. The WebView is the only thing that can perform a
 * login, so it provisions the background uploader's credentials on its behalf.
 */
export const deviceToken = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user!.id;
        const refreshToken = await issueRefreshToken(userId, 'native');
        const token = jwt.sign({ id: userId }, JWT_SECRET, { expiresIn: ACCESS_TOKEN_TTL });

        return res.status(200).json({ token, refreshToken });
    } catch (error) {
        console.error('Device token error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && npx vitest run src/controllers/authController.test.ts`
Expected: PASS — 10 tests

- [ ] **Step 6: Wire the routes**

Replace `backend/src/routes/authRoute.ts` entirely:

```ts
import { Router } from 'express';
import {
    signup,
    login,
    updateProfile,
    refresh,
    logout,
    deviceToken,
} from '../controllers/authController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.post('/signup', signup);
router.post('/login', login);
router.post('/refresh', refresh);
router.post('/logout', logout);
router.post('/device-token', protect, deviceToken);
router.put('/profile', protect, updateProfile);

export default router;
```

- [ ] **Step 7: Run the full backend suite and typecheck**

Run: `cd backend && npx tsc --noEmit && npm test`
Expected: no type errors; all tests pass including the pre-existing call/contact/objectId suites

- [ ] **Step 8: Commit**

```bash
git add backend/src/config/jwt.ts backend/src/controllers/authController.ts backend/src/controllers/authController.test.ts backend/src/routes/authRoute.ts
git commit -m "feat(auth): add refresh, logout and device-token endpoints"
```

---

### Task 5: Root route fix

This is the task that fixes the reported bug. It is deliberately separable from everything else — it works against the current backend and could ship alone.

**Files:**
- Create: `frontend/src/components/AuthLanding.tsx`
- Create: `frontend/src/components/AuthLanding.test.tsx`
- Modify: `frontend/src/App.tsx:27`

**Interfaces:**
- Consumes: nothing
- Produces: default export `AuthLanding` — a component rendering only a `<Navigate>`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/AuthLanding.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import AuthLanding from './AuthLanding';

const renderAt = () =>
    render(
        <MemoryRouter initialEntries={['/']}>
            <Routes>
                <Route path="/" element={<AuthLanding />} />
                <Route path="/home" element={<div>HOME</div>} />
                <Route path="/login" element={<div>LOGIN</div>} />
            </Routes>
        </MemoryRouter>
    );

beforeEach(() => {
    localStorage.clear();
});

describe('AuthLanding', () => {
    // The actual bug: a stored token was ignored and the user saw login every launch.
    it('sends an authenticated user to home', () => {
        localStorage.setItem('token', 'stored-token');
        renderAt();
        expect(screen.getByText('HOME')).toBeTruthy();
    });

    it('sends an unauthenticated user to login', () => {
        renderAt();
        expect(screen.getByText('LOGIN')).toBeTruthy();
    });

    it('treats an empty token as unauthenticated', () => {
        localStorage.setItem('token', '');
        renderAt();
        expect(screen.getByText('LOGIN')).toBeTruthy();
    });
});
```

- [ ] **Step 2: Configure the frontend test environment**

`frontend` has `vitest` and `jsdom` installed but no `test` block in its vite config. Add one so DOM tests can run.

In `frontend/vite.config.ts`, add a `test` property to the object passed to `defineConfig`:

```ts
  test: {
    environment: 'jsdom',
    globals: true,
  },
```

If the config file's import is `from 'vite'`, change it to `from 'vitest/config'` so the `test` key typechecks.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/components/AuthLanding.test.tsx`
Expected: FAIL — `Failed to resolve import "./AuthLanding"`

- [ ] **Step 4: Write minimal implementation**

Create `frontend/src/components/AuthLanding.tsx`:

```tsx
import { Navigate } from 'react-router-dom';

/**
 * The app boots at file:///android_asset/www/index.html with no hash, so HashRouter
 * resolves to "/". That route used to render LoginPage unconditionally, which is why a
 * perfectly good stored token was ignored on every launch.
 *
 * Only presence is checked. An expired access token is handled by the refresh
 * interceptor in apiClient, not here — route guards must stay synchronous.
 */
const AuthLanding: React.FC = () => {
    const token = localStorage.getItem('token');

    return <Navigate to={token ? '/home' : '/login'} replace />;
};

export default AuthLanding;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/components/AuthLanding.test.tsx`
Expected: PASS — 3 tests

- [ ] **Step 6: Use it at the root route**

In `frontend/src/App.tsx`, add the import alongside the others:

```tsx
import AuthLanding from '@components/AuthLanding';
```

and replace line 27:

```tsx
        <Route path="/" element={<LoginPage />} />
```

with:

```tsx
        <Route path="/" element={<AuthLanding />} />
```

- [ ] **Step 7: Verify the build and full suite**

Run: `cd frontend && npm run build && npx vitest run`
Expected: build succeeds; all tests pass

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/AuthLanding.tsx frontend/src/components/AuthLanding.test.tsx frontend/src/App.tsx frontend/vite.config.ts
git commit -m "fix(auth): honour the stored token on the root route

The app opens with no hash, so HashRouter landed on / and rendered
LoginPage regardless of auth state. The token was always there; nothing
ever looked at it."
```

---

### Task 6: Token storage helpers

**Files:**
- Create: `frontend/src/services/authTokens.ts`
- Create: `frontend/src/services/authTokens.test.ts`

**Interfaces:**
- Consumes: nothing
- Produces: `getAccessToken()`, `getRefreshToken()`, `setSession(token, refreshToken, user?)`, `clearSession()`, `STORAGE_KEYS`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/services/authTokens.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import {
    getAccessToken,
    getRefreshToken,
    setSession,
    clearSession,
} from './authTokens';

beforeEach(() => {
    localStorage.clear();
});

describe('authTokens', () => {
    it('round-trips a session', () => {
        setSession('acc', 'ref', { id: '1', name: 'Ben' });

        expect(getAccessToken()).toBe('acc');
        expect(getRefreshToken()).toBe('ref');
        expect(JSON.parse(localStorage.getItem('user')!)).toEqual({ id: '1', name: 'Ben' });
    });

    it('leaves the stored user alone when none is supplied', () => {
        setSession('acc', 'ref', { id: '1', name: 'Ben' });
        setSession('acc2', 'ref2');

        expect(getAccessToken()).toBe('acc2');
        expect(JSON.parse(localStorage.getItem('user')!)).toEqual({ id: '1', name: 'Ben' });
    });

    it('clears everything', () => {
        setSession('acc', 'ref', { id: '1' });
        clearSession();

        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
        expect(localStorage.getItem('user')).toBeNull();
    });

    it('reports null for a missing token', () => {
        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/services/authTokens.test.ts`
Expected: FAIL — `Failed to resolve import "./authTokens"`

- [ ] **Step 3: Write minimal implementation**

Create `frontend/src/services/authTokens.ts`:

```ts
/**
 * Single owner of session storage keys. `token` and `user` keep their historical names
 * so existing installs are not logged out by the upgrade itself.
 */
export const STORAGE_KEYS = {
    token: 'token',
    refreshToken: 'refreshToken',
    user: 'user',
} as const;

export const getAccessToken = (): string | null =>
    localStorage.getItem(STORAGE_KEYS.token);

export const getRefreshToken = (): string | null =>
    localStorage.getItem(STORAGE_KEYS.refreshToken);

export const setSession = (token: string, refreshToken: string, user?: unknown): void => {
    localStorage.setItem(STORAGE_KEYS.token, token);
    localStorage.setItem(STORAGE_KEYS.refreshToken, refreshToken);
    if (user !== undefined) {
        localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(user));
    }
};

export const clearSession = (): void => {
    localStorage.removeItem(STORAGE_KEYS.token);
    localStorage.removeItem(STORAGE_KEYS.refreshToken);
    localStorage.removeItem(STORAGE_KEYS.user);
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/services/authTokens.test.ts`
Expected: PASS — 4 tests

- [ ] **Step 5: Commit**

```bash
git add frontend/src/services/authTokens.ts frontend/src/services/authTokens.test.ts
git commit -m "feat(auth): add session storage helpers"
```

---

### Task 7: Refresh-on-401 interceptor

**Files:**
- Modify: `frontend/src/services/apiClient.ts` (replace the response interceptor, lines 18-32)
- Create: `frontend/src/services/apiClient.test.ts`

**Interfaces:**
- Consumes: `getRefreshToken`, `setSession`, `clearSession` (Task 6)
- Produces: `apiClient` (unchanged default export) with refresh-then-retry behaviour

- [ ] **Step 1: Write the failing test**

Create `frontend/src/services/apiClient.test.ts`:

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import axios from 'axios';
import apiClient from './apiClient';
import { setSession, getAccessToken, getRefreshToken } from './authTokens';

const mock = new MockAdapter(axios);
const clientMock = new MockAdapter(apiClient);

beforeEach(() => {
    localStorage.clear();
    mock.reset();
    clientMock.reset();
    window.location.hash = '';
});

describe('apiClient 401 handling', () => {
    it('refreshes and retries the original request', async () => {
        setSession('expired', 'good-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(200, {
            token: 'fresh',
            refreshToken: 'rotated',
        });
        clientMock.onGet('/calls').replyOnce(401).onGet('/calls').reply(200, { ok: true });

        const res = await apiClient.get('/calls');

        expect(res.data).toEqual({ ok: true });
        expect(getAccessToken()).toBe('fresh');
        expect(getRefreshToken()).toBe('rotated');
    });

    it('logs out when the refresh itself fails', async () => {
        setSession('expired', 'dead-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(401);
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
        expect(window.location.hash).toBe('#/login');
    });

    it('logs out when there is no refresh token at all (upgraded install)', async () => {
        localStorage.setItem('token', 'legacy-7day-token');
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        expect(getAccessToken()).toBeNull();
        expect(window.location.hash).toBe('#/login');
    });

    // Rotation is single-use, so a second concurrent refresh would invalidate the first.
    it('issues exactly one refresh for concurrent 401s', async () => {
        setSession('expired', 'good-refresh');
        const refreshSpy = vi.fn().mockReturnValue([200, { token: 'fresh', refreshToken: 'rotated' }]);
        mock.onPost(/\/auth\/refresh$/).reply(refreshSpy);
        clientMock
            .onGet('/a').replyOnce(401).onGet('/a').reply(200, { r: 'a' })
            .onGet('/b').replyOnce(401).onGet('/b').reply(200, { r: 'b' })
            .onGet('/c').replyOnce(401).onGet('/c').reply(200, { r: 'c' });

        const results = await Promise.all([
            apiClient.get('/a'),
            apiClient.get('/b'),
            apiClient.get('/c'),
        ]);

        expect(results.map((r) => r.data.r)).toEqual(['a', 'b', 'c']);
        expect(refreshSpy).toHaveBeenCalledTimes(1);
    });

    it('does not retry the same request twice', async () => {
        setSession('expired', 'good-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(200, { token: 'fresh', refreshToken: 'rotated' });
        clientMock.onGet('/calls').reply(401);   // still 401 even after refresh

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();
        expect(clientMock.history.get.filter((r) => r.url === '/calls')).toHaveLength(2);
    });

    it('passes non-401 errors through untouched', async () => {
        setSession('good', 'good-refresh');
        clientMock.onGet('/calls').reply(500);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();
        expect(getAccessToken()).toBe('good');
    });
});
```

- [ ] **Step 2: Install the test-only mock adapter**

Run: `cd frontend && npm install --save-dev axios-mock-adapter`

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/services/apiClient.test.ts`
Expected: FAIL — the retry test fails because the current interceptor clears the session on any 401 instead of refreshing

- [ ] **Step 4: Write the implementation**

Replace `frontend/src/services/apiClient.ts` entirely:

```ts
import axios from 'axios';
import { getAccessToken, getRefreshToken, setSession, clearSession } from './authTokens';

const baseURL = '' + (import.meta.env.VITE_API_URL as string);

const apiClient = axios.create({ baseURL });

apiClient.interceptors.request.use((config) => {
    const token = getAccessToken();
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
}, (error) => Promise.reject(error));

/**
 * Shared in-flight refresh. Refresh tokens are single-use, so two concurrent refreshes
 * would rotate each other out and log the user out spuriously — every 401 that arrives
 * while one is running must wait for that same promise.
 */
let refreshInFlight: Promise<string> | null = null;

const performRefresh = async (): Promise<string> => {
    const refreshToken = getRefreshToken();
    if (!refreshToken) {
        throw new Error('No refresh token stored');
    }

    // Deliberately a bare axios call, not apiClient: routing it through the instance
    // would re-enter this interceptor on failure and recurse.
    const { data } = await axios.post(`${baseURL}/auth/refresh`, {
        refreshToken,
        client: 'web',
    });

    setSession(data.token, data.refreshToken);
    return data.token;
};

const failSession = () => {
    clearSession();
    window.BrachaNative?.clearAuth();
    // The app runs from file:///android_asset/www/index.html under HashRouter, so a
    // path-based redirect resolves to file:///login (ERR_FILE_NOT_FOUND) and dead-ends
    // the app until it's force-stopped. Use a hash redirect instead.
    window.location.hash = '#/login';
};

apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
        const original = error.config;

        if (error.response?.status !== 401 || !original || original._retry) {
            return Promise.reject(error);
        }

        original._retry = true;

        try {
            if (!refreshInFlight) {
                refreshInFlight = performRefresh().finally(() => {
                    refreshInFlight = null;
                });
            }
            const token = await refreshInFlight;

            original.headers = original.headers ?? {};
            original.headers.Authorization = `Bearer ${token}`;
            return apiClient(original);
        } catch {
            console.warn('Refresh failed. Logging out user.');
            failSession();
            return Promise.reject(error);
        }
    }
);

export default apiClient;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/services/apiClient.test.ts`
Expected: PASS — 6 tests

- [ ] **Step 6: Commit**

```bash
git add frontend/src/services/apiClient.ts frontend/src/services/apiClient.test.ts frontend/package.json frontend/package-lock.json
git commit -m "feat(auth): refresh and retry on 401 instead of logging out"
```

---

### Task 8: Login, signup and logout wiring

**Files:**
- Modify: `frontend/src/pages/LoginPage/LoginPage.tsx:20-30`
- Modify: `frontend/src/pages/SignupPage/SignupPage.tsx:34-37`
- Modify: `frontend/src/pages/SettingsPage/SettingsPage.tsx:226-227`
- Modify: `frontend/src/types/native.d.ts`
- Modify: `frontend/src/App.tsx:17-22`
- Create: `frontend/src/services/session.ts`
- Create: `frontend/src/services/session.test.ts`

**Interfaces:**
- Consumes: `setSession`/`clearSession`/`getRefreshToken` (Task 6), `apiClient` (Task 7)
- Produces: `establishSession(loginResponse): Promise<void>`, `endSession(): Promise<void>`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/services/session.test.ts`:

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import apiClient from './apiClient';
import { establishSession, endSession } from './session';
import { getAccessToken, getRefreshToken, setSession } from './authTokens';

const clientMock = new MockAdapter(apiClient);

const setAuth = vi.fn();
const clearAuth = vi.fn();

beforeEach(() => {
    localStorage.clear();
    clientMock.reset();
    setAuth.mockReset();
    clearAuth.mockReset();
    (window as any).BrachaNative = { setAuth, clearAuth };
});

describe('establishSession', () => {
    it('stores the web pair and hands native its own separate pair', async () => {
        clientMock.onPost('/auth/device-token').reply(200, {
            token: 'native-access',
            refreshToken: 'native-refresh',
        });

        await establishSession({
            token: 'web-access',
            refreshToken: 'web-refresh',
            user: { id: '1' },
        });

        expect(getAccessToken()).toBe('web-access');
        expect(getRefreshToken()).toBe('web-refresh');
        // Native must NOT receive the web pair — rotation is per client.
        expect(setAuth).toHaveBeenCalledWith('native-access', 'native-refresh');
    });

    it('still completes the web login when device-token provisioning fails', async () => {
        clientMock.onPost('/auth/device-token').reply(500);

        await establishSession({
            token: 'web-access',
            refreshToken: 'web-refresh',
            user: { id: '1' },
        });

        expect(getAccessToken()).toBe('web-access');
        expect(setAuth).not.toHaveBeenCalled();
    });
});

describe('endSession', () => {
    it('revokes server-side, clears storage and tells native', async () => {
        setSession('acc', 'ref', { id: '1' });
        clientMock.onPost('/auth/logout').reply(204);

        await endSession();

        expect(clientMock.history.post[0].data).toContain('ref');
        expect(getAccessToken()).toBeNull();
        expect(clearAuth).toHaveBeenCalled();
    });

    it('clears locally even when the server call fails', async () => {
        setSession('acc', 'ref', { id: '1' });
        clientMock.onPost('/auth/logout').reply(500);

        await endSession();

        expect(getAccessToken()).toBeNull();
        expect(clearAuth).toHaveBeenCalled();
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/services/session.test.ts`
Expected: FAIL — `Failed to resolve import "./session"`

- [ ] **Step 3: Write the implementation**

Create `frontend/src/services/session.ts`:

```ts
import apiClient from './apiClient';
import { setSession, clearSession, getRefreshToken } from './authTokens';

interface LoginResponse {
    token: string;
    refreshToken: string;
    user: unknown;
}

/**
 * Establishes both sessions. The WebView is the only thing that can perform a login, so
 * it also provisions the background uploader's credentials — a *separate* pair, because
 * rotation is per client and sharing one would make the two invalidate each other.
 */
export const establishSession = async (response: LoginResponse): Promise<void> => {
    setSession(response.token, response.refreshToken, response.user);

    try {
        const { data } = await apiClient.post('/auth/device-token');
        window.BrachaNative?.setAuth(data.token, data.refreshToken);
    } catch (error) {
        // The web login has already succeeded; only background uploads are affected, and
        // they retry from the pending queue once native gets a token on a later login.
        console.error('Could not provision the native device token:', error);
    }
};

export const endSession = async (): Promise<void> => {
    const refreshToken = getRefreshToken();

    try {
        if (refreshToken) {
            await apiClient.post('/auth/logout', { refreshToken });
        }
    } catch (error) {
        // Local state is the source of truth for the UI; a failed revoke must not trap
        // the user in a session they have already left.
        console.error('Server-side logout failed:', error);
    }

    clearSession();
    window.BrachaNative?.clearAuth();
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/services/session.test.ts`
Expected: PASS — 4 tests

- [ ] **Step 5: Update the native type declaration**

In `frontend/src/types/native.d.ts`, change the `setAuth` signature to:

```ts
    setAuth(token: string, refreshToken: string): void;
```

- [ ] **Step 6: Use it in LoginPage**

In `frontend/src/pages/LoginPage/LoginPage.tsx`, add the import:

```tsx
import { establishSession } from '@/services/session';
```

and replace lines 25-28 (the `const { token, user } = ...` block through the `setAuth` call) with:

```tsx
      await establishSession(response.data);
```

- [ ] **Step 7: Use it in SignupPage**

In `frontend/src/pages/SignupPage/SignupPage.tsx`, add the same import, then replace lines 34-37 (the `const { token, user } = ...` block through the `setAuth` call) with:

```tsx
            await establishSession(response.data);
```

- [ ] **Step 8: Use it in SettingsPage logout**

In `frontend/src/pages/SettingsPage/SettingsPage.tsx`, add the import:

```tsx
import { endSession } from '@/services/session';
```

and replace lines 226-227 (the two `localStorage.removeItem` calls) with:

```tsx
                            await endSession();
```

Make the enclosing handler `async` if it is not already.

- [ ] **Step 9: Stop pushing the web token to native on boot**

In `frontend/src/App.tsx`, replace the `useEffect` block (lines 17-22) with:

```tsx
  // Native holds its own token pair, provisioned at login via /auth/device-token.
  // Pushing the web token here would overwrite it with credentials that rotate
  // independently, breaking background uploads.
```

Then delete the now-unused `useEffect` import from line 1 if nothing else uses it.

- [ ] **Step 10: Verify the whole frontend**

Run: `cd frontend && npm run build && npx vitest run && npm run lint`
Expected: build succeeds, all tests pass, lint clean

- [ ] **Step 11: Commit**

```bash
git add frontend/src/services/session.ts frontend/src/services/session.test.ts frontend/src/pages frontend/src/types/native.d.ts frontend/src/App.tsx
git commit -m "feat(auth): provision a separate native token pair at login"
```

---

### Task 9: Native token storage

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/TokenStore.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/AuthStore.kt`
- Create: `android/app/src/test/java/com/brachaai/app/FakeTokenStore.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `interface TokenStore { fun getToken(): String?; fun getRefreshToken(): String?; fun setTokens(accessToken: String, refreshToken: String); fun clear(); fun hasEverAuthenticated(): Boolean }`
  - `AuthStore : TokenStore`
  - `FakeTokenStore` (test source set) — a JVM-safe in-memory implementation

`AuthStore.setToken(token)` is removed in favour of `setTokens`. `AuthStore.HISTORY_PREFS_NAME` and `KEY_EVER_AUTHENTICATED` keep their current names and visibility so the existing tests keep working.

- [ ] **Step 1: Define the interface**

Create `android/app/src/main/java/com/brachaai/app/TokenStore.kt`:

```kotlin
package com.brachaai.app

/**
 * The token surface the uploader and refresher depend on.
 *
 * Exists as an interface purely for testability: the real implementation is backed by
 * EncryptedSharedPreferences, which needs the Android keystore and therefore cannot run
 * under Robolectric. Tests substitute an in-memory fake.
 */
interface TokenStore {
    fun getToken(): String?
    fun getRefreshToken(): String?
    fun setTokens(accessToken: String, refreshToken: String)
    fun clear()
    fun hasEverAuthenticated(): Boolean
}
```

- [ ] **Step 2: Implement it on AuthStore**

In `android/app/src/main/java/com/brachaai/app/AuthStore.kt`:

Change the class declaration to:

```kotlin
class AuthStore(context: Context) : TokenStore {
```

Add `override` to `getToken`, `clear` and `hasEverAuthenticated`. Then replace `setToken` with:

```kotlin
    override fun getRefreshToken(): String? = try {
        prefs.getString(KEY_REFRESH_TOKEN, null)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read refresh token", e)
        null
    }

    /**
     * Written as one edit: a half-stored pair — a fresh access token beside a stale
     * refresh token — would survive the next 401 and then fail to refresh, logging the
     * user out with no way back until the next foreground login.
     */
    override fun setTokens(accessToken: String, refreshToken: String) {
        try {
            prefs.edit()
                .putString(KEY_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .apply()
            markEverAuthenticated()
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist auth tokens", e)
        }
    }
```

Update `clear` to remove both:

```kotlin
    override fun clear() {
        try {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear auth tokens", e)
        }
    }
```

Add to the companion object, beside `KEY_TOKEN`:

```kotlin
        private const val KEY_REFRESH_TOKEN = "refresh_jwt"
```

- [ ] **Step 3: Add the test fake**

Create `android/app/src/test/java/com/brachaai/app/FakeTokenStore.kt`:

```kotlin
package com.brachaai.app

/**
 * In-memory [TokenStore] for JVM tests. The real [AuthStore] cannot be exercised here:
 * EncryptedSharedPreferences needs the Android keystore, which Robolectric does not provide.
 */
class FakeTokenStore(
    private var accessToken: String? = null,
    private var refreshToken: String? = null,
    private var everAuthenticated: Boolean = false
) : TokenStore {

    var clearCount = 0
        private set

    override fun getToken(): String? = accessToken

    override fun getRefreshToken(): String? = refreshToken

    override fun setTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.everAuthenticated = true
    }

    override fun clear() {
        accessToken = null
        refreshToken = null
        clearCount++
    }

    override fun hasEverAuthenticated(): Boolean = everAuthenticated
}
```

- [ ] **Step 4: Verify the existing native suite still passes**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS — `AuthStoreTest`, `NativeBridgeTest`, `AudioProcessorTest`, `PendingUploadStoreTest`, `SettingsStoreTest` all still pass. If `NativeBridgeTest` fails to compile on `setToken`, leave it — Task 11 updates it.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/TokenStore.kt android/app/src/main/java/com/brachaai/app/AuthStore.kt android/app/src/test/java/com/brachaai/app/FakeTokenStore.kt
git commit -m "feat(auth): store a refresh token alongside the access token"
```

---

### Task 10: Native token refresher

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/BackendConfig.kt`
- Create: `android/app/src/main/java/com/brachaai/app/TokenRefresher.kt`
- Create: `android/app/src/test/java/com/brachaai/app/TokenRefresherTest.kt`
- Modify: `android/app/build.gradle.kts:70-76`

**Interfaces:**
- Consumes: `TokenStore`, `FakeTokenStore` (Task 9)
- Produces:
  - `object BackendConfig { const val BASE_URL: String }`
  - `class TokenRefresher(tokenStore: TokenStore, baseUrl: String = BackendConfig.BASE_URL, client: OkHttpClient = OkHttpClient())` with `fun refresh(staleToken: String?): String?`

- [ ] **Step 1: Add MockWebServer to the test classpath**

In `android/app/build.gradle.kts`, add beside the other `testImplementation` lines:

```kotlin
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/TokenRefresherTest.kt`:

```kotlin
package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenRefresherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun refresherFor(store: TokenStore) =
        TokenRefresher(store, server.url("/").toString().trimEnd('/'))

    private fun okBody(access: String, refresh: String) = MockResponse()
        .setResponseCode(200)
        .setBody(JSONObject().put("token", access).put("refreshToken", refresh).toString())

    @Test
    fun storesTheRotatedPairAndReturnsTheNewAccessToken() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(okBody("fresh", "r2"))

        val result = refresherFor(store).refresh("stale")

        assertEquals("fresh", result)
        assertEquals("fresh", store.getToken())
        assertEquals("r2", store.getRefreshToken())
    }

    @Test
    fun sendsTheStoredRefreshTokenAsTheNativeClient() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(okBody("fresh", "r2"))

        refresherFor(store).refresh("stale")

        val request = server.takeRequest()
        assertEquals("/api/auth/refresh", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("r1", body.getString("refreshToken"))
        assertEquals("native", body.getString("client"))
    }

    @Test
    fun returnsNullAndClearsWhenTheRefreshTokenIsRejected() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "dead")
        server.enqueue(MockResponse().setResponseCode(401))

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals(1, store.clearCount)
    }

    @Test
    fun keepsTokensOnATransientServerError() {
        // A 500 is not evidence the session is over; wiping here would log the user out
        // because the backend hiccuped.
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals(0, store.clearCount)
        assertEquals("r1", store.getRefreshToken())
    }

    @Test
    fun returnsNullWhenNoRefreshTokenIsStored() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = null)

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun handsBackTheStoredTokenWhenAnotherCallerAlreadyRefreshed() {
        // Single-flight: the caller's token is stale, but the store already holds a newer
        // one, so there is nothing to do and no request to make.
        val store = FakeTokenStore(accessToken = "already-fresh", refreshToken = "r2")

        assertEquals("already-fresh", refresherFor(store).refresh("stale"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun concurrentRefreshesIssueASingleRequest() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(okBody("fresh", "r2"))
        val refresher = refresherFor(store)

        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.submit {
                start.await()
                refresher.refresh("stale")
                done.countDown()
            }
        }
        start.countDown()
        done.await(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(1, server.requestCount)
        assertEquals("fresh", store.getToken())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.TokenRefresherTest"`
Expected: FAIL — compilation error, `Unresolved reference: TokenRefresher`

- [ ] **Step 4: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/BackendConfig.kt`:

```kotlin
package com.brachaai.app

/**
 * Single source for the backend origin. Previously inlined at each call site in
 * AudioProcessor; TokenRefresher would have made a third copy.
 */
object BackendConfig {
    const val BASE_URL = "http://193.106.55.154:3000"
}
```

Create `android/app/src/main/java/com/brachaai/app/TokenRefresher.kt`:

```kotlin
package com.brachaai.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Trades the stored refresh token for a fresh access token.
 *
 * The background uploader can fire hours after the app was last opened, long past the
 * 15-minute access token, so it has to be able to re-authenticate without the WebView.
 */
class TokenRefresher(
    private val tokenStore: TokenStore,
    private val baseUrl: String = BackendConfig.BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /**
     * @param staleToken the access token the caller just had rejected.
     * @return a usable access token, or null if the session is over or the attempt failed.
     *
     * Synchronized, and short-circuits when the store already holds a token newer than
     * [staleToken]: refresh tokens are single-use, so parallel uploads flushing the pending
     * queue would otherwise rotate each other out and log the user out spuriously.
     */
    @Synchronized
    fun refresh(staleToken: String?): String? {
        val current = tokenStore.getToken()
        if (!current.isNullOrBlank() && current != staleToken) {
            Log.d(TAG, "Another caller already refreshed; reusing the stored token")
            return current
        }

        val refreshToken = tokenStore.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No refresh token stored; cannot refresh")
            return null
        }

        return try {
            val body = JSONObject()
                .put("refreshToken", refreshToken)
                .put("client", CLIENT)
                .toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$baseUrl/api/auth/refresh")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val json = JSONObject(response.body?.string().orEmpty())
                        val access = json.optString("token")
                        val rotated = json.optString("refreshToken")

                        if (access.isBlank() || rotated.isBlank()) {
                            Log.e(TAG, "Refresh response was missing a token")
                            return null
                        }

                        tokenStore.setTokens(access, rotated)
                        Log.d(TAG, "Access token refreshed")
                        access
                    }
                    response.code == 401 -> {
                        // The refresh token itself is dead. This is a real logout.
                        Log.w(TAG, "Refresh token rejected; clearing session")
                        tokenStore.clear()
                        null
                    }
                    else -> {
                        // Anything else is not evidence the session ended — keep the
                        // tokens and let the pending queue retry later.
                        Log.e(TAG, "Refresh failed with HTTP ${response.code}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh request failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "TokenRefresher"
        private const val CLIENT = "native"
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.TokenRefresherTest"`
Expected: PASS — 7 tests

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/BackendConfig.kt android/app/src/main/java/com/brachaai/app/TokenRefresher.kt android/app/src/test/java/com/brachaai/app/TokenRefresherTest.kt android/app/build.gradle.kts
git commit -m "feat(auth): add native token refresher with single-flight guard"
```

---

### Task 11: Uploader refreshes on 401

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt:18-32`, `:274-325`
- Modify: `android/app/src/test/java/com/brachaai/app/AudioProcessorTest.kt:32-38`
- Create: `android/app/src/test/java/com/brachaai/app/AudioProcessorUploadTest.kt`

**Interfaces:**
- Consumes: `TokenStore`, `FakeTokenStore` (Task 9); `TokenRefresher`, `BackendConfig` (Task 10)
- Produces: `AudioProcessor` constructor gains `tokenRefresher: TokenRefresher` and `baseUrl: String = BackendConfig.BASE_URL`; `authStore` parameter is retyped from `AuthStore` to `TokenStore`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/AudioProcessorUploadTest.kt`:

```kotlin
package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the 401 -> refresh -> retry path on the upload itself. The rest of the pipeline
 * (FFmpeg, Whisper) is untouched here — only [AudioProcessor.attemptUpload] is exercised,
 * through the pending-queue flush.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioProcessorUploadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun processorFor(store: TokenStore): AudioProcessor {
        val app = RuntimeEnvironment.getApplication()
        return AudioProcessor(
            openAiApiKey = "unused-in-this-test",
            cacheDir = tempFolder.newFolder("cache"),
            authStore = store,
            pendingStore = PendingUploadStore(app),
            callerLookup = CallerLookup(app),
            settingsStore = SettingsStore(app),
            tokenRefresher = TokenRefresher(store, baseUrl()),
            baseUrl = baseUrl()
        )
    }

    private val payload = PendingUpload(
        contactName = "Dana",
        date = "2026-07-31T10:00:00Z",
        transcript = "shalom",
        callerNumber = "0501234567"
    )

    @Test
    fun refreshesAndRetriesOnceWhenTheAccessTokenIsExpired() {
        val store = FakeTokenStore(accessToken = "expired", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(401))                       // upload
        server.enqueue(                                                          // refresh
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put("token", "fresh").put("refreshToken", "r2").toString()
            )
        )
        server.enqueue(MockResponse().setResponseCode(200))                       // retry

        val result = processorFor(store).uploadForTest(payload)

        assertEquals(AudioProcessor.UploadResult.Success, result)
        assertEquals(3, server.requestCount)
        assertEquals("fresh", store.getToken())

        server.takeRequest()
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer fresh", retry.getHeader("Authorization"))
    }

    @Test
    fun reportsUnauthenticatedAndClearsWhenTheRefreshTokenIsAlsoDead() {
        val store = FakeTokenStore(accessToken = "expired", refreshToken = "dead")
        server.enqueue(MockResponse().setResponseCode(401))   // upload
        server.enqueue(MockResponse().setResponseCode(401))   // refresh

        val result = processorFor(store).uploadForTest(payload)

        assertEquals(AudioProcessor.UploadResult.Unauthenticated, result)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun doesNotRetryMoreThanOnce() {
        val store = FakeTokenStore(accessToken = "expired", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(401))   // upload
        server.enqueue(                                       // refresh succeeds
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put("token", "fresh").put("refreshToken", "r2").toString()
            )
        )
        server.enqueue(MockResponse().setResponseCode(401))   // retry still 401

        val result = processorFor(store).uploadForTest(payload)

        assertEquals(AudioProcessor.UploadResult.Unauthenticated, result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun doesNotRefreshWhenTheUploadSucceeds() {
        val store = FakeTokenStore(accessToken = "good", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(AudioProcessor.UploadResult.Success, processorFor(store).uploadForTest(payload))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reportsUnauthenticatedWithoutCallingOutWhenNoTokenIsStored() {
        val store = FakeTokenStore(accessToken = null, refreshToken = null)

        assertEquals(
            AudioProcessor.UploadResult.Unauthenticated,
            processorFor(store).uploadForTest(payload)
        )
        assertEquals(0, server.requestCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.AudioProcessorUploadTest"`
Expected: FAIL — compilation error, `No value passed for parameter 'tokenRefresher'` and `Unresolved reference: uploadForTest`

- [ ] **Step 3: Make the result type visible to tests**

`UploadResult` is declared `private sealed class` at `AudioProcessor.kt:45`, so the test above
cannot reference `AudioProcessor.UploadResult.Success`. Widen it to `internal` — the Android
Gradle plugin compiles the unit test source set as a friend module, so `internal` is visible
there without making it part of the app's public surface:

```kotlin
    internal sealed class UploadResult {
```

Leave the nested `object Success` / `Unauthenticated` / `Transient` / `Rejected` declarations
as they are; they take their visibility from the enclosing class.

- [ ] **Step 4: Widen the constructor**

In `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt`, change the constructor (lines 18-25) to:

```kotlin
class AudioProcessor(
    private val openAiApiKey: String,
    private val cacheDir: File,
    private val authStore: TokenStore,
    private val pendingStore: PendingUploadStore,
    private val callerLookup: CallerLookup,
    private val settingsStore: SettingsStore,
    private val tokenRefresher: TokenRefresher,
    private val baseUrl: String = BackendConfig.BASE_URL
) {
```

- [ ] **Step 5: Add the refresh-and-retry path**

Replace `attemptUpload` (lines 274-325) with:

```kotlin
    /** Exposed for tests: drives one upload attempt without the transcription pipeline. */
    internal fun uploadForTest(payload: PendingUpload): UploadResult = attemptUpload(payload)

    private fun attemptUpload(payload: PendingUpload): UploadResult {
        val token = authStore.getToken()
        if (token.isNullOrBlank()) {
            println("No auth token stored; cannot upload")
            return UploadResult.Unauthenticated
        }

        return when (val first = postCall(payload, token)) {
            is UploadResult.Unauthenticated -> {
                // The access token is only good for 15 minutes and this service fires long
                // after the app was last opened, so an expired token is the normal case —
                // not a logout. Refresh once and retry before giving up on the session.
                val refreshed = tokenRefresher.refresh(token)

                if (refreshed.isNullOrBlank()) {
                    // Compare-and-clear: only wipe the token if it's still the one we just
                    // sent. A login that raced this request may already have stored a fresh
                    // token — clearing unconditionally would wipe that instead of the
                    // expired one, right on the post-login flush path. (TokenRefresher has
                    // already cleared when the refresh token itself was rejected.)
                    if (authStore.getToken() == token) {
                        println("Refresh failed; clearing the rejected token")
                        authStore.clear()
                    } else {
                        println("Backend rejected a stale token; a newer token is already stored, leaving it")
                    }
                    UploadResult.Unauthenticated
                } else {
                    postCall(payload, refreshed)
                }
            }
            else -> first
        }
    }

    private fun postCall(payload: PendingUpload, token: String): UploadResult {
        return try {
            val jsonBody = JSONObject().apply {
                put("contactName", payload.contactName)
                put("date", payload.date)
                put("transcript", payload.transcript)
                put("callerNumber", payload.callerNumber ?: JSONObject.NULL)
            }

            val request = Request.Builder()
                .url("$baseUrl/api/calls")
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadResult.Success
                    response.code == 401 -> UploadResult.Unauthenticated
                    response.code in NON_RETRYABLE_CODES -> {
                        Log.e(TAG, "Backend rejected upload with non-retryable HTTP ${response.code}: ${payload.contactName}")
                        UploadResult.Rejected
                    }
                    else -> {
                        println("FAILED to send to backend. Code: ${response.code}")
                        UploadResult.Transient
                    }
                }
            }
        } catch (e: Exception) {
            println("FAILED to connect to backend: ${e.message}")
            UploadResult.Transient
        }
    }
```

- [ ] **Step 6: Update the existing test helper**

In `android/app/src/test/java/com/brachaai/app/AudioProcessorTest.kt`, the helper at lines 32-38 builds an `AudioProcessor`. Add the two new arguments to that call:

```kotlin
            tokenRefresher = TokenRefresher(authStore),
            baseUrl = BackendConfig.BASE_URL,
```

The existing tests exercise `queuedTranscriptIsDurable`, which never reaches the network, so no other change is needed.

- [ ] **Step 7: Run the full native suite**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS — including the 5 new `AudioProcessorUploadTest` cases and all pre-existing tests

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/AudioProcessor.kt android/app/src/test/java/com/brachaai/app/AudioProcessorTest.kt android/app/src/test/java/com/brachaai/app/AudioProcessorUploadTest.kt
git commit -m "feat(auth): refresh and retry once before failing a background upload"
```

---

### Task 12: Bridge and wiring

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/NativeBridge.kt:24-34`
- Modify: `android/app/src/test/java/com/brachaai/app/NativeBridgeTest.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/MainActivity.kt` (the `AudioProcessor` construction site)
- Modify: `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt` (the `AudioProcessor` construction site)

**Interfaces:**
- Consumes: `AuthStore.setTokens` (Task 9), `TokenRefresher` (Task 10), `AudioProcessor` constructor (Task 11)
- Produces: `NativeBridge.setAuth(token: String?, refreshToken: String?)`

- [ ] **Step 1: Update the bridge**

In `android/app/src/main/java/com/brachaai/app/NativeBridge.kt`, replace `setAuth` (lines 24-34) with:

```kotlin
    /**
     * Receives the *native* token pair, minted by the web app via /auth/device-token.
     * Native deliberately holds its own pair rather than the web session's: refresh tokens
     * rotate per client, so sharing one would have the two clients invalidate each other.
     */
    @JavascriptInterface
    fun setAuth(token: String?, refreshToken: String?) {
        if (token.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            authStore.clear()
            Log.d(TAG, "setAuth called with an incomplete pair; cleared")
            return
        }
        authStore.setTokens(token, refreshToken)
        Log.d(TAG, "Auth tokens stored from WebView")
        onAuthenticated()
    }
```

- [ ] **Step 2: Update the bridge test**

In `android/app/src/test/java/com/brachaai/app/NativeBridgeTest.kt`, update every `setAuth(...)` call to pass both arguments. A call that previously asserted the clear-on-empty behaviour becomes:

```kotlin
        bridge.setAuth(null, null)
```

and a call that stored a token becomes:

```kotlin
        bridge.setAuth("access-token", "refresh-token")
```

- [ ] **Step 3: Update the construction sites**

Both `MainActivity.kt` and `CallMonitorService.kt` construct an `AudioProcessor`. Find them:

Run: `cd android && grep -rn "AudioProcessor(" app/src/main/java/`

At each site, an `AuthStore` is already in scope (or constructed). Add the refresher argument, reusing that same store instance:

```kotlin
                tokenRefresher = TokenRefresher(authStore),
```

`baseUrl` takes its default and needs no argument.

- [ ] **Step 4: Build and run the full native suite**

Run: `cd android && ./gradlew assembleDebug testDebugUnitTest`
Expected: APK builds; all unit tests pass

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/NativeBridge.kt android/app/src/test/java/com/brachaai/app/NativeBridgeTest.kt android/app/src/main/java/com/brachaai/app/MainActivity.kt android/app/src/main/java/com/brachaai/app/CallMonitorService.kt
git commit -m "feat(auth): accept the native token pair over the JS bridge"
```

---

### Task 13: Rebuild the shipped web bundle

`android/app/src/main/assets/www/` is a checked-in build artifact. If it is not regenerated, the app ships the previous bundle — which calls the old single-argument `setAuth` and never gives native a refresh token, so background uploads die 15 minutes after login.

**Files:**
- Modify: `android/app/src/main/assets/www/**` (generated — never hand-edited)

- [ ] **Step 1: Build the frontend**

Run: `cd frontend && npm run build`
Expected: `frontend/dist/` is written with no errors

- [ ] **Step 2: Copy the bundle over the checked-in assets**

Run from the repo root:

```bash
rm -rf android/app/src/main/assets/www
mkdir -p android/app/src/main/assets/www
cp -r frontend/dist/. android/app/src/main/assets/www/
```

- [ ] **Step 3: Confirm the bundle contains the two-argument call**

Run: `grep -rc "device-token" android/app/src/main/assets/www/assets/*.js`
Expected: at least one match — proof the rebuilt bundle is the new code, not the previous artifact

- [ ] **Step 4: Build the APK against the new assets**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/assets/www
git commit -m "chore: rebuild Android WebView bundle with refresh token support"
```

---

### Task 14: Deploy and verify

The backend must be running the new code **before** the rebuilt app reaches a device. Until `/api/auth/refresh` exists, the app treats every refresh as a failure, which means a logout — the exact opposite of this feature.

**Files:** none (deployment only)

- [ ] **Step 1: Push the branch**

```bash
git push origin login-remember
```

- [ ] **Step 2: Open a session on the server**

The user runs this — the password is not to be placed in any command, shell history, or script.

```bash
ssh cs109@10.10.248.154
```

- [ ] **Step 3: Discover the deployment layout**

The repo path and process manager on that box are not recorded anywhere in this repository, so read them rather than assuming:

```bash
pm2 list; systemctl --user list-units --type=service | grep -i bracha; ls ~
```

Note the repo directory and how the backend process is supervised before continuing.

- [ ] **Step 4: Update the checkout**

From the repo directory found in Step 3:

```bash
git checkout master
git pull
git checkout login-remember
git pull origin login-remember
```

- [ ] **Step 5: Install, build and restart**

```bash
cd backend
npm install
npm run build
```

Then restart using the supervisor identified in Step 3 — `pm2 restart <name>` or `systemctl --user restart <unit>`.

- [ ] **Step 6: Verify the endpoint is live**

From any machine:

```bash
curl -i -X POST http://193.106.55.154:3000/api/auth/refresh -H "Content-Type: application/json" -d '{"refreshToken":"definitely-not-valid"}'
```

Expected: `HTTP/1.1 401` with `{"message":"Invalid refresh token"}`.
A `404` means the new code is not running — stop and fix before installing the app.

- [ ] **Step 7: Verify login still works end to end**

```bash
curl -s -X POST http://193.106.55.154:3000/api/auth/login -H "Content-Type: application/json" -d '{"email":"<a real account>","password":"<its password>"}'
```

Expected: JSON containing both `token` and `refreshToken`.

- [ ] **Step 8: Manual device verification**

Install the APK from Task 13, then confirm:

1. Log in → lands on home.
2. Force-stop the app, reopen → **lands on home, no login prompt**. (This is the reported bug.)
3. Wait more than 15 minutes, reopen and navigate → still no login prompt; the access token refreshed silently.
4. Record a call and confirm the transcript reaches the backend without opening the app.
5. Log out from Settings → reopening shows the login screen.

- [ ] **Step 9: Rotate the SSH password**

It was shared in a chat transcript, on a box holding `JWT_SECRET` and the user database. Rotate it, and consider installing an SSH key so future deploys need no password at all.

---

## Notes for the implementer

**Existing users are logged out once.** They hold a 7-day access token and no refresh token. When that token expires the refresh attempt finds nothing to trade in and they re-authenticate. This is covered by the "upgraded install" test in Task 7 and is expected — mention it in release notes rather than treating it as a bug.

**Do not push the web token to native.** The two clients hold independent pairs by design. Task 8 Step 9 removes the boot-time `setAuth` call in `App.tsx` for exactly this reason; re-adding it would overwrite native's credentials with ones that rotate separately.

**`src/services/api.ts` reads a different storage key and is out of scope.** Line 132 does
`localStorage.getItem('authToken')`, but nothing in the app ever writes `authToken` — the key
is `token` everywhere else. Any request routed through that file is therefore already
unauthenticated today. It is a pre-existing bug unrelated to login persistence: do **not**
fix it as part of this plan, and do not "align" it by changing the key here, since that would
silently start sending credentials on paths that currently send none. It is tracked separately.

**The backend is plain HTTP.** A stolen refresh token is usable for 90 days over an unencrypted connection. Mitigations in place: hash-only storage, rotation on every use, per-client revocation, `EncryptedSharedPreferences` at rest. HTTPS is the real fix and is recommended as a follow-up — see the Risks section of the design doc.
