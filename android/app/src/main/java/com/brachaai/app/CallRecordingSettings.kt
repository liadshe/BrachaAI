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

    /**
     * The dialer's own launcher screen. Lands the user in the Phone app, one overflow menu
     * from "Call settings → Record calls" — which is why it outranks anything in the system
     * Settings app. Settings > Apps > Phone used to sit here and was removed: it is always
     * resolvable and therefore always wins, but there is no route from it to call recording
     * at all, so it consumed the fallback and stranded the user somewhere useless.
     */
    data class DialerApp(val packageName: String) : CallRecordingTarget()

    /** The top-level system Settings screen. The last resort; always resolvable. */
    data object SystemSettings : CallRecordingTarget()
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
     * asking the package manager what the dialer actually contains. They come first because
     * they are what is on *this* device, whereas [SETTINGS_ACTIVITIES] is a guess compiled
     * from other people's phones. Guessing is what failed: on a Pixel the two known Google
     * class names both missed, the walk fell through, and the user landed nowhere useful.
     * The known names are kept only as a backstop for when discovery returns nothing.
     *
     * Known class names are emitted only for the package that is actually the default dialer.
     * A Samsung phone must never be handed a Google class name: it could not resolve on any
     * device, so trying it would only lengthen the walk.
     */
    fun targetsFor(
        defaultDialerPackage: String?,
        discoveredSettingsActivities: List<String> = emptyList()
    ): List<CallRecordingTarget> {
        val pkg = defaultDialerPackage?.takeIf { it.isNotBlank() }
            ?: return listOf(CallRecordingTarget.SystemSettings)

        val classNames = (discoveredSettingsActivities + SETTINGS_ACTIVITIES[pkg].orEmpty())
            .filter { it.isNotBlank() }
            .distinct()

        return classNames.map { CallRecordingTarget.DialerSettings(pkg, it) } +
            CallRecordingTarget.DialerApp(pkg) +
            CallRecordingTarget.SystemSettings
    }

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
 * Only [CallRecordingTarget.DialerSettings] is checked with `resolveActivity` before it is
 * started — those component names are undocumented and may not exist on a given device, so a
 * miss has to be a silent skip rather than a crash. [CallRecordingTarget.DialerApp] comes from
 * `getLaunchIntentForPackage`, which has already resolved by construction, and
 * [CallRecordingTarget.SystemSettings] is a documented, always-resolvable system action; gating
 * either the same way would mean that if `resolveActivity` ever returned a false negative, the
 * loop would exhaust with nothing started, for exactly the users whose dialer deep link already
 * missed. They go straight to `startActivity`, and the existing `try/catch` below already covers
 * `ActivityNotFoundException`, so attempting costs nothing.
 */
class CallRecordingSettingsLauncher(context: Context) {

    private val appContext = context.applicationContext

    fun open() {
        val pkg = defaultDialerPackage()
        val targets = CallRecordingSettingsResolver.targetsFor(pkg, discoverSettingsActivities(pkg))

        for (target in targets) {
            val intent = intentFor(target)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: continue
            if (target is CallRecordingTarget.DialerSettings &&
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
