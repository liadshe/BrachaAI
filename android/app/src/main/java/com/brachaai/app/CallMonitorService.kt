package com.brachaai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class CallMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileObserver: FileObserver? = null
    private lateinit var audioProcessor: AudioProcessor
    private lateinit var briefingSync: BriefingSync
    private lateinit var pendingAudioQueue: PendingAudioQueue
    private var networkWatcher: NetworkWatcher? = null
    private lateinit var notificationManager: NotificationManager
    private val errorNotificationId = java.util.concurrent.atomic.AtomicInteger(100)

    override fun onCreate() {
        super.onCreate()
        // One AuthStore instance shared with the refresher: a rotated pair written by the
        // refresher must be visible to the uploader that asked for it. The TokenRefresher
        // itself is shared here too, though it no longer strictly has to be for correctness:
        // refresh()'s single-flight lock is process-wide (see TokenRefresher.refreshLock), so
        // even a separate TokenRefresher instance for AudioProcessor and one for
        // BriefingClient would still serialize correctly against each other. Sharing one
        // instance is kept anyway — one fewer OkHttpClient/object to construct per service.
        val authStore = AuthStore(this)
        val tokenRefresher = TokenRefresher(authStore)
        audioProcessor = AudioProcessor(
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            cacheDir = cacheDir,
            authStore = authStore,
            pendingStore = PendingUploadStore(File(filesDir, "pending")),
            callerLookup = CallerLookup(this),
            settingsStore = SettingsStore(this),
            tokenRefresher = tokenRefresher,
            // Same store PhoneStateReceiver writes to; it is a thin wrapper over
            // SharedPreferences, so a second instance here shares the same underlying data.
            callDirectionStore = CallDirectionStore(this)
        )
        briefingSync = BriefingSync(
            client = BriefingClient(authStore, tokenRefresher),
            store = BriefingStore.default(filesDir),
        )
        pendingAudioQueue = PendingAudioQueue(
            watchDir = File(WATCH_PATH),
            index = RecordingIndex.default(filesDir),
            processor = audioProcessor,
            onStuck = { name, reason -> notifyStuck(name, reason) }
        )
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()

        val notification = buildMonitoringNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startWatching()
        isRunning = true
        flushPending()
        // Registering delivers an immediate callback for the network the device is already
        // on, so this also covers service start and boot.
        networkWatcher = NetworkWatcher(this) { sweepPendingAudio() }.also { it.start() }
        startBriefingSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FLUSH -> flushPending()
            ACTION_SYNC_BRIEFINGS -> syncBriefings()
        }
        return START_STICKY
    }

    private fun flushPending() {
        serviceScope.launch {
            try {
                audioProcessor.flushPending()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush pending uploads", e)
            }
        }
    }

    private fun sweepPendingAudio() {
        serviceScope.launch {
            try {
                pendingAudioQueue.sweep()
                // Unconditional, unlike the sync in handleNewFile: sweep() returns Unit, so
                // there is no outcome here to gate on — the service cannot tell whether this
                // sweep actually landed a call or found nothing pending. A briefing fetch
                // that turns up nothing new is just a cheap GET, and this is the only trigger
                // for the feature's headline scenario: a call recorded while offline finally
                // uploading once the network returns. Without this, that call's briefing
                // would not refresh until the six-hour tick or the app next comes to the
                // foreground.
                briefingSync.syncNow()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sweep pending recordings", e)
            }
        }
    }

    private fun syncBriefings() {
        serviceScope.launch {
            try {
                briefingSync.syncNow()
            } catch (e: Exception) {
                Log.e(TAG, "Briefing sync failed", e)
            }
        }
    }

    /**
     * Periodic refresh, riding on this already-persistent service rather than adding
     * WorkManager for one job. If this service is dead the app is not recording calls
     * either, so there is nothing new to sync.
     */
    private fun startBriefingSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    briefingSync.syncNow()
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic briefing sync failed", e)
                }
                delay(BRIEFING_SYNC_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        fileObserver?.stopWatching()
        networkWatcher?.stop()
        networkWatcher = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWatching() {
        val watchDir = File(WATCH_PATH)
        if (!watchDir.exists() && !watchDir.mkdirs()) {
            Log.w(TAG, "Could not create watch directory: $WATCH_PATH")
        }

        // Use FileObserver.CLOSE_WRITE instead of just CLOSE_WRITE
        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(watchDir, FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) handleNewFile(File(watchDir, path))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(WATCH_PATH, FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) handleNewFile(File(watchDir, path))
                }
            }
        }
        fileObserver?.startWatching()
    }

    private fun handleNewFile(file: File) {
        serviceScope.launch {
            try {
                val outcome = pendingAudioQueue.processNow(file)
                // Deliberately after processNow returns: the queue's mutex is not reentrant,
                // so sweeping from inside would deadlock.
                //
                // Guarded on Completed rather than run unconditionally: processNow no longer
                // throws on an ordinary failure, so an offline call comes back RetryLater, not
                // an exception. Sweeping anyway would immediately retry every other pending
                // recording with the same no-network condition that just failed this one,
                // burning one of their five attempts for nothing — five offline calls would
                // drive every pending recording to stuck, exactly the data loss this queue
                // exists to prevent.
                //
                // Completed is not, by itself, proof the network and token are both good —
                // it also covers "delivery failed but the transcript is durably queued"
                // (queuedTranscriptIsDurable), which happens precisely when the backend is
                // unreachable. Sweeping on that Completed re-transcribes every other pending
                // recording — real OpenAI spend — against a backend that may still be down.
                // That costs money, not data or an attempt, so the gate stays on Completed;
                // this comment exists so the next reader doesn't read this as "network is
                // confirmed good" and rely on that elsewhere.
                if (outcome is ProcessOutcome.Completed) {
                    // The call that just uploaded produces a new summary and new tasks.
                    briefingSync.syncNow()
                    sweepPendingAudio()
                }
            } catch (e: CancellationException) {
                // Not a processing failure — the service is shutting down (serviceScope.cancel()
                // in onDestroy). AudioProcessor.process rethrows cancellation for the same
                // reason: swallowing it here would misreport a clean shutdown as a failure and
                // post a bogus "Failed to process" notification on every stop.
                throw e
            } catch (e: Exception) {
                // processNow does not throw for ordinary processing failures — those are
                // recorded in the index and, if terminal, reported via notifyStuck instead.
                // Nor does anything reachable from here throw for a corrupt index or a missing
                // watch directory; RecordingIndex swallows its own read/write failures. This
                // catch is a last-resort guard for genuinely unexpected runtime failures, not
                // for any specific cause — with CancellationException carved out above, there
                // is currently no known failure that reaches it, and that is the point: it
                // exists for whatever the next one turns out to be.
                Log.e(TAG, "Failed to process ${file.name}", e)
                notifyError(file.name, e.message ?: "Unknown error")
            }
        }
    }

    private fun notifyError(filename: String, message: String) {
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setContentTitle("Failed to process: $filename")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(errorNotificationId.getAndIncrement(), notification)
    }

    /**
     * Posted once, when a recording is given up on. The audio file is still on disk and is
     * never deleted; there is deliberately no retry action, so this is purely a heads-up
     * that one call will not appear in the app.
     */
    private fun notifyStuck(filename: String, reason: String) {
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setContentTitle("Could not process: $filename")
            .setContentText("The recording was kept on your phone. $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("The recording was kept on your phone. $reason"))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(errorNotificationId.getAndIncrement(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val errorChannel = NotificationChannel(
                ERROR_CHANNEL_ID,
                "Processing Errors",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(monitorChannel)
            notificationManager.createNotificationChannel(errorChannel)
        }
    }

    private fun buildMonitoringNotification(): Notification =
        NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setContentTitle("BrachaAI")
            .setContentText("Monitoring call recordings...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val WATCH_PATH = "/storage/emulated/0/Recordings/Call"
        const val ACTION_FLUSH = "com.brachaai.app.action.FLUSH"
        const val ACTION_SYNC_BRIEFINGS = "com.brachaai.app.action.SYNC_BRIEFINGS"
        private val BRIEFING_SYNC_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)
        @Volatile var isRunning = false
        private const val NOTIFICATION_ID = 1
        private const val MONITOR_CHANNEL_ID = "call_monitor"
        private const val ERROR_CHANNEL_ID = "call_monitor_errors"
        private const val TAG = "CallMonitorService"

        /** Asks the running service to retry queued uploads — called right after login. */
        fun requestFlush(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java).apply { action = ACTION_FLUSH }
            context.startForegroundService(intent)
        }

        /** Asks the running service to refresh the overlay's briefing snapshot. */
        fun requestBriefingSync(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_SYNC_BRIEFINGS
            }
            context.startForegroundService(intent)
        }
    }
}
