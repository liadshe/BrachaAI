package com.brachaai.app

import android.content.Context
import android.provider.CallLog
import android.util.Log
import kotlin.math.abs

/**
 * What the call log knew about a call. The fields are independently nullable on
 * purpose: a withheld number carries a perfectly good duration, and a missed-call entry
 * carries a valid number with `DURATION = 0`.
 */
data class CallLogMatch(
    val number: String?,
    val durationSeconds: Int?,
    val callType: String? = null
) {
    companion object {
        /** Nothing usable: no permission, no matching entry, or a failed query. */
        val NONE = CallLogMatch(null, null, null)
    }
}

/** One raw call log row, before normalization. Internal so the tests can build them. */
internal data class CallLogEntry(
    val number: String?,
    val dateMillis: Long,
    val durationSeconds: Int,
    val rawType: Int = 1
)

/**
 * Finds the other party's number, call duration, and call direction by matching the call log
 * entry closest to the recording's start time.
 */
class CallerLookup(context: Context) {

    private val appContext = context.applicationContext

    fun findNear(callStartMillis: Long): CallLogMatch {
        val from = callStartMillis - TOLERANCE_MS
        val to = callStartMillis + TOLERANCE_MS

        return try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf(from.toString(), to.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                val entries = mutableListOf<CallLogEntry>()
                while (cursor.moveToNext()) {
                    entries += CallLogEntry(
                        number = cursor.getString(numberIdx),
                        dateMillis = cursor.getLong(dateIdx),
                        durationSeconds = cursor.getInt(durationIdx),
                        rawType = cursor.getInt(typeIdx)
                    )
                }

                selectBest(entries, callStartMillis).also {
                    if (it.number == null) Log.d(TAG, "No usable caller number near $callStartMillis")
                }
            } ?: CallLogMatch.NONE
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG not granted; caller number and duration unavailable")
            CallLogMatch.NONE
        } catch (e: Exception) {
            Log.w(TAG, "Call log lookup failed", e)
            CallLogMatch.NONE
        }
    }

    fun findCallNear(callStartMillis: Long): CallLogMatch = findNear(callStartMillis)
    fun findNumberNear(callStartMillis: Long): String? = findNear(callStartMillis).number

    /**
     * Picks the entry nearest [callStartMillis] and reduces it to what callers can use.
     *
     * Pure, and `internal` rather than private, so every branch is unit-testable without a
     * ContentResolver — Robolectric ships no CallLog provider, so a query-level test would
     * only be asserting against a fake of our own making.
     */
    internal fun selectBest(entries: List<CallLogEntry>, callStartMillis: Long): CallLogMatch {
        // minByOrNull keeps the first on a tie, and the query is ordered DATE DESC, so a
        // tie resolves to the newer entry — the same behaviour as the older loop.
        val best = entries.minByOrNull { abs(it.dateMillis - callStartMillis) }
            ?: return CallLogMatch.NONE

        // Every inbound flavour is named explicitly rather than swept into a catch-all, so
        // the `else` can mean what it says: a type this version of Android has that we do
        // not recognise. That returns null — "we do not know" — instead of asserting
        // "incoming", which is how an unreadable direction used to become a confident and
        // wrong label on every call in the app.
        val typeStr = when (best.rawType) {
            CallLog.Calls.OUTGOING_TYPE -> CallDirections.OUTGOING
            CallLog.Calls.MISSED_TYPE -> CallDirections.MISSED
            CallLog.Calls.INCOMING_TYPE,
            CallLog.Calls.VOICEMAIL_TYPE,
            CallLog.Calls.REJECTED_TYPE,
            CallLog.Calls.BLOCKED_TYPE,
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallDirections.INCOMING
            else -> null
        }

        return CallLogMatch(
            number = normalize(best.number),
            // A missed or unanswered call is logged with 0. Never report that as a call
            // that lasted no time; it is a call whose length we do not know.
            durationSeconds = best.durationSeconds.takeIf { it > 0 },
            callType = typeStr
        )
    }

    /** Digits only, preserving a leading '+'. Withheld/private numbers become null. */
    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed in WITHHELD) return null
        val prefix = if (trimmed.startsWith("+")) "+" else ""
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.isEmpty()) null else prefix + digits
    }

    companion object {
        private const val TAG = "CallerLookup"
        const val TOLERANCE_MS = 2L * 60 * 1000
        private val WITHHELD = setOf("-1", "-2", "-3")
    }
}
