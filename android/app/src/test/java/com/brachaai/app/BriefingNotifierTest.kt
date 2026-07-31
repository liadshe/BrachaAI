package com.brachaai.app

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BriefingNotifierTest {

    // Mirrors BriefingNotifier's private CHANNEL_ID. Kept as a literal here (rather than
    // exposing the constant) so this test fails loudly if the id in the production code ever
    // reverts to the pre-v2 value -- that revert is exactly the failure mode this file exists
    // to catch.
    private val channelIdV2 = "caller_briefing_v2"

    private lateinit var context: Application
    private lateinit var manager: NotificationManager

    private val briefing = Briefing(
        contactId = "c1",
        name = "David Cohen",
        phone = "+972501234567",
        lastCallSummary = "Promised to send price quote.",
        openTasks = listOf(
            BriefingTask("t1", "Send contract by Tuesday", "HIGH"),
            BriefingTask("t2", "Follow up on invoice", "LOW"),
        ),
        openTaskCount = 2,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun `posting creates the v2 channel with sound and vibration off but importance high`() {
        BriefingNotifier.show(context, briefing)

        val channel = manager.getNotificationChannel(channelIdV2)
        assertTrue("expected the v2 channel to be created", channel != null)
        assertNull("sound must be stripped so it doesn't double up on the ringtone", channel!!.sound)
        assertFalse("vibration must be off for the same reason", channel.shouldVibrate())
        assertEquals(
            "importance must stay HIGH so the card still heads-up over the incoming-call screen",
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance,
        )
    }

    @Test
    fun `posted notification carries the shared lifetime as its timeout backstop`() {
        BriefingNotifier.show(context, briefing)

        val notification = shadowOf(manager).allNotifications.first()
        assertEquals(MAX_BRIEFING_LIFETIME_MS, notification.timeoutAfter)
    }

    @Test
    fun `posted notification title is the contact name and body carries the summary and tasks`() {
        BriefingNotifier.show(context, briefing)

        val notification = shadowOf(manager).allNotifications.first()
        assertEquals("David Cohen", notification.extras.getString(Notification.EXTRA_TITLE))

        val body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue("body should contain the last-call summary", body.contains("Promised to send price quote."))
        assertTrue("body should contain the first open task", body.contains("Send contract by Tuesday"))
        assertTrue("body should contain the second open task", body.contains("Follow up on invoice"))
    }

    @Test
    fun `dismiss cancels the posted notification`() {
        BriefingNotifier.show(context, briefing)
        assertTrue(shadowOf(manager).allNotifications.isNotEmpty())

        BriefingNotifier.dismiss(context)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
