package com.brachaai.app

import android.content.Context
import android.provider.CallLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the selection half of [CallerLookup] — which log row wins, and what survives
 * normalization. The ContentResolver query itself has no JVM seam (Robolectric ships no
 * CallLog provider), so it is exercised on device instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallerLookupTest {

    private val lookup = CallerLookup(RuntimeEnvironment.getApplication() as Context)

    private val start = 1_754_000_000_000L

    @Test
    fun returnsNothingWhenNoEntriesAreNear() {
        assertEquals(CallLogMatch.NONE, lookup.selectBest(emptyList(), start))
    }

    @Test
    fun picksTheEntryClosestInTimeToTheRecording() {
        val entries = listOf(
            CallLogEntry("0501111111", start + 90_000, 30),
            CallLogEntry("0502222222", start + 5_000, 272),
        )

        val match = lookup.selectBest(entries, start)

        assertEquals("0502222222", match.number)
        assertEquals(272, match.durationSeconds)
    }

    @Test
    fun readsTheDurationOfTheWinningEntry() {
        val match = lookup.selectBest(listOf(CallLogEntry("0501234567", start, 272)), start)

        assertEquals(272, match.durationSeconds)
    }

    @Test
    fun treatsAZeroDurationAsUnknown() {
        // Missed and unanswered calls are logged with DURATION = 0. That is not a
        // zero-second call, it is the absence of one.
        val match = lookup.selectBest(listOf(CallLogEntry("0501234567", start, 0)), start)

        assertNull(match.durationSeconds)
        assertEquals("0501234567", match.number)
    }

    @Test
    fun treatsANegativeDurationAsUnknown() {
        val match = lookup.selectBest(listOf(CallLogEntry("0501234567", start, -1)), start)

        assertNull(match.durationSeconds)
    }

    @Test
    fun keepsTheDurationWhenTheNumberIsWithheld() {
        // The two fields are independently nullable: a withheld caller still had a call
        // of some length, and that length is worth showing.
        val match = lookup.selectBest(listOf(CallLogEntry("-1", start, 272)), start)

        assertNull(match.number)
        assertEquals(272, match.durationSeconds)
    }

    @Test
    fun normalizesTheWinningNumber() {
        val match = lookup.selectBest(listOf(CallLogEntry("+972 (54) 123-4567", start, 60)), start)

        assertEquals("+972541234567", match.number)
    }

    @Test
    fun treatsABlankNumberAsUnknownWithoutLosingTheDuration() {
        val match = lookup.selectBest(listOf(CallLogEntry("   ", start, 60)), start)

        assertNull(match.number)
        assertEquals(60, match.durationSeconds)
    }

    // --- direction ---

    private fun directionOf(rawType: Int): String? =
        lookup.selectBest(listOf(CallLogEntry("0501234567", start, 60, rawType)), start).callType

    @Test
    fun readsTheDirectionOfTheWinningEntry() {
        assertEquals(CallDirections.INCOMING, directionOf(CallLog.Calls.INCOMING_TYPE))
        assertEquals(CallDirections.OUTGOING, directionOf(CallLog.Calls.OUTGOING_TYPE))
        assertEquals(CallDirections.MISSED, directionOf(CallLog.Calls.MISSED_TYPE))
    }

    @Test
    fun countsEveryInboundFlavourAsIncoming() {
        // Voicemail, rejected, blocked and answered-elsewhere are all calls that came *to*
        // the user. They are named explicitly so the else branch can mean "unrecognised".
        assertEquals(CallDirections.INCOMING, directionOf(CallLog.Calls.VOICEMAIL_TYPE))
        assertEquals(CallDirections.INCOMING, directionOf(CallLog.Calls.REJECTED_TYPE))
        assertEquals(CallDirections.INCOMING, directionOf(CallLog.Calls.BLOCKED_TYPE))
        assertEquals(CallDirections.INCOMING, directionOf(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE))
    }

    @Test
    fun reportsAnUnrecognisedTypeAsUnknownRatherThanIncoming() {
        // A future Android call type must not be silently labelled incoming — that is the
        // failure mode this whole change exists to remove.
        assertNull(directionOf(99))
    }

    @Test
    fun reportsNoDirectionWhenTheCallLogIsUnavailable() {
        assertNull(CallLogMatch.NONE.callType)
        assertNull(lookup.selectBest(emptyList(), start).callType)
    }
}
