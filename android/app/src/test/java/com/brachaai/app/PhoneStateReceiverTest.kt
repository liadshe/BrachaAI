package com.brachaai.app

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneStateReceiverTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).clearStartedServices()
        // Unlocked is the default here so the WindowManager path stays the one under test;
        // the locked case opts in explicitly (see the keyguard test below).
        setLocked(false)
        // The overlay path is the primary path this feature exists for, so it is the default
        // for every test here unless a test explicitly opts out (see the fallback test below).
        ShadowSettings.setCanDrawOverlays(true)
        BriefingStore.default(context.filesDir).replaceAll(
            listOf(
                Briefing(
                    contactId = "c1",
                    name = "David Cohen",
                    phone = "+972501234567",
                    lastCallSummary = "Promised to send price quote.",
                    openTasks = listOf(BriefingTask("t1", "Send contract", "HIGH")),
                    openTaskCount = 1,
                )
            )
        )
    }

    private fun broadcast(state: String, number: String? = null) {
        val intent = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, state)
            number?.let { putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, it) }
        }
        PhoneStateReceiver().onReceive(context, intent)
    }

    private fun nextService(): Intent? =
        shadowOf(context).nextStartedService

    private fun nextActivity(): Intent? =
        shadowOf(context).nextStartedActivity

    private fun setLocked(locked: Boolean) {
        shadowOf(context.getSystemService(KeyguardManager::class.java))
            .setKeyguardLocked(locked)
    }

    @Test
    fun `a ringing known contact starts the overlay service`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")

        val started = nextService()
        assertEquals(CallOverlayService::class.java.name, started?.component?.className)
        assertEquals(CallOverlayService.ACTION_SHOW, started?.action)
    }

    @Test
    fun `a locked phone takes the same overlay path as an unlocked one`() {
        // The overlay window carries FLAG_SHOW_WHEN_LOCKED, so one path serves both. Nothing
        // here may start an Activity: an Activity over a ring pauses the dialer, and a paused
        // call screen ignores its own answer gesture, so the call becomes unanswerable.
        // Measured on device — this assertion is the regression guard for it.
        setLocked(true)

        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")

        val started = nextService()
        assertEquals(CallOverlayService::class.java.name, started?.component?.className)
        assertEquals(CallOverlayService.ACTION_SHOW, started?.action)
        assertNull("an Activity over the ring makes the call unanswerable", nextActivity())
    }

    @Test
    fun `the notification fallback still wins on a locked phone without the overlay permission`() {
        // Without SYSTEM_ALERT_WINDOW there is no card to show on either side of the lock,
        // and the keyguard state must not change that.
        setLocked(true)
        ShadowSettings.setCanDrawOverlays(false)

        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")

        assertNull(nextService())
        assertNull(nextActivity())
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isNotEmpty())
    }

    @Test
    fun `a ringing known contact without the overlay permission posts a notification instead`() {
        ShadowSettings.setCanDrawOverlays(false)

        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")

        assertNull("the overlay service must not start without the permission", nextService())

        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager).allNotifications
        assertTrue("expected a fallback notification to be posted", notifications.isNotEmpty())
        assertEquals(
            "David Cohen",
            notifications.first().extras.getString(Notification.EXTRA_TITLE),
        )
    }

    @Test
    fun `a ringing unknown number starts nothing`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972529999999")

        assertNull(nextService())
    }

    @Test
    fun `a withheld number starts nothing`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "-1")

        assertNull(nextService())
    }

    @Test
    fun `an unrelated broadcast is ignored`() {
        // A wrong action alone (e.g. Intent.ACTION_BOOT_COMPLETED with no EXTRA_STATE at all)
        // would pass this test even with the action guard deleted, since getStringExtra(EXTRA_STATE)
        // would just return null and match neither `when` branch. So this intent carries a
        // RINGING state and the known contact's number on a non-PHONE_STATE action — the action
        // guard is the *only* thing standing between this and a shown card.
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
            putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, "+972501234567")
        }
        PhoneStateReceiver().onReceive(context, intent)

        assertNull(nextService())
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `an idle broadcast dismisses the overlay`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")
        shadowOf(context).clearStartedServices()

        broadcast(TelephonyManager.EXTRA_STATE_IDLE)

        val dismissed = nextService()
        assertEquals(CallOverlayService::class.java.name, dismissed?.component?.className)
        assertEquals(CallOverlayService.ACTION_DISMISS, dismissed?.action)
    }

    @Test
    fun `an offhook broadcast leaves the card alone`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")
        shadowOf(context).clearStartedServices()

        broadcast(TelephonyManager.EXTRA_STATE_OFFHOOK)

        assertNull("answering must not tear the card down", nextService())
    }
}
