package com.example.obdanalyzer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Note d'éco-conduite sur 100 :
 * accélérations et freinages brusques, temps à haut régime, ralenti prolongé.
 */
class EcoTracker {
    var harshAcc = 0; private set
    var harshBrake = 0; private set
    private var totalS = 0.0
    private var highRpmS = 0.0
    private var idleRunS = 0.0
    private var longIdleS = 0.0
    private var prevSpeed: Double? = null
    private var accCooldown = 0.0
    private var brakeCooldown = 0.0

    fun reset() {
        harshAcc = 0; harshBrake = 0; totalS = 0.0; highRpmS = 0.0
        idleRunS = 0.0; longIdleS = 0.0; prevSpeed = null; accCooldown = 0.0; brakeCooldown = 0.0
    }

    /** speed en km/h, dt en secondes. */
    fun add(speed: Double?, rpm: Double?, dt: Double) {
        if (dt <= 0 || dt > 5) { prevSpeed = speed; return }
        totalS += dt
        accCooldown = max(0.0, accCooldown - dt)
        brakeCooldown = max(0.0, brakeCooldown - dt)
        val prev = prevSpeed
        if (speed != null && prev != null) {
            val a = (speed - prev) / 3.6 / dt // m/s²
            if (a > 3.0 && accCooldown == 0.0) { harshAcc++; accCooldown = 3.0 }
            if (a < -3.5 && brakeCooldown == 0.0) { harshBrake++; brakeCooldown = 3.0 }
        }
        prevSpeed = speed
        if (rpm != null && rpm > 3200) highRpmS += dt
        if (speed != null && speed < 1 && rpm != null && rpm > 400) {
            idleRunS += dt
            if (idleRunS > 60) longIdleS += dt
        } else idleRunS = 0.0
    }

    val score: Int
        get() {
            if (totalS < 30) return 100
            val per10min = 600.0 / max(totalS, 600.0)
            val penalty = (harshAcc * 5 + harshBrake * 5) * per10min +
                highRpmS / totalS * 60 +
                longIdleS / totalS * 30
            return (100 - min(100.0, penalty)).roundToInt()
        }

    fun tips(): List<String> {
        val t = mutableListOf<String>()
        if (harshAcc > 0) t += "Accélérez plus progressivement : $harshAcc accélération(s) brusque(s)."
        if (harshBrake > 0) t += "Anticipez davantage : $harshBrake freinage(s) appuyé(s)."
        if (totalS > 0 && highRpmS / totalS > 0.05) t += "Passez le rapport supérieur plus tôt, vers 2 500 tr/min."
        if (longIdleS > 30) t += "Coupez le moteur lors des arrêts de plus d'une minute."
        if (t.isEmpty()) t += "Conduite souple et économe, bravo !"
        return t
    }
}

fun ecoLabel(score: Int): String = when {
    score >= 85 -> "Excellent"
    score >= 70 -> "Bien"
    score >= 50 -> "Peut mieux faire"
    else -> "Conduite nerveuse"
}
