package com.example.obdanalyzer.obd

import com.example.obdanalyzer.core.CustomPid

/**
 * Import de listes de mesures au format CSV de Torque Pro, très répandu sur les forums :
 * Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
 */
object TorqueImport {

    data class Result(val pids: List<CustomPid>, val skipped: Int)

    fun parse(text: String): Result {
        val out = mutableListOf<CustomPid>()
        var skipped = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("Name", ignoreCase = true)) continue
            val f = splitCsv(line)
            if (f.size < 4) { skipped++; continue }
            val name = f[0].trim().ifEmpty { f[1].trim() }
            val cmd = f[2].trim().removePrefix("0x").removePrefix("0X").uppercase().filter { it.isLetterOrDigit() }
            val formula = f[3].trim()
            val min = f.getOrNull(4)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            val max = f.getOrNull(5)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 100.0
            val unit = f.getOrNull(6)?.trim() ?: ""
            val header = f.getOrNull(7)?.trim()?.uppercase()?.filter { it.isLetterOrDigit() } ?: ""
            if (name.isEmpty() || cmd.length < 4 || Expr.compile(formula) == null) { skipped++; continue }
            out += CustomPid(name = name, unit = unit, header = header, command = cmd, formula = formula, min = min, max = if (max > min) max else min + 100)
        }
        return Result(out, skipped)
    }

    private fun splitCsv(line: String): List<String> {
        val sep = if (line.count { it == ';' } > line.count { it == ',' }) ';' else ','
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var q = false
        for (c in line) {
            when {
                c == '"' -> q = !q
                c == sep && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }
}
