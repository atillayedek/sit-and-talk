package com.sitandtalk.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sitandtalk.app.AppLink
import com.sitandtalk.app.MainActivity
import com.sitandtalk.app.R
import com.sitandtalk.feature.notifications.notificationTextRes
import java.time.Instant
import com.sitandtalk.feature.notifications.R as NotifR

/**
 * Renders data-only pushes locally. The server sends keys and ids, never ready-made text, so the
 * notification is localized on the device and message previews appear only when the sender allows it.
 */
object PushNotifier {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_CALLS = "calls"
    private const val CHANNEL_SOCIAL = "social"
    private const val CHANNEL_SYSTEM = "system"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(NotifR.string.push_channel_messages), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_CALLS, context.getString(NotifR.string.push_channel_calls), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_SOCIAL, context.getString(NotifR.string.push_channel_social), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_SYSTEM, context.getString(NotifR.string.push_channel_system), NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach(manager::createNotificationChannel)
    }

    fun show(context: Context, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val expiresAt = data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) return

        val titleKey = data["title_key"] ?: return
        val kind = data["kind"]
        val link: AppLink?
        val channel: String
        val title: String
        val text: String
        if (titleKey == "push_message") {
            val conversationId = data["conversation_id"] ?: return
            link = AppLink.Chat(conversationId)
            channel = CHANNEL_MESSAGES
            val sender = data["sender_name"]?.takeIf { it.isNotBlank() }
            title = sender ?: context.getString(NotifR.string.push_message)
            text = data["body"]?.takeIf { it.isNotBlank() }
                ?: if (sender != null) context.getString(NotifR.string.push_message_from, sender) else context.getString(NotifR.string.push_message)
        } else {
            if (kind == null) return
            val entityId = data["entity_id"]
            link = when (kind) {
                "friend_request" -> AppLink.FriendRequests
                "friend_accepted", "mutual_match" -> entityId?.let { AppLink.Profile(it) }
                "incoming_call" -> AppLink.ActiveCall
                "room_invite" -> entityId?.let { AppLink.Room(it, null) }
                "event_reminder" -> AppLink.Events
                "comment", "reply" -> entityId?.let { AppLink.Post(it) }
                else -> AppLink.Notifications
            }
            channel = when (kind) {
                "incoming_call" -> CHANNEL_CALLS
                "friend_request", "friend_accepted", "mutual_match", "room_invite", "event_reminder", "comment", "reply" -> CHANNEL_SOCIAL
                else -> CHANNEL_SYSTEM
            }
            title = context.getString(R.string.app_name)
            text = context.getString(notificationTextRes(kind))
        }

        val tag = data["notification_id"] ?: data["message_id"] ?: titleKey
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(AppLink.uriFor(link ?: AppLink.Notifications))
            .putExtra(EXTRA_NOTIFICATION_ID, data["notification_id"])
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, tag.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_sitandtalk)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(if (channel == CHANNEL_CALLS || channel == CHANNEL_MESSAGES) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (kind == "incoming_call") {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
            expiresAt?.let { builder.setTimeoutAfter((it.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1_000)) }
        } else if (channel == CHANNEL_MESSAGES) {
            builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }
        try {
            NotificationManagerCompat.from(context).notify(tag, 0, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: nothing is shown.
        }
    }

    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()
}
