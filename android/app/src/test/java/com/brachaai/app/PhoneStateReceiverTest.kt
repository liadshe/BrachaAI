package com.brachaai.app

import android.app.Application
import android.content.Intent
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneStateReceiverTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).clearStartedServices()
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

    @Test
    fun `a ringing known contact starts the overlay service`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")

        val started = nextService()
        assertEquals(CallOverlayService::class.java.name, started?.component?.className)
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
        PhoneStateReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(nextService())
    }

    @Test
    fun `an idle broadcast dismisses the overlay`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")
        shadowOf(context).clearStartedServices()

        broadcast(TelephonyManager.EXTRA_STATE_IDLE)

        assertEquals(CallOverlayService::class.java.name, nextService()?.component?.className)
    }

    @Test
    fun `an offhook broadcast leaves the card alone`() {
        broadcast(TelephonyManager.EXTRA_STATE_RINGING, "+972501234567")
        shadowOf(context).clearStartedServices()

        broadcast(TelephonyManager.EXTRA_STATE_OFFHOOK)

        assertNull("answering must not tear the card down", nextService())
    }
}
