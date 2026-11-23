package com.example.obdanalyzer.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/** Position GPS du téléphone pendant le trajet (pour la carte). Sans Google Play Services. */
class GpsTracker(private val context: Context) : LocationListener {

    private val lm = context.getSystemService(LocationManager::class.java)
    var last: Location? = null; private set
    private var running = false

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !granted() || lm == null) return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            running = true
        }
    }

    fun stop() {
        if (!running) return
        runCatching { lm?.removeUpdates(this) }
        running = false
        last = null
    }

    override fun onLocationChanged(location: Location) { last = location }

    /** Position récente (moins de 5 s), sinon null. */
    fun fresh(): Location? = last?.takeIf { System.currentTimeMillis() - it.time < 5000 }
}
