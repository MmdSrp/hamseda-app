package ir.vibracenter.hamseda

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

object NotificationHelper {
    private const val CHANNEL_ROOM = "room"
    private const val CHANNEL_SITE = "site"
    private const val CHANNEL_INVITE = "invite"

    const val ROOM_NOTIFICATION_ID = 1001
    const val INVITE_NOTIFICATION_ID = 1002
    const val ACTION_DECLINE = "ir.vibracenter.hamseda.DECLINE_INVITE"

    private val nextId = AtomicInteger(3000)
    private val iconExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** A notification as sent by the site. */
    data class SiteNotification(
        val title: String,
        val body: String,
        val tag: String,
        val url: String?,
        val iconUrl: String?
    )

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
        // The ringing itself is played by Ringer, so this channel stays silent but pops up on screen.
        val invite = NotificationChannel(
            CHANNEL_INVITE, ctx.getString(R.string.channel_invite), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ctx.getString(R.string.channel_invite_desc)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(room)
        nm.createNotificationChannel(site)
        nm.createNotificationChannel(invite)
    }

    private fun appIntent(
        ctx: Context,
        requestCode: Int,
        url: String? = null,
        stopRing: Boolean = false,
        quit: Boolean = false
    ): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            if (url != null) putExtra(MainActivity.EXTRA_URL, url)
            if (stopRing) putExtra(MainActivity.EXTRA_STOP_RING, true)
            if (quit) putExtra(MainActivity.EXTRA_QUIT, true)
        }
        return PendingIntent.getActivity(
            ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** The ongoing notification while the app is alive in the background. */
    fun roomNotification(ctx: Context, inRoom: Boolean): Notification {
        ensureChannels(ctx)
        val text = ctx.getString(if (inRoom) R.string.room_notification_text else R.string.idle_notification_text)
        return NotificationCompat.Builder(ctx, CHANNEL_ROOM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeStore.accent(ctx))
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(appIntent(ctx, 10))
            .addAction(0, ctx.getString(R.string.notif_quit), appIntent(ctx, 11, quit = true))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(if (inRoom) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Updates the ongoing notification in place (new colour, room / idle text). */
    @SuppressLint("MissingPermission")
    fun refreshRoomNotification(ctx: Context, inRoom: Boolean) {
        try {
            val nm = NotificationManagerCompat.from(ctx)
            if (nm.areNotificationsEnabled()) nm.notify(ROOM_NOTIFICATION_ID, roomNotification(ctx, inRoom))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun idForTag(tag: String): Int =
        if (tag.isEmpty()) nextId.incrementAndGet() else 2000 + (tag.hashCode() and 0xFFFF)

    /** A normal notification from the site, with the same title, text and picture, and a tap that opens its link. */
    fun showSite(ctx: Context, n: SiteNotification) {
        val app = ctx.applicationContext
        ensureChannels(app)
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        val id = idForTag(n.tag)
        if (n.iconUrl == null) {
            post(nm, id, buildSite(app, n, id, null))
        } else {
            iconExecutor.execute {
                val bmp = fetchIcon(n.iconUrl)
                post(nm, id, buildSite(app, n, id, bmp))
            }
        }
    }

    private fun buildSite(app: Context, n: SiteNotification, id: Int, icon: Bitmap?): Notification {
        val b = NotificationCompat.Builder(app, CHANNEL_SITE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeStore.accent(app))
            .setContentTitle(n.title.ifEmpty { app.getString(R.string.app_name) })
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(appIntent(app, id, url = n.url))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (icon != null) b.setLargeIcon(icon)
        return b.build()
    }

    /** A call-style notification for a room invitation (the ringing is done by Ringer). */
    fun showInvite(ctx: Context, n: SiteNotification) {
        val app = ctx.applicationContext
        ensureChannels(app)
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return

        val join = appIntent(app, 20, url = n.url, stopRing = true)
        val decline = PendingIntent.getBroadcast(
            app, 21,
            Intent(app, RingActionReceiver::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = n.body.ifEmpty { app.getString(R.string.invite_default_text) }
        val notification = NotificationCompat.Builder(app, CHANNEL_INVITE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeStore.accent(app))
            .setContentTitle(n.title.ifEmpty { app.getString(R.string.invite_default_title) })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(join)
            .addAction(0, app.getString(R.string.invite_join), join)
            .addAction(0, app.getString(R.string.invite_decline), decline)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(Config.INVITE_RING_MS)
            .build()
        post(nm, INVITE_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun post(nm: NotificationManagerCompat, id: Int, n: Notification) {
        try {
            nm.notify(id, n)
        } catch (e: Exception) {
            // notification could not be shown - ignore
        }
    }

    fun cancelSite(ctx: Context, tag: String) {
        if (tag.isEmpty()) return
        NotificationManagerCompat.from(ctx).cancel(idForTag(tag))
    }

    fun cancelInvite(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(INVITE_NOTIFICATION_ID)
    }

    /** Downloads the notification picture (https, the site's own domain, at most 512 KB). */
    private fun fetchIcon(url: String): Bitmap? {
        var c: HttpsURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpsURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.instanceFollowRedirects = false
            if (c.responseCode != 200) return null
            val limit = 512 * 1024
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            c.inputStream.use { input ->
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > limit) return null
                    out.write(buf, 0, r)
                }
            }
            val bytes = out.toByteArray()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val max = 256
            if (bmp.width <= max && bmp.height <= max) return bmp
            val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
            return Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
            )
        } catch (e: Exception) {
            return null
        } finally {
            try {
                c?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
