package com.openchamber.ptt.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal interface RecordingMirrorPlatform {
    fun hasPostNotificationsPermission(): Boolean
    fun post(sessionTitle: String?)
    fun cancel()
}

private class AndroidRecordingMirrorPlatform(
    private val context: Context,
    private val notificationBuilder: PttNotificationBuilder,
) : RecordingMirrorPlatform {
    override fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun post(sessionTitle: String?) {
        notificationBuilder.postRecordingMirrorNotification(sessionTitle)
    }

    override fun cancel() {
        notificationBuilder.cancelRecordingMirrorNotification()
    }
}

internal class RecordingMirrorNotificationController(
    private val platform: RecordingMirrorPlatform,
    private val onDiagnosticEvent: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null,
) {
    constructor(
        context: Context,
        notificationBuilder: PttNotificationBuilder,
        onDiagnosticEvent: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null,
    ) : this(
        platform = AndroidRecordingMirrorPlatform(context, notificationBuilder),
        onDiagnosticEvent = onDiagnosticEvent,
    )

    private val lock = Any()
    private var isPosted: Boolean = false
    private var permissionDeniedLoggedInBurst: Boolean = false

    fun onServiceCreated() {
        val diagnostic = synchronized(lock) {
            val wasPosted = isPosted
            isPosted = false
            permissionDeniedLoggedInBurst = false
            platform.cancel()
            DiagnosticPayload(
                event = DIAGNOSTIC_EVENT,
                outcome = "CANCELLED",
                details = mapOf(
                    "reason" to "service_created",
                    "wasPosted" to wasPosted.toString(),
                ),
            )
        }
        emitDiagnostic(diagnostic)
    }

    fun sync(
        isRecording: Boolean,
        featureEnabled: Boolean,
        sessionTitle: String?,
    ) {
        val diagnostic = synchronized(lock) {
            if (isRecording && featureEnabled) {
                if (isPosted) {
                    null
                } else if (!platform.hasPostNotificationsPermission()) {
                    if (!permissionDeniedLoggedInBurst) {
                        permissionDeniedLoggedInBurst = true
                        DiagnosticPayload(
                            event = DIAGNOSTIC_EVENT,
                            outcome = "SKIPPED",
                            details = mapOf("reason" to "missing_post_notifications_permission"),
                        )
                    } else {
                        null
                    }
                } else {
                    isPosted = true
                    permissionDeniedLoggedInBurst = false
                    platform.post(sessionTitle)
                    val trimmedTitle = sessionTitle?.trim()?.takeIf { it.isNotEmpty() }
                    DiagnosticPayload(
                        event = DIAGNOSTIC_EVENT,
                        outcome = "POSTED",
                        details = if (trimmedTitle != null) {
                            mapOf("sessionTitle" to trimmedTitle)
                        } else {
                            emptyMap()
                        },
                    )
                }
            } else {
                val wasPosted = isPosted
                isPosted = false
                permissionDeniedLoggedInBurst = false
                platform.cancel()
                if (wasPosted) {
                    val reason = if (!isRecording) "not_recording" else "feature_disabled"
                    DiagnosticPayload(
                        event = DIAGNOSTIC_EVENT,
                        outcome = "CANCELLED",
                        details = mapOf("reason" to reason),
                    )
                } else {
                    null
                }
            }
        }
        if (diagnostic != null) {
            emitDiagnostic(diagnostic)
        }
    }

    fun release() {
        val diagnostic = synchronized(lock) {
            val wasPosted = isPosted
            isPosted = false
            permissionDeniedLoggedInBurst = false
            platform.cancel()
            if (wasPosted) {
                DiagnosticPayload(
                    event = DIAGNOSTIC_EVENT,
                    outcome = "CANCELLED",
                    details = mapOf("reason" to "released"),
                )
            } else {
                null
            }
        }
        if (diagnostic != null) {
            emitDiagnostic(diagnostic)
        }
    }

    private fun emitDiagnostic(payload: DiagnosticPayload) {
        onDiagnosticEvent?.invoke(payload.event, payload.outcome, payload.details)
    }

    private data class DiagnosticPayload(
        val event: String,
        val outcome: String,
        val details: Map<String, String>,
    )

    companion object {
        private const val DIAGNOSTIC_EVENT = "recording_mirror_notification"
    }
}
