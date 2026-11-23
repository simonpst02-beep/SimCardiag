package com.example.obdanalyzer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.obdanalyzer.core.Level
import com.example.obdanalyzer.core.ThemeMode
import com.example.obdanalyzer.obd.Family

/** Couleurs propres à l'appli, différentes en clair et en sombre. */
data class ObdColors(
    val ok: Color, val warn: Color, val bad: Color,
    val engine: Color, val temp: Color, val energy: Color, val battery: Color, val speed: Color, val custom: Color,
    val track: Color,
)

private val DarkObd = ObdColors(
    ok = Color(0xFF6DD58C), warn = Color(0xFFFFB870), bad = Color(0xFFFF8A80),
    engine = Color(0xFF8AB4FF), temp = Color(0xFFFFB870), energy = Color(0xFF6DD58C),
    battery = Color(0xFFF5D565), speed = Color(0xFFC9A8FF), custom = Color(0xFF7FD8E6),
    track = Color(0xFF2F3542),
)
private val LightObd = ObdColors(
    ok = Color(0xFF1E8E3E), warn = Color(0xFFC26401), bad = Color(0xFFC5221F),
    engine = Color(0xFF1A63D6), temp = Color(0xFFD46A00), energy = Color(0xFF1E8E3E),
    battery = Color(0xFFA67C00), speed = Color(0xFF7B4CD8), custom = Color(0xFF00838F),
    track = Color(0xFFE1E5EC),
)

val LocalObd = staticCompositionLocalOf { DarkObd }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF), onPrimary = Color(0xFF0A2A66),
    background = Color(0xFF101318), surface = Color(0xFF101318),
    surfaceContainer = Color(0xFF1A1E26), surfaceContainerHigh = Color(0xFF242935), surfaceContainerHighest = Color(0xFF2F3542),
    onSurface = Color(0xFFEEF0F5), onSurfaceVariant = Color(0xFFA9AFBD),
)
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A63D6), onPrimary = Color.White,
    background = Color(0xFFF6F7FA), surface = Color(0xFFF6F7FA),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDEFF3), surfaceContainerHighest = Color(0xFFE1E5EC),
    onSurface = Color(0xFF16181D), onSurfaceVariant = Color(0xFF5A5F6A),
)

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalObd provides if (dark) DarkObd else LightObd) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

@Composable
fun familyColor(f: Family): Color {
    val c = LocalObd.current
    return when (f) {
        Family.ENGINE -> c.engine; Family.TEMP -> c.temp; Family.ENERGY -> c.energy
        Family.BATTERY -> c.battery; Family.SPEED -> c.speed; Family.CUSTOM -> c.custom
    }
}

@Composable
fun levelColor(l: Level): Color {
    val c = LocalObd.current
    return when (l) { Level.OK -> c.ok; Level.WARN -> c.warn; Level.BAD -> c.bad }
}

fun metricIcon(key: String): ImageVector = when (key) {
    "speed" -> Icons.Rounded.Speed
    "rpm" -> Icons.Rounded.Speed
    "cool", "iat", "oil" -> Icons.Rounded.Thermostat
    "ext" -> Icons.Rounded.WbSunny
    "fuel", "fuelrate", "left", "range" -> Icons.Rounded.LocalGasStation
    "volt" -> Icons.Rounded.BatteryFull
    "cons" -> Icons.Rounded.Eco
    "load" -> Icons.Rounded.Bolt
    "thr" -> Icons.Rounded.TrendingUp
    "map" -> Icons.Rounded.Compress
    "maf" -> Icons.Rounded.Air
    else -> if (key.startsWith("c:")) Icons.Rounded.Memory else Icons.Rounded.Opacity
}
