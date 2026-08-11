# Open Call Recording Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dead "Automatic Call Recording" switch on the Settings page with a row that opens the phone's own dialer call-recording setting.

**Architecture:** A pure Kotlin resolver decides the ordered list of places to send the user (dialer settings activity → dialer app-info → system Settings); a thin Android launcher walks that list, checking `resolveActivity` before each `startActivity` so a missing activity is a skip rather than a crash. `NativeBridge` exposes one new no-arg method, and the web Settings page feature-detects it to decide whether to render the row at all.

**Tech Stack:** Kotlin / Android (minSdk 26, targetSdk 36), JUnit 4 for the pure unit test, React + TypeScript + vitest + @testing-library/react for the web frontend.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-11-open-call-recording-settings-design.md`.
- The Kotlin in this plan **cannot be compiled or run** in this environment. Gradle fails locally and no CI job invokes it. Do not claim any Kotlin task is verified; the Gradle commands are recorded for whoever builds the APK.
- `android/app/src/main/assets/www/` is a checked-in build artifact of `frontend/`, not source. Task 4 regenerates it; skipping Task 4 ships the old bundle and none of the UI change appears in the APK.
- Do not touch `autoCallRecording` in `backend/src/models/User.ts`, `backend/src/controllers/authController.ts`, or `data/brachaai.users.json`. The field stays; only the frontend stops writing it.
- Frontend lint runs with `--max-warnings 0`, so an unused import fails the build. Removing `handleToggle` makes the `apiClient` import in `SettingsPage.tsx` unused — it must be removed in the same task.
- Copy strings, verbatim: row title `Automatic Call Recording`, row description `Enable it in your Phone app settings`, toast text `Open Settings → Call recording in your Phone app`.

---

## File Structure

**Create:**
- `android/app/src/main/java/com/brachaai/app/CallRecordingSettings.kt` — the sealed target type, the pure resolver, and the Android launcher. One file for all three, matching how `CallDirection.kt` holds `CallDirectionStore` + `CallDirectionTracker`.
- `android/app/src/test/java/com/brachaai/app/CallRecordingSettingsResolverTest.kt` — pure JUnit 4, no Robolectric needed.
- `frontend/src/pages/SettingsPage/SettingsPage.test.tsx` — the page has no test file today.

**Modify:**
- `android/app/src/main/AndroidManifest.xml:18-20` — add a `<queries>` block between the last `<uses-permission>` and `<application>`.
- `android/app/src/main/java/com/brachaai/app/NativeBridge.kt:22,87-94` — one field, one `@JavascriptInterface` method.
- `frontend/src/types/native.d.ts:21` — one optional method on the bridge interface.
- `frontend/src/pages/SettingsPage/SettingsPage.tsx:3,12,19,31-48,115-137` — remove dead state and handler, convert the row to a button.
- `frontend/src/pages/SettingsPage/SettingsPage.module.css:167` — add `.settingButton`.
- `android/app/src/main/assets/www/` — regenerated wholesale in Task 4.

---

### Task 1: The pure resolver

The only part of the native work that can be tested at all, so it carries all the decision logic. It must not import anything from `android.*`.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/CallRecordingSettings.kt`
- Test: `android/app/src/test/java/com/brachaai/app/CallRecordingSettingsResolverTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `sealed class CallRecordingTarget` with `data class DialerSettings(val packageName: String, val className: String)`, `data class AppInfo(val packageName: String)`, `object SystemSettings`; and `object CallRecordingSettingsResolver` with `fun targetsFor(defaultDialerPackage: String?): List<CallRecordingTarget>`. Task 2 consumes both.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/CallRecordingSettingsResolverTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resolver exists because Android publishes no intent for call-recording settings, so
 * the app guesses at undocumented activity names. The guesses must be scoped to the dialer
 * that is actually installed, and the list must always end somewhere that exists.
 */
class CallRecordingSettingsResolverTest {

    private val google = "com.google.android.dialer"
    private val samsung = "com.samsung.android.dialer"

    @Test
    fun `google dialer tries both known settings activities before any fallback`() {
        assertEquals(
            listOf(
                CallRecordingTarget.DialerSettings(
                    google,
                    "com.android.dialer.main.impl.settings.DialerSettingsActivity"
                ),
                CallRecordingTarget.DialerSettings(
                    google,
                    "com.android.dialer.settings.DialerSettingsActivity"
                ),
                CallRecordingTarget.AppInfo(google),
                CallRecordingTarget.SystemSettings,
            ),
            CallRecordingSettingsResolver.targetsFor(google)
        )
    }

    @Test
    fun `a samsung phone is never handed a google class name`() {
        val targets = CallRecordingSettingsResolver.targetsFor(samsung)

        assertEquals(
            listOf(
                CallRecordingTarget.DialerSettings(
                    samsung,
                    "com.samsung.android.dialer.settings.DialerSettingsActivity"
                ),
                CallRecordingTarget.AppInfo(samsung),
                CallRecordingTarget.SystemSettings,
            ),
            targets
        )
    }

    @Test
    fun `an unrecognised dialer skips straight to app info`() {
        assertEquals(
            listOf(
                CallRecordingTarget.AppInfo("com.oem.unknown.dialer"),
                CallRecordingTarget.SystemSettings,
            ),
            CallRecordingSettingsResolver.targetsFor("com.oem.unknown.dialer")
        )
    }

    @Test
    fun `no default dialer leaves only the system settings screen`() {
        assertEquals(
            listOf(CallRecordingTarget.SystemSettings),
            CallRecordingSettingsResolver.targetsFor(null)
        )
    }

    @Test
    fun `a blank package name is treated as no dialer, not as a package named empty`() {
        assertEquals(
            listOf(CallRecordingTarget.SystemSettings),
            CallRecordingSettingsResolver.targetsFor("   ")
        )
    }

    @Test
    fun `every list ends at a target that always exists`() {
        listOf(null, "", google, samsung, "com.oem.unknown.dialer").forEach { pkg ->
            assertEquals(
                "Last resort missing for $pkg",
                CallRecordingTarget.SystemSettings,
                CallRecordingSettingsResolver.targetsFor(pkg).last()
            )
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.CallRecordingSettingsResolverTest"`
Expected: FAIL — compilation error, `CallRecordingTarget` and `CallRecordingSettingsResolver` are unresolved references.

**If Gradle does not run here, that is expected** (see Global Constraints). Record that the test was not executed. Do not claim it passed.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/CallRecordingSettings.kt` with the target type and resolver only — the launcher is Task 2:

```kotlin
package com.brachaai.app

/**
 * Where to send a user who wants automatic call recording turned on.
 *
 * BrachaAI does not record calls: the phone's dialer does, into the directory
 * [CallMonitorService.WATCH_PATH] watches. Android publishes no intent for that setting —
 * it is a private screen inside whichever dialer the phone ships with — so the only way in
 * is an explicit, undocumented component name that may be renamed or simply absent.
 * Everything after the first target is therefore a fallback, and the last one always exists.
 */
sealed class CallRecordingTarget {
    /** An undocumented settings activity inside the dialer. May not exist on this device. */
    data class DialerSettings(
        val packageName: String,
        val className: String
    ) : CallRecordingTarget()

    /** Settings > Apps > <dialer>. Resolvable whenever the package is installed. */
    data class AppInfo(val packageName: String) : CallRecordingTarget()

    /** The top-level system Settings screen. The last resort; always resolvable. */
    object SystemSettings : CallRecordingTarget()
}

/**
 * Pure decision logic, deliberately free of `android.*` imports so every branch is testable
 * without a device — the same split [OverlayDecider] uses.
 */
object CallRecordingSettingsResolver {

    /**
     * The ordered list of places to try, best first.
     *
     * Class names are emitted only for the package that is actually the default dialer. A
     * Samsung phone must never be handed a Google class name: it could not resolve on any
     * device, so trying it would only lengthen the walk.
     */
    fun targetsFor(defaultDialerPackage: String?): List<CallRecordingTarget> {
        val pkg = defaultDialerPackage?.takeIf { it.isNotBlank() }
            ?: return listOf(CallRecordingTarget.SystemSettings)

        val dialerSettings = SETTINGS_ACTIVITIES[pkg]
            .orEmpty()
            .map { CallRecordingTarget.DialerSettings(pkg, it) }

        return dialerSettings + CallRecordingTarget.AppInfo(pkg) + CallRecordingTarget.SystemSettings
    }

    /**
     * Known settings activities per dialer package, most likely first. The Google Phone app
     * moved its settings activity between releases and both class names are still in the
     * wild, so both are tried before falling back.
     */
    private val SETTINGS_ACTIVITIES = mapOf(
        "com.google.android.dialer" to listOf(
            "com.android.dialer.main.impl.settings.DialerSettingsActivity",
            "com.android.dialer.settings.DialerSettingsActivity",
        ),
        "com.android.dialer" to listOf(
            "com.android.dialer.settings.DialerSettingsActivity",
        ),
        "com.samsung.android.dialer" to listOf(
            "com.samsung.android.dialer.settings.DialerSettingsActivity",
        ),
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.CallRecordingSettingsResolverTest"`
Expected: PASS, 6 tests.
If Gradle cannot run, state plainly that the test is unrun and move on.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/CallRecordingSettings.kt android/app/src/test/java/com/brachaai/app/CallRecordingSettingsResolverTest.kt
git commit -m "Decide where to send a user who wants call recording on"
```

---

### Task 2: The launcher, the manifest, and the bridge method

One deliverable: "native can open the setting". None of it is unit-testable — the launcher is all `PackageManager` and `startActivity`, and the bridge is a `@JavascriptInterface` — so splitting it would create tasks a reviewer could not evaluate separately.

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/CallRecordingSettings.kt` (append the launcher)
- Modify: `android/app/src/main/AndroidManifest.xml:18-20`
- Modify: `android/app/src/main/java/com/brachaai/app/NativeBridge.kt:22,87-94`

**Interfaces:**
- Consumes: `CallRecordingTarget`, `CallRecordingSettingsResolver.targetsFor(String?)` from Task 1.
- Produces: `class CallRecordingSettingsLauncher(context: Context)` with `fun open()`; and on the bridge, `@JavascriptInterface fun openCallRecordingSettings()`. Task 3 consumes the bridge method name.

- [ ] **Step 1: Append the launcher to `CallRecordingSettings.kt`**

Add these imports at the top of the file, after `package com.brachaai.app`:

```kotlin
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
```

Then append at the end of the file:

```kotlin
/**
 * Opens the phone's call-recording setting, best effort.
 *
 * Every target is checked with `resolveActivity` before it is started. The dialer settings
 * activities are undocumented, so a device that lacks one has to be a skip — an unchecked
 * `startActivity` would throw `ActivityNotFoundException` and take the Settings page down
 * with it.
 */
class CallRecordingSettingsLauncher(context: Context) {

    private val appContext = context.applicationContext

    fun open() {
        val targets = CallRecordingSettingsResolver.targetsFor(defaultDialerPackage())

        for (target in targets) {
            val intent = intentFor(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (appContext.packageManager.resolveActivity(intent, 0) == null) {
                Log.d(TAG, "Skipping unresolvable target: $target")
                continue
            }
            try {
                appContext.startActivity(intent)
                showHint()
                return
            } catch (e: Exception) {
                // A target can resolve and still be refused — an unexported activity, or an
                // OEM blocking the start. Keep walking rather than dead-ending the user.
                Log.w(TAG, "Could not start $target", e)
            }
        }

        Log.w(TAG, "No call-recording settings target could be opened")
    }

    /**
     * Null when there is no default dialer, or when the lookup throws — some OEM builds do.
     * The resolver treats null as "system Settings only", which is still somewhere useful.
     */
    private fun defaultDialerPackage(): String? = try {
        appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the default dialer package", e)
        null
    }

    private fun intentFor(target: CallRecordingTarget): Intent = when (target) {
        is CallRecordingTarget.DialerSettings ->
            Intent().setClassName(target.packageName, target.className)

        is CallRecordingTarget.AppInfo ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${target.packageName}")
            }

        CallRecordingTarget.SystemSettings -> Intent(Settings.ACTION_SETTINGS)
    }

    /**
     * Names the last hop for whoever landed on app-info or the top-level Settings screen.
     *
     * Posted to the main looper: JavaScript bridge calls arrive on the WebView's JavaBridge
     * thread, where a bare `Toast.show` throws for want of a Looper.
     */
    private fun showHint() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, HINT, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "CallRecordingSettings"
        const val HINT = "Open Settings → Call recording in your Phone app"
    }
}
```

- [ ] **Step 2: Add the `<queries>` block to the manifest**

In `android/app/src/main/AndroidManifest.xml`, insert between the last `<uses-permission>` (line 18) and `<application>` (line 20):

```xml
    <!-- Package visibility, API 30+: opening the dialer's own settings activity needs an
         explicit component in that package, and an invisible package never resolves. This
         grants visibility to dialer apps only — QUERY_ALL_PACKAGES would need a Play policy
         declaration and is far wider than this needs. -->
    <queries>
        <intent>
            <action android:name="android.intent.action.DIAL" />
        </intent>
    </queries>
```

- [ ] **Step 3: Add the bridge method**

In `android/app/src/main/java/com/brachaai/app/NativeBridge.kt`, add the field after line 22 (`private val settingsStore = SettingsStore(appContext)`):

```kotlin
    private val callRecordingSettings = CallRecordingSettingsLauncher(appContext)
```

Then add the method after `setDeleteAudioAfterProcessing` (after line 94), before the `companion object`:

```kotlin
    /**
     * Opens the phone's own call-recording setting.
     *
     * Unlike every other setting here, this one is not ours to store: the dialer records the
     * calls this app transcribes, so its setting is the only switch that does anything. The
     * body cannot throw — a failure across the JS bridge would break the Settings page, and
     * the launcher already treats "nowhere to go" as a normal outcome.
     */
    @JavascriptInterface
    fun openCallRecordingSettings() {
        try {
            callRecordingSettings.open()
        } catch (e: Exception) {
            Log.w(TAG, "Could not open call recording settings", e)
        }
    }
```

- [ ] **Step 4: Verify what can be verified**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS — nothing here has a test, so this only proves the module still compiles.

**Expect this to be impossible in this environment.** If it is, say so explicitly and record that Task 2 is written but unverified — no compile, no lint, no device check. Do not describe it as working.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/CallRecordingSettings.kt android/app/src/main/java/com/brachaai/app/NativeBridge.kt android/app/src/main/AndroidManifest.xml
git commit -m "Let the web Settings page open the dialer's recording setting"
```

---

### Task 3: The Settings row

**Files:**
- Modify: `frontend/src/types/native.d.ts:21`
- Modify: `frontend/src/pages/SettingsPage/SettingsPage.tsx:3,12,19,31-48,115-137`
- Modify: `frontend/src/pages/SettingsPage/SettingsPage.module.css:167`
- Test: `frontend/src/pages/SettingsPage/SettingsPage.test.tsx` (create)

**Interfaces:**
- Consumes: `window.BrachaNative.openCallRecordingSettings()` from Task 2.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/SettingsPage/SettingsPage.test.tsx`:

```tsx
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import SettingsPage from './SettingsPage';

const clientMock = new MockAdapter(apiClient);

const ROW = 'Automatic Call Recording';

const renderPage = () =>
    render(
        <MemoryRouter>
            <SettingsPage />
        </MemoryRouter>
    );

beforeEach(() => {
    clientMock.reset();
    localStorage.clear();
});

afterEach(() => {
    delete (window as any).BrachaNative;
});

/**
 * The app cannot start a recording — the phone's dialer does that — so this row is a
 * shortcut to the dialer's setting and nothing else. Where there is no dialer to open, the
 * row must not exist at all.
 */
describe('SettingsPage automatic call recording row', () => {
    it('is absent in a plain browser', () => {
        renderPage();

        expect(screen.queryByText(ROW)).toBeNull();
    });

    it('is absent inside an older APK whose bridge predates the method', () => {
        (window as any).BrachaNative = { setAuth: vi.fn(), clearAuth: vi.fn() };

        renderPage();

        expect(screen.queryByText(ROW)).toBeNull();
    });

    it('opens the phone settings when tapped', () => {
        const openCallRecordingSettings = vi.fn();
        (window as any).BrachaNative = { openCallRecordingSettings };

        renderPage();
        fireEvent.click(screen.getByText(ROW).closest('button')!);

        expect(openCallRecordingSettings).toHaveBeenCalledTimes(1);
    });

    it('offers no switch, and writes nothing to the backend', () => {
        (window as any).BrachaNative = { openCallRecordingSettings: vi.fn() };

        renderPage();
        const row = screen.getByText(ROW).closest('button')!;

        expect(row.querySelector('input[type="checkbox"]')).toBeNull();
        expect(clientMock.history.put).toHaveLength(0);
    });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/SettingsPage/SettingsPage.test.tsx`
Expected: FAIL — the first two cases fail because the row currently renders unconditionally, and the third fails because `.closest('button')` returns null for today's `<div>` row.

- [ ] **Step 3: Add the bridge type**

In `frontend/src/types/native.d.ts`, after `setDeleteAudioAfterProcessing?(enabled: boolean): void;` (line 21):

```ts
    /**
     * Opens the phone's own call-recording setting. The app never records calls itself, so
     * this is the only switch that does anything. Optional for the same reason as the
     * settings methods: the committed bundle can run inside an older APK.
     */
    openCallRecordingSettings?(): void;
```

- [ ] **Step 4: Add the button style**

In `frontend/src/pages/SettingsPage/SettingsPage.module.css`, after the `.settingItem + .settingItem` rule (line 167):

```css
/* The recording row links out to the phone's own settings rather than toggling anything, so
   it is a real <button> — focusable, and announced as a control. These resets keep it
   visually identical to the plain .settingItem rows beside it. */
.settingButton {
    width: 100%;
    background: none;
    border: none;
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
}

.settingButton:hover {
    background-color: #f8fafc;
}
```

- [ ] **Step 5: Rewrite the row in `SettingsPage.tsx`**

Five edits in `frontend/src/pages/SettingsPage/SettingsPage.tsx`:

**5a.** Delete the `apiClient` import (line 3). It becomes unused once `handleToggle` goes, and lint runs at `--max-warnings 0`.

```tsx
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { endSession } from '@/services/session';
```

**5b.** Replace the `autoCallRecording` state (line 12) with the support flag:

```tsx
    const [callRecordingSupported, setCallRecordingSupported] = useState(false);
```

**5c.** In the `useEffect`, delete line 19 (`setAutoCallRecording(...)`) and add the probe after the existing audio-setting block, inside the same effect:

```tsx
        // The app cannot record calls; the phone's dialer does, into the directory the
        // native service watches. So this row only shortcuts to the dialer's setting, and in
        // a browser there is nothing to shortcut to.
        if (bridge?.openCallRecordingSettings) {
            setCallRecordingSupported(true);
        }
```

**5d.** Delete `handleToggle` entirely (lines 31–48). Its only caller was the switch being removed.

**5e.** Replace the whole first `.settingItem` block (lines 115–137) with:

```tsx
                        {callRecordingSupported && (
                            <button
                                className={`${styles.settingItem} ${styles.settingButton}`}
                                onClick={() => window.BrachaNative?.openCallRecordingSettings?.()}
                            >
                                <div className={styles.settingInfo}>
                                    <div className={`${styles.iconBox} ${styles.redIcon}`}>
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                            <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z" />
                                            <path d="M19 10v1a7 7 0 0 1-14 0v-1" />
                                            <line x1="12" x2="12" y1="19" y2="22" />
                                        </svg>
                                    </div>
                                    <div className={styles.settingText}>
                                        <span className={styles.settingName}>Automatic Call Recording</span>
                                        <span className={styles.settingDescription}>Enable it in your Phone app settings</span>
                                    </div>
                                </div>
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                                    <polyline points="9 18 15 12 9 6" />
                                </svg>
                            </button>
                        )}
```

Keeping `styles.settingItem` on the button matters: the `.settingItem + .settingItem` divider rule at line 165 still matches button-then-div, so the separator between the two Call Settings rows survives.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/SettingsPage/SettingsPage.test.tsx`
Expected: PASS, 4 tests.

- [ ] **Step 7: Check nothing else broke**

Run: `cd frontend && npm run test && npm run lint && npx tsc --noEmit`
Expected: all pass. A `'apiClient' is declared but its value is never read` error here means step 5a was skipped.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/types/native.d.ts frontend/src/pages/SettingsPage/SettingsPage.tsx frontend/src/pages/SettingsPage/SettingsPage.module.css frontend/src/pages/SettingsPage/SettingsPage.test.tsx
git commit -m "Point the recording row at the setting that actually works"
```

---

### Task 4: Regenerate the bundled web assets

Without this the APK still contains the old bundle, so the entire UI change is invisible on a phone. There is deliberately no Gradle task for this — the copy is done by hand and committed.

**Files:**
- Modify: `android/app/src/main/assets/www/` (whole directory, regenerated)

**Interfaces:**
- Consumes: the built output of Task 3.
- Produces: nothing.

- [ ] **Step 1: Build the frontend**

Run: `cd frontend && npm run build`
Expected: `tsc` passes, then Vite writes `frontend/dist/`.

- [ ] **Step 2: Replace the bundled assets**

PowerShell, from the repo root:

```powershell
Remove-Item -Recurse -Force android/app/src/main/assets/www/*
Copy-Item -Recurse -Force frontend/dist/* android/app/src/main/assets/www/
```

- [ ] **Step 3: Confirm the new bundle carries the change**

Run: `cd android/app/src/main/assets/www && grep -rl "Enable it in your Phone app settings" assets/`
Expected: at least one hashed `index-*.js` matches. No match means the copy did not take, or the build predates step 5e.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/assets/www
git commit -m "Rebuild the bundled web assets"
```

---

## Manual verification

None of the native half is verified by any automated check, so this is the real test. On a physical Android phone with the Google Phone app as default dialer:

1. Build and install: `cd android && ./gradlew installDebug`.
2. Open Settings in BrachaAI. The "Automatic Call Recording" row shows a chevron, not a switch, and reads "Enable it in your Phone app settings".
3. Tap it. The Phone app's settings screen opens, and a toast reads "Open Settings → Call recording in your Phone app".
4. If it lands on Settings > Apps > Phone instead, the deep link missed on that build — capture the value of `TelecomManager.getDefaultDialerPackage()` from logcat (`adb logcat -s CallRecordingSettings`) and add that device's settings activity to `SETTINGS_ACTIVITIES`.
5. Turn call recording on there, return, place a call, and confirm a file appears in `/storage/emulated/0/Recordings/Call` and gets transcribed.
6. Repeat on a Samsung device if one is available — the Samsung class name in the map is unverified.
