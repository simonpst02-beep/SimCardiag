package com.example.obdanalyzer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Places
import com.example.obdanalyzer.core.Live
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.obdanalyzer.core.TripSummary
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.ecoLabel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen(trips: List<TripSummary>, v: Vehicle, l: Live, wide: Boolean) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    var replay by remember { mutableStateOf<TripSummary?>(null) }
    replay?.let { t -> ReplayScreen(t, wide) { replay = null }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Trajets" to Icons.Rounded.Map, "Carburant" to Icons.Rounded.LocalGasStation, "Performances" to Icons.Rounded.Timer, "Statistiques" to Icons.Rounded.BarChart).forEachIndexed { i, (n, ic) ->
                FilterChip(selected = sub == i, onClick = { sub = i }, label = { Text(n) }, leadingIcon = { Icon(ic, null, Modifier.size(18.dp)) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (sub) {
                0 -> TripList(trips, v, wide) { replay = it }
                1 -> FuelScreen(v, wide)
                2 -> PerfScreen(l, v, wide)
                else -> StatsScreen(trips, v)
            }
        }
    }
}

@Composable
private fun TripList(trips: List<TripSummary>, v: Vehicle, wide: Boolean, onReplay: (TripSummary) -> Unit) {
    val mine = trips.filter { it.vehicleId == v.id }
    if (mine.isEmpty()) {
        EmptyState(Icons.Rounded.Map, LocalObd.current.speed, "Aucun trajet",
            "Vos trajets s'enregistrent tout seuls dès que vous roulez avec le boîtier connecté. Ils apparaîtront ici avec leur distance, leur consommation et leur note d'éco-conduite.")
        return
    }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ObdManager.nameRecentTrips() }   // villes de départ / arrivée des trajets qui n'en ont pas encore
    LazyVerticalGrid(
        GridCells.Fixed(if (wide) 2 else 1),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { HistoryCard(mine, wide) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Mes trajets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(mine, key = { it.id }) { t -> TripCard(t, ctx) { onReplay(t) } }
    }
}

@Composable
private fun HistoryCard(trips: List<TripSummary>, wide: Boolean) {
    val obd = LocalObd.current
    // 8 dernières semaines
    val weeks = remember(trips) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val starts = (7 downTo 0).map { w -> (cal.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, -w) }.timeInMillis }
        starts.mapIndexed { i, s ->
            val e = if (i < starts.size - 1) starts[i + 1] else Long.MAX_VALUE
            val ts = trips.filter { it.start in s until e }
            val km = ts.sumOf { it.distanceKm }
            val fuel = ts.sumOf { it.fuelL }
            Triple(SimpleDateFormat("d/MM", Locale.FRANCE).format(Date(s)), km, if (km > 1) fuel / km * 100 else null)
        }
    }
    val totalKm = trips.sumOf { it.distanceKm }
    val totalFuel = trips.sumOf { it.fuelL }
    val avgEco = trips.map { it.ecoScore }.average().toInt()
    SectionCard(Icons.Rounded.BarChart, obd.speed, "Historique · 8 semaines") {
        Row(Modifier.fillMaxWidth()) {
            BigStat("${ObdManager.fmt(totalKm)} km", "au total", Modifier.weight(1f))
            BigStat(if (totalKm > 1) ObdManager.fmt(totalFuel / totalKm * 100) else "—", "L/100 km moyen", Modifier.weight(1f))
            BigStat("$avgEco/100", "éco-conduite", Modifier.weight(1f))
        }
        val maxKm = maxOf(1.0, weeks.maxOf { it.second })
        val consVals = weeks.mapNotNull { it.third }
        val maxCons = maxOf(1.0, consVals.maxOrNull() ?: 1.0)
        val bar = obd.speed; val line = obd.energy; val track = obd.track
        Canvas(Modifier.fillMaxWidth().height(if (wide) 160.dp else 130.dp)) {
            val n = weeks.size
            val slot = size.width / n
            val bw = slot * 0.5f
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            var prev: Offset? = null
            weeks.forEachIndexed { i, (_, km, cons) ->
                val h = (km / maxKm).toFloat() * size.height * 0.9f
                val x = i * slot + (slot - bw) / 2
                if (h > 0) drawRoundRect(bar.copy(alpha = 0.75f), Offset(x, size.height - h), Size(bw, h), CornerRadius(6f, 6f))
                if (cons != null) {
                    val p = Offset(i * slot + slot / 2, size.height - (cons / maxCons).toFloat() * size.height * 0.9f)
                    prev?.let { drawLine(line, it, p, 3.dp.toPx()) }
                    drawCircle(line, 4.dp.toPx(), p)
                    prev = p
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            weeks.forEach { Text(it.first, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(bar, "Kilomètres"); Legend(line, "Consommation")
        }
    }
}

@Composable
private fun Legend(c: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(c) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TripCard(t: TripSummary, ctx: Context, onReplay: () -> Unit) {
    val obd = LocalObd.current
    val ecoC = when { t.ecoScore >= 70 -> obd.ok; t.ecoScore >= 50 -> obd.warn; else -> obd.bad }
    var ask by remember { mutableStateOf(false) }
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBadge(Icons.Rounded.Map, obd.speed)
                Column(Modifier.weight(1f)) {
                    t.route?.let { Text(it, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, maxLines = 2) }
                    Text(whenLabel(t.start), fontWeight = if (t.route == null) FontWeight.Bold else FontWeight.Normal,
                        color = if (t.route == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Éco-conduite ${t.ecoScore}/100 · ${ecoLabel(t.ecoScore)}", style = MaterialTheme.typography.bodySmall, color = ecoC)
                }
                if (t.file != null) FilledTonalIconButton(onClick = { share(ctx, File(ObdManager.logsDir, t.file)) }) {
                    Icon(Icons.Rounded.Share, "Partager le CSV")
                }
                FilledTonalIconButton(onClick = { ask = true }) { Icon(Icons.Rounded.Delete, "Supprimer", tint = obd.bad) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("${ObdManager.fmt(t.distanceKm)} km", "distance", Modifier.weight(1f))
                Chip("${t.durationS / 60} min", "durée", Modifier.weight(1f))
                Chip(t.avgConsumption?.let { ObdManager.fmt(it) } ?: "—", "L/100 km", Modifier.weight(1f))
                Chip(String.format(java.util.Locale.FRANCE, "%.2f €", t.cost(ObdManager.settings.value.fuelPrice)), "coût estimé", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReplay, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Rounded.PlayCircle, null); Spacer(Modifier.width(6.dp)); Text("Revoir le trajet")
                }
                TextButton(onClick = {
                    val uri = Places.mapsUri(ObdManager.tripPoints(t))
                    if (uri == null) ObdManager.toast("Pas de positions GPS pour ce trajet : activez « Tracer les trajets sur une carte ».")
                    else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        .onFailure { ObdManager.toast("Aucune appli de cartes ni navigateur trouvé.") }
                }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Rounded.Map, null); Spacer(Modifier.width(6.dp)); Text("Voir sur Maps")
                }
            }
            if (t.harshAcc + t.harshBrake > 0) Text("${t.harshAcc} accélération(s) et ${t.harshBrake} freinage(s) brusques · vitesse max ${ObdManager.fmt(t.maxSpeed)} km/h",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false }, title = { Text("Supprimer ce trajet ?") },
        confirmButton = { TextButton(onClick = { ask = false; ObdManager.deleteTrip(t) }) { Text("Supprimer", color = obd.bad) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Annuler") } },
    )
}

@Composable
private fun Chip(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(8.dp)) {
            Text(value, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun whenLabel(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val hm = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Aujourd'hui · $hm"
        yesterday -> "Hier · $hm"
        else -> SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.FRANCE).format(Date(t)).replaceFirstChar { it.uppercase() }
    }
}

private fun share(ctx: Context, f: File) {
    if (!f.exists()) return
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Partager le trajet"))
}
