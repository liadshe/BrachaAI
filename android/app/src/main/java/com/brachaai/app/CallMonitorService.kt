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
import kotlinx.coroutines.launch
import java.io.File

class CallMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileObserver: FileObserver? = null
    private lateinit var audioProcessor: AudioProcessor
    private lateinit var notificationManager: NotificationManager
    private val errorNotificationId = java.util.concurrent.atomic.AtomicInteger(100)

    override fun onCreate() {
        super.onCreate()
        audioProcessor = AudioProcessor(
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            cacheDir = cacheDir,
            authStore = AuthStore(this),
            pendingStore = PendingUploadStore(File(filesDir, "pending")),
            callerLookup = CallerLookup(this)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FLUSH) flushPending()
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
                audioProcessor.processAndSendToBackend(file)
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
    }
}
