package ir.vibracenter.hamseda

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Rings (sound + vibration) when someone invites you to a room. Respects the phone's silent / vibrate mode. */
object Ringer {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var looping = false
    private var active = false

    private val stopRunnable = Runnable { stop() }

    private val keepPlaying = object : Runnable {
        override fun run() {
            val r = ringtone ?: return
            try {
                if (looping && !r.isPlaying) r.play()
            } catch (e: Exception) {
                // ignore
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** loop = true: ring like a call for up to INVITE_RING_MS. loop = false: a single short sound. */
    fun start(context: Context, loop: Boolean) {
        stop()
        val app = context.applicationContext
        active = true
        looping = loop

        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = audio.ringerMode

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val type = if (loop) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
                val r = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(type))
                if (r != null) {
                    r.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                if (loop) AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                                else AudioAttributes.USAGE_NOTIFICATION
                            )
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    if (loop && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.setLooping(true)
                    r.play()
                    ringtone = r
                }
            } catch (e: Exception) {
                // no ringtone available
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) vibrate(app, loop)

        if (loop) handler.postDelayed(keepPlaying, 1000)
        handler.postDelayed(stopRunnable, if (loop) Config.INVITE_RING_MS else 4000L)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, loop: Boolean) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            val pattern = longArrayOf(0, 700, 500, 700, 1500)
            val repeat = if (loop) 0 else -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                v.vibrate(pattern, repeat)
            }
            vibrator = v
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Returns true if something was ringing. */
    fun stop(): Boolean {
        val was = active
        active = false
        looping = false
        handler.removeCallbacks(stopRunnable)
        handler.removeCallbacks(keepPlaying)
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            // ignore
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        vibrator = null
        return was
    }
}
