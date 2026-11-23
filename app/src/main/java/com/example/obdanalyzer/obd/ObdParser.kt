package com.example.obdanalyzer.obd

data class Monitor(val name: String, val complete: Boolean)

/** État des tests antipollution (mode 01 PID 01), utile avant un contrôle technique. */
data class Readiness(
    val mil: Boolean,
    val dtcCount: Int,
    val diesel: Boolean,
    val monitors: List<Monitor>,
) {
    val ready: Boolean get() = monitors.all { it.complete }
    val pending: List<Monitor> get() = monitors.filterNot { it.complete }
}

data class Dtc(val code: String, val title: String, val severity: Severity, val advice: String)

enum class Severity(val label: String) { LOW("faible"), MEDIUM("modérée"), HIGH("élevée") }

object ObdParser {

    private val ERRORS = listOf(
        "NODATA", "UNABLETOCONNECT", "STOPPED", "ERROR", "?", "BUSINIT", "BUSBUSY", "FBERROR", "CANERROR"
    )
    private val FRAME_COUNT = Regex("^[0-9A-F]{3}$")
    private val FRAME_PREFIX = Regex("^[0-9A-F]:")

    fun clean(raw: String): String =
        raw.replace("SEARCHING...", "").replace(">", "").uppercase()

    fun lines(raw: String): List<String> =
        clean(raw).split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .filter { it.isNotEmpty() }

    fun isError(raw: String): Boolean {
        val l = lines(raw)
        return l.isEmpty() || l.any { line -> ERRORS.any { line.contains(it) } }
    }

    /** Lignes d'une réponse multi-trame CAN, sans la ligne de longueur ni les préfixes « 0: ». */
    private fun payload(raw: String): String =
        lines(raw).filterNot { FRAME_COUNT.matches(it) }
            .joinToString("") { it.replace(FRAME_PREFIX, "") }

    private fun bytesAt(hex: String, start: Int, count: Int): IntArray? {
        if (start < 0 || hex.length < start + count * 2) return null
        return runCatching { IntArray(count) { hex.substring(start + it * 2, start + it * 2 + 2).toInt(16) } }.getOrNull()
    }

    /** Réponse mode 01 à un seul PID : "410C1AF8" -> [0x1A, 0xF8]. */
    fun parseMode01(raw: String, pid: String, count: Int): IntArray? {
        if (isError(raw)) return null
        val header = "41$pid"
        for (line in lines(raw)) {
            val i = line.indexOf(header)
            if (i >= 0) bytesAt(line, i + header.length, count)?.let { return it }
        }
        return null
    }

    /**
     * Réponse à une requête groupée (jusqu'à 6 PIDs, CAN uniquement), ex. "010C0D05".
     * La réponse enchaîne PID + données : "41 0C 1A F8 0D 32 05 7B".
     */
    fun parseMulti(raw: String, pids: List<Pid>): Map<String, IntArray> {
        if (isError(raw)) return emptyMap()
        val hex = payload(raw)
        val byCode = pids.associateBy { it.code }
        val out = mutableMapOf<String, IntArray>()
        var i = hex.indexOf("41")
        if (i < 0) return out
        i += 2
        while (i + 2 <= hex.length) {
            val code = hex.substring(i, i + 2)
            if (code == "41") { i += 2; continue }        // réponse suivante (autre trame)
            val p = byCode[code] ?: break
            val data = bytesAt(hex, i + 2, p.bytes) ?: break
            out[code] = data
            i += 2 + p.bytes * 2
        }
        return out
    }

    /** Masque des PIDs supportés (0100, 0120, 0140…). */
    fun parseSupported(raw: String, base: Int): Set<String> {
        val bytes = parseMode01(raw, "%02X".format(base), 4) ?: return emptySet()
        val out = mutableSetOf<String>()
        for (bit in 0 until 32) {
            if ((bytes[bit / 8] shr (7 - bit % 8)) and 1 == 1) out += "%02X".format(base + bit + 1)
        }
        return out
    }

    fun parseVoltage(raw: String): Double? =
        Regex("(\\d+\\.?\\d*)V").find(clean(raw))?.groupValues?.get(1)?.toDoubleOrNull()

    // ---------------- Codes défaut (mode 03 / 07) ----------------

    fun parseDtcs(raw: String, isCan: Boolean, mode: String = "43"): List<String> {
        if (isError(raw)) return emptyList()
        val codes = mutableListOf<String>()
        if (isCan) {
            val hex = payload(raw)
            val start = hex.indexOf(mode)
            if (start < 0) return emptyList()
            var data = hex.substring(start + 2)
            if (data.length >= 2) data = data.substring(2) // nombre de codes
            codes += decodeChunks(data)
        } else {
            for (line in lines(raw)) {
                val i = line.indexOf(mode)
                if (i >= 0) codes += decodeChunks(line.substring(i + 2))
            }
        }
        return codes.distinct()
    }

    private fun decodeChunks(data: String): List<String> =
        data.chunked(4).filter { it.length == 4 && it != "0000" }.mapNotNull { runCatching { decodeDtc(it) }.getOrNull() }

    fun decodeDtc(h: String): String {
        val a = h.substring(0, 2).toInt(16)
        val letter = "PCBU"[a shr 6]
        val d1 = (a shr 4) and 0x3
        val d2 = (a and 0x0F).toString(16).uppercase()
        return "$letter$d1$d2${h.substring(2, 4)}"
    }

    // ---------------- Préparation au contrôle technique ----------------

    private val CONTINUOUS = listOf("Ratés d'allumage", "Système carburant", "Composants")
    private val SPARK = listOf(
        "Catalyseur", "Catalyseur chauffé", "Système EVAP (vapeurs)", "Air secondaire",
        null, "Sonde lambda", "Chauffage sonde lambda", "Système EGR"
    )
    private val DIESEL = listOf(
        "Catalyseur NMHC", "Traitement NOx / SCR", null, "Pression de suralimentation",
        null, "Capteur gaz d'échappement", "Filtre à particules", "EGR / VVT"
    )

    fun parseReadiness(raw: String): Readiness? {
        val b = parseMode01(raw, "01", 4) ?: return null
        val (a, bb, c, d) = listOf(b[0], b[1], b[2], b[3])
        val diesel = bb and 0x08 != 0
        val monitors = mutableListOf<Monitor>()
        for (k in 0..2) {
            if ((bb shr k) and 1 == 1) monitors += Monitor(CONTINUOUS[k], (bb shr (k + 4)) and 1 == 0)
        }
        val names = if (diesel) DIESEL else SPARK
        for (k in 0..7) {
            val n = names[k] ?: continue
            if ((c shr k) and 1 == 1) monitors += Monitor(n, (d shr k) and 1 == 0)
        }
        return Readiness(mil = a and 0x80 != 0, dtcCount = a and 0x7F, diesel = diesel, monitors = monitors)
    }

    // ---------------- Contexte du défaut (freeze frame, mode 02) ----------------

    /** "42 0C 00 1A F8" pour la commande "020C00". */
    fun parseMode02(raw: String, pid: String, count: Int): IntArray? {
        if (isError(raw)) return null
        val header = "42${pid}00"
        for (line in lines(raw)) {
            val i = line.indexOf(header)
            if (i >= 0) bytesAt(line, i + header.length, count)?.let { return it }
        }
        return null
    }

    /** Code défaut à l'origine du freeze frame (commande "020200"). */
    fun parseFreezeDtc(raw: String): String? {
        val b = parseMode02(raw, "02", 2) ?: return null
        if (b[0] == 0 && b[1] == 0) return null
        return decodeDtc("%02X%02X".format(b[0], b[1]))
    }

    // ---------------- VIN (mode 09 PID 02) ----------------

    private val VIN_CHAR = Regex("[A-HJ-NPR-Z0-9]")

    fun parseVin(raw: String): String? {
        if (isError(raw)) return null
        val ls = lines(raw).filterNot { FRAME_COUNT.matches(it) }.map { it.replace(FRAME_PREFIX, "") }
        val withHeader = ls.count { it.contains("4902") }
        val hex = StringBuilder()
        if (withHeader <= 1) {
            // CAN : un seul en-tête "49 02 01" puis 17 octets répartis sur plusieurs trames
            val all = ls.joinToString("")
            val i = all.indexOf("4902")
            if (i < 0) return null
            hex.append(all.substring(minOf(all.length, i + 6)))
        } else {
            // Anciens protocoles : chaque ligne "49 02 NN" + 4 octets
            for (l in ls) {
                val i = l.indexOf("4902")
                if (i >= 0) hex.append(l.substring(minOf(l.length, i + 6)))
            }
        }
        val text = hex.chunked(2).filter { it.length == 2 }
            .mapNotNull { runCatching { it.toInt(16).toChar() }.getOrNull() }
            .filter { VIN_CHAR.matches(it.toString()) }
            .joinToString("")
        return if (text.length >= 17) text.takeLast(17) else null
    }

    // ---------------- Requête libre (capteurs personnalisés, mode 21/22…) ----------------

    /** Pour la commande "221940", la réponse attendue commence par "621940". */
    fun parseGeneric(raw: String, command: String): IntArray? {
        if (isError(raw)) return null
        val cmd = command.uppercase().replace(" ", "")
        if (cmd.length < 2) return null
        val mode = cmd.substring(0, 2).toIntOrNull(16) ?: return null
        val header = "%02X".format(mode + 0x40) + cmd.substring(2)
        val hex = payload(raw)
        val i = hex.indexOf(header)
        if (i < 0) return null
        val data = hex.substring(i + header.length)
        val n = minOf(8, data.length / 2)
        if (n == 0) return null
        return bytesAt(data, 0, n)
    }

    // ---------------- Explications des codes ----------------

    private val KNOWN = mapOf(
        "P0087" to Dtc("P0087", "Pression de carburant trop basse", Severity.HIGH, "Risque de perte de puissance ou de calage. Faites vérifier le filtre et la pompe à carburant rapidement."),
        "P0101" to Dtc("P0101", "Débitmètre d'air hors plage", Severity.MEDIUM, "Souvent un débitmètre encrassé ou une prise d'air. À faire vérifier ; la consommation peut augmenter."),
        "P0128" to Dtc("P0128", "Moteur trop lent à chauffer", Severity.LOW, "Souvent un thermostat bloqué ouvert. Pas urgent, mais le chauffage et la consommation s'en ressentent."),
        "P0171" to Dtc("P0171", "Mélange trop pauvre (banc 1)", Severity.MEDIUM, "Souvent une prise d'air ou un débitmètre encrassé. À faire vérifier dans les prochaines semaines."),
        "P0172" to Dtc("P0172", "Mélange trop riche (banc 1)", Severity.MEDIUM, "Injecteur qui fuit, sonde ou filtre à air bouché. La consommation augmente : à faire vérifier."),
        "P0299" to Dtc("P0299", "Sous-alimentation turbo", Severity.MEDIUM, "Durite de turbo percée ou vanne bloquée. Évitez les fortes accélérations et faites contrôler."),
        "P0300" to Dtc("P0300", "Ratés d'allumage aléatoires", Severity.HIGH, "Le catalyseur peut s'abîmer. Roulez calmement et faites vérifier bougies et bobines rapidement."),
        "P0420" to Dtc("P0420", "Catalyseur moins efficace", Severity.MEDIUM, "Pas urgent. Faites contrôler le catalyseur et la sonde lambda aval : risque de refus au contrôle technique."),
        "P0401" to Dtc("P0401", "Débit EGR insuffisant", Severity.MEDIUM, "Vanne EGR souvent encrassée. À faire nettoyer ; risque de refus au contrôle technique."),
        "P0562" to Dtc("P0562", "Tension électrique trop basse", Severity.HIGH, "Batterie ou alternateur en cause. Faites tester la charge rapidement pour éviter la panne."),
        "P2002" to Dtc("P2002", "Filtre à particules peu efficace", Severity.MEDIUM, "Un trajet sur voie rapide peut aider la régénération. Sinon, faites contrôler le FAP."),
    )

    fun explain(code: String): Dtc {
        KNOWN[code]?.let { return it }
        if (code.length == 5 && code[0] == 'P' && code[2] == '3' && code[1] == '0') {
            val cyl = code.substring(3).toIntOrNull()
            if (cyl != null && cyl in 1..12) return Dtc(code, "Ratés d'allumage cylindre $cyl", Severity.HIGH,
                "Bougie, bobine ou injecteur du cylindre $cyl. Roulez calmement et faites vérifier rapidement.")
        }
        val system = when (code.firstOrNull()) {
            'P' -> "Moteur / boîte"; 'C' -> "Châssis"; 'B' -> "Carrosserie"; 'U' -> "Communication entre calculateurs"; else -> "Système inconnu"
        }
        val area = if (code.firstOrNull() == 'P' && code.length >= 3) when (code[2]) {
            '0', '1' -> " : dosage air / carburant"
            '2' -> " : injection"
            '3' -> " : allumage"
            '4' -> " : antipollution"
            '5' -> " : vitesse, ralenti"
            '6' -> " : calculateur"
            '7', '8', '9' -> " : boîte de vitesses"
            else -> ""
        } else ""
        val generic = code.length >= 2 && (code[1] == '0' || code[1] == '2')
        return Dtc(code, "$system$area", Severity.MEDIUM,
            if (generic) "Code générique : un garage pourra préciser la cause avec une valise de diagnostic."
            else "Code propre au constructeur : sa signification exacte dépend de votre marque.")
    }
}
