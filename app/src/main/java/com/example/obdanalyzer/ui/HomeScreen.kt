package com.example.obdanalyzer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Presets
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.WidgetCfg

@Composable
fun HomeScreen(l: Live, v: Vehicle, wide: Boolean, onTab: (Int) -> Unit) {
    if (l.conn == Conn.IDLE || l.conn == Conn.CONNECTING) {
        ConnectPanel(l, wide)
        return
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HealthCard(l, v, onTab)
                DeviceCard(l, v)
            }
            Dashboard(l, v, cols = 4, wide = true, onTab = onTab, header = null)
        }
    } else {
        Dashboard(l, v, cols = 2, wide = false, onTab = onTab, header = { HealthCard(l, v, onTab) })
    }
}

// ---------------------------------------------------------------- tableau de bord

@Composable
private fun Dashboard(l: Live, v: Vehicle, cols: Int, wide: Boolean, onTab: (Int) -> Unit, header: (@Composable () -> Unit)?) {
    var edit by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val layout = v.layout
    fun save(list: List<WidgetCfg>) = ObdManager.updateActive { it.copy(layout = list, preset = "Personnalisé") }

    LazyVerticalGrid(
        GridCells.Fixed(cols),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (edit) "Personnaliser" else "Mon tableau de bord", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (edit) Button(onClick = { edit = false }) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Terminé") }
                    else FilledTonalButton(onClick = { edit = true }) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(6.dp)); Text("Personnaliser") }
                }
                AnimatedVisibility(edit) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Partez d'un modèle, puis ajustez chaque widget.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Presets.ALL.keys.forEach { name ->
                                FilterChip(selected = v.preset == name, onClick = {
                                    ObdManager.updateActive { it.copy(layout = Presets.get(name), preset = name) }
                                }, label = { Text(name) })
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(layout, span = { _, w -> GridItemSpan(if (w.wide) minOf(2, maxLineSpan) else 1) }) { i, w ->
            WidgetCard(w, l, v, edit, i, layout.size, onTab,
                onMove = { d -> val j = i + d; if (j in layout.indices) save(layout.toMutableList().apply { add(j, removeAt(i)) }) },
                onResize = { save(layout.toMutableList().apply { set(i, w.copy(wide = !w.wide)) }) },
                onRemove = { save(layout.toMutableList().apply { removeAt(i) }) })
        }
        if (edit || layout.isEmpty()) item(span = { GridItemSpan(minOf(2, maxLineSpan)) }) {
            OutlinedButton(onClick = { adding = true }, Modifier.fillMaxWidth().height(64.dp), shape = CardShape) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter un widget")
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (v.layout.any { it.metric == "cons" } && l.values["fuelrate"] == null) "* consommation estimée à partir du débit d'air (${v.fuel.label.lowercase()})" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (adding) AddWidgetSheet(layout, wide, onAdd = { save(layout + it); adding = false }, onDismiss = { adding = false })
}

@Composable
private fun WidgetCard(
    w: WidgetCfg, l: Live, v: Vehicle, edit: Boolean, i: Int, n: Int, onTab: (Int) -> Unit,
    onMove: (Int) -> Unit, onResize: () -> Unit, onRemove: () -> Unit,
) {
    val bare = w.kind == "rec" && !edit
    val body: @Composable ColumnScope.() -> Unit = {
        if (edit) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallIcon(Icons.Rounded.ChevronLeft, "Déplacer avant", i > 0) { onMove(-1) }
            SmallIcon(Icons.Rounded.ChevronRight, "Déplacer après", i < n - 1) { onMove(1) }
            if (w.kind == "val" || w.kind == "gauge") SmallIcon(Icons.Rounded.SwapHoriz, "Changer la taille", true, onClick = onResize)
            SmallIcon(Icons.Rounded.Close, "Retirer", true, tint = LocalObd.current.bad, onClick = onRemove)
        }
        WidgetContent(w, l, v, onTab)
    }
    if (bare) Column(content = body)
    else Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp).fillMaxWidth().heightIn(min = 90.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = body)
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean,
                      tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp).padding(2.dp)) {
        Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------- état du véhicule

@Composable
fun HealthCard(l: Live, v: Vehicle, onTab: (Int) -> Unit) {
    val h = health(l, v)
    val c = levelColor(h.level)
    val base = MaterialTheme.colorScheme.surfaceContainer
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.22f), base, base)))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("État du véhicule", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (h.level == Level.OK) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = c)
                        Text(h.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = c)
                    }
                }
                Icon(Icons.Rounded.DirectionsCar, null, tint = c, modifier = Modifier.size(64.dp))
            }
            Text(h.text, style = MaterialTheme.typography.bodyMedium)
            nextMaintenance(v, l.stats.distanceKm)?.let {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.EventNote, null, tint = LocalObd.current.warn, modifier = Modifier.size(20.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onTab(3) }) { Text("Voir") }
                }
            }
            if (l.dtcs == null && l.conn == Conn.CONNECTED) {
                Button(onClick = { ObdManager.readDiagnostics() }, enabled = !l.diagLoading) {
                    Icon(Icons.Rounded.Build, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (l.diagLoading) "Lecture…" else "Vérifier les codes défaut")
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(l: Live, v: Vehicle) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WidgetTitle(Icons.Rounded.Bluetooth, LocalObd.current.engine, "Boîtier OBD")
            KeyValue("Modèle", l.device ?: "—")
            KeyValue("Protocole", l.protocol.ifEmpty { "—" })
            KeyValue("Capteurs", "${l.supported.size} détectés")
            KeyValue("Lecture", if (l.multi) "Groupée (rapide)" else if (l.isStn) "Puce STN" else "Standard")
            KeyValue("Véhicule", v.name)
            l.vin?.let { KeyValue("VIN", it) }
            JournalButton()
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- connexion

private val STEPS = listOf("Connexion au boîtier", "Réveil de la puce OBDLink", "Recherche du langage de la voiture", "Détection des capteurs")

@Composable
private fun ConnectPanel(l: Live, wide: Boolean) {
    var devices by remember { mutableStateOf(ObdManager.pairedDevices()) }
    val btOn = ObdManager.bluetoothOn()
    val left: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.SettingsInputComponent, LocalObd.current.engine, 76)
            Icon(Icons.Rounded.Bluetooth, null, tint = LocalObd.current.ok, modifier = Modifier.padding(horizontal = 18.dp).size(36.dp))
            IconBadge(Icons.Rounded.PhoneAndroid, LocalObd.current.ok, 76)
        }
        if (l.conn == Conn.CONNECTING) {
            Text("On fait connaissance…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("Quelques secondes, le temps que le boîtier parle à votre voiture.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Connectons votre voiture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            listOf(
                "Branchez l'OBDLink MX+ sur la prise OBD, souvent sous le volant.",
                "Mettez le contact, inutile de démarrer.",
                "Appuyez sur le bouton Connect du boîtier, puis appairez-le dans les réglages Bluetooth dans les 2 minutes.",
            ).forEachIndexed { i, s ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    val right: @Composable ColumnScope.() -> Unit = {
        if (l.conn == Conn.CONNECTING) {
            STEPS.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        i < l.step -> Icon(Icons.Rounded.CheckCircle, null, tint = LocalObd.current.ok, modifier = Modifier.size(30.dp))
                        i == l.step -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                        else -> Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                    Text(s, color = if (i <= l.step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) { Text(if (open) "Masquer les détails techniques" else "Détails techniques") }
            if (open) Text(l.log.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            l.error?.let { ErrorBox(it); JournalButton() }
            if (!btOn) ErrorBox("Le Bluetooth est désactivé. Activez-le puis touchez Actualiser.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mes appareils Bluetooth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { devices = ObdManager.pairedDevices(); ObdManager.clearError() }) {
                    Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Actualiser")
                }
            }
            if (devices.isEmpty() && btOn) Text("Aucun appareil appairé pour l'instant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            devices.forEach { d ->
                Card(onClick = {
                    if (d.isObd) ObdManager.connect(d.address)
                    else ObdManager.connect(d.address)
                }, shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Rounded.Bluetooth, if (d.isObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Bold)
                            Text(if (d.isObd) "Boîtier OBD" else d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.isObd) AssistChip(onClick = { ObdManager.connect(d.address) }, label = { Text("Recommandé") })
                    }
                }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = left)
            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            left(); right()
        }
    }
}

@Composable
fun ErrorBox(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalObd.current.bad.copy(alpha = 0.14f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.Warning, null, tint = LocalObd.current.bad)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
