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

    /** Envoie une touche multimédia à l'appli qui joue de la musique. */
    private fun key(ctx: Context, code: Int) = runCatching {
        val a = am(ctx) ?: return@runCatching
        val t = SystemClock.uptimeMillis()
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    fun playPause(ctx: Context) = key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun previous(ctx: Context) = key(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun next(ctx: Context) = key(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)

    const val SPOTIFY = "com.spotify.music"

    /** Ouvre Spotify ; s'il n'est pas installé, ouvre sa page dans le Play Store. Renvoie false si rien n'a pu s'ouvrir. */
    fun openSpotify(ctx: Context): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)
        val i = launch ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$SPOTIFY"))
        return runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrElse {
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://open.spotify.com")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }.getOrDefault(false)
        }
    }

    fun isPlaying(ctx: Context) = am(ctx)?.isMusicActive == true
}
