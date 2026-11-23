package com.openchamber.ptt.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RecordingNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        handleAction(intent?.action) {
            val nm = context?.getSystemService(NotificationManager::class.java)
            if (nm != null) {
                runCatching {
                    nm.cancel(PttNotificationBuilder.RECORDING_MIRROR_NOTIFICATION_ID)
                }
            }
        }
    }

    internal fun handleAction(
        action: String?,
        cancelMirrorNotification: () -> Unit,
    ) {
        if (action != ACTION_STOP_RECORDING_MIRROR) return
        cancelMirrorNotification()
        val handled = onStopRecordingRequested?.invoke() == true
        onDiagnosticEvent?.invoke(
            DIAGNOSTIC_EVENT,
            if (handled) "ACCEPTED" else "IGNORED",
            if (handled) emptyMap() else mapOf("reason" to REASON_NOT_RECORDING_OR_INACTIVE),
        )
    }

    companion object {
        const val ACTION_STOP_RECORDING_MIRROR = "com.openchamber.ptt.action.STOP_RECORDING_MIRROR"
        private const val DIAGNOSTIC_EVENT = "recording_mirror_stop_action"
        private const val REASON_NOT_RECORDING_OR_INACTIVE = "not_recording_or_service_inactive"

        @Volatile
        var onStopRecordingRequested: (() -> Boolean)? = null

        @Volatile
        var onDiagnosticEvent: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null
    }
}
