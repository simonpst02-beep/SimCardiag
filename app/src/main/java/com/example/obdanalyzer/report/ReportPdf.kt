package com.example.obdanalyzer.report

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.obdanalyzer.core.EcuScan
import com.example.obdanalyzer.core.Live
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.TripSummary
import com.example.obdanalyzer.core.Vehicle
import com.example.obdanalyzer.core.status
import com.example.obdanalyzer.obd.ObdParser
import com.example.obdanalyzer.obd.Vin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Rapport de diagnostic A4 à remettre au garagiste ou à joindre à une annonce. */
object ReportPdf {

    private const val W = 595; private const val H = 842; private const val M = 48

    fun generate(context: Context, v: Vehicle, l: Live, ecu: EcuScan, trips: List<TripSummary>): File {
        val doc = PdfDocument()
        val w = Writer(doc)
        val df = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)

        w.title("Rapport de diagnostic")
        w.small("Établi le ${df.format(Date())} avec SimCarDiag et un boîtier ${l.device ?: "OBD"}")
        w.gap(10)

        w.h("Véhicule")
        w.kv("Nom", v.name)
        v.vin?.let { w.kv("VIN", it); w.kv("Identification", Vin.describe(it, Calendar.getInstance().get(Calendar.YEAR))) }
        w.kv("Carburant", v.fuel.label)
        w.kv("Kilométrage", "${(v.odometerKm + l.stats.distanceKm).roundToInt()} km (déclaré puis suivi par l'appli)")
        if (l.protocol.isNotEmpty()) w.kv("Protocole", l.protocol)

        w.h("Codes défaut moteur")
        val d = l.dtcs
        when {
            d == null -> w.line("Non lus lors de cette session.")
            d.isEmpty() -> w.line("Aucun code défaut enregistré.", Color.rgb(30, 142, 62))
            else -> d.forEach { w.line("${it.code} · ${it.title} (gravité ${it.severity.label})", bold = true); w.line("   ${it.advice}") }
        }
        if (l.pending.isNotEmpty()) {
            w.line("En attente de confirmation :", bold = true)
            l.pending.forEach { w.line("   ${it.code} · ${it.title}") }
        }
        l.freeze?.let { f ->
            w.h("Contexte du défaut ${f.dtc ?: ""}")
            f.values.forEach { (k, x) -> w.kv(k, x) }
        }
        l.readiness?.let { r ->
            w.h("Préparation au contrôle technique")
            w.line(when {
                r.mil -> "Non prêt : voyant moteur allumé."
                r.ready -> "Prêt : tous les tests antipollution sont terminés."
                else -> "Pas encore prêt : ${r.pending.size} test(s) en cours."
            }, bold = true)
            r.monitors.forEach { w.line("   ${if (it.complete) "✓" else "…"}  ${it.name}") }
        }

        if (ecu.results.isNotEmpty()) {
            w.h("Calculateurs testés")
            ecu.ecus.forEach { e ->
                val r = ecu.results[e.net.name + e.resp] ?: return@forEach
                val st = when {
                    !r.reachable -> "ne répond pas"
                    r.note != null && r.dtcs.isEmpty() -> "répond (lecture constructeur impossible)"
                    r.dtcs.isEmpty() -> "aucun défaut"
                    else -> "${r.dtcs.size} défaut(s)"
                }
                w.line("${e.name} (${e.req} → ${e.resp}, ${r.protocol}) : $st", bold = r.dtcs.isNotEmpty())
                r.dtcs.forEach { x -> w.line("   ${x.code} · ${ObdParser.explain(x.base).title}") }
            }
        }

        w.h("Entretien")
        val now = System.currentTimeMillis()
        v.maintenance.forEach { m ->
            val s = m.status(v.odometerKm + l.stats.distanceKm, now)
            val txt = listOfNotNull(
                s.kmLeft?.let { if (it <= 0) "dépassé de ${(-it).roundToInt()} km" else "dans ${it.roundToInt()} km" },
                s.daysLeft?.let { if (it <= 0) "échéance passée" else "dans $it jours" }).joinToString(" ou ")
            w.kv(m.name, txt)
        }

        val mine = trips.filter { it.vehicleId == v.id }
        if (mine.isNotEmpty()) {
            w.h("Utilisation")
            val km = mine.sumOf { it.distanceKm }; val fuel = mine.sumOf { it.fuelL }
            w.kv("Trajets enregistrés", "${mine.size} · ${km.roundToInt()} km")
            if (km > 1 && fuel > 0) w.kv("Consommation moyenne", "${ObdManager.fmt(fuel / km * 100)} L/100 km (estimation)")
            w.kv("Note d'éco-conduite moyenne", "${mine.map { it.ecoScore }.average().roundToInt()}/100")
        }

        w.gap(16)
        w.small("Ce rapport reprend les informations transmises par les calculateurs via la prise OBD. Il ne remplace pas un contrôle par un professionnel.")
        w.finish()

        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "rapport_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.FRANCE).format(Date())}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** Note de frais : les trajets d'un mois, avec distance, carburant et coût estimé. */
    fun expenses(context: Context, v: Vehicle, monthLabel: String, trips: List<TripSummary>, pricePerL: Double): File {
        val doc = PdfDocument()
        val w = Writer(doc)
        val df = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE)
        w.title("Relevé des trajets · $monthLabel")
        w.small("${v.name}${v.vin?.let { " · VIN $it" } ?: ""} · établi le ${SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date())} avec SimCarDiag")
        w.gap(10)
        val widths = floatArrayOf(110f, 215f, 55f, 55f, 64f)
        w.row(listOf("Date", "Trajet", "km", "Litres", "Coût"), widths, bold = true)
        trips.sortedBy { it.start }.forEach { t ->
            w.row(listOf(df.format(Date(t.start)), t.route ?: "—", "%.1f".format(Locale.FRANCE, t.distanceKm),
                "%.2f".format(Locale.FRANCE, t.fuelL), "%.2f €".format(Locale.FRANCE, t.cost(pricePerL))), widths)
        }
        w.gap(6)
        w.row(listOf("Total", "${trips.size} trajet(s)", "%.1f".format(Locale.FRANCE, trips.sumOf { it.distanceKm }),
            "%.2f".format(Locale.FRANCE, trips.sumOf { it.fuelL }), "%.2f €".format(Locale.FRANCE, trips.sumOf { it.cost(pricePerL) })), widths, bold = true)
        w.gap(14)
        w.small("Distances mesurées par la vitesse du véhicule (prise OBD). Carburant estimé par l'appli et coût calculé à %.3f €/L.".format(Locale.FRANCE, pricePerL) +
            " Pour des indemnités kilométriques, appliquez le barème fiscal en vigueur à la distance totale.")
        w.finish()
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "trajets_${monthLabel.replace(' ', '_')}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private class Writer(val doc: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private var y = 0f
        private val body = Paint().apply { textSize = 10.5f; color = Color.rgb(22, 24, 29); isAntiAlias = true }
        private val bold = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val head = Paint(bold).apply { textSize = 14f; color = Color.rgb(26, 99, 214) }
        private val titleP = Paint(bold).apply { textSize = 22f }
        private val smallP = Paint(body).apply { textSize = 9f; color = Color.rgb(90, 95, 106) }

        init { newPage() }

        private fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
            page!!.canvas.drawText("SimCarDiag · page $pageNo", (W - M).toFloat() - smallP.measureText("SimCarDiag · page $pageNo"), (H - 24).toFloat(), smallP)
        }

        private fun need(h: Float) { if (y + h > H - M) newPage() }

        private fun wrap(text: String, p: Paint, width: Float): List<String> {
            val out = mutableListOf<String>(); var cur = ""
            for (word in text.split(' ')) {
                val t = if (cur.isEmpty()) word else "$cur $word"
                if (p.measureText(t) > width && cur.isNotEmpty()) { out += cur; cur = word } else cur = t
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        private fun text(s: String, p: Paint, x: Float = M.toFloat(), lead: Float = p.textSize * 1.45f) {
            for (ln in wrap(s, p, W - M - x)) { need(lead); y += lead; page!!.canvas.drawText(ln, x, y, p) }
        }

        fun title(s: String) = text(s, titleP)
        fun small(s: String) = text(s, smallP)
        fun gap(px: Int) { y += px }
        fun h(s: String) { need(40f); y += 14f; text(s, head) ; y += 2f }
        fun line(s: String, color: Int? = null, bold: Boolean = false) {
            val p = Paint(if (bold) this.bold else body).apply { if (color != null) this.color = color }
            text(s, p)
        }
        fun kv(k: String, v: String) {
            need(16f); y += 15f
            page!!.canvas.drawText(k, M.toFloat(), y, smallP.apply { textSize = 10f })
            val x = M + 170f
            val lines = wrap(v, body, W - M - x)
            lines.forEachIndexed { i, ln -> if (i > 0) { need(15f); y += 15f }; page!!.canvas.drawText(ln, x, y, body) }
            smallP.textSize = 9f
        }
        fun finish() { page?.let { doc.finishPage(it) }; page = null }

        /** Une ligne de tableau ; le texte trop long est coupé avec « … ». */
        fun row(cells: List<String>, widths: FloatArray, bold: Boolean = false) {
            need(16f); y += 15f
            val p = if (bold) this.bold else body
            var x = M.toFloat()
            cells.forEachIndexed { i, c ->
                var s = c
                while (s.length > 1 && p.measureText(s) > widths[i] - 6) s = s.dropLast(2) + "…"
                page!!.canvas.drawText(s, x, y, p)
                x += widths[i]
            }
        }
    }
}
