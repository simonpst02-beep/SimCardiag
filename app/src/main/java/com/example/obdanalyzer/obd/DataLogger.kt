package com.example.obdanalyzer.obd

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Détail d'un trajet en CSV (séparateur « ; », virgule décimale : s'ouvre directement dans Excel FR). */
class DataLogger(private val dir: File) {

    private var writer: BufferedWriter? = null
    var file: File? = null; private set

    fun start(columns: List<String>) {
        stop()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val f = File(dir, "trajet_$stamp.csv")
        file = f
        writer = f.bufferedWriter().apply {
            write("horodatage;" + columns.joinToString(";"))
            newLine()
        }
    }

    fun log(values: List<Double?>) {
        val w = writer ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.FRANCE).format(Date())
        w.write(time + ";" + values.joinToString(";") { v -> v?.let { String.format(Locale.FRANCE, "%.2f", it) } ?: "" })
        w.newLine()
    }

    /** Ferme le fichier et renvoie son nom. */
    fun stop(): String? {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        val name = file?.name
        file = null
        return name
    }

    val active: Boolean get() = writer != null
}
