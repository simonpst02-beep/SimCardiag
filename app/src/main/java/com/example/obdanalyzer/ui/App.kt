package com.example.obdanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.ObdManager

private data class Tab(val label: String, val icon: ImageVector)
private val TABS = listOf(
    Tab("Accueil", Icons.Rounded.Home),
    Tab("Diagnostic", Icons.Rounded.Build),
    Tab("Trajets", Icons.Rounded.Map),
    Tab("Garage", Icons.Rounded.DirectionsCar),
)

@Composable
fun ObdRoot() {
    val l by ObdManager.live.collectAsStateWithLifecycle()
    val s by ObdManager.settings.collectAsStateWithLifecycle()
    val vehicles by ObdManager.vehicles.collectAsStateWithLifecycle()
    val trips by ObdManager.trips.collectAsStateWithLifecycle()
    val v = vehicles.firstOrNull { it.id == s.activeVehicleId } ?: vehicles.first()

    AppTheme(s.theme) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var drive by rememberSaveable { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(l.message) {
            l.message?.let { snack.showSnackbar(it); ObdManager.consumeMessage() }
        }
        val connected = l.conn == Conn.CONNECTED || l.conn == Conn.RECONNECTING
        LaunchedEffect(connected) { if (!connected) drive = false }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (drive) {
                DriveMode(l, v, s) { drive = false }
                return@Surface
            }
            if (settingsOpen) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    SettingsScreen(s, maxWidth >= 700.dp) { settingsOpen = false }
                }
                return@Surface
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 700.dp
                val content: @Composable (Modifier) -> Unit = { m ->
                    Column(m.fillMaxSize()) {
                        TopBar(l.conn, l.recording, l.recStart, v.name, l.device, wide, onDrive = { drive = true }, onSettings = { settingsOpen = true })
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> HomeScreen(l, v, wide) { tab = it }
                                1 -> DiagScreen(l, wide)
                                2 -> TripsScreen(trips, v, l, wide)
                                else -> GarageScreen(vehicles, v, s, l.stats.distanceKm, wide)
                            }
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize().systemBarsPadding()) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                            Spacer(Modifier.height(12.dp))
                            TABS.forEachIndexed { i, t ->
                                NavigationRailItem(selected = tab == i, onClick = { tab = i },
                                    icon = { TabIcon(t, i == 1 && !l.dtcs.isNullOrEmpty()) }, label = { Text(t.label) })
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                        content(Modifier.weight(1f))
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                TABS.forEachIndexed { i, t ->
                                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                        icon = { TabIcon(t, i == 1 && !l.dtcs.isNullOrEmpty()) }, label = { Text(t.label) })
                                }
                            }
                        },
                        snackbarHost = { SnackbarHost(snack) },
                        containerColor = MaterialTheme.colorScheme.background,
                    ) { pad -> content(Modifier.padding(pad)) }
                }
                if (wide) SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(24.dp))
            }
        }
    }
}

@Composable
private fun TabIcon(t: Tab, dot: Boolean) {
    BadgedBox(badge = { if (dot) Badge(containerColor = LocalObd.current.bad) }) { Icon(t.icon, t.label) }
}

@Composable
private fun TopBar(conn: Conn, rec: Boolean, recStart: Long, vehicle: String, device: String?, wide: Boolean, onDrive: () -> Unit, onSettings: () -> Unit) {
    val obd = LocalObd.current
    var ask by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (wide) 8.dp else 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(vehicle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            val (txt, c) = when {
                rec && conn == Conn.CONNECTED -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
                    val t = (now - recStart) / 1000
                    "Enregistrement · ${t / 60}:${"%02d".format(t % 60)}" to obd.bad
                }
                conn == Conn.CONNECTED -> "Connecté · ${device ?: "OBD"}" to obd.ok
                conn == Conn.RECONNECTING -> "Reconnexion en cours…" to obd.warn
                conn == Conn.CONNECTING -> "Connexion…" to obd.warn
                else -> "Non connecté" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Text(txt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (conn == Conn.CONNECTED || conn == Conn.RECONNECTING) {
            FilledTonalButton(onClick = onDrive) { Icon(Icons.Rounded.Fullscreen, null); Spacer(Modifier.width(6.dp)); Text("Conduite") }
            IconButton(onClick = { ask = true }) { Icon(Icons.Rounded.PowerSettingsNew, "Déconnecter") }
        } else if (conn == Conn.CONNECTING) {
            IconButton(onClick = { ObdManager.disconnect() }) { Icon(Icons.Rounded.Close, "Annuler") }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Paramètres") }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Terminer le trajet ?") },
        text = { Text("L'appli se déconnecte du boîtier et enregistre ce trajet dans l'historique.") },
        confirmButton = { TextButton(onClick = { ask = false; ObdManager.disconnect() }) { Text("Terminer") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Continuer") } },
    )
}
