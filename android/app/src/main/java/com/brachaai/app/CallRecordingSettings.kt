package com.brachaai.app

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /** Settings > Apps > <dialer>. Resolvable whenever the package is installed. */
    data class AppInfo(val packageName: String) : CallRecordingTarget()

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

/**
 * Opens the phone's call-recording setting, best effort.
 *
 * Only [CallRecordingTarget.DialerSettings] is checked with `resolveActivity` before it is
 * started — those component names are undocumented and may not exist on a given device, so a
 * miss has to be a silent skip rather than a crash. [CallRecordingTarget.AppInfo] and
 * [CallRecordingTarget.SystemSettings] are documented, always-resolvable system actions; gating
 * them the same way would mean that if `resolveActivity` ever returned a false negative for one
 * of them, the loop would exhaust with nothing started, for exactly the users whose dialer deep
 * link already missed. They go straight to `startActivity`, and the existing `try/catch` below
 * already covers `ActivityNotFoundException`, so attempting costs nothing.
 */
class CallRecordingSettingsLauncher(context: Context) {

    private val appContext = context.applicationContext

    fun open() {
        val targets = CallRecordingSettingsResolver.targetsFor(defaultDialerPackage())

        for (target in targets) {
            val intent = intentFor(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
     * Names the manual path to call recording. Shown after every successful start, including
     * a direct hit on the dialer's own settings activity — even there the user still has to
     * find "Call recording" themselves, so the hint is useful on every path, not just the
     * app-info and system-Settings fallbacks.
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
