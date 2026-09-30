package com.umurpaket.datacheck

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.umurpaket.datacheck.data.Level
import com.umurpaket.datacheck.data.Settings

/** Bunyi & getar berbeda per status, supaya petugas tidak perlu melihat layar. */
class Feedback(ctx: Context) {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    fun play(level: Level, dup: Boolean, s: Settings) {
        if (s.sound) tone?.runCatching {
            when {
                dup -> startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                level == Level.NOT_FOUND -> startTone(ToneGenerator.TONE_SUP_ERROR, 700)
                level == Level.CRIT -> startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
                level == Level.WARN -> startTone(ToneGenerator.TONE_PROP_ACK, 300)
                else -> startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }
        }
        if (s.vibrate) vibrator?.runCatching {
            val pattern = when {
                dup -> longArrayOf(0, 80, 80, 80)
                level == Level.NOT_FOUND -> longArrayOf(0, 500)
                level == Level.CRIT -> longArrayOf(0, 200, 100, 200, 100, 200)
                level == Level.WARN -> longArrayOf(0, 150, 100, 150)
                else -> longArrayOf(0, 80)
            }
            vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    fun release() {
        tone?.release()
    }
}
