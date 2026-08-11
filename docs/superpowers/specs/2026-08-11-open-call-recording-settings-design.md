# Open the phone's call-recording settings from the Settings page

Date: 2026-08-11

## Problem

The "Automatic Call Recording" row on the Settings page is a switch that does nothing.
Flipping it writes `autoCallRecording` to the user's record in Mongo
(`frontend/src/pages/SettingsPage/SettingsPage.tsx:133` → `PUT /auth/profile`), and no
consumer ever reads it back. Grep finds the field only in `backend/src/models/User.ts`,
`backend/src/controllers/authController.ts`, the seed data, and the frontend. The Android
side does not know it exists.

BrachaAI does not record calls. The phone's own dialer app does, writing files into
`/storage/emulated/0/Recordings/Call`, which `CallMonitorService` watches with a
`FileObserver` (`android/app/src/main/java/com/brachaai/app/CallMonitorService.kt:442`).
Automatic call recording can therefore only be turned on in the dialer's own settings —
somewhere the app has no control over.

So the switch is worse than useless: it implies the app can start recording, and a user
who turns it on and gets no transcripts has been actively misled.

## Solution

Replace the switch with a link row. Tapping it sends the user to the dialer's
call-recording settings screen.

Android publishes no intent for call-recording settings — it is a private screen inside
the Google Phone app or the OEM equivalent, with no documented action string. The deep
link is therefore best-effort against known activity names, backed by a fallback chain
that always lands somewhere useful.

## Design

### Native: `CallRecordingSettings.kt`

Split along the same seam as `OverlayDecider`: pure decision logic that itself uses no
Android types, plus a thin Android launcher, both in one file. Kotlin imports are
file-scoped, so the file as a whole does import `android.*` (nine types, for the
launcher) — the property that holds is narrower: every branch of the resolver is
testable on the plain JVM, no device required.

**`CallRecordingTarget`** — a sealed class of destinations:

| Target | Becomes |
| --- | --- |
| `SettingsAction(action)` | An implicit `Intent(action)` |
| `DialerSettings(pkg, cls)` | An explicit `Intent` on that `ComponentName` |
| `DialerApp(pkg)` | `packageManager.getLaunchIntentForPackage(pkg)` |
| `SystemSettings` | `Settings.ACTION_SETTINGS` |

**`CallRecordingSettingsResolver.targetsFor(defaultDialerPackage, discoveredSettingsActivities)`**
— pure, unit-testable, returns the ordered list to try:

0. **Known intent actions**, device-wide. Currently one:
   `com.samsung.android.app.telephonyui.action.OPEN_CALL_SETTINGS`, verified on a Galaxy S25
   to land on the screen holding "Record calls". Actions rank first because an action carries
   an intent filter — it is exported by construction and survives class renames, which is
   everything a component name is not. Deliberately *not* keyed to the dialer package: on One
   UI the screen belongs to the telephony UI, and a Samsung phone running Google's dialer as
   default should still reach it.
1. **Discovered** settings activities, in the order the package manager reported them.
   These are exported activities the launcher found inside the dialer package itself, so
   they describe *this* device rather than someone else's.
2. **Known** class names for that package, as a backstop for when discovery is blocked or
   empty. Emitted only for the package that is actually the default dialer, so a Samsung
   phone is never asked to open a Google class:
   - `com.google.android.dialer` → `com.android.dialer.main.impl.settings.DialerSettingsActivity`,
     `com.android.dialer.app.settings.DialerSettingsActivity`,
     `com.android.dialer.settings.DialerSettingsActivity`.
   - `com.android.dialer` (AOSP) → the last two of those.
   - `com.samsung.android.dialer` → `com.samsung.android.dialer.settings.DialerSettingsActivity`.
3. `DialerApp(pkg)`.
4. `SystemSettings`.

Class names appearing in both 1 and 2 are de-duplicated, and blank ones dropped. A null or
blank package yields `listOf(SystemSettings)` alone.

**What the device actually showed, and what it cost.** The first version guessed class names
and fell back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`. On a Galaxy S25 the guess
missed and app-info — always resolvable — won every time, stranding the user on a page with
no route to call recording. `dumpsys package com.samsung.android.dialer` then showed why the
guess could never have worked: **the Samsung dialer contains no settings activity at all.**
Its only `.setting.` component is a broadcast receiver. The screen is in
`com.samsung.android.app.telephonyui`, a different package entirely, reachable by the action
above. The original design's premise — that a dialer's settings live inside the dialer — is
simply false on One UI.

Hence the two structural changes: an action tier above the class names, and app-info deleted
in favour of the dialer's own launcher screen, which is one overflow menu from the setting
rather than a dead end.

**`CallRecordingSettingsLauncher`** — the Android half. Reads
`TelecomManager.getDefaultDialerPackage()`, enumerates that package's activities via
`getPackageInfo(pkg, GET_ACTIVITIES)`, keeps the **exported** ones whose class name contains
"settings", and hands those to the resolver. Non-exported activities are filtered out because
another app cannot start them at all — offering one guarantees a `SecurityException` later.
Enumeration failure degrades to an empty list and the known-names backstop. It then walks the
target list and starts the first that works, with `FLAG_ACTIVITY_NEW_TASK` since the bridge
holds an application context.

The `packageManager.resolveActivity` check runs **only for `DialerSettings` targets** — those
are the undocumented activities that may genuinely not exist. `DialerApp` comes from
`getLaunchIntentForPackage`, already resolved by construction, and `SystemSettings` is a
documented system action. Gating those would put the chain's one guarantee — that the last
target always exists — behind a runtime check that could veto it, and a vetoed last target
means the loop exhausts and the row does nothing at all, for exactly the users whose dialer
deep link already missed. The loop's `try/catch` covers a refused start either way.

Alongside the launch it posts a toast naming the manual path — "Phone app: tap ⋮ (top right)
→ Settings → Record calls" — shown after every successful start, since even a direct hit on
the dialer's settings screen leaves the user to find "Record calls". The wording starts from
the dialer's home screen because that is the most common landing. These are the Phone app's
own labels, reported from a device; an OEM whose menu reads differently needs this string
changed, not the navigation logic. The toast is posted to `Looper.getMainLooper()`: JavaScript
bridge calls arrive on the WebView's JavaBridge thread, where a bare `Toast.show` throws.

### Manifest

Android 11+ package visibility hides other packages from us, and an explicit component in
an invisible package fails to resolve. Add a `<queries>` block declaring an
`android.intent.action.DIAL` intent filter, which grants visibility to dialer apps
specifically. This is narrower than `QUERY_ALL_PACKAGES` and does not trigger the Play
policy declaration that permission requires.

### Bridge contract

`NativeBridge` gains one method:

```kotlin
@JavascriptInterface
fun openCallRecordingSettings()
```

No arguments, no return value. The whole body is wrapped so nothing can throw back into
JavaScript; the resolve-before-launch loop already covers the missing-activity case, and
the wrapper covers a hostile OEM refusing the start outright. A failure logs and does
nothing visible beyond the toast.

`frontend/src/types/native.d.ts` declares it optional
(`openCallRecordingSettings?(): void`). The method's *presence* is the feature detection,
which means an older APK running a newer web bundle hides the row rather than calling
into a method that isn't there.

### The Settings row

In `frontend/src/pages/SettingsPage/SettingsPage.tsx`:

- Add `callRecordingSupported` state, probed in the `useEffect` that already inspects the
  bridge at line 24, using the same feature-detect shape as
  `getDeleteAudioAfterProcessing`.
- The row renders only when `callRecordingSupported` is true. In a plain browser there is
  no dialer to open, so the row is absent entirely — the pattern
  `audioSettingSupported` already establishes at line 138.
- The row becomes a `<button>`, not a `<div>` with an `onClick`. It is an actionable
  control, so it must be keyboard-reachable and announced as one. A new `.settingButton`
  class in the CSS module resets button defaults (background, border, font, full width,
  text alignment) and composes with `.settingItem`, so the row is visually identical to
  its neighbours.
- The `<label className={styles.switch}>` block is replaced by a right-pointing chevron
  SVG, matching the stroke weight of the existing row icons.
- The description changes from "Record calls automatically" to "Enable it in your Phone
  app settings" — the row's job is now to explain where the real switch lives.
- `onClick` calls `window.BrachaNative?.openCallRecordingSettings?.()`.

Deletions, all now dead: the `autoCallRecording` state (lines 12, 19, 32) and
`handleToggle` (lines 31–48). `handleToggle` had no caller other than the switch being
removed.

### What is deliberately left alone

`autoCallRecording` stays in the Mongo schema, in `authController`'s default settings
object, and in `data/brachaai.users.json`. It is already read by nothing, so removing it
buys nothing, and editing the seed file risks the users collection on a reseed.

## Testing

**`CallRecordingSettingsResolverTest`** (new JVM unit test) — the resolver is pure, so
every branch is testable without a device:

- Google Phone package yields both Google classes, in order, then app-info, then system
  settings.
- Samsung package yields the Samsung class, then app-info, then system settings, and
  never a Google class.
- An unrecognised package yields app-info then system settings.
- A null package yields system settings alone.

**`SettingsPage.test.tsx`** (new, vitest) — the page has no test file today:

- No bridge on `window` → the row is absent.
- Bridge present but without `openCallRecordingSettings` → the row is absent.
- Bridge with the method → the row renders, and clicking it calls the method exactly
  once.
- The old switch is gone: no checkbox is rendered for automatic call recording, and no
  `PUT /auth/profile` fires from this row.

**Verification limits, stated plainly:**

- The Kotlin will be written but not compiled or run. Gradle cannot execute in this
  environment, and no CI job invokes it — the resolver test compiles nowhere until an APK
  is built by hand.
- The deep-link target is an undocumented activity. The only real proof it opens the
  right screen is tapping the row on a physical phone. Expect to confirm it per device
  family.

**Assets:** `android/app/src/main/assets/www/` is a checked-in build artifact, not source.
The frontend must be rebuilt and `dist/` copied over it in the same change, or the APK
ships the previous bundle and none of the UI work appears.

## Manual verification

On a physical Android phone with the Google Phone app as the default dialer:

1. Open Settings in BrachaAI. The "Automatic Call Recording" row shows a chevron, not a
   switch.
2. Tap it. The Phone app's settings screen opens, and a toast names the path to call
   recording.
3. Enable call recording there, return to BrachaAI, place a call, and confirm a file
   lands in `/storage/emulated/0/Recordings/Call` and is transcribed.
