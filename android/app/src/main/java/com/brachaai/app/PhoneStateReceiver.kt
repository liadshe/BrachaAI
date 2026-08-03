package com.brachaai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Turns a ringing phone into a briefing card.
 *
 * Manifest-registered rather than a TelephonyCallback registered from a service:
 * ACTION_PHONE_STATE_CHANGED is exempt from Android 8's implicit-broadcast restrictions, so
 * this still fires when the app has been closed.
 *
 * The decision runs synchronously on the main thread. It reads one small JSON file and does
 * no network, which is well inside a receiver's budget — and the whole point is to decide
 * before the ring feels stale.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        // Recorded for *every* state, including the OFFHOOK the overlay ignores: telling an
        // outgoing call from an answered incoming one is precisely the RINGING-then-OFFHOOK
        // distinction, so the store has to see the whole sequence to read it. This is the
        // only source of call direction that survives READ_CALL_LOG being denied. It writes
        // two small SharedPreferences keys and never touches the overlay below.
        recordDirection(context, state)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> onRinging(context, intent)

            // The card is meant to persist through the answered call, so OFFHOOK is
            // deliberately not rendered. IDLE is the only thing that ends it.
            TelephonyManager.EXTRA_STATE_IDLE -> dismiss(context)
        }
    }

    /** Never allowed to break the overlay: a failed write costs one call's direction. */
    private fun recordDirection(context: Context, state: String?) {
        try {
            CallDirectionStore(context).onPhoneState(state, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Could not record call direction for state $state", e)
        }
    }

    private fun onRinging(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        // A null extra and an unrecognised caller both end in DoNothing, but they mean very
        // different things and only one is a bug. EXTRA_INCOMING_NUMBER is deprecated and is
        // withheld unless READ_PHONE_STATE *and* READ_CALL_LOG are both granted, so a null
        // here is the single most likely reason this feature silently does nothing on a real
        // device. Log it distinctly so that shows up in a bug report instead of hiding behind
        // "unknown caller".
        if (number == null) {
            Log.w(
                TAG,
                "Ringing with no EXTRA_INCOMING_NUMBER — READ_PHONE_STATE/READ_CALL_LOG " +
                    "denied, or this platform no longer populates the extra"
            )
        }

        val decider = OverlayDecider(BriefingStore.default(context.filesDir))
        when (val action = decider.decide(number, CallOverlayService.canDrawOverlays(context))) {
            is OverlayAction.DoNothing -> Log.d(TAG, "Nothing to show for this caller")

            // Both renderers re-read the briefing themselves, so no model crosses an Intent.
            is OverlayAction.Show -> when {
                action.asNotification -> BriefingNotifier.show(context, action.briefing)

                // One path, locked or not: CallOverlayService's window carries
                // FLAG_SHOW_WHEN_LOCKED, so it sits above the keyguard without ever taking
                // the foreground — which is what leaves the dialer's own answer and decline
                // controls working. There was briefly a separate Activity for the locked
                // case; it paused the dialer and made calls unanswerable. Do not reintroduce
                // one.
                else -> CallOverlayService.show(context, PhoneNormalizer.key(number)!!)
            }
        }
    }

    /** Every renderer is cleared: whichever one is up, the call is over. */
    private fun dismiss(context: Context) {
        CallOverlayService.dismiss(context)
        BriefingNotifier.dismiss(context)
    }

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }
}
