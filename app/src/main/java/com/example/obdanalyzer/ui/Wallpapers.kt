package com.example.obdanalyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.ui.graphics.luminance
import com.example.obdanalyzer.core.Wallpaper

/** Fonds d'écran dessinés (aucune image à embarquer), discrets pour garder les valeurs lisibles. */
@Composable
fun WallpaperLayer(w: Wallpaper, modifier: Modifier = Modifier.fillMaxSize(), speed: Double? = null, rpm: Double? = null, rpmLimit: Int = 5500) {
    if (w == Wallpaper.NONE) return
    if (w == Wallpaper.CARBON_RED_LIVE) { LiveCarbon(modifier, speed, rpm, rpmLimit); return }
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = if (dark) Color.White else Color.Black
    Canvas(modifier) {
        when (w) {
            Wallpaper.CARBON_RED, Wallpaper.CARBON_GREEN -> {
                // Fond noir carbone, plus marqué que « Fibre de carbone »
                drawRect(Color(0xFF07080A).copy(alpha = if (dark) 0.85f else 0.0f))
                val c = 12f
                val a = Color.White.copy(alpha = if (dark) 0.065f else 0.05f)
                val b = Color.White.copy(alpha = if (dark) 0.025f else 0.02f)
                var yy = 0f; var row = 0
                while (yy < size.height) {
                    var x = 0f; var col = 0
                    while (x < size.width) {
                        val hz = (row + col) % 2 == 0
                        drawRect(if (hz) a else b, Offset(x, yy), Size(c, c * 0.92f))
                        drawRect(if (hz) b else a, Offset(x + c, yy), Size(c * 0.92f, c))
                        x += 2 * c; col++
                    }
                    yy += c; row++
                }
                val W = size.width; val H = size.height
                if (w == Wallpaper.CARBON_RED) {
                    // Livrée sport : deux lames rouges en biais à droite, un coin rouge en bas, un filet fin
                    val red = Color(0xFFE10600)
                    fun blade(x0: Float, wd: Float, alpha: Float) = drawPath(Path().apply {
                        moveTo(W * x0, 0f); lineTo(W * (x0 + wd), 0f); lineTo(W * (x0 + wd - 0.32f), H); lineTo(W * (x0 - 0.32f), H); close()
                    }, Brush.verticalGradient(listOf(red.copy(alpha = alpha), red.copy(alpha = alpha * 0.25f))))
                    blade(0.86f, 0.07f, 0.55f)
                    blade(0.96f, 0.03f, 0.40f)
                    drawPath(Path().apply { moveTo(0f, H); lineTo(W * 0.34f, H); lineTo(0f, H * 0.80f); close() }, red.copy(alpha = 0.35f))
                    drawLine(red.copy(alpha = 0.5f), Offset(0f, H * 0.78f), Offset(W * 0.36f, H), 2f)
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, red.copy(alpha = 0.08f))))
                } else {
                    // Néon vert : traits anguleux lumineux en biais (halo + trait net)
                    val green = Color(0xFF39FF14)
                    fun slash(x0: Float, y0: Float, x1: Float, y1: Float, wd: Float) {
                        drawLine(green.copy(alpha = 0.10f), Offset(W * x0, H * y0), Offset(W * x1, H * y1), wd * 4f, StrokeCap.Round)
                        drawLine(green.copy(alpha = 0.55f), Offset(W * x0, H * y0), Offset(W * x1, H * y1), wd, StrokeCap.Round)
                    }
                    slash(0.70f, -0.02f, 0.98f, 0.36f, 6f)
                    slash(0.78f, -0.02f, 1.02f, 0.30f, 3f)
                    // éclair en zigzag en bas à gauche
                    val z = Path().apply {
                        moveTo(W * 0.02f, H * 0.70f); lineTo(W * 0.20f, H * 0.80f); lineTo(W * 0.12f, H * 0.84f); lineTo(W * 0.34f, H * 1.0f)
                    }
                    drawPath(z, green.copy(alpha = 0.12f), style = Stroke(16f, cap = StrokeCap.Round))
                    drawPath(z, green.copy(alpha = 0.6f), style = Stroke(3f, cap = StrokeCap.Round))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, green.copy(alpha = 0.07f))))
                }
            }
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
            Wallpaper.TACHO -> {
                // Grand compte-tours : graduations, zone rouge et aiguille
                val c = Offset(size.width * 0.5f, size.height * 0.58f)
                val r = size.minDimension * 0.62f
                drawArc(ink.copy(alpha = 0.10f), 150f, 240f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(r * 0.05f))
                drawArc(Color(0xFFFF5A5F).copy(alpha = 0.22f), 342f, 48f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(r * 0.05f))
                for (i in 0..24) {
                    val a = Math.toRadians(150.0 + i * 10.0)
                    val major = i % 3 == 0
                    val r1 = r * (if (major) 0.82f else 0.88f); val r2 = r * 0.93f
                    drawLine(ink.copy(alpha = if (major) 0.18f else 0.10f),
                        Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                        Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), if (major) 4f else 2f)
                }
                val na = Math.toRadians(305.0)
                drawLine(accent.copy(alpha = 0.30f), c, Offset(c.x + r * 0.78f * cos(na).toFloat(), c.y + r * 0.78f * sin(na).toFloat()), r * 0.025f, StrokeCap.Round)
                drawCircle(accent.copy(alpha = 0.25f), r * 0.05f, c)
            }
            Wallpaper.CHECKERED -> {
                // Bande de drapeau à damier en travers de l'écran
                val sq = size.minDimension / 14f
                rotate(-18f, Offset(size.width / 2, size.height * 0.25f)) {
                    val y0 = size.height * 0.18f
                    var x = -size.width * 0.5f; var col = 0
                    while (x < size.width * 1.5f) {
                        for (row in 0..2) if ((row + col) % 2 == 0) drawRect(ink.copy(alpha = if (dark) 0.11f else 0.10f), Offset(x, y0 + row * sq), Size(sq, sq))
                        x += sq; col++
                    }
                }
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.10f))))
            }
            Wallpaper.CIRCUIT -> {
                // Tracé de circuit stylisé avec ligne de départ
                val w = size.width; val h = size.height
                val track = Path().apply {
                    moveTo(w * 0.22f, h * 0.80f)
                    cubicTo(w * 0.05f, h * 0.70f, w * 0.10f, h * 0.45f, w * 0.30f, h * 0.42f)
                    cubicTo(w * 0.50f, h * 0.40f, w * 0.40f, h * 0.20f, w * 0.60f, h * 0.15f)
                    cubicTo(w * 0.85f, h * 0.10f, w * 0.95f, h * 0.30f, w * 0.80f, h * 0.45f)
                    cubicTo(w * 0.68f, h * 0.56f, w * 0.88f, h * 0.68f, w * 0.80f, h * 0.82f)
                    cubicTo(w * 0.72f, h * 0.95f, w * 0.40f, h * 0.92f, w * 0.22f, h * 0.80f)
                    close()
                }
                drawPath(track, ink.copy(alpha = if (dark) 0.06f else 0.05f), style = Stroke(size.minDimension * 0.06f, cap = StrokeCap.Round))
                drawPath(track, accent.copy(alpha = 0.25f), style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))))
                val sx = w * 0.50f; val sy = h * 0.918f; val q = size.minDimension * 0.012f
                for (i in -2..1) for (j in 0..1) if ((i + j) % 2 == 0) drawRect(ink.copy(alpha = 0.15f), Offset(sx + i * q, sy - q + j * q), Size(q, q))
            }
            Wallpaper.SYNTHWAVE -> {
                // Soleil rétro et grille en perspective
                val hz = size.height * 0.46f
                val cx = size.width / 2
                val sr = size.minDimension * 0.30f
                drawCircle(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), Color(0xFFFF5A9E).copy(alpha = 0.18f)), hz - sr, hz), sr, Offset(cx, hz))
                val bg = if (dark) Color(0xFF101318) else Color(0xFFF6F7FA)
                var k = 0
                var y = hz - sr * 0.45f
                while (y < hz) { drawRect(bg, Offset(cx - sr, y), Size(2 * sr, 3f + k * 1.5f)); y += sr * 0.13f; k++ }
                drawRect(bg, Offset(0f, hz), Size(size.width, size.height - hz))
                val line = accent.copy(alpha = 0.22f)
                drawLine(line, Offset(0f, hz), Offset(size.width, hz), 2f)
                var t = 0.04f
                while (t < 1f) { val yy = hz + (size.height - hz) * t * t; drawLine(line, Offset(0f, yy), Offset(size.width, yy), 1.5f); t += 0.09f }
                for (i in -8..8) drawLine(line, Offset(cx, hz), Offset(cx + i * size.width * 0.18f, size.height), 1.5f)
            }
            Wallpaper.HONEYCOMB -> {
                // Calandre en nid d'abeille
                val r = size.minDimension / 22f
                val hw = r * sqrt(3f)
                val path = Path()
                var row = 0
                var cy = 0f
                while (cy < size.height + r) {
                    var cx = if (row % 2 == 0) 0f else hw / 2
                    while (cx < size.width + hw) {
                        for (i in 0..6) {
                            val a = Math.toRadians(60.0 * i - 30.0)
                            val px = cx + r * cos(a).toFloat(); val py = cy + r * sin(a).toFloat()
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        cx += hw
                    }
                    cy += r * 1.5f; row++
                }
                drawPath(path, ink.copy(alpha = if (dark) 0.06f else 0.05f), style = Stroke(2f))
                drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent, accent.copy(alpha = 0.08f))))
            }
            Wallpaper.NONE, Wallpaper.CARBON_RED_LIVE -> Unit
        }
    }
}


/**
 * Carbone rouge dynamique : fond carbone noir, lames rouges parcourues par un reflet,
 * traits de vitesse qui défilent plus vite quand la voiture accélère, lueur qui monte avec le régime.
 * À l'arrêt ou sans boîtier, l'animation tourne au ralenti.
 */
@Composable
private fun LiveCarbon(modifier: Modifier, speed: Double?, rpm: Double?, rpmLimit: Int) {
    val sp by androidx.compose.runtime.rememberUpdatedState(speed ?: 0.0)
    var phase by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var sweep by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (last != 0L) {
                    val dt = ((t - last) / 1e9f).coerceAtMost(0.1f)
                    phase = (phase + dt * (0.08f + (sp / 130.0).toFloat().coerceIn(0f, 1.2f))) % 1f
                    sweep = (sweep + dt * 0.25f) % 1f
                }
                last = t
            }
        }
    }
    val glow = ((rpm ?: 0.0) / rpmLimit).toFloat().coerceIn(0f, 1f)
    val red = Color(0xFFE10600)
    Canvas(modifier) {
        val W = size.width; val H = size.height
        // carbone
        drawRect(Color(0xFF07080A))
        val c = 12f
        val a = Color.White.copy(alpha = 0.065f); val b = Color.White.copy(alpha = 0.025f)
        var yy = 0f; var row = 0
        while (yy < H) {
            var x = 0f; var col = 0
            while (x < W) {
                val hz = (row + col) % 2 == 0
                drawRect(if (hz) a else b, Offset(x, yy), Size(c, c * 0.92f))
                drawRect(if (hz) b else a, Offset(x + c, yy), Size(c * 0.92f, c))
                x += 2 * c; col++
            }
            yy += c; row++
        }
        // lueur basse qui monte avec le régime
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, red.copy(alpha = 0.06f + glow * 0.22f)), startY = H * 0.45f, endY = H))
        // traits de vitesse en biais
        for (i in 0 until 9) {
            val p = (phase + i / 9f) % 1f
            val x0 = W * (1.15f - p * 1.4f) + (i * 37 % 60)
            val y0 = H * ((i * 0.13f) % 1f)
            val len = W * (0.10f + 0.08f * (i % 3))
            drawLine(red.copy(alpha = 0.10f + 0.25f * (1 - p)), Offset(x0, y0), Offset(x0 + len, y0 - len * 0.35f), 2f + (i % 3), StrokeCap.Round)
        }
        // lames rouges avec reflet qui les parcourt
        fun blade(x0: Float, wd: Float, alpha: Float) {
            val path = Path().apply { moveTo(W * x0, 0f); lineTo(W * (x0 + wd), 0f); lineTo(W * (x0 + wd - 0.32f), H); lineTo(W * (x0 - 0.32f), H); close() }
            drawPath(path, Brush.verticalGradient(listOf(red.copy(alpha = alpha), red.copy(alpha = alpha * 0.25f))))
            val sy = H * (sweep * 1.4f - 0.2f)
            drawPath(path, Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), startY = sy - H * 0.08f, endY = sy + H * 0.08f))
        }
        blade(0.86f, 0.07f, 0.55f + glow * 0.3f)
        blade(0.96f, 0.03f, 0.40f + glow * 0.3f)
        drawPath(Path().apply { moveTo(0f, H); lineTo(W * 0.34f, H); lineTo(0f, H * 0.80f); close() }, red.copy(alpha = 0.30f + glow * 0.3f))
    }
}
