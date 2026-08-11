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
