package ir.vibracenter.hamseda

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while it is open (also in the background), so room invitations and site
 * notifications keep arriving, and the microphone / camera keep working while you are inside a room.
 * Android shows an ongoing notification for it ("اتاق در پس‌زمینه فعاله" inside a room, otherwise "Hamseda فعاله").
 */
class RoomService : Service() {

    companion object {
        const val EXTRA_IN_ROOM = "in_room"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inRoom = intent?.getBooleanExtra(EXTRA_IN_ROOM, false) ?: false
        val notification = NotificationHelper.roomNotification(this, inRoom)
        if (!startInForeground(notification, inRoom)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (inRoom) acquireWakeLock() else releaseWakeLock()
        return START_NOT_STICKY
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground(notification: Notification, inRoom: Boolean): Boolean {
        return try {
            start(notification, types(inRoom))
            true
        } catch (e: Exception) {
            if (!inRoom) return false
            try {
                start(notification, mediaOnlyType())
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun start(notification: Notification, types: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.ROOM_NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NotificationHelper.ROOM_NOTIFICATION_ID, notification)
        }
    }

    private fun mediaOnlyType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    private fun types(inRoom: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        if (!inRoom) {
            // idle: just waiting for invitations / notifications
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        }
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (granted(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (granted(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return t
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hamseda:room").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            // not critical
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped the app away: it is closed for good, so remove the notification too.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
