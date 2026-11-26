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

/**
 * Position GPS de l'appareil pendant le trajet : carte, distance de secours quand la voiture ne donne pas sa vitesse,
 * comparaison avec le compteur. Sans Google Play Services.
 */
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
        prevGood = null
        totalKm = 0.0
    }

    /** Distance parcourue selon le GPS depuis le démarrage (km), en ignorant les positions imprécises. */
    var totalKm = 0.0; private set
    private var prevGood: Location? = null

    override fun onLocationChanged(location: Location) {
        last = location
        if (!location.hasAccuracy() || location.accuracy > 25f) return
        val p = prevGood
        prevGood = location
        if (p == null) return
        val dtS = (location.time - p.time) / 1000.0
        if (dtS <= 0 || dtS > 30) return
        val m = p.distanceTo(location)
        // à l'arrêt, le GPS « flotte » de quelques mètres : on ne compte que les vrais déplacements
        if (m < 3 || m / dtS > 70) return
        totalKm += m / 1000.0
    }

    /** Vitesse GPS récente et fiable (km/h), sinon null. */
    fun speedKmh(): Double? = fresh()?.takeIf { it.hasSpeed() && it.hasAccuracy() && it.accuracy <= 15f }?.let { it.speed * 3.6 }

    /** Position récente (moins de 5 s), sinon null. */
    fun fresh(): Location? = last?.takeIf { System.currentTimeMillis() - it.time < 5000 }
}
