package com.brachaai.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the floating briefing card for the duration of a call.
 *
 * Not a foreground service: a second persistent notification on every call would be worse
 * than the card itself, and the process is already kept alive by [CallMonitorService]. The
 * SYSTEM_ALERT_WINDOW grant is what exempts this from Android 12's background-start rules —
 * which is why the *routine* notification fallback is posted straight from the receiver
 * instead of starting this service (see [BriefingNotifier]). The one case that does fall back
 * from in here is an `addView` that throws despite the permission check having passed.
 */
class CallOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        Log.w(TAG, "Overlay timed out; a call-ended broadcast was probably missed")
        stopSelf()
    }

    private var windowManager: WindowManager? = null
    private var view: View? = null
    private var store: BriefingStore? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        store = BriefingStore.default(filesDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val phoneKey = intent.getStringExtra(EXTRA_PHONE_KEY)
                if (phoneKey.isNullOrBlank()) stopSelf() else showFor(phoneKey)
            }
            ACTION_DISMISS -> stopSelf()
            else -> stopSelf()
        }
        // START_NOT_STICKY: a card resurrected after a process death would have no call to
        // belong to.
        return START_NOT_STICKY
    }

    private fun showFor(phoneKey: String) {
        val briefing = store?.lookup(phoneKey)
        // The receiver already made this decision, but it looked the key up in its own
        // BriefingStore instance and the intent has been round-tripped through the system
        // since. A sync landing in between can leave a different answer here, so re-apply
        // the rule rather than trusting that the earlier one still holds.
        if (briefing == null || !briefing.hasContent) {
            Log.d(TAG, "No briefing worth showing for the ringing number")
            stopSelf()
            return
        }

        render(briefing)

        mainHandler.removeCallbacks(timeout)
        mainHandler.postDelayed(timeout, MAX_BRIEFING_LIFETIME_MS)

        refreshInBackground(briefing.contactId)
    }

    /**
     * Live refresh. The result is discarded unless the card is still on screen and still
     * belongs to the contact that was fetched — a response that arrives after the call ended
     * has nowhere to go, and one that arrives after the card moved on belongs to someone else.
     */
    private fun refreshInBackground(contactId: String) {
        scope.launch {
            val authStore = AuthStore(this@CallOverlayService)
            val fresh = BriefingClient(authStore, TokenRefresher(authStore)).fetchOne(contactId)
                ?: return@launch

            withContext(Dispatchers.Main) {
                val root = view ?: return@withContext

                // Call waiting reuses this service instance: a second RINGING arrives during
                // OFFHOOK with no intervening IDLE, so the card is rebound to contact B while
                // contact A's fetch is still in flight. bind() stamps the displayed id onto
                // the tag; without this check A's response repaints over B's card.
                if (root.tag != contactId) {
                    Log.d(TAG, "Discarding a refresh for a contact that is no longer shown")
                    return@withContext
                }

                // The card can be emptied out from under us — the user closes the last open
                // task in the web app mid-ring. Repainting would leave an avatar, a name and
                // an X, which the design forbids. Keep the stale-but-meaningful card instead
                // of tearing one down that the user is mid-glance at.
                if (!fresh.hasContent) {
                    Log.d(TAG, "Refresh returned an empty briefing; keeping the current card")
                    return@withContext
                }

                render(fresh)
            }
        }
    }

    private fun render(briefing: Briefing) {
        val root = view ?: inflate(briefing) ?: return
        bind(root, briefing)
    }

    private fun inflate(briefing: Briefing): View? {
        val manager = windowManager ?: return null
        val root = LayoutInflater.from(this).inflate(R.layout.overlay_call_briefing, null)

        root.findViewById<View>(R.id.overlay_close).setOnClickListener { stopSelf() }
        root.findViewById<View>(R.id.overlay_card).setOnClickListener {
            openContact(root.tag as? String)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE keeps key events with the dialer; the card is still clickable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // WindowManager.LayoutParams.y is in raw pixels, not dp — unlike every other
            // spacing value in this feature, which lives in XML and gets the dp conversion
            // for free. Convert explicitly so the offset is ~48dp on every density instead of
            // 48 raw px (a ~16dp offset on a 3x screen). Do not simplify this back to a bare
            // integer.
            y = (OVERLAY_TOP_MARGIN_DP * resources.displayMetrics.density).toInt()
        }

        return try {
            manager.addView(root, params)
            view = root
            root
        } catch (e: Exception) {
            // Permission revoked between the check and here, or an OEM refusing the window.
            // This is precisely the case the notification fallback exists for, so fall back
            // instead of showing nothing: we already know the briefing is worth showing and
            // that the card cannot attach. IDLE dismisses both renderers, so the notification
            // is torn down by the same broadcast that would have torn down the card.
            Log.e(TAG, "Could not attach the overlay; falling back to a notification", e)
            BriefingNotifier.show(this, briefing)
            stopSelf()
            null
        }
    }

    /**
     * Delegates to [bindBriefingCard], which [CallOverlayActivity] also uses — the locked and
     * unlocked cards must be indistinguishable, and one binder is what guarantees that.
     */
    private fun bind(root: View, briefing: Briefing) = bindBriefingCard(this, root, briefing)

    private fun openContact(contactId: String?) {
        if (contactId.isNullOrBlank()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CONTACT_ID, contactId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeout)
        view?.let { attached ->
            try {
                windowManager?.removeView(attached)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay was already detached", e)
            }
        }
        view = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallOverlayService"

        // Test-visible (not private) so PhoneStateReceiverTest can assert on the action of a
        // started intent instead of only its component — show() and dismiss() both target this
        // same service class, so the component alone can't tell the two apart.
        internal const val ACTION_SHOW = "com.brachaai.app.action.SHOW_OVERLAY"
        internal const val ACTION_DISMISS = "com.brachaai.app.action.DISMISS_OVERLAY"
        private const val EXTRA_PHONE_KEY = "com.brachaai.app.extra.PHONE_KEY"

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun show(context: Context, phoneKey: String) {
            val intent = Intent(context, CallOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_PHONE_KEY, phoneKey)
            }
            context.startService(intent)
        }

        fun dismiss(context: Context) {
            val intent = Intent(context, CallOverlayService::class.java).apply {
                action = ACTION_DISMISS
            }
            context.startService(intent)
        }
    }
}
