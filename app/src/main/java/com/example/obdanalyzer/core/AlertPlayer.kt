package com.example.obdanalyzer.core

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Bip et vibration quand une nouvelle alerte apparaît. */
class AlertPlayer(private val context: Context) {

    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun play(severe: Boolean, sound: Boolean, vibrate: Boolean) {
        if (sound) runCatching {
            tone?.startTone(if (severe) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP2, if (severe) 900 else 300)
        }
        if (vibrate) runCatching {
            val pattern = if (severe) longArrayOf(0, 300, 150, 300, 150, 300) else longArrayOf(0, 200)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
