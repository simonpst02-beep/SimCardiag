package com.example.obdanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.obd.Ecu
import com.example.obdanalyzer.obd.EcuResult
import com.example.obdanalyzer.obd.ObdParser
import com.example.obdanalyzer.obd.Net
import com.example.obdanalyzer.core.ScanOptions

/** Diagnostic calculateur par calculateur. */
@Composable
fun EcuScreen(l: Live, wide: Boolean) {
    val obd = LocalObd.current
    if (l.conn != Conn.CONNECTED) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EmptyState(Icons.Rounded.Memory, MaterialTheme.colorScheme.primary, "Boîtier non connecté",
                "Connectez l'OBDLink depuis l'accueil pour rechercher et tester les calculateurs de la voiture.")
        }
        return
    }
    val s by ObdManager.ecu.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf<Ecu?>(null) }
    val brand = ObdManager.activeVehicle().vin?.let { com.example.obdanalyzer.obd.Vin.brand(it) } ?: ObdManager.activeVehicle().name
    var opt by remember(brand) {
        mutableStateOf(ScanOptions(hs11 = true, hs29 = brand.contains("Volvo", true), ms = brand.contains("Opel", true) && l.isStn))
    }

    LazyVerticalGrid(
        GridCells.Fixed(if (wide) 2 else 1),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionCard(Icons.Rounded.Search, MaterialTheme.colorScheme.primary, "Rechercher les calculateurs") {
                Text("La recherche rapide trouve les calculateurs OBD (moteur, boîte…). L'analyse approfondie interroge toutes les adresses " +
                    "du réseau pour trouver les autres (ABS, airbag, habitacle…) : environ 1 minute, contact mis, moteur coupé de préférence.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.running) {
                    LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth(), trackColor = obd.track)
                    Text(s.label, style = MaterialTheme.typography.bodySmall)
                } else {
                    if (s.label.isNotEmpty()) Text(s.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Réseaux de l'analyse approfondie", style = MaterialTheme.typography.labelLarge)
                    CheckRow("CAN principal (adresses 11 bits)", opt.hs11) { opt = opt.copy(hs11 = it) }
                    CheckRow("Adresses longues 29 bits (Volvo et autres)", opt.hs29) { opt = opt.copy(hs29 = it) }
                    CheckRow(if (l.isStn) "Réseau confort, broches 3-11 (Opel)" else "Réseau confort (nécessite un OBDLink)", opt.ms, enabled = l.isStn) { opt = opt.copy(ms = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { ObdManager.scanEcus(false) }, enabled = s.testing == null) { Text("Recherche rapide") }
                        FilledTonalButton(onClick = { ObdManager.scanEcus(true, opt) }, enabled = s.testing == null && (opt.hs11 || opt.hs29 || opt.ms)) { Text("Analyse approfondie") }
                    }
                    if (s.ecus.size > 1) TextButton(onClick = { ObdManager.testAllEcus() }, enabled = s.testing == null) {
                        Icon(Icons.Rounded.PlaylistPlay, null); Spacer(Modifier.width(6.dp)); Text("Tester tous les calculateurs")
                    }
                }
            }
        }
        if (s.ecus.isEmpty() && !s.running) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(Icons.Rounded.Memory, obd.engine, "Aucun calculateur listé", "Lancez une recherche pour voir les calculateurs de la voiture.")
        }
        items(s.ecus, key = { it.net.name + it.resp }) { e ->
            EcuCard(e, s.results[e.net.name + e.resp], testing = s.testing == e.net.name + e.resp, busy = s.running || s.testing != null,
                onTest = { ObdManager.testEcu(e) }, onClear = { clearing = e })
        }
        if (s.deepDone) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Certaines marques utilisent des adresses longues (29 bits) ou un réseau secondaire non relié à la prise OBD : " +
                "leurs calculateurs peuvent ne pas apparaître ici. L'analyse 29 bits utilise l'adressage normalisé (18 DA xx F1).", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    clearing?.let { e ->
        AlertDialog(
            onDismissRequest = { clearing = null },
            title = { Text("Effacer les codes de « ${e.name} » ?") },
            text = { Text("Seuls les défauts de ce calculateur seront effacés. Ils reviendront si la panne n'est pas réparée.") },
            confirmButton = { TextButton(onClick = { clearing = null; ObdManager.clearEcu(e) }) { Text("Effacer", color = obd.bad) } },
            dismissButton = { TextButton(onClick = { clearing = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun EcuCard(e: Ecu, r: EcuResult?, testing: Boolean, busy: Boolean, onTest: () -> Unit, onClear: () -> Unit) {
    val obd = LocalObd.current
    val (statusText, statusColor) = when {
        testing -> "Test en cours…" to MaterialTheme.colorScheme.primary
        r == null -> "Pas encore testé" to MaterialTheme.colorScheme.onSurfaceVariant
        !r.reachable -> "Ne répond pas" to obd.bad
        r.note != null && r.dtcs.isEmpty() -> "Répond" to obd.warn
        r.dtcs.isEmpty() && r.pending.isEmpty() -> "Aucun défaut" to obd.ok
        else -> "${r.dtcs.size + r.pending.size} défaut(s)" to obd.warn
    }
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBadge(ecuIcon(e), if (e.obd) obd.engine else obd.custom)
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontWeight = FontWeight.Bold)
                    Text(if (e.req.isEmpty()) "Adresse générale" else "${e.req} → ${e.resp} · ${r?.protocol ?: if (e.obd) "OBD" else "Constructeur"}" +
                        if (e.net != Net.HS) " · ${e.net.label}" else "",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(statusText, statusColor)
            }
            if (testing) LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = obd.track)
            if (r != null && !testing) {
                if (r.reachable) {
                    val extra = buildList {
                        add("Réponse en ${r.ms} ms")
                        r.name?.let { add("Nom : $it") }
                    }.joinToString(" · ")
                    Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                r.dtcs.forEach { d ->
                    val x = ObdParser.explain(d.base)
                    EcuDtcRow(d.code, x.title, x.advice, if (d.pending) "En attente" else "Confirmé", if (d.pending) obd.warn else obd.bad)
                }
                r.pending.forEach { c -> val x = ObdParser.explain(c); EcuDtcRow(c, x.title, x.advice, "En attente", obd.warn) }
                if (r.permanent.isNotEmpty()) Text("Codes permanents (effacés seulement après réparation) : ${r.permanent.joinToString()}",
                    style = MaterialTheme.typography.bodySmall, color = obd.warn)
                r.readiness?.let { rd ->
                    val ok = rd.ready && !rd.mil
                    Text(if (ok) "Tests antipollution : tous prêts" else "Tests antipollution : ${rd.pending.size} en cours",
                        style = MaterialTheme.typography.bodySmall, color = if (ok) obd.ok else obd.warn)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onTest, enabled = !busy) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (r == null) "Tester" else "Retester")
                }
                if (r != null && (r.dtcs.isNotEmpty() || r.pending.isNotEmpty())) TextButton(onClick = onClear, enabled = !busy) {
                    Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(4.dp)); Text("Effacer")
                }
            }
        }
    }
}

@Composable
private fun EcuDtcRow(code: String, title: String, advice: String, tag: String, c: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(code, fontWeight = FontWeight.ExtraBold)
            StatusPill(tag, c)
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusPill(text: String, c: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp))
}

private fun ecuIcon(e: Ecu) = when {
    e.resp == "7E8" || e.resp == "MOT" -> Icons.Rounded.Settings
    e.resp == "7E9" -> Icons.Rounded.SettingsApplications
    e.name.contains("ABS", true) || e.name.contains("ESP", true) -> Icons.Rounded.DiscFull
    e.name.contains("AIRBAG", true) || e.name.contains("SRS", true) -> Icons.Rounded.Security
    else -> Icons.Rounded.Memory
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
