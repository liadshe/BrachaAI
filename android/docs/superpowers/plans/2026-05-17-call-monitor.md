# Call Recording Monitor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add always-on background monitoring of `/storage/emulated/0/Recordings/Call` so every new recording is automatically parsed, transcribed via Whisper, and POSTed to the backend without any user interaction.

**Architecture:** A `ForegroundService` holds a `FileObserver` watching the recordings directory for `CLOSE_WRITE` events. On detection it runs the existing `AudioProcessor` pipeline in a coroutine on a `SupervisorJob` scope. A `BroadcastReceiver` restarts the service after reboot. `MainActivity` requests runtime permissions on first launch and starts the service.

**Tech Stack:** Kotlin, Jetpack Compose, Android ForegroundService, FileObserver, OkHttp3, FFmpeg-kit-audio, kotlinx-coroutines

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/AndroidManifest.xml` | Modify | Add permissions + declare service + receiver |
| `app/src/main/java/com/brachaai/app/AudioProcessor.kt` | Modify | Rethrow exceptions so callers can handle errors |
| `app/src/main/java/com/brachaai/app/CallMonitorService.kt` | Create | Foreground service with FileObserver + error notifications |
| `app/src/main/java/com/brachaai/app/BootReceiver.kt` | Create | Start service on device boot |
| `app/src/main/java/com/brachaai/app/MainActivity.kt` | Modify | Runtime permission request + service start + status UI |
| `app/src/test/java/com/brachaai/app/CallMonitorServiceTest.kt` | Create | Unit test for WATCH_PATH constant |

---

### Task 1: Add Permissions and Component Declarations to Manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Replace AndroidManifest.xml contents**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.BrachaAI"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.BrachaAI">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".CallMonitorService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />

        <receiver
            android:name=".BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

- [ ] **Step 2: Verify the project still compiles**

Run: `./gradlew assembleDebug -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: add permissions and component declarations for call monitor"
```

---

### Task 2: Modify AudioProcessor to Rethrow Exceptions

The existing `processAndSendToBackend` swallows all exceptions — the `CallMonitorService` error notification can never fire unless exceptions propagate. This task adds a single `throw e` after the existing log.

**Files:**
- Modify: `app/src/main/java/com/brachaai/app/AudioProcessor.kt`

- [ ] **Step 1: Add rethrow to the catch block**

In `AudioProcessor.kt`, find the catch block inside `processAndSendToBackend` (currently lines 48–51). Change it from:

```kotlin
} catch (e: Exception) {
    println("Error during processing: ${e.message}")
    e.printStackTrace()
}
```

To:

```kotlin
} catch (e: Exception) {
    println("Error during processing: ${e.message}")
    e.printStackTrace()
    throw e
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/brachaai/app/AudioProcessor.kt
git commit -m "feat: rethrow exceptions from AudioProcessor so callers can show error notifications"
```

---

### Task 3: Create CallMonitorService

**Files:**
- Create: `app/src/main/java/com/brachaai/app/CallMonitorService.kt`
- Create: `app/src/test/java/com/brachaai/app/CallMonitorServiceTest.kt`

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/com/brachaai/app/CallMonitorServiceTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CallMonitorServiceTest {
    @Test
    fun watchPathPointsToCallRecordingsDirectory() {
        assertEquals(
            "/storage/emulated/0/Recordings/Call",
            CallMonitorService.WATCH_PATH
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.brachaai.app.CallMonitorServiceTest" -q`
Expected: compilation error — `Unresolved reference: CallMonitorService`

- [ ] **Step 3: Create CallMonitorService.kt**

Create `app/src/main/java/com/brachaai/app/CallMonitorService.kt`:

```kotlin
package com.brachaai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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

    override fun onCreate() {
        super.onCreate()
        audioProcessor = AudioProcessor(BuildConfig.OPENAI_API_KEY)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildMonitoringNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildMonitoringNotification())
        }
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        fileObserver?.stopWatching()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWatching() {
        val watchDir = File(WATCH_PATH)
        if (!watchDir.exists()) watchDir.mkdirs()
        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(watchDir, CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) handleNewFile(File(watchDir, path))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(WATCH_PATH, CLOSE_WRITE) {
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
            .build()
        notificationManager.notify(filename.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL_ID, "Call Monitor", NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(ERROR_CHANNEL_ID, "Processing Errors", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildMonitoringNotification(): Notification =
        NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setContentTitle("BrachaAI")
            .setContentText("Monitoring call recordings...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    companion object {
        const val WATCH_PATH = "/storage/emulated/0/Recordings/Call"
        private const val NOTIFICATION_ID = 1
        private const val MONITOR_CHANNEL_ID = "call_monitor"
        private const val ERROR_CHANNEL_ID = "call_monitor_errors"
        private const val TAG = "CallMonitorService"
    }
}
```

- [ ] **Step 4: Run unit test — expect it to pass**

Run: `./gradlew testDebugUnitTest --tests "com.brachaai.app.CallMonitorServiceTest" -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Full compile check**

Run: `./gradlew assembleDebug -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/brachaai/app/CallMonitorService.kt \
        app/src/test/java/com/brachaai/app/CallMonitorServiceTest.kt
git commit -m "feat: add CallMonitorService with FileObserver, error notifications, and START_STICKY"
```

---

### Task 4: Create BootReceiver

**Files:**
- Create: `app/src/main/java/com/brachaai/app/BootReceiver.kt`

- [ ] **Step 1: Create BootReceiver.kt**

```kotlin
package com.brachaai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallMonitorService::class.java)
            )
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew assembleDebug -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/brachaai/app/BootReceiver.kt
git commit -m "feat: add BootReceiver to auto-start CallMonitorService after reboot"
```

---

### Task 5: Update MainActivity — Permissions, Service Start, Status UI

**Files:**
- Modify: `app/src/main/java/com/brachaai/app/MainActivity.kt`

- [ ] **Step 1: Replace MainActivity.kt with the following**

```kotlin
package com.brachaai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = hasPermissions()
        if (permissionsGranted) {
            startMonitorService()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (permissionsGranted) {
                    Text("Monitoring active", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Watching: ${CallMonitorService.WATCH_PATH}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Storage permission required for monitoring to work",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startMonitorService() {
        startForegroundService(Intent(this, CallMonitorService::class.java))
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew assembleDebug -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run all unit tests**

Run: `./gradlew testDebugUnitTest -q`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/brachaai/app/MainActivity.kt
git commit -m "feat: update MainActivity to request permissions and start call monitor service"
```

---

### Task 6: Manual Smoke Test on Emulator

- [ ] **Step 1: Install on emulator**

Run: `./gradlew installDebug`
Expected: `BUILD SUCCESSFUL` and APK installed.

- [ ] **Step 2: Launch app and grant permissions**

Open the app. On Android 13+, grant "Read audio files" and "Allow notifications" when prompted.
Expected: app shows "Monitoring active — Watching: /storage/emulated/0/Recordings/Call"

- [ ] **Step 3: Verify the foreground notification**

Pull down the notification shade.
Expected: persistent notification "BrachaAI — Monitoring call recordings..."

- [ ] **Step 4: Push a correctly-named test file**

```bash
adb shell mkdir -p /storage/emulated/0/Recordings/Call
adb push <any-audio-file.m4a> /storage/emulated/0/Recordings/Call/TestContact_260517_120000.m4a
```

- [ ] **Step 5: Verify pipeline executes in logcat**

```bash
adb logcat -s CallMonitorService System.out
```

Expected output (in order):
```
1. Starting processing for: TestContact_260517_120000.m4a
2. Parsed Info - Name: TestContact, Date: 260517
3. Converting audio to true MP3 format...
4. Conversion Success! Saved to: TestContact_260517_120000.mp3
5. Uploading MP3 to Whisper...
6. Whisper Transcript: <transcribed text>
7. Sending data to backend...
8. SUCCESS! Data sent to backend: ...
```

- [ ] **Step 6: Verify error notification for malformed filename**

```bash
adb push <any-audio-file.m4a> /storage/emulated/0/Recordings/Call/badname.m4a
```

Expected: error notification appears — "Failed to process: badname.m4a"
(triggered because `parseFilename` throws `IllegalArgumentException` on `badname.m4a`)
