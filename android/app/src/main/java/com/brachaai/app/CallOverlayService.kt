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
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Holds the floating briefing card for the duration of a call.
 *
 * Not a foreground service: a second persistent notification on every call would be worse
 * than the card itself, and the process is already kept alive by [CallMonitorService]. The
 * SYSTEM_ALERT_WINDOW grant is what exempts this from Android 12's background-start rules —
 * which is why the notification fallback never comes through here (see [BriefingNotifier]).
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
        if (briefing == null) {
            Log.d(TAG, "No cached briefing for the ringing number")
            stopSelf()
            return
        }

        render(briefing)

        mainHandler.removeCallbacks(timeout)
        mainHandler.postDelayed(timeout, MAX_LIFETIME_MS)

        refreshInBackground(briefing.contactId)
    }

    /**
     * Live refresh. The result is discarded unless the card is still on screen — a response
     * that arrives after the call ended has nowhere to go.
     */
    private fun refreshInBackground(contactId: String) {
        scope.launch {
            val authStore = AuthStore(this@CallOverlayService)
            val fresh = BriefingClient(authStore, TokenRefresher(authStore)).fetchOne(contactId)
                ?: return@launch

            withContext(Dispatchers.Main) {
                if (view != null) render(fresh)
            }
        }
    }

    private fun render(briefing: Briefing) {
        val root = view ?: inflate() ?: return
        bind(root, briefing)
    }

    private fun inflate(): View? {
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
            Log.e(TAG, "Could not attach the overlay", e)
            stopSelf()
            null
        }
    }

    private fun bind(root: View, briefing: Briefing) {
        root.tag = briefing.contactId
        root.findViewById<TextView>(R.id.overlay_name).text = briefing.name

        val summary = briefing.lastCallSummary?.takeIf { it.isNotBlank() }
        root.findViewById<View>(R.id.overlay_summary_section).visibility =
            if (summary == null) View.GONE else View.VISIBLE
        summary?.let { root.findViewById<TextView>(R.id.overlay_summary).text = it }

        val shown = briefing.openTasks.take(MAX_TASKS_SHOWN)
        root.findViewById<View>(R.id.overlay_tasks_section).visibility =
            if (shown.isEmpty()) View.GONE else View.VISIBLE

        val container = root.findViewById<LinearLayout>(R.id.overlay_tasks)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        shown.forEach { task ->
            val row = inflater.inflate(R.layout.overlay_task_item, container, false)
            row.findViewById<TextView>(R.id.overlay_task_title).text = task.title
            container.addView(row)
        }

        // Counted from the untruncated total, not the list, which is capped twice over.
        val hidden = briefing.openTaskCount - shown.size
        val more = root.findViewById<TextView>(R.id.overlay_more_tasks)
        if (hidden > 0) {
            more.text = getString(R.string.overlay_more_tasks, hidden)
            more.visibility = View.VISIBLE
        } else {
            more.visibility = View.GONE
        }
    }

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
        private const val ACTION_SHOW = "com.brachaai.app.action.SHOW_OVERLAY"
        private const val ACTION_DISMISS = "com.brachaai.app.action.DISMISS_OVERLAY"
        private const val EXTRA_PHONE_KEY = "com.brachaai.app.extra.PHONE_KEY"

        /** Target top offset for the card, in dp — converted to px against display density. */
        private const val OVERLAY_TOP_MARGIN_DP = 48

        /**
         * Backstop only — a dropped call-ended broadcast must not strand a card forever.
         * Set well past any plausible call length; IDLE is what normally ends the card.
         */
        private val MAX_LIFETIME_MS = TimeUnit.MINUTES.toMillis(30)

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
