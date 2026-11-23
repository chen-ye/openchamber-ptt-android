package com.openchamber.ptt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.openchamber.ptt.diagnostics.AudioDebugCategory
import com.openchamber.ptt.diagnostics.AudioDebugOutcome
import com.openchamber.ptt.diagnostics.AudioDebugRegistry
import com.openchamber.ptt.diagnostics.AudioPlatformDiagnosticMonitor
import com.openchamber.ptt.service.PttForegroundService
import com.openchamber.ptt.ui.AudioDebugScreen
import com.openchamber.ptt.ui.theme.OpenChamberPttTheme

class AudioDebugActivity : ComponentActivity() {
    private var boundService by mutableStateOf<PttForegroundService?>(null)
    private var isServiceBound = false
    private lateinit var snapshotHelper: AudioPlatformDiagnosticMonitor

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PttForegroundService.PttBinder
            boundService = binder?.service
            snapshotHelper.refreshAudioManagerSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        snapshotHelper = AudioPlatformDiagnosticMonitor(applicationContext)

        setContent {
            OpenChamberPttTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AudioDebugScreen(
                        buffer = AudioDebugRegistry.shared,
                        onRefresh = { snapshotHelper.refreshAudioManagerSnapshot() },
                        onNavigateBack = { finish() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PttForegroundService::class.java)
        isServiceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        snapshotHelper.refreshAudioManagerSnapshot()
    }

    override fun onStop() {
        if (isServiceBound) {
            runCatching { unbindService(serviceConnection) }
            isServiceBound = false
        }
        boundService = null
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            recordForegroundKeyEvent(event, "DOWN")
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            recordForegroundKeyEvent(event, "UP")
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun recordForegroundKeyEvent(event: KeyEvent, actionLabel: String) {
        AudioDebugRegistry.shared.record(
            category = AudioDebugCategory.FOREGROUND_KEY,
            event = KeyEvent.keyCodeToString(event.keyCode),
            outcome = AudioDebugOutcome.ACCEPTED,
            details = buildMap {
                put("action", actionLabel)
                put("repeatCount", event.repeatCount.toString())
                event.device?.name?.takeIf { it.isNotBlank() }?.let { put("device", it) }
            },
        )
    }
}
