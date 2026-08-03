package com.brachaai.app

import android.content.Context
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the fallback direction source: the phone-state state machine and the time-window
 * matching that ties a recording back to the call it came from.
 *
 * The distinction that matters throughout is RINGING-then-OFFHOOK (an answered incoming
 * call) versus a bare OFFHOOK (a call you placed). Getting that backwards is the whole
 * feature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallDirectionTest {

    private val context = RuntimeEnvironment.getApplication() as Context

    private val start = 1_754_000_000_000L

    private fun store() = CallDirectionStore(context)

    private val RINGING = TelephonyManager.EXTRA_STATE_RINGING
    private val OFFHOOK = TelephonyManager.EXTRA_STATE_OFFHOOK
    private val IDLE = TelephonyManager.EXTRA_STATE_IDLE

    // --- the pure state machine ---

    @Test
    fun ringingIsAlwaysIncoming() {
        assertEquals(CallDirections.INCOMING, CallDirectionTracker.directionFor(RINGING, null))
        assertEquals(CallDirections.INCOMING, CallDirectionTracker.directionFor(RINGING, IDLE))
    }

    @Test
    fun offhookWithNoPrecedingRingIsOutgoing() {
        assertEquals(CallDirections.OUTGOING, CallDirectionTracker.directionFor(OFFHOOK, IDLE))
        assertEquals(CallDirections.OUTGOING, CallDirectionTracker.directionFor(OFFHOOK, null))
    }

    @Test
    fun answeringARingingCallRecordsNothingFurther() {
        // The RINGING that came first already recorded this call as incoming. Returning a
        // direction here would overwrite it with "outgoing" the moment the user answers.
        assertNull(CallDirectionTracker.directionFor(OFFHOOK, RINGING))
    }

    @Test
    fun idleRecordsNothing() {
        assertNull(CallDirectionTracker.directionFor(IDLE, OFFHOOK))
        assertNull(CallDirectionTracker.directionFor(null, OFFHOOK))
    }

    // --- the store, driven the way the receiver drives it ---

    @Test
    fun recordsAnIncomingCallAcrossTheFullRingAnswerHangUpSequence() {
        val store = store()
        store.onPhoneState(RINGING, start)
        store.onPhoneState(OFFHOOK, start + 8_000)
        store.onPhoneState(IDLE, start + 120_000)

        // The recording starts when the call is answered, a little after the ring.
        assertEquals(CallDirections.INCOMING, store.directionNear(start + 8_000))
    }

    @Test
    fun recordsAnOutgoingCallAcrossDialAnswerHangUp() {
        val store = store()
        store.onPhoneState(OFFHOOK, start)
        store.onPhoneState(IDLE, start + 120_000)

        assertEquals(CallDirections.OUTGOING, store.directionNear(start + 15_000))
    }

    @Test
    fun anOutgoingCallAfterAnIncomingOneIsNotContaminatedByIt() {
        val store = store()
        store.onPhoneState(RINGING, start)
        store.onPhoneState(OFFHOOK, start + 5_000)
        store.onPhoneState(IDLE, start + 60_000)

        val laterDial = start + 10L * 60 * 1000
        store.onPhoneState(OFFHOOK, laterDial)

        assertEquals(CallDirections.INCOMING, store.directionNear(start))
        assertEquals(CallDirections.OUTGOING, store.directionNear(laterDial))
    }

    @Test
    fun returnsNothingWhenNoCallIsCloseEnoughInTime() {
        val store = store()
        store.onPhoneState(OFFHOOK, start)

        assertNull(store.directionNear(start + CallerLookup.TOLERANCE_MS + 1))
    }

    @Test
    fun returnsNothingWhenNothingWasEverRecorded() {
        assertNull(store().directionNear(start))
    }

    @Test
    fun picksTheCallNearestTheRecording() {
        val store = store()
        store.onPhoneState(OFFHOOK, start)
        store.onPhoneState(IDLE, start + 30_000)
        store.onPhoneState(RINGING, start + 100_000)

        assertEquals(CallDirections.INCOMING, store.directionNear(start + 95_000))
        assertEquals(CallDirections.OUTGOING, store.directionNear(start + 2_000))
    }

    @Test
    fun survivesBeingReadThroughASeparateInstance() {
        // The receiver and CallMonitorService each build their own store; they must see the
        // same records, which is the whole reason this is backed by SharedPreferences.
        store().onPhoneState(OFFHOOK, start)

        assertEquals(CallDirections.OUTGOING, store().directionNear(start))
    }

    @Test
    fun validOrNullNeverInventsADirection() {
        assertNull(CallDirections.validOrNull(null))
        assertNull(CallDirections.validOrNull(""))
        assertNull(CallDirections.validOrNull("unknown"))
        assertEquals(CallDirections.OUTGOING, CallDirections.validOrNull("outgoing"))
    }
}
