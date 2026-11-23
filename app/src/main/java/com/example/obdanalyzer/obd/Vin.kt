package com.example.obdanalyzer.obd

/** Décodage simple d'un numéro de série (VIN) : constructeur et année si elle est codée. */
object Vin {

    private val WMI = listOf(
        "VF1" to "Renault", "VF3" to "Peugeot", "VR3" to "Peugeot", "VF7" to "Citroën", "VR7" to "Citroën",
        "VR1" to "DS", "UU1" to "Dacia", "VF8" to "Matra", "VNK" to "Toyota", "SB1" to "Toyota", "JT" to "Toyota",
        "WVW" to "Volkswagen", "WVG" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen",
        "WAU" to "Audi", "WUA" to "Audi", "WBA" to "BMW", "WBS" to "BMW", "WMW" to "Mini",
        "WDD" to "Mercedes-Benz", "WDB" to "Mercedes-Benz", "W1K" to "Mercedes-Benz", "WME" to "Smart",
        "W0L" to "Opel", "W0V" to "Opel", "WF0" to "Ford", "ZFA" to "Fiat", "ZAR" to "Alfa Romeo",
        "ZFF" to "Ferrari", "VSS" to "Seat", "VSK" to "Nissan", "SJN" to "Nissan", "JN" to "Nissan",
        "TMB" to "Škoda", "YV1" to "Volvo", "KMH" to "Hyundai", "TMA" to "Hyundai", "KNA" to "Kia",
        "U5Y" to "Kia", "JMZ" to "Mazda", "JS" to "Suzuki", "TSM" to "Suzuki", "JHM" to "Honda",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "WP0" to "Porsche", "LRW" to "Tesla", "5YJ" to "Tesla",
        "VXK" to "Opel", "VR8" to "DS",
    )

    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun brand(vin: String): String? =
        WMI.firstOrNull { vin.startsWith(it.first) }?.second

    /** Année modèle (10e caractère). Les constructeurs européens ne la codent pas toujours : à prendre comme indication. */
    fun year(vin: String, currentYear: Int): Int? {
        if (vin.length != 17) return null
        val idx = YEAR_CODES.indexOf(vin[9])
        if (idx < 0) return null
        var y = 1980 + idx
        while (y + 30 <= currentYear + 1) y += 30
        return y
    }

    fun describe(vin: String, currentYear: Int): String {
        val b = brand(vin) ?: "Constructeur inconnu"
        val y = year(vin, currentYear)
        return if (y != null) "$b · modèle $y (indicatif)" else b
    }
}
