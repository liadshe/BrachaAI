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

        val decider = OverlayDecider(BriefingStore.default(context.filesDir))
        when (val action = decider.decide(number, CallOverlayService.canDrawOverlays(context))) {
            is OverlayAction.DoNothing -> Log.d(TAG, "Nothing to show for this caller")

            is OverlayAction.Show -> if (action.asNotification) {
                BriefingNotifier.show(context, action.briefing)
            } else {
                // The service re-reads the briefing itself, so no model crosses the Intent.
                CallOverlayService.show(context, PhoneNormalizer.key(number)!!)
            }
        }
    }

    /** Both renderers are cleared: whichever is up, the call is over. */
    private fun dismiss(context: Context) {
        CallOverlayService.dismiss(context)
        BriefingNotifier.dismiss(context)
    }

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }
}
