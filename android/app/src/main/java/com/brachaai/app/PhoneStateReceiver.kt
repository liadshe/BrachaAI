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

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> onRinging(context, intent)

            // The card is meant to persist through the answered call, so OFFHOOK is
            // deliberately not handled. IDLE is the only thing that ends it.
            TelephonyManager.EXTRA_STATE_IDLE -> dismiss(context)
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

                // A TYPE_APPLICATION_OVERLAY window is layered BELOW the keyguard, so on a
                // locked phone CallOverlayService draws a card nobody can see. Showing above
                // the keyguard is an Activity capability (setShowWhenLocked), so the locked
                // case takes CallOverlayActivity.
                //
                // canHandleCalls is a safety gate, not a feature check. That Activity takes
                // the foreground, which pauses the dialer, and a paused call screen ignores
                // its own answer gesture — so the card must be able to answer the call
                // itself. Without ANSWER_PHONE_CALLS it cannot, and showing it anyway would
                // leave the user unable to take the call. Measured on device; do not relax
                // this condition.
                CallOverlayActivity.isDeviceLocked(context) &&
                    CallOverlayActivity.canHandleCalls(context) ->
                    CallOverlayActivity.show(context, PhoneNormalizer.key(number)!!)

                // Locked, but we could not answer the call for them. The notification shows
                // on the lock screen without ever owning the foreground, so the dialer stays
                // fully usable.
                CallOverlayActivity.isDeviceLocked(context) ->
                    BriefingNotifier.show(context, action.briefing)

                else -> CallOverlayService.show(context, PhoneNormalizer.key(number)!!)
            }
        }
    }

    /** Every renderer is cleared: whichever one is up, the call is over. */
    private fun dismiss(context: Context) {
        CallOverlayService.dismiss(context)
        CallOverlayActivity.dismiss(context)
        BriefingNotifier.dismiss(context)
    }

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }
}
