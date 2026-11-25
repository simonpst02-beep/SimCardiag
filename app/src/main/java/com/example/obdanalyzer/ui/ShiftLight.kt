package com.example.obdanalyzer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Témoin de passage de vitesse : une rangée de voyants qui s'allument du vert au rouge
 * à l'approche du régime limite, et clignotent tous quand il est atteint.
 */
@Composable
fun ShiftLight(rpm: Double?, limit: Int, modifier: Modifier = Modifier, height: Dp = 18.dp, leds: Int = 10) {
    // s'allume dès 40 % du régime d'alerte (ex. 1 800 tr/min pour 4 500) pour suivre la conduite normale
    val start = limit * 0.4
    val frac = if (rpm == null) 0.0 else ((rpm - start) / (limit - start)).coerceIn(0.0, 1.0)
    val lit = (frac * leds).let { kotlin.math.ceil(it).toInt() }
    val over = rpm != null && rpm >= limit
    val blink by rememberInfiniteTransition(label = "shift").animateFloat(
        1f, 0.15f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "blink")
    Row(modifier.height(height).alpha(if (over) blink else 1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(leds) { i ->
            val p = i.toFloat() / (leds - 1)
            val on = over || i < lit
            val c = when {
                over -> Color(0xFF3D7BFF)
                p < 0.5f -> Color(0xFF2ECC71)
                p < 0.8f -> Color(0xFFFFC107)
                else -> Color(0xFFFF3B30)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (on) c else c.copy(alpha = 0.15f)))
        }
    }
}
