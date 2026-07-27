package com.brachaai.app

import android.content.Context
import android.provider.CallLog
import android.util.Log
import kotlin.math.abs

/**
 * Finds the phone number of the other party by matching the call log entry
 * closest to the recording's start time.
 */
class CallerLookup(context: Context) {

    private val appContext = context.applicationContext

    fun findNumberNear(callStartMillis: Long): String? {
        val from = callStartMillis - TOLERANCE_MS
        val to = callStartMillis + TOLERANCE_MS

        return try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf(from.toString(), to.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

                var best: String? = null
                var bestDelta = Long.MAX_VALUE

                while (cursor.moveToNext()) {
                    val delta = abs(cursor.getLong(dateIdx) - callStartMillis)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        best = cursor.getString(numberIdx)
                    }
                }

                normalize(best).also {
                    if (it == null) Log.d(TAG, "No usable caller number near $callStartMillis")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG not granted; caller number unavailable")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Call log lookup failed", e)
            null
        }
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
