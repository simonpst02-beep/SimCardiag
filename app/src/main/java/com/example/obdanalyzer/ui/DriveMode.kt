package com.example.obdanalyzer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.Settings
import com.example.obdanalyzer.core.Vehicle

/** Écran simplifié pour la conduite : 4 très grandes valeurs, aucune manipulation nécessaire. */
@Composable
fun DriveMode(l: Live, v: Vehicle, s: Settings, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val defs = s.driveMetrics.mapNotNull { ObdManager.metric(it) }.take(4)
    val alert = l.alerts.firstOrNull { it.level != Level.OK }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (l.conn == Conn.RECONNECTING) "Reconnexion…" else v.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onExit, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("Quitter")
                }
            }
            if (alert != null) {
                val c = levelColor(alert.level)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Color.Black, modifier = Modifier.size(32.dp))
                    Text(alert.text, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            val cols = if (landscape) 2 else 1
            val rows = defs.chunked(cols)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { d ->
                        val value = l.values[d.key]
                        val (_, lvl) = hint(d.key, value, l, v)
                        val c = when (lvl) { Level.BAD -> LocalObd.current.bad; Level.WARN -> LocalObd.current.warn; else -> MaterialTheme.colorScheme.onSurface }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value?.let { ObdManager.fmt(it) } ?: "—", fontSize = if (landscape) 72.sp else 64.sp,
                                fontWeight = FontWeight.Black, color = c, maxLines = 1)
                            Text(if (d.key == "cons") l.consUnit else d.unit, style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (row.size < cols) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
