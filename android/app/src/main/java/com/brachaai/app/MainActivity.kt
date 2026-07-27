package com.brachaai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.WebView as AndroidWebView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)
    private var allFilesGranted by mutableStateOf(false)
    private var webView: AndroidWebView? = null

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    // READ_CALL_LOG is requested opportunistically (so users who grant it still get caller
    // numbers) but deliberately excluded from requiredPermissions: it's hard-restricted on
    // API 29+ and can be denied instantly with no dialog, and it must never block the WebView
    // from rendering — the WebView is the only place the auth token comes from. A denial or
    // no-op here is fully handled downstream: CallerLookup.findNumberNear catches
    // SecurityException and returns null, and the backend accepts callerNumber: null.
    private val optionalPermissions: Array<String> = arrayOf(Manifest.permission.READ_CALL_LOG)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Re-derive from actual grant state rather than trusting `results` directly, since a
        // single launcher call may request both required and optional permissions together.
        permissionsGranted = hasPermissions()
        if (permissionsGranted && allFilesGranted) startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            if (webView?.canGoBack() == true) {
                webView?.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        refreshPermissionState()

        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (permissionsGranted && allFilesGranted) {
                    WebViewScreen(
                        url = "file:///android_asset/www/index.html",
                        onAuthenticated = { CallMonitorService.requestFlush(this@MainActivity) }
                    ) { wv ->
                        webView = wv
                    }
                } else if (!allFilesGranted) {
                    Text(
                        "\"All files access\" is required so the app can read call recordings.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { openAllFilesSettings() }) {
                        Text("Grant access in Settings")
                    }
                } else {
                    Text(
                        "Required permissions were not granted. Please restart the app and allow all permissions.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        permissionsGranted = hasPermissions()
        allFilesGranted = hasAllFilesAccess()
        if (!permissionsGranted && requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions + optionalPermissions)
        } else if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            // Required permissions are already satisfied (or there are none to ask for on
            // this SDK level). Ask for READ_CALL_LOG on its own — opportunistic, non-gating.
            permissionLauncher.launch(optionalPermissions)
            if (permissionsGranted && allFilesGranted) startMonitorService()
        } else if (permissionsGranted && allFilesGranted) {
            startMonitorService()
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions.all(::hasPermission)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun openAllFilesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun startMonitorService() {
        if (!CallMonitorService.isRunning) {
            startForegroundService(Intent(this, CallMonitorService::class.java))
        }
    }
}