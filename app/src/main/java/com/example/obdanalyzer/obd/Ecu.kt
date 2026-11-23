package com.example.obdanalyzer.obd

/** Un calculateur joignable sur le bus CAN (adresses 11 bits). */
data class Ecu(
    val req: String,   // adresse de requête, ex. "7E0"
    val resp: String,  // adresse de réponse, ex. "7E8"
    val name: String,
    val obd: Boolean,  // true = calculateur OBD légal (moteur, boîte…), false = diagnostic constructeur (UDS / KWP)
    val net: Net = Net.HS,
)

/** Réseau sur lequel le calculateur répond. */
enum class Net(val label: String) {
    HS("CAN principal"),
    HS29("CAN principal · 29 bits"),
    MS("Réseau confort (broches 3-11)"),
}

/** Code défaut d'un calculateur. status = octet d'état UDS/KWP (null en OBD). */
data class EcuDtc(val code: String, val status: Int?) {
    val confirmed: Boolean get() = status == null || status and 0x08 != 0 || status and 0x01 != 0
    val pending: Boolean get() = status != null && status and 0x04 != 0 && !confirmed
    /** Code sans le type de défaut, pour l'explication : "C0035-1C" -> "C0035". */
    val base: String get() = code.substringBefore('-')
}

data class EcuResult(
    val ecu: Ecu,
    val reachable: Boolean,
    val ms: Long,
    val protocol: String,
    val name: String?,
    val dtcs: List<EcuDtc>,
    val pending: List<String> = emptyList(),
    val permanent: List<String> = emptyList(),
    val readiness: Readiness? = null,
    val note: String? = null,
)

object EcuParser {

    private val HEADER = Regex("^([0-7][0-9A-F]{2})([0-9A-F]{2,})$")

    /** Adresses CAN 11 bits qui ont répondu (en-têtes activés, ATH1). */
    fun responders(raw: String): List<String> =
        ObdParser.lines(raw).mapNotNull { HEADER.find(it)?.groupValues?.get(1) }.distinct()

    /**
     * Réponse à « Tester Present » (3E) pendant l'analyse : on ne garde que les trames
     * dont les données sont une réponse 7E (positive) ou 7F 3E (négative), pour ignorer le trafic normal du bus.
     */
    private val TP_REPLY = Regex("^([0-7][0-9A-F]{2})0[1-3](7E|7F3E)")
    fun testerPresentReplies(raw: String): List<String> =
        ObdParser.lines(raw).mapNotNull { TP_REPLY.find(it)?.groupValues?.get(1) }.distinct()

    /** Réponse 29 bits (adressage normalisé 18 DA F1 xx) : renvoie l'adresse xx de chaque calculateur. */
    private val TP29 = Regex("^18DAF1([0-9A-F]{2})(?:0[1-3])?(7E|7F3E)")
    fun testerPresentReplies29(raw: String): List<String> =
        ObdParser.lines(raw).mapNotNull { TP29.find(it)?.groupValues?.get(1) }.distinct()

    /** Adresses interrogées par l'analyse approfondie : plage GM/Opel 0x240–0x25F puis 0x600–0x7F7. */
    val SCAN_IDS: List<Int> = ((0x240..0x25F) + (0x600..0x7F7)).filter { it != 0x7DF && it !in 0x7E0..0x7EF }

    fun obdName(resp: String): String = when (resp) {
        "7E8" -> "Moteur (ECM)"
        "7E9" -> "Boîte de vitesses (TCM)"
        "7EA" -> "Hybride / batterie haute tension"
        "7EB" -> "Calculateur OBD n°4"
        else -> "Calculateur OBD $resp"
    }

    /** Données d'une réponse dont la trame commence par [header] (en-têtes désactivés). */
    private fun dataAfter(raw: String, header: String): String? {
        if (ObdParser.isError(raw)) return null
        val hex = ObdParser.lines(raw)
            .filterNot { Regex("^[0-9A-F]{3}$").matches(it) }
            .joinToString("") { it.replace(Regex("^[0-9A-F]:"), "") }
        val i = hex.indexOf(header)
        return if (i < 0) null else hex.substring(i + header.length)
    }

    fun isNegative(raw: String, service: String): Boolean =
        ObdParser.lines(raw).any { it.contains("7F$service") && !it.contains("7F${service}78") }

    /** UDS 19 02 : 59 02 <masque> puis 4 octets par code (3 octets DTC + état). */
    fun udsDtcs(raw: String): List<EcuDtc>? {
        val d = dataAfter(raw, "5902") ?: return null
        if (d.length < 2) return emptyList()
        return d.substring(2).chunked(8).filter { it.length == 8 }.mapNotNull { c ->
            if (c.startsWith("000000")) return@mapNotNull null
            runCatching { EcuDtc(ObdParser.decodeDtc(c.substring(0, 4)) + "-" + c.substring(4, 6), c.substring(6, 8).toInt(16)) }.getOrNull()
        }
    }

    /** KWP2000 18 : 58 <nombre> puis 3 octets par code (2 octets DTC + état). */
    fun kwpDtcs(raw: String): List<EcuDtc>? {
        val d = dataAfter(raw, "58") ?: return null
        if (d.length < 2) return emptyList()
        return d.substring(2).chunked(6).filter { it.length == 6 && !it.startsWith("0000") }.mapNotNull { c ->
            runCatching {
                val st = c.substring(4, 6).toInt(16)
                // Statut KWP : bit 5 = défaut présent, bit 6 = mémorisé ; on le ramène au format UDS (bit 3 = confirmé)
                EcuDtc(ObdParser.decodeDtc(c.substring(0, 4)), if (st and 0x60 != 0) 0x08 else 0x04)
            }.getOrNull()
        }
    }

    /** Texte ASCII après l'en-tête (nom du calculateur, référence…). */
    fun ascii(raw: String, header: String): String? {
        val d = dataAfter(raw, header) ?: return null
        val s = d.chunked(2).filter { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16) }
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("").trim()
        return s.ifEmpty { null }
    }

    fun positive(raw: String, header: String): Boolean = dataAfter(raw, header) != null
}
