package ir.vibracenter.hamseda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the "رد کردن" button of the invitation notification (stops the ring without opening the app). */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationHelper.ACTION_DECLINE) {
            Ringer.stop()
            NotificationHelper.cancelInvite(context)
        }
    }
}
