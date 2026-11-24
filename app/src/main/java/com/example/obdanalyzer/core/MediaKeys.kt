package com.example.obdanalyzer.core

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/** Commandes de la musique en cours (radio, Spotify, YouTube Music…) et du volume. */
object MediaKeys {
    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun volumeDown(ctx: Context) = runCatching {
        am(ctx)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    fun volumeUp(ctx: Context) = runCatching {
        am(ctx)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }

    /** Envoie la touche « lecture/pause » à l'appli qui joue de la musique. */
    fun playPause(ctx: Context) = runCatching {
        val a = am(ctx) ?: return@runCatching
        val t = SystemClock.uptimeMillis()
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
    }

    fun isPlaying(ctx: Context) = am(ctx)?.isMusicActive == true
}
