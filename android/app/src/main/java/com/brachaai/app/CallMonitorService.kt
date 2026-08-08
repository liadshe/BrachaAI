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
import androidx.annotation.RequiresApi
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

    /**
     * Set when the system refused the foreground start or timed us out. Read from
     * `onStartCommand`, which runs on the main thread, and written from [enterForeground] and
     * [onTimeout], which also do — `@Volatile` is belt-and-braces for the restart path.
     */
    @Volatile
    private var foregroundStartRefused = false

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
            onStuck = { name, reason -> notifyStuck(name, reason) },
            // App-private, alongside the index. Deliberately not in shared storage: the two
            // belong together, so clearing app data drops both and the folder is simply
            // re-adopted rather than half-adopted and half re-uploaded.
            baselineMarker = File(filesDir, "recordings-baseline")
        )
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()

        // Nothing below this line runs if the system refuses us the foreground: there is no
        // point watching a directory in a process Android is about to cache or kill.
        if (!enterForeground()) return

        startWatching()
        isRunning = true
        flushPending()
        // Registering delivers an immediate callback for the network the device is already
        // on, so this also covers service start and boot.
        networkWatcher = NetworkWatcher(this) { sweepPendingAudio() }.also { it.start() }
        startBriefingSyncLoop()
    }

    /**
     * Enters the foreground, or reports that the system would not let us.
     *
     * `startForeground` is not a call that can be assumed to succeed. Two refusals have both
     * killed this app in production, and neither is a bug in our own logic:
     *
     * - `ForegroundServiceStartNotAllowedException: Time limit already exhausted` — the daily
     *   budget for the service type is spent. This is what the `dataSync` 6-hour cap did every
     *   day: `START_STICKY` restarted the service into a start it could not legally make, so
     *   the restart crashed ~400ms in and kept crashing on the backoff.
     * - `ForegroundServiceStartNotAllowedException: ... mAllowStartForeground false` — the app
     *   was in the background and had no exemption to start a foreground service at all.
     *
     * Both are the system telling us "not now", which is survivable information, not a fatal
     * error. Uncaught, it propagates out of `onCreate` as `RuntimeException: Unable to create
     * service` and the user sees "BrachaAI keeps stopping". Caught, the service stops quietly
     * and the app lives; [MainActivity] starts it again on the next resume, and bringing the
     * app to the foreground is also what resets the system's timer.
     *
     * The type must match `android:foregroundServiceType` in the manifest or the call throws
     * regardless of budget.
     */
    private fun enterForeground(): Boolean {
        val notification = buildMonitoringNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Deliberately broad. The documented refusals are IllegalStateException subclasses,
            // but an OEM refusing the notification or a revoked POST_NOTIFICATIONS would arrive
            // as something else, and every one of them means the same thing here: we are not
            // going to be a foreground service right now, and that must not be fatal.
            foregroundStartRefused = true
            Log.e(TAG, "The system refused the foreground start; stopping quietly instead of crashing", e)
            stopSelf()
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A refused foreground start already called stopSelf, but the start command that
        // created us is still delivered. START_NOT_STICKY here stops Android relaunching us
        // straight back into the same refusal — the condition is a daily budget or a
        // background-start restriction, and neither clears in the seconds a restart takes.
        // MainActivity.startMonitorService brings us back once the user is looking at the app,
        // which is also when the system's timer resets.
        if (foregroundStartRefused) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_FLUSH -> flushPending()
            ACTION_SYNC_BRIEFINGS -> syncBriefings()
        }
        return START_STICKY
    }

    /**
     * The system's "your time is up" tap, for any foreground service type that has a time
     * budget. We have a few seconds to call [stopSelf]; miss that window and the process is
     * killed with `ForegroundServiceDidNotStopInTimeException`, which is the crash that took
     * this app down daily under `dataSync`.
     *
     * `specialUse` carries no budget today, so this should never fire. It is here because the
     * absence of this method is exactly what turned a routine system limit into a crash loop,
     * and because which types are capped is Google's decision to change, not ours — if
     * `specialUse` is capped in a future release, this degrades to a clean stop on its own.
     *
     * Deliberately does no cleanup beyond stopping: `onDestroy` already tears down the
     * observer, the network watcher and the coroutine scope, and anything slow here would miss
     * the few-second window and cause the very crash it exists to prevent.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "The system timed out this foreground service (type $fgsType); stopping now")
        foregroundStartRefused = true
        stopSelf()
    }

    /**
     * Runs one piece of background work, letting cancellation through.
     *
     * A [CancellationException] here is not a failure: it means `onDestroy` cancelled
     * `serviceScope`. Catching it as an error logs a spurious ERROR on every clean stop and
     * swallows the signal structured concurrency depends on, so it is rethrown ahead of the
     * general catch — the same fix already applied to `handleNewFile` and
     * `AudioProcessor.process`. Everything else is logged and swallowed: none of these jobs
     * is allowed to take the service down.
     */
    private suspend fun runGuarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
        }
    }

    private fun flushPending() {
        serviceScope.launch {
            runGuarded("Flushing pending uploads") { audioProcessor.flushPending() }
        }
    }

    private fun sweepPendingAudio() {
        serviceScope.launch {
            runGuarded("Sweeping pending recordings") {
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
            }
            // Deliberately outside the block above, so it runs even if the sweep threw, and
            // deliberately after sweep() has returned — i.e. after PendingAudioQueue's mutex
            // is released. A drain walks up to 200 queued transcripts at 30s connect / 60s
            // read timeouts; it used to run inside AudioProcessor's success branch with that
            // mutex held, which could block every processNow for hours.
            runGuarded("Queue drain after a sweep") { audioProcessor.flushPending() }
        }
    }

    private fun syncBriefings() {
        serviceScope.launch {
            runGuarded("Briefing sync") { briefingSync.syncNow() }
        }
    }

    /**
     * Periodic refresh, riding on this already-persistent service rather than adding
     * WorkManager for one job. If this service is dead the app is not recording calls
     * either, so there is nothing new to sync.
     *
     * The sweep rides the same tick — no new timer and no new wakeup. It is the backstop for
     * the failures that are *not* correlated with a network change. The other two triggers
     * are a validated-network transition and a `Completed` `handleNewFile`; between them they
     * miss the case of a transcription that fails while online (an OpenAI 429 or 5xx) on a
     * phone that stays on one Wi-Fi and takes no further calls. Such a recording would sit at
     * one attempt forever: never retried, never reaching five attempts, never marked stuck,
     * and so never reported to the user at all — silently lost, which is the failure this
     * whole queue exists to prevent.
     */
    private fun startBriefingSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                runGuarded("Periodic briefing sync") { briefingSync.syncNow() }
                delay(BRIEFING_SYNC_INTERVAL_MS)
                // Deliberately after the delay rather than before it. The loop's first pass
                // runs inside onCreate, before NetworkWatcher has reported whether there is a
                // validated network at all; sweeping there would burn one of every pending
                // recording's five attempts against a connection that may well be down, and
                // BootReceiver restarts this service on every boot, so a handful of offline
                // reboots would strand every recording. The backstop exists for a phone that
                // has been sitting on one network for hours, so it loses nothing by waiting
                // for the first tick.
                //
                // Gated on connectivity, unlike the other two triggers. This one fires on a
                // timer with no evidence behind it, so offline it would burn one of every
                // pending recording's five attempts per tick: a phone offline for ~30 hours
                // sees five ticks and marks the lot `stuck` — kept and notified, but never
                // retried again. A long flight or a weekend without signal reaches that, and
                // it is the exact stranding this queue exists to prevent.
                //
                // UNKNOWN sweeps anyway — see shouldSweepOnConnection. The other two triggers
                // are deliberately not gated: NetworkWatcher's callback already fires only on
                // a validated network, and handleNewFile's sweep is already gated on Completed.
                val connection = networkWatcher?.connectionState() ?: ConnectionState.UNKNOWN
                if (shouldSweepOnConnection(connection)) {
                    runGuarded("Periodic sweep of pending recordings") { pendingAudioQueue.sweep() }
                    // Same reason as the drain after sweepPendingAudio: outside the sweep so a
                    // sweep failure cannot skip it, and after sweep() returns so the queue's
                    // mutex is not held for the length of a 200-entry drain. Inside the same
                    // gate because it exists to deliver what that sweep produced, and draining
                    // with no network only fails every entry.
                    runGuarded("Queue drain after the periodic sweep") { audioProcessor.flushPending() }
                } else {
                    Log.d(TAG, "Skipping the periodic sweep: no usable connection ($connection)")
                }
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
                    // Replaces the flushPending() that used to sit on AudioProcessor's upload
                    // success path. Same trigger, same intent — network and token both just
                    // proved good, so this is the best moment to drain the transcript queue —
                    // but here processNow has already returned, so PendingAudioQueue's mutex
                    // is released. Inside it, a drain of up to 200 entries at 30s connect /
                    // 60s read timeouts could hold that mutex for hours and block every other
                    // processNow behind it.
                    //
                    // Guarded, so a failed drain cannot retract a call that already landed or
                    // post a bogus "Failed to process" notification for it.
                    runGuarded("Queue drain after a successful upload") { audioProcessor.flushPending() }
                    // The call that just uploaded produces a new summary and new tasks.
                    // Guarded like the drain above: this call already landed and was uploaded,
                    // so a failed briefing fetch must not fall through to the catch below and
                    // post a "Failed to process" notification for a call that did not fail.
                    runGuarded("Briefing sync after a successful upload") { briefingSync.syncNow() }
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
        fun requestFlush(context: Context) = deliver(context, ACTION_FLUSH)

        /** Asks the running service to refresh the overlay's briefing snapshot. */
        fun requestBriefingSync(context: Context) = deliver(context, ACTION_SYNC_BRIEFINGS)

        /**
         * Hands one command to the service, without asking for a foreground service.
         *
         * Both callers are messages to a service that is already up — "retry the queue", "the
         * user edited a task" — not requests to create one. `MainActivity.startMonitorService`
         * is the call that creates it, and `BootReceiver` is the call that revives it after a
         * reboot; those two legitimately use `startForegroundService`.
         *
         * These two did as well, and it was wrong on both counts. `startForegroundService`
         * carries a contract — the service must reach `startForeground` within about five
         * seconds or the app is killed — and re-arms the whole foreground lifecycle for what is
         * just an intent delivery. `requestBriefingSync` runs from `MainActivity.onResume`, so
         * it fired on every unlock and every app switch; under the old `dataSync` type that was
         * the call the crash trace named, and it is why the dialog appeared the moment the app
         * was opened. `startService` delivers the same `onStartCommand` with no such contract,
         * and is legal here because both callers run with the activity in the foreground.
         *
         * Wrapped anyway: a caller can be backgrounded between deciding to send and sending, and
         * a background `startService` throws. A missed briefing refresh is a stale card until
         * the next sync; taking the app down over one would be far worse.
         */
        private fun deliver(context: Context, action: String) {
            val intent = Intent(context, CallMonitorService::class.java).apply {
                this.action = action
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not deliver $action to the monitor service", e)
            }
        }
    }
}
