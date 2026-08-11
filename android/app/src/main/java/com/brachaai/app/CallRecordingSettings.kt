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
