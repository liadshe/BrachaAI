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
