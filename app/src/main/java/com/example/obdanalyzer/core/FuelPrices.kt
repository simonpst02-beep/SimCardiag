package com.example.obdanalyzer.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Prix des carburants autour de soi, données officielles du ministère de l'Économie (mises à jour toutes les 10 minutes). */
object FuelPrices {

    enum class Kind(val label: String, val field: String) {
        GAZOLE("Gazole", "gazole_prix"), E10("SP95-E10", "e10_prix"), SP95("SP95", "sp95_prix"),
        SP98("SP98", "sp98_prix"), E85("E85", "e85_prix"), GPL("GPL", "gplc_prix"),
    }

    data class Station(val address: String, val city: String, val price: Double, val lat: Double, val lon: Double,
                       val distanceKm: Double, val updated: String)

    private const val API = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records"

    /** Position actuelle : GPS du trajet en cours, sinon dernière position connue de l'appareil. */
    @SuppressLint("MissingPermission")
    fun lastLocation(ctx: Context): Location? = runCatching {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    /** Stations les moins chères dans un rayon donné, triées par prix. */
    suspend fun nearby(lat: Double, lon: Double, kind: Kind, radiusKm: Int = 10): List<Station> = withContext(Dispatchers.IO) {
        val where = "within_distance(geom, geom'POINT($lon $lat)', ${radiusKm}km) and ${kind.field} is not null"
        val url = "$API?limit=30&order_by=${kind.field}&where=" + URLEncoder.encode(where, "UTF-8")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "SimCarDiag")
        }
        if (c.responseCode !in 200..299) throw IllegalStateException("le service des prix ne répond pas (${c.responseCode})")
        val json = c.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val res = json.optJSONArray("results") ?: return@withContext emptyList()
        val here = Location("").apply { latitude = lat; longitude = lon }
        (0 until res.length()).mapNotNull { i ->
            val r = res.getJSONObject(i)
            val price = r.optDouble(kind.field, Double.NaN).takeIf { !it.isNaN() && it > 0.3 } ?: return@mapNotNull null
            val g = r.optJSONObject("geom")
            val sLat = g?.optDouble("lat") ?: (r.optString("latitude").toDoubleOrNull()?.div(100000)) ?: return@mapNotNull null
            val sLon = g?.optDouble("lon") ?: (r.optString("longitude").toDoubleOrNull()?.div(100000)) ?: return@mapNotNull null
            val there = Location("").apply { latitude = sLat; longitude = sLon }
            Station(r.optString("adresse").trim(), r.optString("ville").trim(), price, sLat, sLon,
                here.distanceTo(there) / 1000.0, r.optString(kind.field.replace("_prix", "_maj")).take(16).replace('T', ' '))
        }.sortedWith(compareBy({ it.price }, { it.distanceKm }))
    }
}
