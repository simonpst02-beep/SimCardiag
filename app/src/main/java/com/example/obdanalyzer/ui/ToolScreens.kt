package com.example.obdanalyzer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Fuel
import com.example.obdanalyzer.core.FuelEntry
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.fuelStats
import com.example.obdanalyzer.report.ReportPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- rapport PDF

@Composable
fun ReportButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    FilledTonalButton(modifier = modifier, enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    ReportPdf.generate(ctx, ObdManager.activeVehicle(), ObdManager.live.value, ObdManager.ecu.value, ObdManager.trips.value)
                }.getOrNull()
            }
            busy = false
            if (f != null) shareFile(ctx, f, "application/pdf", "Partager le rapport")
        }
    }) {
        Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Création…" else "Rapport PDF")
    }
}

fun shareFile(ctx: Context, f: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, title))
}

// ---------------------------------------------------------------- batterie

@Composable
fun BatteryScreen(l: Live, wide: Boolean) {
    val obd = LocalObd.current
    if (l.conn != Conn.CONNECTED) {
        EmptyState(Icons.Rounded.BatteryFull, obd.battery, "Boîtier non connecté", "Connectez l'OBDLink, contact mis et moteur coupé, pour tester la batterie.")
        return
    }
    val b by ObdManager.battery.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(Icons.Rounded.BatteryFull, obd.battery, "Test batterie et démarreur") {
            Text("1. Moteur coupé, contact mis, phares et clim éteints.\n2. Lancez le test, puis démarrez le moteur quand l'appli le demande.\n3. Laissez tourner au ralenti quelques secondes.",
                style = MaterialTheme.typography.bodyMedium)
            when (b.phase) {
                "idle" -> Button(onClick = { ObdManager.startBatteryTest() }) { Text("Lancer le test") }
                "rest", "charge" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp); Text(b.message)
                }
                "crank" -> Text(b.message, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = obd.warn)
                "error" -> { ErrorBox(b.message); TextButton(onClick = { ObdManager.resetBatteryTest() }) { Text("Recommencer") } }
                else -> TextButton(onClick = { ObdManager.resetBatteryTest() }) { Text("Refaire le test") }
            }
        }
        if (b.samples.isNotEmpty()) SectionCard(Icons.Rounded.ShowChart, obd.battery, "Tension pendant le démarrage") {
            Sparkline(b.samples.takeLast(com.example.obdanalyzer.core.HISTORY), obd.battery, Modifier.fillMaxWidth().height(120.dp), 8f)
        }
        if (b.rest != null || b.crankMin != null || b.charge != null) SectionCard(Icons.Rounded.FactCheck, obd.ok, "Résultat") {
            b.rest?.let { v ->
                val (t, c) = when { v >= 12.5 -> "Bonne charge" to obd.ok; v >= 12.2 -> "Charge moyenne" to obd.warn; else -> "Batterie déchargée" to obd.bad }
                ResultRow("Au repos", "${ObdManager.fmt(v)} V", t, c)
            }
            b.crankMin?.let { v ->
                val (t, c) = when { v >= 10.0 -> "Démarrage facile" to obd.ok; v >= 9.6 -> "Correct, à surveiller" to obd.warn; else -> "Batterie fatiguée" to obd.bad }
                ResultRow("Au démarrage (minimum)", "${ObdManager.fmt(v)} V", t, c)
            }
            b.charge?.let { v ->
                val (t, c) = when { v in 13.5..14.9 -> "Alternateur OK" to obd.ok; v < 13.5 -> "Charge faible" to obd.bad; else -> "Surcharge" to obd.bad }
                ResultRow("Moteur au ralenti", "${ObdManager.fmt(v)} V", t, c)
            }
            if (b.phase == "done") Text(batteryAdvice(b.rest, b.crankMin, b.charge), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun batteryAdvice(rest: Double?, crank: Double?, charge: Double?): String = when {
    charge != null && charge < 13.5 -> "L'alternateur ne recharge pas assez : faites-le contrôler rapidement, la batterie va se vider."
    crank != null && crank < 9.6 -> "La batterie peine au démarrage : prévoyez son remplacement, surtout avant l'hiver."
    rest != null && rest < 12.2 -> "La batterie est déchargée : roulez au moins 30 minutes ou rechargez-la, puis refaites le test."
    else -> "Batterie et charge en bon état."
}

@Composable
private fun ResultRow(label: String, value: String, verdict: String, c: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(verdict, fontWeight = FontWeight.SemiBold, color = c)
        }
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ---------------------------------------------------------------- filtre à particules

private val DPF_WORDS = listOf("FAP", "DPF", "SUIE", "SOOT", "REGEN", "RÉGÉN", "PARTICUL")

@Composable
fun DpfScreen(l: Live, wide: Boolean) {
    val obd = LocalObd.current
    val v = ObdManager.activeVehicle()
    val custom = v.customPids.filter { c -> DPF_WORDS.any { c.name.uppercase().contains(it) } }
    val pressure = l.values["dpfp"]; val temp = l.values["dpft"]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (v.fuel == Fuel.ESSENCE && pressure == null && custom.isEmpty()) {
            EmptyState(Icons.Rounded.FilterAlt, obd.temp, "Pas de filtre à particules suivi",
                "Ce véhicule est réglé en essence. Si c'est un diesel, changez le carburant dans le Garage.")
            return
        }
        val regen = (temp != null && temp > 500) || custom.any { c -> c.name.uppercase().contains("REGEN") && (l.values[c.key] ?: 0.0) > 0.5 }
        SectionCard(Icons.Rounded.FilterAlt, if (regen) obd.warn else obd.ok, "Filtre à particules") {
            Text(
                when {
                    l.conn != Conn.CONNECTED -> "Connectez le boîtier pour suivre le filtre en direct."
                    regen -> "Régénération probablement en cours : si possible, continuez à rouler à allure régulière (voie rapide) jusqu'à la fin, sans couper le moteur."
                    else -> "Pas de régénération en cours."
                }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
            )
            pressure?.let { KeyValue("Pression différentielle", "${ObdManager.fmt(it)} kPa") }
            temp?.let { KeyValue("Température", "${ObdManager.fmt(it)} °C") }
            custom.forEach { c -> KeyValue(c.name, l.values[c.key]?.let { "${ObdManager.fmt(it)} ${c.unit}" } ?: "—") }
            if (pressure == null && temp == null && custom.isEmpty()) Text(
                "Votre voiture ne transmet pas les mesures standard du filtre. Ajoutez celles de votre modèle dans Garage › Données constructeur " +
                    "(nommez-les avec « FAP », « suie » ou « régénération » pour qu'elles apparaissent ici). Une liste au format Torque peut être importée d'un coup.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        l.history["dpfp"]?.takeIf { it.size > 2 }?.let {
            SectionCard(Icons.Rounded.ShowChart, obd.temp, "Pression différentielle") { Sparkline(it, obd.temp, Modifier.fillMaxWidth().height(100.dp), 0f) }
        }
        SectionCard(Icons.Rounded.Lightbulb, obd.battery, "Bons réflexes") {
            Text("• Faites régulièrement 20 minutes de voie rapide pour laisser le filtre se nettoyer.\n" +
                "• Évitez de couper le moteur pendant une régénération (ventilateur qui tourne, ralenti plus haut).\n" +
                "• Si le voyant FAP s'allume, roulez 15 à 20 minutes à vitesse stable ; s'il reste allumé, voyez un garage.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- carnet de carburant

/** Indiquer le niveau de la jauge à la main (voitures qui ne transmettent pas leur jauge, comme la Corsa D). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuelLevelDialog(v: Vehicle, onDismiss: () -> Unit) {
    var eighths by remember { mutableIntStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Niveau du réservoir") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Votre voiture ne transmet pas sa jauge. Indiquez où en est l'aiguille : l'appli calcule ensuite le carburant restant et l'autonomie au fil des trajets.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Réserve", 2 to "1/4", 4 to "1/2", 6 to "3/4", 8 to "Plein").forEach { (e, n) ->
                        FilterChip(selected = eighths == e, onClick = { eighths = e }, label = { Text(n) })
                    }
                }
                Slider(value = eighths.toFloat(), onValueChange = { eighths = it.roundToInt() }, valueRange = 0f..8f, steps = 7)
                Text("≈ ${ObdManager.fmt(eighths / 8.0 * v.tankL)} L sur ${ObdManager.fmt(v.tankL)} L", fontWeight = FontWeight.SemiBold)
                Text("Après un plein complet, notez-le plutôt dans le carnet de carburant : c'est plus précis.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { ObdManager.setFuelLevel(eighths / 8.0); onDismiss() }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun FuelScreen(v: Vehicle, wide: Boolean) {
    val obd = LocalObd.current
    val all by ObdManager.fuelLog.collectAsStateWithLifecycle()
    val mine = all.filter { it.vehicleId == v.id }
    val st = fuelStats(mine)
    var adding by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(false) }
    if (level) FuelLevelDialog(v) { level = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(Icons.Rounded.LocalGasStation, obd.energy, "Carnet de carburant") {
            OutlinedButton(onClick = { level = true }) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(6.dp)); Text("Indiquer le niveau du réservoir") }
            Row(Modifier.fillMaxWidth()) {
                BigStat(st.realConsumption?.let { ObdManager.fmt(it) } ?: "—", "L/100 km réels", Modifier.weight(1f))
                BigStat(st.costPerKm?.let { String.format(Locale.FRANCE, "%.2f €", it * 100) } ?: "—", "pour 100 km", Modifier.weight(1f))
                BigStat(String.format(Locale.FRANCE, "%.0f €", st.totalCost), "au total", Modifier.weight(1f))
            }
            val est = ObdManager.trips.value.filter { it.vehicleId == v.id }.let { t ->
                val km = t.sumOf { it.distanceKm }; if (km > 5) t.sumOf { it.fuelL } / km * 100 else null
            }
            if (st.realConsumption != null && est != null) Text(
                "Estimation de l'appli : ${ObdManager.fmt(est)} L/100 km (écart ${ObdManager.fmt((est - st.realConsumption) / st.realConsumption * 100)} %)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st.realConsumption == null) Text("La consommation réelle s'affiche à partir de deux pleins complets.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter un plein") }
        }
        if (st.monthly.isNotEmpty()) SectionCard(Icons.Rounded.BarChart, obd.energy, "Dépenses par mois") {
            val max = st.monthly.maxOf { it.second }.coerceAtLeast(1.0)
            st.monthly.takeLast(6).forEach { (m, c) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(monthLabel(m), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.weight(1f).height(14.dp)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((c / max).toFloat()).clip(RoundedCornerShape(7.dp)).background(obd.energy))
                    }
                    Text(String.format(Locale.FRANCE, "%.0f €", c), Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        mine.forEach { e ->
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(Icons.Rounded.LocalGasStation, obd.energy)
                    Column(Modifier.weight(1f)) {
                        Text("${SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date(e.date))} · ${ObdManager.fmt(e.liters)} L${if (e.full) "" else " (partiel)"}", fontWeight = FontWeight.Bold)
                        Text(String.format(Locale.FRANCE, "%.3f €/L · %.2f € · %,.0f km", e.pricePerL, e.cost, e.odometerKm),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { ObdManager.deleteFuel(e.id) }) { Icon(Icons.Rounded.Delete, "Supprimer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    if (adding) FuelDialog(v, onDismiss = { adding = false })
}

private fun monthLabel(m: String): String = runCatching {
    SimpleDateFormat("MMM yyyy", Locale.FRANCE).format(SimpleDateFormat("yyyy-MM", Locale.FRANCE).parse(m)!!)
}.getOrDefault(m)

@Composable
private fun FuelDialog(v: Vehicle, onDismiss: () -> Unit) {
    val odoNow = (v.odometerKm + ObdManager.live.value.stats.distanceKm).toInt()
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf(odoNow.toString()) }
    var full by remember { mutableStateOf(true) }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau plein") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(liters, { liters = it }, label = { Text("Litres") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(price, { price = it }, label = { Text("Prix au litre (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(odo, { odo = it.filter(Char::isDigit) }, label = { Text("Kilométrage au compteur") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = full, onCheckedChange = { full = it }); Text("Plein complet (réservoir rempli)")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = num(liters) != null && num(price) != null && odo.isNotEmpty(), onClick = {
                ObdManager.addFuel(FuelEntry(vehicleId = v.id, date = System.currentTimeMillis(), liters = num(liters)!!, pricePerL = num(price)!!,
                    odometerKm = odo.toDouble(), full = full))
                onDismiss()
            }) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- performances

@Composable
fun PerfScreen(l: Live, v: Vehicle, wide: Boolean) {
    val obd = LocalObd.current
    val runs by ObdManager.perfRuns.collectAsStateWithLifecycle()
    val status by ObdManager.perfStatus.collectAsStateWithLifecycle()
    val mine = runs.filter { it.vehicleId == v.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(Icons.Rounded.Timer, obd.speed, "Chronos automatiques") {
            Text("Uniquement sur circuit ou route fermée, dans le respect du code de la route. Les chronos se déclenchent seuls, sans toucher le téléphone.",
                style = MaterialTheme.typography.bodySmall, color = obd.warn)
            if (l.conn == Conn.CONNECTED) {
                Text(l.values["speed"]?.let { "${it.toInt()} km/h" } ?: "—", fontSize = 48.sp, fontWeight = FontWeight.Black)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (!l.multi) Text("Astuce : la lecture groupée (voitures CAN) donne des chronos plus précis.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("Connectez le boîtier pour chronométrer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("0-100", "80-120", "400 m").forEach { k ->
                val best = mine.filter { it.kind == k }.minByOrNull { it.seconds }
                Card(Modifier.weight(1f), shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(k, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(best?.let { String.format(Locale.FRANCE, "%.2f s", it.seconds) } ?: "—", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text("meilleur", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        mine.forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.kind} · ${String.format(Locale.FRANCE, "%.2f s", r.seconds)}", fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("d MMM · HH:mm", Locale.FRANCE).format(Date(r.date)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ObdManager.deletePerf(r.id) }) { Icon(Icons.Rounded.Delete, "Supprimer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---------------------------------------------------------------- journal technique

/** Bouton qui affiche les derniers échanges avec le boîtier, et permet de les envoyer. */
@Composable
fun JournalButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Rounded.Terminal, null); Spacer(Modifier.width(6.dp)); Text("Journal technique")
    }
    if (open) {
        val text = remember { ObdManager.journalText() }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Journal technique") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Derniers échanges avec le boîtier. Envoyez-les pour faire analyser un problème de connexion.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Journal SimCarDiag")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    ctx.startActivity(Intent.createChooser(i, "Envoyer le journal"))
                }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Envoyer") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Fermer") } },
        )
    }
}
