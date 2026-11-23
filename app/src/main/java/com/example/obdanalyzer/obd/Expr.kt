package com.example.obdanalyzer.obd

/**
 * Petit évaluateur de formules pour les capteurs personnalisés.
 * Variables A à H = octets de la réponse. Opérateurs + - * / et parenthèses.
 * Exemple : "(A*256+B)/10-40". signed(A) interprète un octet signé (format Torque).
 */
class Expr private constructor(private val src: String) {

    companion object {
        /** Renvoie null si la formule est invalide. */
        fun compile(formula: String): Expr? {
            val e = Expr(formula.replace(" ", "").uppercase())
            return if (runCatching { e.eval(IntArray(8)) }.isSuccess) e else null
        }
    }

    private var pos = 0
    private lateinit var vars: IntArray

    fun eval(bytes: IntArray): Double {
        vars = bytes
        pos = 0
        val v = parseSum()
        if (pos != src.length) throw IllegalArgumentException("Caractère inattendu en position $pos")
        return v
    }

    private fun parseSum(): Double {
        var v = parseProduct()
        while (pos < src.length) {
            when (src[pos]) {
                '+' -> { pos++; v += parseProduct() }
                '-' -> { pos++; v -= parseProduct() }
                else -> return v
            }
        }
        return v
    }

    private fun parseProduct(): Double {
        var v = parseUnary()
        while (pos < src.length) {
            when (src[pos]) {
                '*' -> { pos++; v *= parseUnary() }
                '/' -> { pos++; val d = parseUnary(); v = if (d == 0.0) 0.0 else v / d }
                else -> return v
            }
        }
        return v
    }

    private fun parseUnary(): Double {
        if (pos < src.length && src[pos] == '-') { pos++; return -parseUnary() }
        if (pos < src.length && src[pos] == '+') { pos++; return parseUnary() }
        return parseAtom()
    }

    private fun parseAtom(): Double {
        if (pos >= src.length) throw IllegalArgumentException("Formule incomplète")
        val c = src[pos]
        return when {
            c == '(' -> {
                pos++
                val v = parseSum()
                if (pos >= src.length || src[pos] != ')') throw IllegalArgumentException("Parenthèse manquante")
                pos++
                v
            }
            src.startsWith("SIGNED(", pos) -> {
                pos += 7
                val v = parseSum()
                if (pos >= src.length || src[pos] != ')') throw IllegalArgumentException("Parenthèse manquante")
                pos++
                if (v > 127 && v < 256) v - 256 else if (v >= 32768 && v < 65536) v - 65536 else v
            }
            c in 'A'..'H' && (pos + 1 >= src.length || !src[pos + 1].isLetter()) -> {
                pos++
                val i = c - 'A'
                if (i < vars.size) vars[i].toDouble() else 0.0
            }
            c.isDigit() || c == '.' || c == ',' -> {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == ',')) pos++
                src.substring(start, pos).replace(',', '.').toDouble()
            }
            else -> throw IllegalArgumentException("Caractère « $c » non reconnu")
        }
    }
}
