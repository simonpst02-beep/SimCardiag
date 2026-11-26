package com.example.obdanalyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.WidgetCfg
import com.example.obdanalyzer.core.ecoLabel
import com.example.obdanalyzer.core.displayValue
import com.example.obdanalyzer.obd.MetricDef
import kotlin.math.roundToInt

val CardShape = RoundedCornerShape(22.dp)

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Int = 34) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = color, modifier = Modifier.size((size * 0.56).dp)) }
}

@Composable
fun MetricHead(def: MetricDef, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBadge(metricIcon(def.key), familyColor(def.family))
        Text(def.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun HintLine(text: String, level: Level?) {
    if (text.isEmpty()) return
    val c = level?.let { levelColor(it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (c != null) Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (level == Level.WARN || level == Level.BAD) c!! else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Jauge dont le remplissage suit la valeur brute mais qui affiche la valeur convertie (km/L, °F). */
@Composable
fun GaugeLabeled(raw: Double?, shown: Double?, def: MetricDef, color: Color, unit: String, modifier: Modifier = Modifier) =
    Gauge(raw, def, color, unit, modifier, shown)

/** Jauge en arc de cercle (240°). */
@Composable
fun Gauge(value: Double?, def: MetricDef, color: Color, unit: String, modifier: Modifier = Modifier, shown: Double? = value) {
    val track = LocalObd.current.track
    val frac = if (value == null || value.isNaN()) 0f else ((value - def.min) / (def.max - def.min)).toFloat().coerceIn(0f, 1f)
    Box(modifier.aspectRatio(1.35f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val stroke = size.minDimension * 0.09f
            val d = minOf(size.width, size.height * 1.25f) - stroke
            val topLeft = Offset((size.width - d) / 2, stroke / 2 + 2f)
            drawArc(track, 150f, 240f, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            if (frac > 0f) drawArc(color, 150f, 240f * frac, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 10.dp)) {
            Text(shown?.let { ObdManager.fmt(it) } ?: "—", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Courbe des dernières valeurs avec dégradé. */
@Composable
fun Sparkline(data: List<Float>, color: Color, modifier: Modifier = Modifier, minY: Float? = null) {
    val grid = LocalObd.current.track
    Canvas(modifier) {
        for (i in 1..3) {
            val y = size.height * i / 4
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (data.size < 2) return@Canvas
        val lo = minOf(minY ?: data.min(), data.min())
        val hi = maxOf(data.max(), lo + 1f)
        val step = size.width / (com.example.obdanalyzer.core.HISTORY - 1)
        val off = com.example.obdanalyzer.core.HISTORY - data.size
        val line = Path()
        data.forEachIndexed { i, v ->
            val x = (i + off) * step
            val y = size.height - (v - lo) / (hi - lo) * size.height * 0.92f
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(off * step, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Contenu d'un widget du tableau de bord. */
@Composable
fun ColumnScope.WidgetContent(w: WidgetCfg, l: Live, v: Vehicle, onTab: (Int) -> Unit) {
    val def = ObdManager.metric(w.metric)
    when (w.kind) {
        "val" -> if (def != null) {
            val raw = l.values[def.key]
            val (value, unit) = displayValue(def.key, raw, if (def.key == "cons") l.consUnit else def.unit, ObdManager.settings.value)
            val (h, lvl) = hint(def.key, raw, l, v)
            MetricHead(def)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value?.let { ObdManager.fmt(it) } ?: "—", fontSize = if (w.wide) 46.sp else 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            HintLine(h, lvl)
            if (raw == null && def.key in setOf("left", "range") && l.values.isNotEmpty()) {
                var ask by remember { mutableStateOf(false) }
                TextButton(onClick = { ask = true }, contentPadding = PaddingValues(0.dp)) { Text("Indiquer le niveau du réservoir") }
                if (ask) FuelLevelDialog(v) { ask = false }
            }
        }
        "gauge" -> if (def != null) {
            val value = l.values[def.key]
            val (h, lvl) = hint(def.key, value, l, v)
            val col = when (lvl) { Level.BAD -> LocalObd.current.bad; Level.WARN -> LocalObd.current.warn; else -> familyColor(def.family) }
            MetricHead(def)
            val shown = displayValue(def.key, value, if (def.key == "cons") l.consUnit else def.unit, ObdManager.settings.value)
            GaugeLabeled(value, shown.first, def, col, shown.second,
                Modifier.fillMaxWidth().widthIn(max = 260.dp).align(Alignment.CenterHorizontally))
            HintLine(h, lvl)
        }
        "chart" -> if (def != null) {
            val data = l.history[def.key].orEmpty()
            MetricHead(def) {
                if (data.isNotEmpty()) Text("max ${ObdManager.fmt(data.max().toDouble())} ${def.unit}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Sparkline(data, familyColor(def.family), Modifier.fillMaxWidth().height(90.dp), def.min.toFloat())
        }
        "stats" -> {
            val s = l.stats
            WidgetTitle(Icons.Rounded.Map, LocalObd.current.speed, "Trajet en cours")
            Row(Modifier.fillMaxWidth()) {
                BigStat("${ObdManager.fmt(s.distanceKm)} km", "distance", Modifier.weight(1f))
                BigStat("${s.durationS / 60} min", "durée", Modifier.weight(1f))
                BigStat(s.avgConsumption?.let { ObdManager.fmt(it) } ?: "—", "L/100 km*", Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            Text("Moyenne ${ObdManager.fmt(s.avgSpeed)} km/h · Max ${ObdManager.fmt(s.maxSpeed)} km/h · Carburant ${"%.2f".format(s.fuelL)} L (≈ ${"%.2f".format(s.fuelL * ObdManager.settings.value.fuelPrice)} €)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "shift" -> {
            val lim = shiftRpmFor(ObdManager.settings.value, v)
            WidgetTitle(Icons.Rounded.Speed, LocalObd.current.engine, "Passage de vitesse")
            ShiftLight(l.values["rpm"], lim, Modifier.fillMaxWidth(), height = 28.dp, leds = 12)
            Text("${l.values["rpm"]?.let { ObdManager.fmt(it) } ?: "—"} tr/min · clignote à $lim tr/min",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "eco" -> {
            val s = l.stats
            val c = when { s.eco >= 70 -> LocalObd.current.ok; s.eco >= 50 -> LocalObd.current.warn; else -> LocalObd.current.bad }
            WidgetTitle(Icons.Rounded.Eco, LocalObd.current.energy, "Éco-conduite")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    val track = LocalObd.current.track
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(track, -90f, 360f, false, style = Stroke(7.dp.toPx()))
                        drawArc(c, -90f, 360f * s.eco / 100f, false, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Text("${s.eco}", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(ecoLabel(s.eco), fontWeight = FontWeight.Bold, color = c)
                    Text(s.tips.firstOrNull() ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "dtc" -> {
            WidgetTitle(Icons.Rounded.Build, LocalObd.current.warn, "Codes défaut")
            val d = l.dtcs
            when {
                l.diagLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp); Text("Lecture des calculateurs…", style = MaterialTheme.typography.bodySmall)
                }
                d == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pas encore vérifié", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { ObdManager.readDiagnostics() }) { Text("Vérifier") }
                }
                d.isEmpty() -> HintLine("Aucun code défaut", Level.OK)
                else -> {
                    d.forEach { Text("${it.code}  ${it.title}", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { onTab(1) }, contentPadding = PaddingValues(0.dp)) { Text("Voir quoi faire") }
                }
            }
        }
        "rec" -> if (l.recording) {
            Button(onClick = { ObdManager.toggleRecording() }, Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LocalObd.current.bad)) {
                Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Arrêter l'enregistrement")
            }
        } else {
            Button(onClick = { ObdManager.toggleRecording() }, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.FiberManualRecord, null); Spacer(Modifier.width(8.dp)); Text("Enregistrer le détail (CSV)")
            }
        }
    }
}

@Composable
fun WidgetTitle(icon: ImageVector, color: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBadge(icon, color)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val SPECIAL = listOf(
    Triple("stats", "Bilan du trajet", Icons.Rounded.Map),
    Triple("eco", "Éco-conduite", Icons.Rounded.Eco),
    Triple("shift", "Témoin de passage", Icons.Rounded.Speed),
    Triple("dtc", "Codes défaut", Icons.Rounded.Build),
    Triple("rec", "Enregistrement CSV", Icons.Rounded.FiberManualRecord),
)

/** Fenêtre d'ajout de widget. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWidgetSheet(layout: List<WidgetCfg>, wide: Boolean, onAdd: (WidgetCfg) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf("val") }
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ajouter un widget", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("1. Choisissez l'affichage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("val" to "Valeur", "gauge" to "Jauge", "chart" to "Courbe").forEach { (k, n) ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(n) })
                }
            }
            Text("2. Touchez une mesure", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val defs = ObdManager.metricDefs()
            val cols = if (wide) 3 else 2
            LazyVerticalGrid(GridCells.Fixed(cols), Modifier.heightIn(max = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(defs, key = { it.key }) { d ->
                    PickCard(metricIcon(d.key), familyColor(d.family), d.name, d.unit, true) {
                        onAdd(WidgetCfg(kind, d.key, kind == "chart"))
                    }
                }
                items(SPECIAL, key = { it.first }) { (k, n, ic) ->
                    val has = layout.any { it.kind == k }
                    PickCard(ic, LocalObd.current.speed, n, if (has) "Déjà affiché" else "Widget", !has) { onAdd(WidgetCfg(k, null, true)) }
                }
            }
        }
    }
    if (wide) {
        AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
            text = { content() }, modifier = Modifier.widthIn(max = 720.dp))
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) { content() }
    }
}

@Composable
private fun PickCard(icon: ImageVector, color: Color, name: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(10.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(icon, color, 32)
            Column {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, color: Color, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(92.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(46.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp))
        action?.invoke()
    }
}

fun pct(x: Double) = (x * 100).roundToInt()
