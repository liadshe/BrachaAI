package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver exists because Android publishes no intent for call-recording settings, so the
 * only way in is an activity class name — and the hardcoded guesses missed on a real Pixel,
 * which is why the caller now discovers the dialer's activities and passes them in.
 *
 * What these tests pin: discovery outranks the guesses, the guesses stay scoped to the dialer
 * that is installed, and the walk always ends somewhere that exists — and never at a screen
 * with no route to call recording.
 */
class CallRecordingSettingsResolverTest {

    private val google = "com.google.android.dialer"
    private val samsung = "com.samsung.android.dialer"
    private val oem = "com.oem.unknown.dialer"

    @Test
    fun `what the device actually reports is tried before any hardcoded guess`() {
        val targets = CallRecordingSettingsResolver.targetsFor(
            google,
            listOf("com.google.android.dialer.SettingsActivity")
        )

        assertEquals(
            CallRecordingTarget.DialerSettings(google, "com.google.android.dialer.SettingsActivity"),
            targets.first()
        )
    }

    @Test
    fun `discovery rescues a dialer no hardcoded name covers`() {
        assertEquals(
            listOf(
                CallRecordingTarget.DialerSettings(oem, "com.oem.dialer.ui.CallSettingsActivity"),
                CallRecordingTarget.DialerApp(oem),
                CallRecordingTarget.SystemSettings,
            ),
            CallRecordingSettingsResolver.targetsFor(
                oem,
                listOf("com.oem.dialer.ui.CallSettingsActivity")
            )
        )
    }

    @Test
    fun `a name that is both discovered and hardcoded is tried once, not twice`() {
        val shared = "com.android.dialer.app.settings.DialerSettingsActivity"

        val targets = CallRecordingSettingsResolver.targetsFor(google, listOf(shared))

        assertEquals(
            1,
            targets.count { it == CallRecordingTarget.DialerSettings(google, shared) }
        )
    }

    @Test
    fun `google dialer falls back to all three known class names when discovery finds nothing`() {
        assertEquals(
            listOf(
                CallRecordingTarget.DialerSettings(
                    google,
                    "com.android.dialer.main.impl.settings.DialerSettingsActivity"
                ),
                CallRecordingTarget.DialerSettings(
                    google,
                    "com.android.dialer.app.settings.DialerSettingsActivity"
                ),
                CallRecordingTarget.DialerSettings(
                    google,
                    "com.android.dialer.settings.DialerSettingsActivity"
                ),
                CallRecordingTarget.DialerApp(google),
                CallRecordingTarget.SystemSettings,
            ),
            CallRecordingSettingsResolver.targetsFor(google)
        )
    }

    @Test
    fun `a samsung phone is never handed a google class name`() {
        assertEquals(
            listOf(
                CallRecordingTarget.DialerSettings(
                    samsung,
                    "com.samsung.android.dialer.settings.DialerSettingsActivity"
                ),
                CallRecordingTarget.DialerApp(samsung),
                CallRecordingTarget.SystemSettings,
            ),
            CallRecordingSettingsResolver.targetsFor(samsung)
        )
    }

    @Test
    fun `an unrecognised dialer with nothing discovered still opens the dialer itself`() {
        assertEquals(
            listOf(
                CallRecordingTarget.DialerApp(oem),
                CallRecordingTarget.SystemSettings,
            ),
            CallRecordingSettingsResolver.targetsFor(oem)
        )
    }

    /**
     * The regression this file exists for. Settings > Apps > <dialer> used to sit above the
     * dialer itself; it always resolves, so it always won, and it has no route to call
     * recording at all — the user tapped the row and landed on an app-info page.
     */
    @Test
    fun `the dialer itself always outranks the system settings app`() {
        listOf(google, samsung, oem).forEach { pkg ->
            val targets = CallRecordingSettingsResolver.targetsFor(pkg)
            assertTrue(
                "Dialer app must precede system Settings for $pkg, got $targets",
                targets.indexOf(CallRecordingTarget.DialerApp(pkg)) <
                    targets.indexOf(CallRecordingTarget.SystemSettings)
            )
        }
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
    fun `a blank discovered class name is dropped rather than launched`() {
        val targets = CallRecordingSettingsResolver.targetsFor(google, listOf("", "   "))

        assertTrue(
            "Blank class names must not become targets, got $targets",
            targets.none { it is CallRecordingTarget.DialerSettings && it.className.isBlank() }
        )
    }

    @Test
    fun `every list ends at a target that always exists`() {
        listOf(null, "", google, samsung, oem).forEach { pkg ->
            assertEquals(
                "Last resort missing for $pkg",
                CallRecordingTarget.SystemSettings,
                CallRecordingSettingsResolver.targetsFor(pkg).last()
            )
        }
    }
}
