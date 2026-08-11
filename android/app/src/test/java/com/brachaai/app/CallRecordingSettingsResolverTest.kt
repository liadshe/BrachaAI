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

    private val samsungAction =
        CallRecordingTarget.SettingsAction(
            "com.samsung.android.app.telephonyui.action.OPEN_CALL_SETTINGS"
        )

    /**
     * An action carries an intent filter, so it is exported by construction and survives class
     * renames. Nothing brittler should be tried ahead of it.
     */
    @Test
    fun `an intent action is tried before any class name`() {
        assertEquals(
            samsungAction,
            CallRecordingSettingsResolver.targetsFor(
                google,
                listOf("com.google.android.dialer.SettingsActivity")
            ).first()
        )
    }

    /**
     * The Galaxy S25 case that broke the class-name approach: the Samsung dialer package holds
     * no settings activity at all, and the screen lives in the OEM telephony UI instead. Keying
     * actions to the dialer package would strand a Samsung phone running Google's dialer.
     */
    @Test
    fun `the action is offered whatever the default dialer is`() {
        listOf(google, samsung, oem, null).forEach { pkg ->
            val targets = CallRecordingSettingsResolver.targetsFor(pkg)
            assertTrue(
                "Action missing for dialer $pkg, got $targets",
                targets.contains(samsungAction)
            )
        }
    }

    @Test
    fun `what the device actually reports is tried before any hardcoded class name`() {
        val discovered = "com.google.android.dialer.SettingsActivity"

        val targets = CallRecordingSettingsResolver.targetsFor(google, listOf(discovered))
        val classTargets = targets.filterIsInstance<CallRecordingTarget.DialerSettings>()

        assertEquals(CallRecordingTarget.DialerSettings(google, discovered), classTargets.first())
    }

    @Test
    fun `discovery rescues a dialer no hardcoded name covers`() {
        assertEquals(
            listOf(
                samsungAction,
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

    /**
     * Only the OEM guesses may be skipped on a resolve miss. If the last resort were gated too,
     * a false negative would exhaust the walk and the row would do nothing at all.
     */
    @Test
    fun `only the targets that might not exist are resolve-gated`() {
        val targets = CallRecordingSettingsResolver.targetsFor(samsung, listOf("com.x.SettingsY"))

        targets.forEach { target ->
            val expected = target is CallRecordingTarget.SettingsAction ||
                target is CallRecordingTarget.DialerSettings
            assertEquals("Wrong gating for $target", expected, target.needsResolveCheck)
        }
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
                samsungAction,
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
                samsungAction,
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
                samsungAction,
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
    fun `no default dialer still tries the action before giving up on system settings`() {
        assertEquals(
            listOf(samsungAction, CallRecordingTarget.SystemSettings),
            CallRecordingSettingsResolver.targetsFor(null)
        )
    }

    @Test
    fun `a blank package name is treated as no dialer, not as a package named empty`() {
        assertEquals(
            listOf(samsungAction, CallRecordingTarget.SystemSettings),
            CallRecordingSettingsResolver.targetsFor("   ")
        )
    }

    @Test
    fun `no dialer means no dialer target, since there is nothing to launch`() {
        assertTrue(
            CallRecordingSettingsResolver.targetsFor(null)
                .none { it is CallRecordingTarget.DialerApp || it is CallRecordingTarget.DialerSettings }
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
