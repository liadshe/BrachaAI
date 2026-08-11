package com.brachaai.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast

/**
 * Where to send a user who wants automatic call recording turned on.
 *
 * BrachaAI does not record calls: the phone's dialer does, into the directory
 * [CallMonitorService.WATCH_PATH] watches. The *platform* publishes no intent for that
 * setting, and where the screen lives is not even consistent between vendors — One UI keeps
 * it in the telephony UI, not in the dialer package at all. Some OEMs do expose an action for
 * it, which is the one durable kind of target; everything else is an undocumented component
 * name that may be renamed, unexported, or simply absent.
 *
 * So the list is ordered by how much it can be trusted, and every entry after the first is a
 * fallback. The last one always exists.
 */
sealed class CallRecordingTarget {
    /**
     * An implicit intent action that opens a call-settings screen. The best kind of target:
     * an action carries an intent filter, so it is exported by construction, and it survives
     * the class renames that make [DialerSettings] brittle. Verified on a Galaxy S25 —
     * `OPEN_CALL_SETTINGS` lands directly on the screen holding "Record calls".
     *
     * Kept device-wide rather than keyed to the dialer package: the screen belongs to the
     * OEM's telephony UI, which is installed regardless of which dialer is currently default.
     */
    data class SettingsAction(val action: String) : CallRecordingTarget()

    /** An undocumented settings activity inside the dialer. May not exist on this device. */
    data class DialerSettings(
        val packageName: String,
        val className: String
    ) : CallRecordingTarget()

    /**
     * The dialer's own launcher screen. Lands the user in the Phone app, one overflow menu
     * from "⋮ → Settings → Record calls" — which is why it outranks anything in the system
     * Settings app. Settings > Apps > Phone used to sit here and was removed: it is always
     * resolvable and therefore always wins, but there is no route from it to call recording
     * at all, so it consumed the fallback and stranded the user somewhere useless.
     */
    data class DialerApp(val packageName: String) : CallRecordingTarget()

    /** The top-level system Settings screen. The last resort; always resolvable. */
    data object SystemSettings : CallRecordingTarget()

    /**
     * Whether the launcher must ask the package manager before starting this target.
     *
     * True for the OEM-specific guesses, which genuinely may not exist here — an unchecked
     * start would throw. False for the two that are resolvable by construction, because a
     * false negative on the last resort would exhaust the walk and leave the user with a row
     * that does nothing.
     */
    val needsResolveCheck: Boolean
        get() = this is SettingsAction || this is DialerSettings
}

/**
 * Pure decision logic. Unlike [CallRecordingSettingsLauncher] below — which shares this file
 * but imports nine `android.*` types — the resolver itself uses none, so every branch here is
 * testable on the plain JVM, no device required. Same split [OverlayDecider] uses.
 */
object CallRecordingSettingsResolver {

    /**
     * The ordered list of places to try, best first.
     *
     * [discoveredSettingsActivities] are exported activity class names the caller found by
     * asking the package manager what the dialer actually contains. They outrank
     * [SETTINGS_ACTIVITIES] because they describe *this* device, whereas the map is a guess
     * compiled from other people's phones — and guessing is exactly what failed: on a Galaxy
     * S25 the Samsung class name missed, the walk fell through, and the user landed on an
     * app-info page with no route to call recording. The map is now only a backstop for when
     * discovery is blocked or returns nothing.
     *
     * Known class names are emitted only for the package that is actually the default dialer.
     * A Samsung phone must never be handed a Google class name: it could not resolve on any
     * device, so trying it would only lengthen the walk.
     */
    fun targetsFor(
        defaultDialerPackage: String?,
        discoveredSettingsActivities: List<String> = emptyList()
    ): List<CallRecordingTarget> {
        val actions = SETTINGS_ACTIONS.map { CallRecordingTarget.SettingsAction(it) }

        val pkg = defaultDialerPackage?.takeIf { it.isNotBlank() }
            ?: return actions + CallRecordingTarget.SystemSettings

        val classNames = (discoveredSettingsActivities + SETTINGS_ACTIVITIES[pkg].orEmpty())
            .filter { it.isNotBlank() }
            .distinct()

        return actions +
            classNames.map { CallRecordingTarget.DialerSettings(pkg, it) } +
            CallRecordingTarget.DialerApp(pkg) +
            CallRecordingTarget.SystemSettings
    }

    /**
     * Intent actions that open a call-settings screen, tried before any class name.
     *
     * Not keyed on the dialer package on purpose. The screen lives in the OEM's telephony UI,
     * not in the dialer — on a Galaxy S25 the dialer package contains no settings activity at
     * all, which is what made the class-name approach fail there. A Samsung phone running
     * Google's dialer as default should still reach the Samsung screen.
     *
     * An action that nothing handles is skipped by the launcher's resolve check, so listing
     * one costs a lookup on devices that lack it. Each entry needs a matching `<queries>`
     * element in the manifest, or package visibility hides its handler and it never resolves.
     */
    private val SETTINGS_ACTIONS = listOf(
        // Samsung (One UI). Filtered onto .callsettings.ui.preference.CallSettingsActivity.
        "com.samsung.android.app.telephonyui.action.OPEN_CALL_SETTINGS",
    )

    /**
     * Known settings activities per dialer package, most likely first — the backstop for a
     * device where discovery is blocked or finds nothing.
     *
     * The Google Phone app has moved its settings activity between releases and all three
     * class names are still in the wild, so all three are tried.
     */
    private val SETTINGS_ACTIVITIES = mapOf(
        "com.google.android.dialer" to listOf(
            "com.android.dialer.main.impl.settings.DialerSettingsActivity",
            "com.android.dialer.app.settings.DialerSettingsActivity",
            "com.android.dialer.settings.DialerSettingsActivity",
        ),
        "com.android.dialer" to listOf(
            "com.android.dialer.app.settings.DialerSettingsActivity",
            "com.android.dialer.settings.DialerSettingsActivity",
        ),
        "com.samsung.android.dialer" to listOf(
            "com.samsung.android.dialer.settings.DialerSettingsActivity",
        ),
    )
}

/**
 * Opens the phone's call-recording setting, best effort.
 *
 * Which targets get a `resolveActivity` check is [CallRecordingTarget.needsResolveCheck]: the
 * OEM guesses do, since a miss must be a silent skip rather than a crash; the two that resolve
 * by construction do not, because a false negative on the last resort would exhaust the walk
 * and leave the user with a row that does nothing. The `try/catch` below covers a refused start
 * either way, so attempting an ungated target costs nothing.
 */
class CallRecordingSettingsLauncher(context: Context) {

    private val appContext = context.applicationContext

    fun open() {
        val pkg = defaultDialerPackage()
        val targets = CallRecordingSettingsResolver.targetsFor(pkg, discoverSettingsActivities(pkg))

        for (target in targets) {
            val intent = intentFor(target)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: continue
            if (target.needsResolveCheck &&
                appContext.packageManager.resolveActivity(intent, 0) == null
            ) {
                Log.d(TAG, "Skipping unresolvable target: $target")
                continue
            }
            try {
                appContext.startActivity(intent)
                Log.i(TAG, "Opened $target")
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

    /**
     * Asks the package manager what settings activities the dialer actually has, rather than
     * trusting a hardcoded list of class names — the guess that missed on a real Pixel.
     *
     * Only `exported` activities are returned. A non-exported activity cannot be started from
     * another app at all, so offering one would guarantee a `SecurityException` further down;
     * filtering here keeps the walk honest instead of burning a turn on a certain failure.
     *
     * Empty on anything unexpected: no package, the dialer invisible behind package-visibility
     * rules, or the lookup throwing. The resolver's known-names backstop covers that case.
     */
    private fun discoverSettingsActivities(dialerPackage: String?): List<String> {
        val pkg = dialerPackage?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            appContext.packageManager
                .getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                .activities
                .orEmpty()
                .filter { it.exported && it.name.contains("settings", ignoreCase = true) }
                .map { it.name }
                .also { Log.d(TAG, "Discovered settings activities in $pkg: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate activities in $pkg", e)
            emptyList()
        }
    }

    /** Null when the target cannot be expressed as an intent on this device; the caller skips it. */
    private fun intentFor(target: CallRecordingTarget): Intent? = when (target) {
        is CallRecordingTarget.SettingsAction -> Intent(target.action)

        is CallRecordingTarget.DialerSettings ->
            Intent().setClassName(target.packageName, target.className)

        is CallRecordingTarget.DialerApp ->
            appContext.packageManager.getLaunchIntentForPackage(target.packageName)

        CallRecordingTarget.SystemSettings -> Intent(Settings.ACTION_SETTINGS)
    }

    /**
     * Names the manual path to call recording. Shown after every successful start, including a
     * direct hit on the dialer's settings activity — even there the user still has to find
     * "Record calls" themselves, so the hint is useful on every path.
     *
     * The wording starts from the dialer's home screen, because that is where the most common
     * landing now is: [CallRecordingTarget.DialerApp]. From a settings screen the first step is
     * simply already done. These are the Phone app's own labels, reported from a real device —
     * an OEM whose menu reads differently needs this string changed, not the navigation logic.
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
        const val HINT = "Phone app: tap ⋮ (top right) → Settings → Record calls"
    }
}
