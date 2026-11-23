package com.example.obdanalyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import com.example.obdanalyzer.core.Wallpaper

/** Fonds d'écran dessinés (aucune image à embarquer), discrets pour garder les valeurs lisibles. */
@Composable
fun WallpaperLayer(w: Wallpaper, modifier: Modifier = Modifier.fillMaxSize()) {
    if (w == Wallpaper.NONE) return
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = if (dark) Color.White else Color.Black
    Canvas(modifier) {
        when (w) {
            Wallpaper.CARBON -> {
                // Tissage de fibre de carbone : petits rectangles alternés
                val c = 14f
                val a = ink.copy(alpha = if (dark) 0.045f else 0.05f)
                val b = ink.copy(alpha = if (dark) 0.02f else 0.025f)
                var y = 0f; var row = 0
                while (y < size.height) {
                    var x = 0f; var col = 0
                    while (x < size.width) {
                        val horizontal = (row + col) % 2 == 0
                        drawRect(if (horizontal) a else b, Offset(x, y), Size(c, c))
                        drawRect(if (horizontal) b else a, Offset(x + c, y), Size(c, c))
                        x += 2 * c; col++
                    }
                    y += c; row++
                }
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.10f))))
            }
            Wallpaper.STRIPES -> {
                // Deux bandes de course en diagonale, couleur d'accent
                val wStripe = size.minDimension * 0.16f
                val gap = wStripe * 0.35f
                for (k in 0..1) {
                    val off = k * (wStripe + gap)
                    val p = Path().apply {
                        moveTo(size.width * 0.55f + off, 0f)
                        lineTo(size.width * 0.55f + off + wStripe, 0f)
                        lineTo(size.width * 0.05f + off + wStripe, size.height)
                        lineTo(size.width * 0.05f + off, size.height)
                        close()
                    }
                    drawPath(p, accent.copy(alpha = if (dark) 0.18f else 0.20f))
                }
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = 0.04f))))
            }
            Wallpaper.NIGHT_ROAD -> {
                // Route en perspective qui file vers l'horizon
                val hz = size.height * 0.38f
                val cx = size.width / 2
                drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), 0f, hz))
                val road = Path().apply {
                    moveTo(cx - 6f, hz); lineTo(cx + 6f, hz)
                    lineTo(size.width * 0.95f, size.height); lineTo(size.width * 0.05f, size.height); close()
                }
                drawPath(road, ink.copy(alpha = if (dark) 0.05f else 0.04f))
                // bords de route
                drawLine(accent.copy(alpha = 0.25f), Offset(cx - 6f, hz), Offset(size.width * 0.05f, size.height), 3f)
                drawLine(accent.copy(alpha = 0.25f), Offset(cx + 6f, hz), Offset(size.width * 0.95f, size.height), 3f)
                // tirets centraux de plus en plus grands
                var t = 0.05f
                while (t < 1f) {
                    val y0 = hz + (size.height - hz) * t * t
                    val y1 = hz + (size.height - hz) * (t + 0.04f) * (t + 0.04f)
                    drawLine(ink.copy(alpha = 0.10f), Offset(cx, y0), Offset(cx, y1), 2f + 6f * t)
                    t += 0.09f
                }
                // lignes de vitesse
                for (i in 0..5) {
                    val y = hz * (0.2f + i * 0.13f)
                    drawLine(ink.copy(alpha = 0.04f), Offset(size.width * (0.1f + i * 0.07f), y), Offset(size.width * (0.45f + i * 0.08f), y), 1.5f)
                }
                drawCircle(accent.copy(alpha = 0.12f), size.minDimension * 0.5f, Offset(cx, hz), style = Stroke(1.5f))
            }
            Wallpaper.NONE -> Unit
        }
    }
}
