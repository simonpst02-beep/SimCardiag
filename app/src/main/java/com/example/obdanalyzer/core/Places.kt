package com.example.obdanalyzer.core

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Noms de villes des trajets et lien vers une appli de cartes (Google Maps, Waze…). */
object Places {

    /** Ville la plus proche d'une position : service d'Android, sinon OpenStreetMap (besoin d'internet, 4G ou Wi-Fi). */
    suspend fun city(ctx: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        fromAndroid(ctx, lat, lon) ?: fromOsm(lat, lon)
    }

    @Suppress("DEPRECATION")
    private fun fromAndroid(ctx: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(ctx, Locale.FRANCE).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()

    private fun fromOsm(lat: Double, lon: Double): String? = runCatching {
        val u = URL("https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&accept-language=fr&lat=$lat&lon=$lon")
        val c = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "SimCarDiag/2 (application Android personnelle)")
        }
        c.inputStream.use { s ->
            val ad = JSONObject(s.readBytes().toString(Charsets.UTF_8)).optJSONObject("address") ?: return null
            listOf("city", "town", "village", "municipality", "county").firstNotNullOfOrNull { k -> ad.optString(k).ifBlank { null } }
        }
    }.getOrNull()

    /**
     * Lien d'itinéraire Google Maps qui suit le trajet : départ, arrivée et jusqu'à 8 points de passage.
     * Il s'ouvre dans l'appli Maps si elle est installée, sinon dans le navigateur.
     */
    fun mapsUri(points: List<Pair<Double, Double>>): Uri? {
        if (points.size < 2) return null
        fun p(x: Pair<Double, Double>) = "%.5f,%.5f".format(Locale.US, x.first, x.second)
        val inner = points.subList(1, points.size - 1)
        val way = if (inner.isEmpty()) emptyList() else (1..minOf(8, inner.size)).map { inner[(it * inner.size / (minOf(8, inner.size) + 1)).coerceAtMost(inner.size - 1)] }
        val b = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", p(points.first()))
            .appendQueryParameter("destination", p(points.last()))
            .appendQueryParameter("travelmode", "driving")
        if (way.isNotEmpty()) b.appendQueryParameter("waypoints", way.joinToString("|") { p(it) })
        return b.build()
    }
}
