package com.example.obdanalyzer.core

/**
 * Chronométrage automatique : 0-100 km/h, 80-120 km/h et 400 m départ arrêté.
 * Alimenté par la vitesse lue en continu ; les temps sont interpolés entre deux mesures.
 */
class PerfTimer {

    private var lastT = 0L
    private var lastV = 0.0
    private var hasLast = false

    // départ arrêté (0-100 et 400 m)
    private var standStart: Double? = null   // instant de départ en secondes
    private var standMax = 0.0
    private var dist = 0.0
    private var done100 = false
    private var done400 = false

    // reprise 80-120
    private var midStart: Double? = null
    private var midMax = 0.0

    /** Statut lisible pour l'écran (chrono en cours…). */
    var status: String = "Arrêtez-vous pour un départ arrêté, ou roulez sous 80 km/h pour une reprise"
        private set
    var running: Double? = null
        private set

    fun reset() {
        hasLast = false; standStart = null; midStart = null; running = null; dist = 0.0
    }

    /** t en millisecondes, v en km/h. Renvoie les mesures terminées à cet instant. */
    fun feed(t: Long, v: Double): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        if (!hasLast) { lastT = t; lastV = v; hasLast = true; return out }
        val t0 = lastT / 1000.0; val t1 = t / 1000.0
        val dt = t1 - t0
        if (dt <= 0 || dt > 2) { lastT = t; lastV = v; return out }
        fun cross(target: Double): Double = if (v == lastV) t1 else t0 + (target - lastV) / (v - lastV) * dt

        // ---- départ arrêté
        val ss = standStart
        if (ss == null) {
            if (lastV <= 0.5 && v > 0.5) {
                standStart = cross(0.5); standMax = v; dist = 0.0; done100 = false; done400 = false
            }
        } else {
            dist += (lastV + v) / 2 / 3.6 * dt
            standMax = maxOf(standMax, v)
            if (!done100 && lastV < 100 && v >= 100) { out += "0-100" to cross(100.0) - ss; done100 = true }
            if (!done400 && dist >= 400) {
                val over = dist - 400; val vms = maxOf(v / 3.6, 0.1)
                out += "400 m" to (t1 - over / vms) - ss; done400 = true
            }
            val elapsed = t1 - ss
            if (v < standMax - 8 || elapsed > 45 || (done100 && done400) || v <= 0.5) standStart = null
        }

        // ---- reprise 80-120
        val ms = midStart
        if (ms == null) {
            if (lastV < 80 && v >= 80 && lastV > 60) { midStart = cross(80.0); midMax = v }
        } else {
            midMax = maxOf(midMax, v)
            if (lastV < 120 && v >= 120) { out += "80-120" to cross(120.0) - ms; midStart = null }
            else if (v < 78 || v < midMax - 6 || t1 - ms > 30) midStart = null
        }

        running = standStart?.let { t1 - it } ?: midStart?.let { t1 - it }
        status = when {
            standStart != null -> "Départ arrêté en cours… ${"%.1f".format(t1 - standStart!!)} s · ${dist.toInt()} m"
            midStart != null -> "Reprise 80-120 en cours… ${"%.1f".format(t1 - midStart!!)} s"
            v <= 0.5 -> "Prêt : accélérez franchement pour lancer le 0-100 et le 400 m"
            else -> "Arrêtez-vous pour un départ arrêté, ou passez 80 km/h en accélérant pour une reprise"
        }
        lastT = t; lastV = v
        return out
    }
}
