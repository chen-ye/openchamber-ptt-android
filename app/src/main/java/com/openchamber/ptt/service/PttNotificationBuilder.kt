package com.openchamber.ptt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.openchamber.ptt.MainActivity
import com.openchamber.ptt.R

internal data class PttNotificationActionSpec(
    @StringRes val labelRes: Int?,
    val labelText: String?,
    val action: String,
    val requestCode: Int,
)

internal class PttNotificationBuilder(
    private val context: Context,
    private val notificationManager: NotificationManager,
) {
    fun createNotificationChannels() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.ptt_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.ptt_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(serviceChannel)

        val alertChannel = NotificationChannel(
            ALERT_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.ptt_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ptt_alert_channel_description)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(alertChannel)
    }

    fun statusTextForState(pttState: PttState): String =
        context.getString(statusTextResForState(pttState))

    fun buildNotification(
        pttState: PttState,
        promotedForStandaloneTts: Boolean,
    ): Notification {
        val status = statusTextForState(pttState)
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt_mic)
            .setContentTitle(context.getString(R.string.ptt_notification_title))
            .setContentText(status)
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        for (spec in actionsForState(pttState, promotedForStandaloneTts)) {
            val label = spec.labelText ?: spec.labelRes?.let { context.getString(it) }.orEmpty()
            builder.addAction(
                0,
                label,
                serviceActionPendingIntent(spec.action, spec.requestCode),
            )
        }

        return builder.build()
    }

    fun notifyServiceState(
        pttState: PttState,
        promotedForStandaloneTts: Boolean,
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(pttState, promotedForStandaloneTts),
        )
    }

    fun postPermissionAlertNotification(tool: String) {
        val notification = NotificationCompat.Builder(context, ALERT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt_mic)
            .setContentTitle(context.getString(R.string.ptt_alert_permission_title))
            .setContentText(PttForegroundService.formatPermissionAlertSpeech(tool))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()

        runCatching {
            notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        }
    }

    fun cancelPermissionAlertNotification() {
        runCatching { notificationManager.cancel(ALERT_NOTIFICATION_ID) }
    }

    private fun mainActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PttForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "openchamber_ptt_channel"
        const val ALERT_NOTIFICATION_CHANNEL_ID = "openchamber_ptt_alerts_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002

        const val REQUEST_CODE_ARM = 2001
        const val REQUEST_CODE_DISARM = 2002
        const val REQUEST_CODE_TOGGLE = 2003
        const val REQUEST_CODE_STAGE = 2004
        const val REQUEST_CODE_DISCARD = 2005
        const val REQUEST_CODE_COMMIT_DRAFT = 2006
        const val REQUEST_CODE_PULL_BACK = 2007

        @StringRes
        internal fun statusTextResForState(pttState: PttState): Int = when (pttState) {
            PttState.DISARMED -> R.string.ptt_status_disarmed
            PttState.ARMED_IDLE -> R.string.ptt_status_armed_idle
            PttState.RECORDING -> R.string.ptt_status_recording
            PttState.TRANSCRIBING_SEND -> R.string.ptt_status_transcribing
            PttState.TRANSCRIBING_STAGE -> R.string.ptt_status_organizing_draft
            PttState.SEND_GRACE -> R.string.ptt_status_send_grace
            PttState.STAGED_DRAFT -> R.string.ptt_status_staged_draft
            PttState.STAGED_DRAFT_SPEAKING -> R.string.ptt_status_staged_draft_speaking
            PttState.WAITING_AGENT -> R.string.ptt_status_waiting_agent
            PttState.SPEAKING_TTS -> R.string.ptt_status_speaking_tts
            PttState.PERMISSION_BLOCKED -> R.string.ptt_status_permission_blocked
            PttState.ERROR -> R.string.ptt_status_error
        }

        internal fun actionsForState(
            pttState: PttState,
            promotedForStandaloneTts: Boolean,
        ): List<PttNotificationActionSpec> = when (pttState) {
            PttState.DISARMED -> listOf(
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_arm,
                    labelText = null,
                    action = PttForegroundService.ACTION_ARM,
                    requestCode = REQUEST_CODE_ARM,
                ),
            )

            PttState.RECORDING -> listOf(
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_send,
                    labelText = null,
                    action = PttForegroundService.ACTION_TOGGLE_PTT,
                    requestCode = REQUEST_CODE_TOGGLE,
                ),
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_stage,
                    labelText = null,
                    action = PttForegroundService.ACTION_STAGE_CAPTURE,
                    requestCode = REQUEST_CODE_STAGE,
                ),
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_discard,
                    labelText = null,
                    action = PttForegroundService.ACTION_DISCARD_CAPTURE,
                    requestCode = REQUEST_CODE_DISCARD,
                ),
            )

            PttState.SEND_GRACE -> listOf(
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_undo,
                    labelText = null,
                    action = PttForegroundService.ACTION_PULL_BACK_DRAFT,
                    requestCode = REQUEST_CODE_PULL_BACK,
                ),
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_discard,
                    labelText = null,
                    action = PttForegroundService.ACTION_DISCARD_CAPTURE,
                    requestCode = REQUEST_CODE_DISCARD,
                ),
            )

            PttState.STAGED_DRAFT,
            PttState.STAGED_DRAFT_SPEAKING,
            -> listOf(
                PttNotificationActionSpec(
                    labelRes = if (pttState == PttState.STAGED_DRAFT_SPEAKING) null else R.string.ptt_action_append,
                    labelText = if (pttState == PttState.STAGED_DRAFT_SPEAKING) "Stop" else null,
                    action = PttForegroundService.ACTION_TOGGLE_PTT,
                    requestCode = REQUEST_CODE_TOGGLE,
                ),
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_send,
                    labelText = null,
                    action = PttForegroundService.ACTION_COMMIT_DRAFT,
                    requestCode = REQUEST_CODE_COMMIT_DRAFT,
                ),
                PttNotificationActionSpec(
                    labelRes = R.string.ptt_action_discard,
                    labelText = null,
                    action = PttForegroundService.ACTION_DISCARD_CAPTURE,
                    requestCode = REQUEST_CODE_DISCARD,
                ),
            )

            else -> buildList {
                add(
                    PttNotificationActionSpec(
                        labelRes = if (pttState == PttState.SPEAKING_TTS) null else R.string.ptt_action_talk,
                        labelText = if (pttState == PttState.SPEAKING_TTS) "Stop" else null,
                        action = PttForegroundService.ACTION_TOGGLE_PTT,
                        requestCode = REQUEST_CODE_TOGGLE,
                    ),
                )
                if (!promotedForStandaloneTts) {
                    add(
                        PttNotificationActionSpec(
                            labelRes = R.string.ptt_action_disarm,
                            labelText = null,
                            action = PttForegroundService.ACTION_DISARM,
                            requestCode = REQUEST_CODE_DISARM,
                        ),
                    )
                }
            }
        }
    }
}
