package com.example.obdanalyzer.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Vehicle
import java.util.Locale
import kotlinx.coroutines.launch

/** Retrouver ma voiture : position de la fin du dernier trajet, itinéraire à pied. */
@Composable
fun ParkCard(v: Vehicle) {
    val lat = v.parkLat ?: return
    val lon = v.parkLon ?: return
    val ctx = LocalContext.current
    SectionCard(Icons.Rounded.LocalParking, LocalObd.current.speed, "Retrouver ma voiture") {
        Text("${v.name} garée " + DateUtils.getRelativeTimeSpanString(v.parkAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase(Locale.FRANCE),
            style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = {
            val u = Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=" + "%.6f,%.6f".format(Locale.US, lat, lon))
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }.onFailure { ObdManager.toast("Aucune appli de cartes trouvée.") }
        }) { Icon(Icons.Rounded.DirectionsWalk, null); Spacer(Modifier.width(6.dp)); Text("Itinéraire à pied") }
        Text("Position enregistrée à la fin de chaque trajet, quand le GPS est disponible.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Historique des tensions au démarreur, mesurées automatiquement à chaque démarrage. */
@Composable
fun CrankHistoryCard(v: Vehicle) {
    val obd = LocalObd.current
    SectionCard(Icons.Rounded.BatteryChargingFull, obd.battery, "Batterie aux derniers démarrages") {
        if (v.crankHistory.isEmpty()) {
            Text("Rien encore : connectez le boîtier contact mis, moteur coupé, puis démarrez. L'appli mesure la chute de tension toute seule à chaque démarrage.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val last = v.crankHistory.last()
        val (txt, c) = when {
            last < 9.6 -> "Faible : faites tester la batterie" to obd.bad
            last < 10.0 -> "Un peu juste : à surveiller" to obd.warn
            else -> "Bonne forme" to obd.ok
        }
        Text("Dernier démarrage : ${"%.1f".format(last)} V · $txt", fontWeight = FontWeight.Bold, color = c)
        Sparkline(v.crankHistory.map { it.toFloat() }, c, Modifier.fillMaxWidth().height(60.dp), 8f)
        Text("Au-dessus de 10 V : bon. Entre 9,6 et 10 V : fatiguée. Sous 9,6 V : risque de panne, surtout l'hiver. Une baisse régulière d'un démarrage à l'autre est un signe d'usure.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Stations les moins chères autour de soi (GPS + internet). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FuelPricesCard(v: Vehicle) {
    val ctx = LocalContext.current
    val obd = LocalObd.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(if (v.fuel == com.example.obdanalyzer.core.Fuel.DIESEL) com.example.obdanalyzer.core.FuelPrices.Kind.GAZOLE else com.example.obdanalyzer.core.FuelPrices.Kind.E10) }
    var radius by remember { mutableIntStateOf(10) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<com.example.obdanalyzer.core.FuelPrices.Station>?>(null) }
    fun search() {
        val loc = com.example.obdanalyzer.core.FuelPrices.lastLocation(ctx)
        if (loc == null) { error = "Position inconnue : activez la localisation de l'appareil, puis réessayez."; return }
        loading = true; error = null
        scope.launch {
            list = runCatching { com.example.obdanalyzer.core.FuelPrices.nearby(loc.latitude, loc.longitude, kind, radius) }
                .onFailure { error = "Recherche impossible (${it.message ?: "pas d'internet"})." }.getOrNull()
            loading = false
        }
    }
    SectionCard(Icons.Rounded.LocalGasStation, obd.energy, "Prix des carburants autour de moi") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.example.obdanalyzer.core.FuelPrices.Kind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k; list = null }, label = { Text(k.label) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 20).forEach { r -> FilterChip(selected = radius == r, onClick = { radius = r; list = null }, label = { Text("$r km") }) }
        }
        Button(onClick = { search() }, enabled = !loading) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, null)
            Spacer(Modifier.width(8.dp)); Text("Chercher les moins chères")
        }
        error?.let { Text(it, color = obd.bad, style = MaterialTheme.typography.bodySmall) }
        list?.let { l ->
            if (l.isEmpty()) Text("Aucune station trouvée dans ce rayon.", style = MaterialTheme.typography.bodySmall)
            l.take(10).forEachIndexed { i, s ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(String.format(Locale.FRANCE, "%.3f €", s.price), fontWeight = FontWeight.ExtraBold,
                        color = if (i == 0) obd.ok else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(80.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${s.address}, ${s.city}", maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        Text(String.format(Locale.FRANCE, "à %.1f km", s.distanceKm) + (if (s.updated.isNotBlank()) " · prix du ${s.updated}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        val u = Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=" + "%.6f,%.6f".format(Locale.US, s.lat, s.lon))
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    }) { Icon(Icons.Rounded.Directions, "Y aller") }
                    IconButton(onClick = {
                        ObdManager.updateSettings { it.copy(fuelPrice = s.price) }
                        ObdManager.toast(String.format(Locale.FRANCE, "Prix de %.3f € retenu pour le carnet et le coût des trajets", s.price))
                    }) { Icon(Icons.Rounded.Check, "Utiliser ce prix") }
                }
            }
            if (l.isNotEmpty()) Text("Source : prix-carburants.gouv.fr. ✓ retient le prix pour votre prochain plein et le coût des trajets.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Statistiques mois par mois : km, litres, euros, consommation moyenne ; export PDF du mois (note de frais). */
@Composable
fun StatsScreen(trips: List<com.example.obdanalyzer.core.TripSummary>, v: Vehicle) {
    val ctx = LocalContext.current
    val obd = LocalObd.current
    val price = ObdManager.settings.value.fuelPrice
    val fuelLog by ObdManager.fuelLog.collectAsState()
    val mine = trips.filter { it.vehicleId == v.id }
    val fmtMonth = java.text.SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
    data class Month(val key: Int, val label: String, val trips: List<com.example.obdanalyzer.core.TripSummary>, val paid: Double)
    val months = remember(mine, fuelLog) {
        val cal = java.util.Calendar.getInstance()
        fun key(t: Long): Int { cal.timeInMillis = t; return cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH) }
        val fuelByMonth = fuelLog.filter { it.vehicleId == v.id }.groupBy { key(it.date) }
        mine.groupBy { key(it.start) }.map { (k, list) ->
            cal.set(k / 12, k % 12, 1)
            Month(k, fmtMonth.format(cal.time).replaceFirstChar { it.uppercase() }, list, fuelByMonth[k]?.sumOf { it.cost } ?: 0.0)
        }.sortedByDescending { it.key }.take(12)
    }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (months.isEmpty()) item {
            EmptyState(Icons.Rounded.BarChart, obd.speed, "Pas encore de statistiques", "Elles apparaissent mois par mois dès vos premiers trajets.")
        } else item {
            SectionCard(Icons.Rounded.BarChart, obd.speed, "Kilomètres par mois") {
                val shown = months.take(6).reversed()
                val maxKm = shown.maxOf { m -> m.trips.sumOf { it.distanceKm } }.coerceAtLeast(1.0)
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    shown.forEach { m ->
                        val km = m.trips.sumOf { it.distanceKm }
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text(ObdManager.fmt(km), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Box(Modifier.fillMaxWidth().fillMaxHeight((km / maxKm).toFloat().coerceIn(0.02f, 0.8f))
                                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)))
                            Text(m.label.take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        items(months.size) { i ->
            val m = months[i]
            val km = m.trips.sumOf { it.distanceKm }; val l = m.trips.sumOf { it.fuelL }
            SectionCard(Icons.Rounded.CalendarMonth, obd.energy, m.label) {
                Row(Modifier.fillMaxWidth()) {
                    BigStat("${ObdManager.fmt(km)} km", "${m.trips.size} trajet(s)", Modifier.weight(1f))
                    BigStat("${ObdManager.fmt(l)} L", if (km > 5) "${ObdManager.fmt(l / km * 100)} L/100 km" else "consommés", Modifier.weight(1f))
                    BigStat(String.format(Locale.FRANCE, "%.0f €", if (m.paid > 0) m.paid else l * price), if (m.paid > 0) "payés à la pompe" else "estimés", Modifier.weight(1f))
                }
                TextButton(onClick = {
                    runCatching {
                        val f = com.example.obdanalyzer.report.ReportPdf.expenses(ctx, v, m.label, m.trips, price)
                        shareFile(ctx, f, "application/pdf", "Relevé des trajets")
                    }.onFailure { ObdManager.toast("PDF impossible : ${it.message}") }
                }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("Relevé PDF (note de frais)")
                }
            }
        }
    }
}
