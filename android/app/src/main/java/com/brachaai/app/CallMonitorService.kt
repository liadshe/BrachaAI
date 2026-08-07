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
                audioProcessor.process(file)
                // The call that just uploaded produces a new summary and new tasks.
                briefingSync.syncNow()
            } catch (e: Exception) {
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
