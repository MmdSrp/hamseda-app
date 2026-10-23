package ir.vibracenter.hamseda

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {
    private const val CHANNEL_ROOM = "room"
    private const val CHANNEL_SITE = "site"
    const val ROOM_NOTIFICATION_ID = 1001
    private val nextId = AtomicInteger(3000)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val room = NotificationChannel(
            CHANNEL_ROOM, ctx.getString(R.string.channel_room), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = ctx.getString(R.string.channel_room_desc)
            setShowBadge(false)
        }
        val site = NotificationChannel(
            CHANNEL_SITE, ctx.getString(R.string.channel_site), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ctx.getString(R.string.channel_site_desc)
        }
        nm.createNotificationChannel(room)
        nm.createNotificationChannel(site)
    }

    private fun openAppIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        return PendingIntent.getActivity(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** The ongoing notification that says "you are still in the room, it is active in the background". */
    fun roomNotification(ctx: Context): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_ROOM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(ctx, R.color.brand))
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.room_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.room_notification_text)))
            .setContentIntent(openAppIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun idForTag(tag: String): Int =
        if (tag.isEmpty()) nextId.incrementAndGet() else 2000 + (tag.hashCode() and 0xFFFF)

    @SuppressLint("MissingPermission")
    fun showSite(ctx: Context, title: String, body: String, tag: String) {
        ensureChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_SITE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(ctx, R.color.brand))
            .setContentTitle(title.ifEmpty { ctx.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            nm.notify(idForTag(tag), n)
        } catch (e: Exception) {
            // notification could not be shown - ignore
        }
    }

    fun cancelSite(ctx: Context, tag: String) {
        if (tag.isEmpty()) return
        NotificationManagerCompat.from(ctx).cancel(idForTag(tag))
    }
}
