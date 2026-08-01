package com.brachaai.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The briefing card, shown over a *locked* screen.
 *
 * [CallOverlayService] draws the same card as a `TYPE_APPLICATION_OVERLAY` window, which the
 * platform layers *below* the keyguard — no flag changes that, so on a locked phone that card
 * renders where nobody can see it. Showing above the keyguard is an Activity capability
 * (`setShowWhenLocked`, which replaced the deprecated `FLAG_SHOW_WHEN_LOCKED` window flag in
 * API 27), so the locked case has to be an Activity. Both inflate the same layout through
 * [bindBriefingCard], so the user cannot tell which one they are looking at.
 *
 * **The window is deliberately small.** An Activity normally owns the whole screen, which
 * here would put an opaque-to-input surface on top of the dialer and leave the answer control
 * unreachable — the card would make the phone unanswerable, which is far worse than showing
 * no card. Instead the window is sized to the card and flagged `FLAG_NOT_TOUCH_MODAL`, so
 * every touch outside its bounds passes through to the call screen behind it.
 */
class CallOverlayActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        Log.w(TAG, "Locked card timed out; a call-ended broadcast was probably missed")
        finish()
    }

    /**
     * The card is torn down by a broadcast rather than by finishing from outside, because
     * `PhoneStateReceiver` has no handle on this instance. Registered at runtime and not
     * exported: only this app may end its own card.
     */
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }

    private var root: View? = null

    /** The key currently displayed, so a re-assert can rebuild the same card. */
    private var phoneKey: String? = null

    /**
     * Whether this card has already fought for the foreground once.
     *
     * Telecom launches the dialer's full-screen incoming-call activity a moment *after* the
     * PHONE_STATE broadcast this card reacts to, so the dialer resumes second and covers us.
     * Coming back once, after it settles, wins the exchange. Exactly once, though: if it
     * covers us again the dialer genuinely owns the screen, and a second attempt would turn
     * into a visible flicker war that makes the phone feel broken.
     */
    private var hasReasserted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showAboveKeyguard()

        root = layoutInflater.inflate(R.layout.overlay_call_briefing, null).also { view ->
            setContentView(view)
            view.findViewById<View>(R.id.overlay_close).setOnClickListener { finish() }
            view.findViewById<View>(R.id.overlay_card).setOnClickListener {
                openContact(view.tag as? String)
            }
        }

        sizeWindowToCard()
        registerDismissReceiver()

        if (!render(intent)) return
        mainHandler.postDelayed(timeout, MAX_BRIEFING_LIFETIME_MS)
    }

    /** A second call arriving while this card is up replaces its content rather than stacking. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun showAboveKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // API 26 only. These are the pre-27 equivalents the docs point away from.
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun sizeWindowToCard() {
        window.apply {
            // Full width, only as tall as the card, pinned to the top — the same geometry
            // CallOverlayService gives its WindowManager window, so the card sits in the same
            // place on both paths.
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)

            // Everything below the card belongs to the dialer. Without this flag the Activity
            // consumes the whole screen's input and the call cannot be answered.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

            attributes = attributes.apply {
                // Raw pixels, not dp — see OVERLAY_TOP_MARGIN_DP.
                y = (OVERLAY_TOP_MARGIN_DP * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun registerDismissReceiver() {
        val filter = IntentFilter(ACTION_DISMISS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dismissReceiver, filter)
        }
    }

    /**
     * Reclaims the foreground after the dialer's call screen has covered this card.
     *
     * See [hasReasserted] for why this happens at all, and why it happens only once.
     */
    override fun onStop() {
        super.onStop()
        if (hasReasserted || isFinishing) return
        val key = phoneKey ?: return

        hasReasserted = true
        mainHandler.postDelayed({
            // isFinishing covers the ordinary endings — the call ended, the user tapped the
            // card or its X — so a teardown that lands inside this delay is not undone.
            if (!isFinishing) {
                Log.d(TAG, "Card was covered by the call screen; reclaiming the foreground")
                show(this, key)
            }
        }, REASSERT_DELAY_MS)
    }

    /** @return false when there was nothing worth showing and the card has been finished. */
    private fun render(intent: Intent?): Boolean {
        val phoneKey = intent?.getStringExtra(EXTRA_PHONE_KEY)
        this.phoneKey = phoneKey
        val briefing = phoneKey?.let { BriefingStore.default(filesDir).lookup(it) }

        // Re-applied here for the same reason CallOverlayService re-applies it: the receiver
        // decided this against its own BriefingStore instance, and a sync can land in between.
        if (briefing == null || !briefing.hasContent) {
            Log.d(TAG, "No briefing worth showing for the ringing number")
            finish()
            return false
        }

        root?.let { bindBriefingCard(this, it, briefing) }
        return true
    }

    private fun openContact(contactId: String?) {
        if (contactId.isNullOrBlank()) return
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CONTACT_ID, contactId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeout)
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dismiss receiver was already unregistered", e)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallOverlayActivity"
        private const val EXTRA_PHONE_KEY = "com.brachaai.app.extra.PHONE_KEY"

        /** Internal rather than private so the receiver test can assert on it. */
        internal const val ACTION_DISMISS = "com.brachaai.app.action.DISMISS_LOCKED_OVERLAY"

        /**
         * How long to wait before reclaiming the foreground from the dialer's call screen.
         *
         * Long enough that the dialer has finished resuming — coming back too early just
         * loses the same race again — and short enough that the card is back before the user
         * has looked away. Tuned by hand on device; there is no callback that says "the call
         * screen has settled".
         */
        private const val REASSERT_DELAY_MS = 700L

        /**
         * True when the keyguard is up, i.e. the `WindowManager` card would be drawn
         * underneath it and never seen.
         */
        fun isDeviceLocked(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

        fun show(context: Context, phoneKey: String) {
            val intent = Intent(context, CallOverlayActivity::class.java).apply {
                putExtra(EXTRA_PHONE_KEY, phoneKey)
                // NO_ANIMATION: the card should appear with the ring, not slide in over it.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
        }

        fun dismiss(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_DISMISS).setPackage(context.packageName)
            )
        }
    }
}
