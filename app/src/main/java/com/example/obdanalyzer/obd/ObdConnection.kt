package com.example.obdanalyzer.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Connexion Bluetooth classique (SPP) vers un adaptateur ELM327 / OBDLink (puce STN).
 * Une seule commande à la fois grâce au mutex.
 */
class ObdConnection {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private val mutex = Mutex()

    var isCan = false; private set
    var isStn = false; private set
    var protocol = ""; private set

    /** Journal des derniers échanges avec le boîtier (pour le diagnostic de connexion). */
    private val journal = ArrayDeque<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.FRANCE)

    fun note(line: String) = synchronized(journal) {
        journal.addLast("${timeFmt.format(java.util.Date())}  $line")
        while (journal.size > 400) journal.removeFirst()
    }

    fun journal(): List<String> = synchronized(journal) { journal.toList() }

    val isOpen: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    suspend fun open(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        close()
        physical = false
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: IOException) {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
        }
        socket = s
    }

    /** Initialise l'adaptateur. [log] reçoit chaque échange pour l'écran « Détails techniques ». */
    /** Ancienne méthode (2.0.35) : minutage agressif sur OBDLink, protocole non figé. */
    var legacy = false

    suspend fun initialize(log: (String, Int) -> Unit) {
        val id = send("ATZ", 5000)
        log("ATZ → ${ObdParser.clean(id).trim()}", 1)
        delay(800)
        // Puce STN (OBDLink MX, MX+, LX…) : STI répond « STN1110… », AT@1 répond « OBDLink … »
        isStn = try { ObdParser.clean(send("STI")).contains("STN") } catch (e: IOException) { false }
        if (!isStn) isStn = try { ObdParser.clean(send("AT@1")).contains("OBDLINK") } catch (e: IOException) { false }
        log(if (isStn) "Boîtier OBDLink (puce STN) : mode rapide" else "Boîtier ELM327 standard", 1)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) send(c)
        // Minutage adaptatif normal : le mode agressif (ATAT2) fait rater des réponses des calculateurs lents (Opel / GM)
        send(if (legacy && isStn) "ATAT2" else "ATAT1")
        log("ATE0 ATL0 ATS0 ATH0 ATSP0 → OK", 2)
        // Recherche du protocole : le calculateur peut mettre quelques secondes à se réveiller après le contact
        var found = false
        for (attempt in 1..3) {
            val r = try { send("0100", 15000) } catch (e: IOException) { "" }
            log("0100 (essai $attempt) → ${ObdParser.clean(r).replace("\r", " ").trim().ifEmpty { "pas de réponse" }}", 2)
            if (!ObdParser.isError(r) && ObdParser.parseMode01(r, "00", 4) != null) { found = true; break }
            delay(2000)
        }
        if (!found) throw IOException("La voiture ne répond pas : vérifiez que le contact est mis, puis réessayez.")
        refreshProtocol()
        // On fige le protocole trouvé : sinon, à la moindre absence de réponse, le boîtier relance une recherche
        // de plusieurs secondes et l'appli croit la connexion perdue (déconnexions / reconnexions en boucle)
        if (!legacy) protoNum?.let { n -> runCatching { send("ATSP$n") }; note("Protocole figé : ATSP$n") }
        log("Protocole : $protocol", 3)
    }

    /** Numéro du protocole trouvé (ex. « 6 » = CAN 11 bits 500 kbit/s), pour le figer. */
    var protoNum: Char? = null; private set

    /** Relit le protocole trouvé par l'adaptateur. */
    suspend fun refreshProtocol() {
        val dp = ObdParser.clean(send("ATDPN")).filter { it.isLetterOrDigit() }
        isCan = dp.lastOrNull()?.let { it in "6789ABC" } ?: false
        protoNum = dp.lastOrNull()?.takeIf { it in "123456789ABC" }
        protocol = ObdParser.clean(send("ATDP")).replace("\r", " ").replace("AUTO,", "").trim()
    }

    suspend fun send(cmd: String, timeoutMs: Long = 4000): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw IOException("Non connecté")
            val input = s.inputStream
            val output = s.outputStream
            while (input.available() > 0) input.read()
            output.write("$cmd\r".toByteArray())
            output.flush()
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b < 0) throw IOException("Flux fermé")
                    if (b == '>'.code) {
                        note("$cmd → ${sb.toString().replace('\r', ' ').trim()}")
                        return@withContext sb.toString()
                    }
                    sb.append(b.toChar())
                } else {
                    delay(4)
                }
            }
            note("$cmd → délai dépassé (${timeoutMs} ms)${if (sb.isNotEmpty()) " : " + sb.toString().replace('\r', ' ').trim() else ""}")
            // Resynchronisation : on laisse le boîtier finir (ou s'interrompre) pour que la réponse en retard
            // ne se mélange pas à la commande suivante
            val end = System.currentTimeMillis() + 400
            while (System.currentTimeMillis() < end) {
                if (input.available() > 0) { if (input.read() == '>'.code) break } else delay(5)
            }
            throw ObdTimeout("Délai dépassé pour $cmd")
        }
    }

    /** Le boîtier est là mais une requête n'a pas eu de réponse à temps (calculateur occupé, mesure non gérée). */
    class ObdTimeout(msg: String) : IOException(msg)

    /** true : on parle directement au calculateur moteur (7E0) avec filtre de réception 7E8. */
    var physical = false

    /** En-tête par défaut après une requête vers un calculateur précis. */
    suspend fun resetHeader() {
        if (physical) { send("ATSH7E0"); send("ATCRA7E8"); return }
        send(if (isCan) "ATSH7DF" else "ATSH686AF1")
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
