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

    val isOpen: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    suspend fun open(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        close()
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: IOException) {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
        }
        socket = s
    }

    /** Initialise l'adaptateur. [log] reçoit chaque échange pour l'écran « Détails techniques ». */
    suspend fun initialize(log: (String, Int) -> Unit) {
        val id = send("ATZ", 5000)
        log("ATZ → ${ObdParser.clean(id).trim()}", 1)
        delay(300)
        isStn = try { ObdParser.clean(send("STI")).contains("STN") } catch (e: IOException) { false }
        log(if (isStn) "STI → puce STN (OBDLink), mode rapide" else "STI → ELM327 standard", 1)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) send(c)
        send(if (isStn) "ATAT2" else "ATAT1")
        log("ATE0 ATL0 ATS0 ATH0 ATSP0 → OK", 2)
        val r = send("0100", 15000)
        log("0100 → ${ObdParser.clean(r).replace("\r", " ").trim()}", 2)
        val dp = ObdParser.clean(send("ATDPN")).filter { it.isLetterOrDigit() }
        isCan = dp.lastOrNull()?.let { it in "6789ABC" } ?: false
        protocol = ObdParser.clean(send("ATDP")).replace("\r", " ").replace("AUTO,", "").trim()
        log("ATDP → $protocol", 3)
    }

    suspend fun send(cmd: String, timeoutMs: Long = 2500): String = mutex.withLock {
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
                    if (b == '>'.code) return@withContext sb.toString()
                    sb.append(b.toChar())
                } else {
                    delay(4)
                }
            }
            throw IOException("Délai dépassé pour $cmd")
        }
    }

    /** En-tête par défaut après une requête vers un calculateur précis. */
    suspend fun resetHeader() {
        send(if (isCan) "ATSH7DF" else "ATSH686AF1")
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
