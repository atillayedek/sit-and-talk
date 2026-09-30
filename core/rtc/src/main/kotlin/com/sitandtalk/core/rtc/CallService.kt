package com.sitandtalk.core.rtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps an ongoing call or room alive while the app is in the background, with a visible ongoing
 * notification and an end action. Started only from the foreground, only while RTC is active.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            endRequests.tryEmit(Unit)
            return START_NOT_STICKY
        }
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: RtcSessionInfo.Kind.Call.name
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        ensureChannel(this)

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_OPEN_ACTIVE, true)
        }
        val contentIntent = openIntent?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val endIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CallService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val isRoom = kind == RtcSessionInfo.Kind.Room.name
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title.ifBlank { getString(if (isRoom) R.string.rtc_notification_room else R.string.rtc_notification_call) })
            .setContentText(getString(R.string.rtc_notification_tap))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentIntent)
            .addAction(0, getString(if (isRoom) R.string.rtc_action_leave else R.string.rtc_action_end), endIntent)
            .build()

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (e: RuntimeException) {
            // Missing permission or background-start restriction: do not pretend the call is protected.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "ongoing_call"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_END = "com.sitandtalk.rtc.END"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_VIDEO = "video"
        const val EXTRA_OPEN_ACTIVE = "open_active_session"

        private val endRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Emits when the user tapped "End"/"Leave" in the ongoing notification. */
        val endActions: SharedFlow<Unit> = endRequests.asSharedFlow()

        fun start(context: Context, info: RtcSessionInfo) {
            val intent = Intent(context, CallService::class.java)
                .putExtra(EXTRA_KIND, info.kind.name)
                .putExtra(EXTRA_TITLE, info.title)
                .putExtra(EXTRA_VIDEO, info.video)
            // A plain start (the app is in the foreground here) that then promotes itself. Unlike
            // startForegroundService, a failed promotion (e.g. missing microphone permission) cannot crash the app.
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                // App not in the foreground; the call continues only while visible.
            } catch (_: SecurityException) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.rtc_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = context.getString(R.string.rtc_channel_description)
            manager.createNotificationChannel(channel)
        }
    }
}
