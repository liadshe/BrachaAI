# Deploy Runbook — Call Ownership Fix

**Branch:** `ben-test-run` (pushed, at `5f233fa`)
**Server:** `193.106.55.154`

## Order is load-bearing

**Install the APK on the phone BEFORE deploying the backend.**

The deploy makes `POST /api/calls` reject unauthenticated uploads. The currently
installed app has no token and no retry queue, so any call it records after the
deploy is 401'd and lost outright. Reversing the order costs recordings.

If you have more than one device running this app, every one of them needs the
new APK first.

---

## Step 1 — Phone (do this first)

Connect the phone with USB debugging on, then from the repo root:

```bash
cd android && ./gradlew installDebug
```

The APK is ~91 MB (FFmpeg). If the install fails for space, free some on the
device — do not uninstall to make room unless you are willing to lose the
logged-in session.

Open the app and grant permissions. You will now get **two** prompts: the
existing ones, plus call-log access. Call-log access is optional — denying it
only costs caller phone numbers, it no longer blocks the app.

Confirm the token reached native:

```bash
adb logcat -s AuthBridge
```

Expect `Auth token stored from WebView`. This fires on app start from the
`App.tsx` sync effect, so you should not need to log out and back in. If it does
not appear, stop — the deploy will lock you out of uploads.

---

## Step 2 — Server

SSH to `193.106.55.154`, then in the repo checkout (currently on `master`):

```bash
git fetch origin
git checkout ben-test-run
git pull origin ben-test-run
```

**Before bringing it up, check `devops/.env` still exists and `JWT_SECRET` is
unchanged.** That file is untracked, so switching branches leaves it alone — but
verify. A different `JWT_SECRET` invalidates every token already issued: every
device 401s at once and queues everything until each user logs in again.

```bash
cd devops
docker compose up -d --build backend
```

`docker-compose.yaml` requires `JWT_SECRET` to be set and will refuse to start
without it. `API` (the OpenAI key) is also read from that file.

---

## Step 3 — Verify the deploy took

From the server:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:3000/api/calls \
  -H 'Content-Type: application/json' -d '{}'
```

Expect **401**. Before this change the same request returned 200 — that is the
whole bug in one line. Anything other than 401 means the old code is still
running.

Also confirm startup:

```bash
docker logs bracha-backend --tail 20
```

Expect `🍃 Connected to MongoDB Successfully`.

---

## Step 4 — The real test

Make a short call on the phone, let it record, then on the server:

```bash
docker logs bracha-backend --tail 100 | grep DEBUG
```

You want two lines whose IDs **match**:

```
[DEBUG] Android call webhook for userId: X
[DEBUG] Fetching calls for userId: Y
```

`X == Y` is the fix. `X != Y` is the original bug.

Then open the app: the call should appear with the correct contact name, a real
phone number instead of `000-000-000`, and a summary (briefly showing
`Summary pending analysis...` until the analysis lands).

---

## Step 5 — The queue

Log out, record a short call, and check:

```bash
adb logcat -s PendingUploadStore
```

Expect `Queued upload …; queue size = 1`. Log back in; expect `Flushed …` and
the call appearing in the app.

---

## If something is wrong

Roll the server back without touching the phone:

```bash
git checkout master
cd devops && docker compose up -d --build backend
```

The old backend accepts unauthenticated uploads, so the new APK keeps working
against it — calls just get mis-filed under the seeded user again, as before.
That direction is safe; the forward direction is not.

Known limitations that are **not** bugs to chase are recorded in
`docs/superpowers/specs/2026-07-25-call-ownership-known-limitations.md` — read
that before investigating anything surprising.
