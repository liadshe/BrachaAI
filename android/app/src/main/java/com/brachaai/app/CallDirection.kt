package com.brachaai.app

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The directions a call can have on the wire.
 *
 * There is deliberately no "unknown" *value* here: not knowing is represented by `null`,
 * so it cannot be confused with a direction we actually observed. Every layer that used
 * to substitute [INCOMING] for a missing answer now passes the null through — a call
 * whose direction we never learned must not be labelled as one we did.
 */
object CallDirections {
    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"
    const val MISSED = "missed"

    val VALID = setOf(INCOMING, OUTGOING, MISSED)

    /** The value if it is one we recognise, otherwise null. Never guesses. */
    fun validOrNull(raw: String?): String? = raw?.takeIf { it in VALID }
}

/** One call as the phone-state broadcasts saw it. */
internal data class DirectionRecord(val startMillis: Long, val direction: String)

/**
 * Decides what a phone-state transition says about a call's direction.
 *
 * Pure and with no Android state of its own — only the broadcast's own string constants —
 * so every branch is unit-testable without a receiver, a Context or a device.
 */
internal object CallDirectionTracker {

    /**
     * The direction to record for [state], or null to record nothing.
     *
     * RINGING is unambiguous: only an inbound call rings. OFFHOOK is the ambiguous one —
     * it fires both when you dial out *and* when you answer something that was ringing —
     * so it only means "outgoing" when no ring preceded it. Answering an incoming call
     * returns null rather than a direction, because the RINGING that came first already
     * recorded this call and must not be overwritten.
     */
    fun directionFor(state: String?, previousState: String?): String? = when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> CallDirections.INCOMING

        TelephonyManager.EXTRA_STATE_OFFHOOK ->
            if (previousState == TelephonyManager.EXTRA_STATE_RINGING) null
            else CallDirections.OUTGOING

        else -> null
    }
}

/**
 * Remembers which way recent calls went, so a recording can be matched back to one.
 *
 * This exists because the call log is not a reliable source of direction: `READ_CALL_LOG`
 * is hard-restricted on API 29+ and can be auto-denied with no dialog, which leaves
 * [CallerLookup] returning [CallLogMatch.NONE] and the direction unknowable. The
 * phone-state broadcasts need only `READ_PHONE_STATE`, which is an ordinary dangerous
 * permission the user can actually grant, so this keeps working where the call log does
 * not. The call log stays the preferred source when it *is* readable — it is the
 * platform's own record — and this is the fallback beneath it.
 *
 * Records are keyed by the call's start time (the RINGING or dial instant), matching
 * `CallLog.Calls.DATE` semantics, so [directionNear] can use the same tolerance window as
 * [CallerLookup.findNear] and the two sources agree on what "this call" means.
 *
 * Backed by plain SharedPreferences rather than a file: writes happen on the main thread
 * inside a BroadcastReceiver, where the budget is small and the payload is a handful of
 * longs. It holds no personal data — no numbers, no names, just a time and a direction.
 *
 * `@Synchronized` here locks the instance, and PhoneStateReceiver and CallMonitorService
 * each construct their own — so unlike TokenRefresher, this is *not* a process-wide
 * guarantee. It does not need to be: [onPhoneState] is the only mutator and the receiver
 * is its only caller, so the read-modify-write below has a single writer by construction.
 * CallMonitorService only ever reads. Adding a second writer would break that and would
 * need the same companion-object lock TokenRefresher uses.
 */
class CallDirectionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records what [state] implies, if anything, and remembers the state for next time.
     *
     * [nowMillis] is passed in rather than read here so the receiver and the tests agree on
     * the clock.
     */
    @Synchronized
    fun onPhoneState(state: String?, nowMillis: Long) {
        val previous = prefs.getString(KEY_LAST_STATE, null)
        prefs.edit().putString(KEY_LAST_STATE, state).apply()

        val direction = CallDirectionTracker.directionFor(state, previous) ?: return
        record(DirectionRecord(nowMillis, direction))
    }

    /**
     * The direction of the call that started nearest [callStartMillis], or null when
     * nothing is close enough to be the same call.
     */
    @Synchronized
    fun directionNear(callStartMillis: Long): String? =
        readAll()
            .filter { abs(it.startMillis - callStartMillis) <= CallerLookup.TOLERANCE_MS }
            .minByOrNull { abs(it.startMillis - callStartMillis) }
            ?.direction

    private fun record(entry: DirectionRecord) {
        val kept = (readAll() + entry)
            .filter { entry.startMillis - it.startMillis <= MAX_AGE_MS }
            .sortedBy { it.startMillis }
            .takeLast(MAX_ENTRIES)

        val array = JSONArray()
        kept.forEach {
            array.put(JSONObject().put(FIELD_START, it.startMillis).put(FIELD_DIRECTION, it.direction))
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun readAll(): List<DirectionRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val direction = CallDirections.validOrNull(obj.optString(FIELD_DIRECTION))
                    ?: return@mapNotNull null
                DirectionRecord(obj.optLong(FIELD_START), direction)
            }
        } catch (e: Exception) {
            // A corrupt blob costs one call's direction, which is already an optional
            // field. Never let it take down a broadcast receiver or an upload.
            Log.w(TAG, "Discarding unreadable call-direction records", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "CallDirectionStore"
        private const val PREFS_NAME = "call_direction"
        private const val KEY_RECORDS = "records"
        private const val KEY_LAST_STATE = "last_state"
        private const val FIELD_START = "start"
        private const val FIELD_DIRECTION = "direction"

        /** A recording is processed within minutes; a day is generous and stays tiny. */
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val MAX_ENTRIES = 50
    }
}
