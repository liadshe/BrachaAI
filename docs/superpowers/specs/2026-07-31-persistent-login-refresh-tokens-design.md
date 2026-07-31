# Persistent Login (Refresh Tokens) — Design

**Date:** 2026-07-31
**Branch:** `login-remember`

## Problem

Opening the app always lands on the login screen, so the user re-enters credentials every
launch.

The cause is **not** token storage. The JWT is written to `localStorage` at
`LoginPage.tsx:26`, `domStorageEnabled` is true, nothing native wipes WebView storage, and
the token is valid for 7 days. The token survives; the app never looks at it.

`App.tsx:27` routes `/` to `LoginPage` unconditionally:

```tsx
<Route path="/" element={<LoginPage />} />
```

The WebView loads `file:///android_asset/www/index.html` with no hash, so `HashRouter`
resolves to `/` and renders the login page regardless of auth state. `ProtectedRoute` does
check for a token, but it only guards `/home`, `/contacts`, `/tasks`, `/settings`,
`/edit-profile` — never the landing route.

## Goal

1. Launching the app with a valid session goes straight to the home screen.
2. The session stays valid indefinitely with continued use, rather than expiring after 7
   days.
3. Background transcript uploads keep working across that whole period, without the user
   opening the app.

## Non-goals

- Migrating the backend to HTTPS (see Risks — real, pre-existing, out of scope here).
- Biometric or PIN re-authentication on launch.
- "Remember me" as a user-facing toggle. Persistent login is the only behaviour.
- Refresh-token reuse detection / breach response beyond rejecting unknown tokens.
- Deduplicating the backend base URL beyond the one native constant this work requires.

## Key constraint: two clients, one login

`AudioProcessor` uploads transcripts from `CallMonitorService` at arbitrary times — hours or
days after the app was last opened — using the token in `AuthStore`
(`AudioProcessor.kt:275`). Today that works because the token lives 7 days.

A textbook refresh-token design uses a ~15 minute access token. Applied naively here, the
background uploader's token would be expired on nearly every fire: every upload would 401
and divert into `PendingUploadStore`. That is a **regression** from current behaviour.

Therefore native must be able to refresh on its own, not merely hold a token. This is the
single constraint that shapes most of the design below.

A second consequence: two clients sharing one rotating, single-use refresh token race each
other — native refreshes, the web copy is silently dead, and the user is logged out for no
reason. This is solved structurally, with per-client tokens, rather than with retry logic.

## Design

### 1. Root route (the actual bug fix)

New `AuthLanding` component at `/`:

- token in `localStorage` → `<Navigate to="/home" replace />`
- otherwise → `<Navigate to="/login" replace />`

`ProtectedRoute` keeps its presence-only check. Token *expiry* is handled by the 401 →
refresh path (§3), not by route guards — guards must stay synchronous and cheap.

This change alone fixes the reported problem. Sections 2–4 are what keep it fixed past day 7.

### 2. Backend: refresh tokens

#### New `RefreshToken` collection

A separate collection rather than a field on `User`, because it needs a TTL index and
per-client rows.

| field | type | notes |
| --- | --- | --- |
| `userId` | ObjectId | indexed, ref `User` |
| `tokenHash` | String | SHA-256 of the raw token |
| `client` | String | `'web'` \| `'native'` |
| `expiresAt` | Date | TTL index — Mongo reaps expired rows |
| `createdAt` | Date | |

**Unique compound index on `(userId, client)`.** This is what makes per-client rotation
work: a web refresh upserts only the web row and cannot invalidate native's. It also caps
storage at two rows per user.

**Only the hash is stored.** A database leak must not hand over live sessions. The raw token
is returned to the client once and never persisted server-side.

The refresh token is `crypto.randomBytes(32).toString('hex')` — an opaque handle, not a JWT.
Opaque means it is revocable by deleting the row; a self-validating JWT would not be.

#### Lifetimes

- Access token: `expiresIn: '15m'` (was `'7d'`)
- Refresh token: 90 days

#### Endpoints

| route | auth | body | returns |
| --- | --- | --- | --- |
| `POST /api/auth/refresh` | none | `{ refreshToken, client }` | `{ token, refreshToken }` |
| `POST /api/auth/logout` | none | `{ refreshToken }` | `204` |
| `POST /api/auth/device-token` | `protect` | — | `{ token, refreshToken }` |
| `POST /api/auth/login` | none | `+ client` | `{ token, refreshToken, user }` |
| `POST /api/auth/signup` | none | `+ client` | `{ token, refreshToken, user }` |

`refresh` verifies the hash, checks `expiresAt`, then **rotates**: issues a new refresh token
for that client and replaces the row atomically. An unknown or expired token is a plain 401 —
per-client tokens remove the race that would otherwise make an unknown token ambiguous, so no
reuse-detection machinery is warranted.

`logout` deletes only the presented client's row, so signing out of the web session does not
kill the background uploader mid-transcript.

`device-token` exists because the web app is the only thing that can perform a login; it
provisions native's credentials on native's behalf.

`login`/`signup` default `client` to `'web'` when absent.

### 3. Frontend (WebView)

- **After login/signup:** store `token` + `refreshToken` in `localStorage`, then call
  `device-token` and push that separate pair to native via `BrachaNative.setAuth(...)`.
- **`apiClient` 401 interceptor stops being a logout.** It now refreshes once, retries the
  original request, and logs out only if the *refresh itself* fails. Logging out on a mere
  expired access token is exactly the behaviour being removed.
  - **Single-flight guard:** concurrent 401s share one in-flight refresh promise; they must
    not fire N refreshes, because rotation would invalidate each other's result.
  - A 401 from the refresh endpoint itself must not recurse into the interceptor.
- **Logout** (`SettingsPage.tsx:226`) calls `POST /api/auth/logout` before clearing
  `localStorage` and calling `BrachaNative.clearAuth()`.
- The existing hash-redirect (`window.location.hash = '#/login'`) is preserved on the real
  logout path — the `file://`-origin comment at `apiClient.ts:26` still applies.

### 4. Native (Kotlin)

- **`AuthStore`** gains `setTokens(access, refresh)` and `getRefreshToken()`. Both values live
  in `EncryptedSharedPreferences`. `clear()` removes both. `hasEverAuthenticated()` and its
  plain-prefs history flag are unchanged.
- **`TokenRefresher.kt` (new)** — one job: POST the native refresh token to `/api/auth/refresh`,
  persist the returned pair, return the new access token. Single-flight (synchronized), so
  parallel uploads flushing the pending queue cannot stampede the endpoint and rotate each
  other out.
- **`AudioProcessor.attemptUpload`** — on 401, ask `TokenRefresher` for a fresh token and
  retry **once**. Only when refresh fails does it clear the token and return
  `UploadResult.Unauthenticated`. The existing compare-and-clear guard (`AudioProcessor.kt:303`)
  stays on that failure path — it protects against wiping a token a racing login just stored.
- **`NativeBridge.setAuth(token, refreshToken)`** — signature change. Empty/blank access token
  still means "clear".
- **Base URL constant.** `http://193.106.55.154:3000` is currently hardcoded in
  `AudioProcessor`; `TokenRefresher` would make a third copy. Extracted to one constant. This
  is a targeted cleanup in code being touched, not general refactoring — the duplication is
  already flagged in `android/CLAUDE.md`.

## Verification

Frontend has `vitest` + `@testing-library/react`; backend has `vitest`; native has JVM unit
tests. All three layers are testable.

**Backend**
- refresh happy path returns a new pair and rotates the stored row
- expired refresh token → 401
- unknown / already-rotated refresh token → 401
- **web refresh does not invalidate the native row** (the per-client isolation guarantee)
- `logout` deletes only the presented client's row
- `device-token` requires a valid access token

**Frontend**
- `AuthLanding` redirects to `/home` with a token, `/login` without
- 401 → refresh → original request retried and succeeds
- refresh failure → tokens cleared, redirected to `#/login`
- concurrent 401s trigger exactly one refresh call

**Native**
- `TokenRefresher` persists the rotated pair; concurrent callers produce one HTTP call
- `AudioProcessor` 401 → refresh → retry → success
- `AudioProcessor` 401 → refresh fails → token cleared, `Unauthenticated`
- `EncryptedSharedPreferences` cannot be exercised on the JVM (see the note at
  `AuthStore.kt:100`); `TokenRefresher` tests follow the same seeding workaround already used
  by `AuthStoreTest`.

**Manual**
- Log in, force-stop the app, reopen → lands on home, no login prompt.
- Log in, wait past access-token expiry, reopen → still no login prompt.
- Log out → reopening shows login.

## Risks

**Rollout order is load-bearing.**
1. The backend must be deployed **before** the app ships, or every refresh call 404s and users
   are logged out.
2. `android/app/src/main/assets/www/` is a checked-in build artifact. If the bundle is not
   rebuilt in lockstep with the `setAuth` signature change, native silently stops receiving
   refresh tokens and background uploads die after 15 minutes. Rebuild per `android/CLAUDE.md`.

**Existing users are logged out once.** They hold a 7-day access token and no refresh token.
When that access token expires, the refresh attempt finds nothing to trade in and they
re-authenticate. One-time, unavoidable, worth noting in release notes.

**Longer credential exposure over cleartext HTTP.** The backend is plain HTTP
(`usesCleartextTraffic=true`). Today a stolen token is usable for 7 days; afterwards a stolen
*refresh* token is usable for 90 — over an unencrypted connection. This design improves
convenience and lengthens that exposure window simultaneously. Accepted knowingly. Mitigations
in place: only hashes stored server-side, rotation on every use, per-client revocation,
`EncryptedSharedPreferences` at rest on device. **Migrating to HTTPS is the real fix and is
recommended as a follow-up.**

**15-minute access tokens increase request volume** on the refresh endpoint for the background
service. Bounded by the single-flight guard and by how often recordings appear.
