package com.openchamber.ptt.bubble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.openchamber.ptt.BubbleActivity
import com.openchamber.ptt.MainActivity
import com.openchamber.ptt.R

enum class BubbleAvailability {
    AVAILABLE,
    DISABLED_BY_USER,
    NOTIFICATIONS_DISABLED,
}

object BubbleHelper {
    const val BUBBLE_CHANNEL_ID = "openchamber_ptt_bubble_channel"
    const val BUBBLE_NOTIFICATION_ID = 1003
    private const val SHORTCUT_ID = "openchamber_ptt_voice_conversation"
    private const val SHORTCUT_CATEGORY = "com.openchamber.ptt.category.VOICE_CONVERSATION"
    private const val DESIRED_HEIGHT_DP = 440

    @Volatile
    private var bubblePosted: Boolean = false

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(BUBBLE_CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            BUBBLE_CHANNEL_ID,
            context.getString(R.string.ptt_bubble_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.ptt_bubble_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            setAllowBubbles(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun checkAvailability(context: Context): BubbleAvailability {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return BubbleAvailability.NOTIFICATIONS_DISABLED
            }
        }

        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return BubbleAvailability.DISABLED_BY_USER

        if (!manager.areNotificationsEnabled()) {
            return BubbleAvailability.NOTIFICATIONS_DISABLED
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (manager.bubblePreference) {
                NotificationManager.BUBBLE_PREFERENCE_NONE -> BubbleAvailability.DISABLED_BY_USER
                else -> BubbleAvailability.AVAILABLE
            }
        }

        @Suppress("DEPRECATION")
        return if (manager.areBubblesAllowed()) {
            BubbleAvailability.AVAILABLE
        } else {
            BubbleAvailability.DISABLED_BY_USER
        }
    }

    fun openBubbleSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    fun showOrUpdateBubble(
        context: Context,
        sessionTitle: String,
        statusSummary: String,
        userPrompt: String? = null,
        agentReply: String? = null,
        autoExpand: Boolean = false,
    ): Boolean {
        if (checkAvailability(context) != BubbleAvailability.AVAILABLE) {
            return false
        }

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false

        val cleanTitle = sessionTitle.trim().ifEmpty { "OpenChamber Voice" }
        val bubbleIcon = IconCompat.createWithResource(context, R.drawable.ic_ptt_bubble)

        val agentPerson = Person.Builder()
            .setName(cleanTitle)
            .setIcon(bubbleIcon)
            .setImportant(true)
            .build()

        val userPerson = Person.Builder()
            .setName("You")
            .setImportant(true)
            .build()

        val shortcutIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setCategories(setOf(SHORTCUT_CATEGORY))
            .setIntent(shortcutIntent)
            .setLongLived(true)
            .setShortLabel(cleanTitle)
            .setLongLabel("OpenChamber Voice — $cleanTitle")
            .setIcon(bubbleIcon)
            .setPerson(agentPerson)
            .setLocusId(LocusIdCompat(SHORTCUT_ID))
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        val bubbleTarget = Intent(context, BubbleActivity::class.java)
        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            BUBBLE_NOTIFICATION_ID,
            bubbleTarget,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(bubblePendingIntent, bubbleIcon)
            .setDesiredHeight(DESIRED_HEIGHT_DP)
            .setAutoExpandBubble(autoExpand)
            .setSuppressNotification(autoExpand)
            .build()

        val style = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(cleanTitle)
            .setGroupConversation(false)

        val now = System.currentTimeMillis()
        if (!userPrompt.isNullOrBlank()) {
            style.addMessage(userPrompt.take(280), now - 500L, userPerson)
        }
        val agentLine = agentReply?.takeIf { it.isNotBlank() }?.take(360) ?: statusSummary
        style.addMessage(agentLine, now, agentPerson)

        val notification = NotificationCompat.Builder(context, BUBBLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt_mic)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(SHORTCUT_ID)
            .addPerson(agentPerson)
            .setStyle(style)
            .setBubbleMetadata(bubbleMetadata)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

        return runCatching {
            manager.notify(BUBBLE_NOTIFICATION_ID, notification)
            bubblePosted = true
            true
        }.getOrDefault(false)
    }

    fun updateIfPosted(
        context: Context,
        sessionTitle: String,
        statusSummary: String,
        userPrompt: String?,
        agentReply: String?,
    ) {
        if (!bubblePosted) return
        showOrUpdateBubble(
            context = context,
            sessionTitle = sessionTitle,
            statusSummary = statusSummary,
            userPrompt = userPrompt,
            agentReply = agentReply,
            autoExpand = false,
        )
    }

    fun dismissBubble(context: Context) {
        bubblePosted = false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(BUBBLE_NOTIFICATION_ID) }
    }
}
