package com.example.obdanalyzer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.TripPoint
import com.example.obdanalyzer.core.TripSummary
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private data class Curve(val key: String, val label: String, val unit: String, val get: (TripPoint) -> Double?)

private val CURVES = listOf(
    Curve("speed", "Vitesse", "km/h") { it.speed },
    Curve("rpm", "Régime", "tr/min") { it.rpm },
    Curve("cons", "Consommation", "L/100km") { it.cons },
    Curve("cool", "Temp. moteur", "°C") { it.cool },
    Curve("thr", "Accélérateur", "%") { it.thr },
)

/** Revoir un trajet : carte colorée par consommation + courbes superposées avec curseur. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReplayScreen(trip: TripSummary, wide: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val obd = LocalObd.current
    val pts = remember(trip.id) { ObdManager.series(trip.id) }
    var on by remember { mutableStateOf(setOf("speed", "rpm")) }
    var cursor by remember { mutableFloatStateOf(0f) }
    val colors = mapOf("speed" to obd.speed, "rpm" to obd.engine, "cons" to obd.energy, "cool" to obd.temp, "thr" to obd.battery)
    val idx = if (pts.isEmpty()) 0 else (cursor * (pts.size - 1)).toInt().coerceIn(0, pts.size - 1)
    val hasGps = pts.any { it.lat != null }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Retour") }
            Column {
                Text("Revoir le trajet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("${ObdManager.fmt(trip.distanceKm)} km · ${trip.durationS / 60} min · éco ${trip.ecoScore}/100",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pts.isEmpty()) {
            EmptyState(Icons.Rounded.Map, obd.speed, "Pas de données", "Ce trajet a été enregistré avant cette version de l'appli.")
            return@Column
        }
        val map: @Composable (Modifier) -> Unit = { m ->
            if (hasGps) TripMap(pts, idx, m)
            else Box(m.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                Text("Pas de position GPS pour ce trajet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val curves: @Composable ColumnScope.() -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CURVES.forEach { c ->
                    FilterChip(selected = c.key in on, onClick = { on = if (c.key in on) on - c.key else on + c.key },
                        label = { Text(c.label) }, leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.getValue(c.key))) })
                }
            }
            MultiChart(pts, CURVES.filter { it.key in on }, colors, cursor, Modifier.fillMaxWidth().height(180.dp))
            Slider(value = cursor, onValueChange = { cursor = it })
            val p = pts[idx]
            Text("À ${p.t / 60000} min ${(p.t / 1000) % 60} s", fontWeight = FontWeight.Bold)
            CURVES.filter { it.key in on }.forEach { c -> KeyValue(c.label, c.get(p)?.let { "${ObdManager.fmt(it)} ${c.unit}" } ?: "—") }
            if (p.event != 0) Text(if (p.event > 0) "Accélération brusque ici" else "Freinage brusque ici", color = obd.bad, fontWeight = FontWeight.SemiBold)
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                map(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = curves)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                map(Modifier.fillMaxWidth().height(260.dp))
                curves()
            }
        }
    }
}

@Composable
private fun MultiChart(pts: List<TripPoint>, curves: List<Curve>, colors: Map<String, Color>, cursor: Float, modifier: Modifier) {
    val grid = LocalObd.current.track
    val cur = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        for (i in 1..3) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
        val n = pts.size
        if (n < 2) return@Canvas
        curves.forEach { c ->
            val vals = pts.map { c.get(it) }
            val nn = vals.filterNotNull()
            if (nn.isEmpty()) return@forEach
            val lo = nn.min(); val hi = maxOf(nn.max(), lo + 1)
            val path = Path(); var started = false
            vals.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val x = i * size.width / (n - 1)
                val y = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height * 0.92f
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path, colors.getValue(c.key), style = Stroke(2.dp.toPx()))
        }
        val x = cursor * size.width
        drawLine(cur, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
    }
}

/** Carte OpenStreetMap : tracé coloré du vert (économe) au rouge (gourmand), freinages brusques en rouge. */
@Composable
private fun TripMap(pts: List<TripPoint>, idx: Int, modifier: Modifier) {
    val obd = LocalObd.current
    val okC = obd.ok.toArgb(); val warnC = obd.warn.toArgb(); val badC = obd.bad.toArgb()
    val gps = remember(pts) { pts.filter { it.lat != null && it.lon != null } }
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(20.dp)),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                // Segments colorés selon la consommation
                gps.zipWithNext().forEach { (a, b) ->
                    val c = b.cons
                    val col = when { c == null -> okC; c < 6 -> okC; c < 10 -> warnC; else -> badC }
                    overlays += Polyline(this).apply {
                        setPoints(listOf(GeoPoint(a.lat!!, a.lon!!), GeoPoint(b.lat!!, b.lon!!)))
                        outlinePaint.color = col; outlinePaint.strokeWidth = 10f
                    }
                }
                gps.filter { it.event < 0 }.forEach { e ->
                    overlays += Marker(this).apply {
                        position = GeoPoint(e.lat!!, e.lon!!); title = "Freinage brusque"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                }
                overlays += Marker(this).apply { id = "cursor"; title = "Position"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) }
                post {
                    if (gps.size > 1) {
                        val box = BoundingBox.fromGeoPoints(gps.map { GeoPoint(it.lat!!, it.lon!!) })
                        zoomToBoundingBox(box.increaseByScale(1.2f), false)
                    } else if (gps.size == 1) { controller.setZoom(15.0); controller.setCenter(GeoPoint(gps[0].lat!!, gps[0].lon!!)) }
                }
            }
        },
        update = { map ->
            val p = pts.getOrNull(idx)
            val near = if (p?.lat != null) p else pts.take(idx + 1).lastOrNull { it.lat != null }
            (map.overlays.firstOrNull { it is Marker && it.id == "cursor" } as? Marker)?.let { m ->
                if (near?.lat != null) { m.position = GeoPoint(near.lat, near.lon!!); map.invalidate() }
            }
        },
    )
}
