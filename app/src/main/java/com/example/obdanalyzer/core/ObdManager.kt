package com.example.obdanalyzer.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.obdanalyzer.data.Store
import com.example.obdanalyzer.obd.DataLogger
import com.example.obdanalyzer.obd.Dtc
import com.example.obdanalyzer.obd.Ecu
import com.example.obdanalyzer.obd.EcuDtc
import com.example.obdanalyzer.obd.EcuParser
import com.example.obdanalyzer.obd.EcuResult
import com.example.obdanalyzer.obd.Net
import com.example.obdanalyzer.widget.CarWidget
import com.example.obdanalyzer.obd.Expr
import com.example.obdanalyzer.obd.MetricDef
import com.example.obdanalyzer.obd.ObdConnection
import com.example.obdanalyzer.obd.ObdParser
import com.example.obdanalyzer.obd.Pid
import com.example.obdanalyzer.obd.Pids
import com.example.obdanalyzer.obd.Readiness
import com.example.obdanalyzer.obd.Vin
import com.example.obdanalyzer.obd.Family
import com.example.obdanalyzer.service.ObdService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Calendar
import kotlin.math.max

enum class Conn { IDLE, CONNECTING, CONNECTED, RECONNECTING }
enum class Level { OK, WARN, BAD }

data class DeviceInfo(val name: String, val address: String, val isObd: Boolean)
data class Alert(val id: String, val level: Level, val text: String)
data class FreezeFrame(val dtc: String?, val values: List<Pair<String, String>>)

data class LiveStats(
    val durationS: Long = 0, val distanceKm: Double = 0.0, val fuelL: Double = 0.0,
    val maxSpeed: Double = 0.0, val avgSpeed: Double = 0.0, val eco: Int = 100,
    val harshAcc: Int = 0, val harshBrake: Int = 0, val tips: List<String> = emptyList(),
) {
    val avgConsumption: Double? get() = if (distanceKm > 0.3 && fuelL > 0) fuelL / distanceKm * 100 else null
}

data class Live(
    val conn: Conn = Conn.IDLE,
    val step: Int = 0,
    val device: String? = null,
    val error: String? = null,
    val log: List<String> = emptyList(),
    val supported: List<Pid> = emptyList(),
    val protocol: String = "",
    val isStn: Boolean = false,
    val multi: Boolean = false,
    val values: Map<String, Double> = emptyMap(),
    val consUnit: String = "L/100km",
    val history: Map<String, List<Float>> = emptyMap(),
    val stats: LiveStats = LiveStats(),
    val alerts: List<Alert> = emptyList(),
    val recording: Boolean = false,
    val recStart: Long = 0,
    val diagLoading: Boolean = false,
    val dtcs: List<Dtc>? = null,
    val pending: List<Dtc> = emptyList(),
    val readiness: Readiness? = null,
    val freeze: FreezeFrame? = null,
    val vin: String? = null,
    val message: String? = null,
)

const val HISTORY = 120

/** Test de batterie et de démarreur. */
data class BatteryTest(
    val phase: String = "idle",          // idle, rest, crank, charge, done, error
    val rest: Double? = null,
    val crankMin: Double? = null,
    val charge: Double? = null,
    val samples: List<Float> = emptyList(),
    val message: String = "",
)

/** Options de l'analyse approfondie. */
data class ScanOptions(val hs11: Boolean = true, val hs29: Boolean = false, val ms: Boolean = false, val msBaud: Int = 95238)

/** État de la recherche et des tests par calculateur. */
data class EcuScan(
    val running: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
    val ecus: List<Ecu> = emptyList(),
    val results: Map<String, EcuResult> = emptyMap(),
    val testing: String? = null,
    val deepDone: Boolean = false,
)

/**
 * Cœur de l'appli : connexion, lecture en boucle, reconnexion, trajets, véhicules.
 * Vit tant que le processus vit ; le service au premier plan le garde en vie écran éteint.
 */
@SuppressLint("StaticFieldLeak")
object ObdManager {

    private lateinit var ctx: Context
    private lateinit var store: Store
    private lateinit var alerts: AlertPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val conn = ObdConnection()
    private var logger: DataLogger? = null
    private var pollJob: Job? = null
    private var sessionJob: Job? = null

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()
    private val _trips = MutableStateFlow<List<TripSummary>>(emptyList())
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()
    private val _ecu = MutableStateFlow(EcuScan())
    val ecu: StateFlow<EcuScan> = _ecu.asStateFlow()
    @Volatile private var pollPaused = false
    private val _fuel = MutableStateFlow<List<FuelEntry>>(emptyList())
    val fuelLog: StateFlow<List<FuelEntry>> = _fuel.asStateFlow()
    private val _perf = MutableStateFlow<List<PerfRun>>(emptyList())
    val perfRuns: StateFlow<List<PerfRun>> = _perf.asStateFlow()
    private val _battery = MutableStateFlow(BatteryTest())
    val battery: StateFlow<BatteryTest> = _battery.asStateFlow()
    private val _perfStatus = MutableStateFlow("")
    val perfStatus: StateFlow<String> = _perfStatus.asStateFlow()

    private lateinit var gps: GpsTracker
    private val perfTimer = PerfTimer()
    private val series = ArrayList<TripPoint>()
    private var lastSeriesT = 0L
    private var lastWidgetT = 0L
    private var lastDtcCheck = 0L
    private var knownDtcs: Set<String>? = null

    // Session (= trajet en cours, survit aux reconnexions)
    private var sessionStart = 0L
    private var eco = EcoTracker()
    private var dist = 0.0; private var fuel = 0.0; private var maxSpeed = 0.0
    private var speedSum = 0.0; private var speedN = 0
    private var multiFails = 0
    private var activeAlertIds = emptySet<String>()
    private val hist = HashMap<String, ArrayDeque<Float>>()
    private var recFile: String? = null
    private var pendingEvent = 0

    private val adapter: BluetoothAdapter?
        get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        store = Store(ctx)
        alerts = AlertPlayer(ctx)
        _settings.value = store.loadSettings()
        var vs = store.loadVehicles()
        if (vs.isEmpty()) {
            val v = Vehicle(name = "Ma voiture", maintenance = Presets.defaultMaintenance(0.0, System.currentTimeMillis()))
            vs = listOf(v)
            store.saveVehicles(vs)
        }
        _vehicles.value = vs
        if (_settings.value.activeVehicleId == null || vs.none { it.id == _settings.value.activeVehicleId }) {
            updateSettings { it.copy(activeVehicleId = vs.first().id) }
        }
        _trips.value = store.loadTrips()
        _fuel.value = store.loadFuel()
        _perf.value = store.loadPerf()
        gps = GpsTracker(ctx)
        pruneTrips()
    }

    val logsDir get() = store.logsDir

    // ---------------------------------------------------------------- véhicules & réglages

    fun activeVehicle(): Vehicle =
        _vehicles.value.firstOrNull { it.id == _settings.value.activeVehicleId } ?: _vehicles.value.first()

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.value = f(_settings.value)
        store.saveSettings(_settings.value)
    }

    fun updateVehicle(id: String, f: (Vehicle) -> Vehicle) {
        _vehicles.value = _vehicles.value.map { if (it.id == id) f(it) else it }
        store.saveVehicles(_vehicles.value)
    }

    fun updateActive(f: (Vehicle) -> Vehicle) = updateVehicle(activeVehicle().id, f)

    fun addVehicle(name: String, fuel: Fuel): Vehicle {
        val v = Vehicle(name = name, fuel = fuel, maintenance = Presets.defaultMaintenance(0.0, System.currentTimeMillis()))
        _vehicles.value = _vehicles.value + v
        store.saveVehicles(_vehicles.value)
        return v
    }

    fun deleteVehicle(id: String) {
        if (_vehicles.value.size <= 1) return
        _vehicles.value = _vehicles.value.filterNot { it.id == id }
        store.saveVehicles(_vehicles.value)
        if (_settings.value.activeVehicleId == id) updateSettings { it.copy(activeVehicleId = _vehicles.value.first().id) }
    }

    fun selectVehicle(id: String) = updateSettings { it.copy(activeVehicleId = id) }

    fun deleteTrip(t: TripSummary) {
        t.file?.let { java.io.File(store.logsDir, it).delete() }
        store.deleteSeries(t.id)
        _trips.value = _trips.value.filterNot { it.id == t.id }
        store.saveTrips(_trips.value)
    }

    /** Toutes les mesures affichables pour le véhicule actif. */
    fun metricDefs(): List<MetricDef> =
        Pids.ALL.map { it.def } + listOf(Pids.VOLT, Pids.CONS, Pids.LEFT, Pids.RANGE) +
            activeVehicle().customPids.map { MetricDef(it.key, it.name, it.unit, it.min, it.max, Family.CUSTOM) }

    fun metric(key: String?): MetricDef? = metricDefs().firstOrNull { it.key == key }

    // ---------------------------------------------------------------- Bluetooth

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<DeviceInfo> {
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return try {
            a.bondedDevices.map {
                val n = it.name ?: "Inconnu"
                DeviceInfo(n, it.address, listOf("OBD", "ELM", "VGATE", "VLINK", "V-LINK").any { k -> n.contains(k, true) })
            }.sortedByDescending { it.isObd }
        } catch (e: SecurityException) { emptyList() }
    }

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    /** À l'ouverture de l'appli : reconnexion au dernier boîtier utilisé. */
    fun autoConnect() {
        val s = _settings.value
        if (_live.value.conn == Conn.IDLE && s.autoReconnect && s.lastDevice != null && bluetoothOn()) connect(s.lastDevice)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val a = adapter ?: return
        if (_live.value.conn != Conn.IDLE) return
        sessionJob?.cancel()
        sessionJob = scope.launch {
            val device = a.getRemoteDevice(address)
            val name = try { device.name } catch (e: SecurityException) { null } ?: address
            _live.value = Live(conn = Conn.CONNECTING, device = name)
            ObdService.start(ctx)
            try {
                openAndInit(device)
                updateSettings { it.copy(lastDevice = address) }
                startSession()
                _live.update { it.copy(conn = Conn.CONNECTED, step = 4, message = "Connecté ! ${it.supported.size} capteurs disponibles") }
                startPolling()
                if (_settings.value.autoRecord && logger == null) toggleRecording()
            } catch (e: Exception) {
                conn.close()
                ObdService.stop(ctx)
                _live.update {
                    it.copy(conn = Conn.IDLE, error = e.message?.takeIf { m -> m.startsWith("La voiture") }
                        ?: "Impossible de se connecter à $name. Vérifiez que le contact est mis et que le boîtier est bien branché.")
                }
            }
        }
    }

    private fun addLog(line: String, step: Int) =
        _live.update { it.copy(log = it.log + line, step = max(it.step, step)) }

    @SuppressLint("MissingPermission")
    private suspend fun openAndInit(device: android.bluetooth.BluetoothDevice) {
        conn.open(device)
        addLog("Bluetooth connecté", 1)
        conn.initialize { line, step -> addLog(line, step) }
        val supported = detectSupported()
        addLog("${supported.size} capteurs détectés", 4)
        val vin = runCatching { ObdParser.parseVin(conn.send("0902", 5000)) }.getOrNull()
        _live.update {
            it.copy(supported = supported, protocol = conn.protocol, isStn = conn.isStn, multi = conn.isCan, vin = vin ?: it.vin)
        }
        multiFails = 0
        if (vin != null) matchVehicle(vin)
    }

    private suspend fun detectSupported(): List<Pid> {
        val codes = mutableSetOf<String>()
        var base = 0x00
        while (base <= 0x40) {
            val set = ObdParser.parseSupported(conn.send("01%02X".format(base), 5000), base)
            codes += set
            if ("%02X".format(base + 0x20) !in set) break
            base += 0x20
        }
        val list = Pids.ALL.filter { it.code in codes }
        return list.ifEmpty { Pids.ALL.filter { it.code in setOf("0C", "0D", "05") } }
    }

    /** Associe la voiture branchée à un véhicule enregistré, ou en crée un. */
    private fun matchVehicle(vin: String) {
        val known = _vehicles.value.firstOrNull { it.vin == vin }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        when {
            known != null -> if (known.id != activeVehicle().id) {
                selectVehicle(known.id)
                _live.update { it.copy(message = "Véhicule reconnu : ${known.name}") }
            }
            activeVehicle().vin == null -> {
                val brand = Vin.brand(vin)
                updateActive { v -> v.copy(vin = vin, name = if (v.name == "Ma voiture" && brand != null) brand else v.name) }
            }
            else -> {
                val v = addVehicle(Vin.brand(vin) ?: "Nouvelle voiture", Fuel.ESSENCE).copy(vin = vin)
                updateVehicle(v.id) { v }
                selectVehicle(v.id)
                _live.update { it.copy(message = "Nouveau véhicule détecté : ${Vin.describe(vin, year)}") }
            }
        }
    }

    private fun startSession() {
        sessionStart = System.currentTimeMillis()
        eco = EcoTracker()
        dist = 0.0; fuel = 0.0; maxSpeed = 0.0; speedSum = 0.0; speedN = 0
        hist.clear()
        series.clear(); lastSeriesT = 0L
        perfTimer.reset()
        knownDtcs = null; lastDtcCheck = System.currentTimeMillis()
        if (_settings.value.gps) gps.start()
        activeAlertIds = emptySet()
    }

    // ---------------------------------------------------------------- lecture en boucle

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var fails = 0
            var loop = 0
            var last = System.currentTimeMillis()
            while (isActive) {
                if (pollPaused) { delay(100); last = System.currentTimeMillis(); continue }
                val values = HashMap<String, Double>()
                val ok = try {
                    readStandard(values)
                    if (loop % 10 == 0) ObdParser.parseVoltage(conn.send("ATRV"))?.let { values["volt"] = it }
                    else _live.value.values["volt"]?.let { values["volt"] = it }
                    if (loop % 4 == 0) readCustom(values)
                    else activeVehicle().customPids.forEach { c -> _live.value.values[c.key]?.let { values[c.key] = it } }
                    true
                } catch (e: IOException) { false }

                if (!ok) {
                    fails++
                    if (fails >= 5) { conn.note("5 échecs de suite : connexion perdue"); onConnectionLost(); return@launch }
                    continue
                }
                fails = 0
                loop++
                if (_settings.value.monitorDtc && System.currentTimeMillis() - lastDtcCheck > 60_000) {
                    lastDtcCheck = System.currentTimeMillis()
                    runCatching { monitorDtcs() }
                }
                val now = System.currentTimeMillis()
                val dt = (now - last) / 1000.0
                last = now
                compute(values, dt)
                delay(when (_settings.value.pollRate) {
                    PollRate.ECO -> 250L
                    PollRate.FAST -> 0L
                    PollRate.NORMAL -> if (_live.value.multi) 30L else 60L
                })
            }
        }
    }

    private suspend fun readStandard(values: HashMap<String, Double>) {
        val pids = _live.value.supported
        if (_live.value.multi) {
            for (chunk in pids.chunked(6)) {
                val raw = conn.send("01" + chunk.joinToString("") { it.code })
                val parsed = ObdParser.parseMulti(raw, chunk)
                for (p in chunk) parsed[p.code]?.let { values[p.def.key] = p.decode(it) }
                if (parsed.size * 2 < chunk.size) {
                    multiFails++
                    if (multiFails >= 3) _live.update { it.copy(multi = false) }
                }
            }
        } else {
            for (p in pids) {
                ObdParser.parseMode01(conn.send("01${p.code}"), p.code, p.bytes)?.let { values[p.def.key] = p.decode(it) }
            }
        }
    }

    private suspend fun readCustom(values: HashMap<String, Double>) {
        for (c in activeVehicle().customPids) {
            val expr = Expr.compile(c.formula) ?: continue
            if (c.header.isNotBlank()) conn.send("ATSH${c.header.trim()}")
            val bytes = ObdParser.parseGeneric(conn.send(c.command.replace(" ", ""), 3000), c.command)
            if (c.header.isNotBlank()) conn.resetHeader()
            if (bytes != null) values[c.key] = runCatching { expr.eval(bytes) }.getOrDefault(Double.NaN)
        }
    }

    private fun compute(values: HashMap<String, Double>, dt: Double) {
        val v = activeVehicle()
        val speed = values["speed"]
        val rpm = values["rpm"]
        val lph = values["fuelrate"] ?: values["maf"]?.let { it * 3600.0 / (v.fuel.afr * v.fuel.densityGPerL) * v.consFactor }
        var unit = "L/100km"
        if (lph != null) {
            if (speed != null && speed > 10) values["cons"] = lph / speed * 100 else { values["cons"] = lph; unit = "L/h" }
        }
        // Carburant restant : jauge de la voiture si elle la transmet, sinon calcul depuis le dernier plein complet
        val left = values["fuel"]?.let { it / 100 * v.tankL } ?: litersSinceFull(v)?.let { (v.tankL - it - fuel).coerceAtLeast(0.0) }
        if (left != null) {
            values["left"] = left
            val avg = (if (dist > 5) fuel / dist * 100 else null) ?: v.lastAvgCons ?: 6.5
            values["range"] = left / avg * 100
        }
        if (dt in 0.0..5.0) {
            if (speed != null) {
                dist += speed * dt / 3600
                maxSpeed = max(maxSpeed, speed)
                speedSum += speed; speedN++
            }
            if (lph != null) fuel += lph * dt / 3600
            val a0 = eco.harshAcc; val b0 = eco.harshBrake
            eco.add(speed, rpm, dt)
            pendingEvent = if (eco.harshAcc > a0) 1 else if (eco.harshBrake > b0) -1 else pendingEvent
        }
        for ((k, x) in values) {
            val q = hist.getOrPut(k) { ArrayDeque() }
            q.addLast(x.toFloat())
            if (q.size > HISTORY) q.removeFirst()
        }
        val stats = LiveStats(
            durationS = (System.currentTimeMillis() - sessionStart) / 1000,
            distanceKm = dist, fuelL = fuel, maxSpeed = maxSpeed,
            avgSpeed = if (speedN > 0) speedSum / speedN else 0.0,
            eco = eco.score, harshAcc = eco.harshAcc, harshBrake = eco.harshBrake, tips = eco.tips(),
        )
        val al = buildAlerts(values)
        val newOnes = al.filter { it.id !in activeAlertIds && it.level != Level.OK }
        if (newOnes.isNotEmpty()) {
            val s = _settings.value
            alerts.play(newOnes.any { it.level == Level.BAD }, s.sound, s.vibration)
        }
        activeAlertIds = al.map { it.id }.toSet()

        val now = System.currentTimeMillis()
        if (speed != null) {
            for ((kind, sec) in perfTimer.feed(now, speed)) {
                val run = PerfRun(vehicleId = v.id, date = now, kind = kind, seconds = sec, endSpeed = speed)
                _perf.value = listOf(run) + _perf.value
                store.savePerf(_perf.value)
                _live.update { it.copy(message = "Chrono $kind : ${"%.2f".format(sec)} s") }
            }
            _perfStatus.value = perfTimer.status
        }
        if (now - lastSeriesT >= 1000 && series.size < 4 * 3600) {
            lastSeriesT = now
            val loc = gps.fresh()
            series += TripPoint(now - sessionStart, speed, rpm, values["cool"], values["cons"]?.takeIf { unit == "L/100km" }, values["thr"],
                loc?.latitude, loc?.longitude, pendingEvent)
            pendingEvent = 0
        }
        if (now - lastWidgetT > 30_000) { lastWidgetT = now; CarWidget.refresh(ctx) }
        logger?.log(_live.value.supported.map { values[it.def.key] } + listOf(values["volt"], values["cons"]) +
            v.customPids.map { values[it.key] })

        _live.update {
            it.copy(
                values = values, consUnit = unit, stats = stats, alerts = al,
                history = hist.mapValues { e -> e.value.toList() },
            )
        }
    }

    private fun buildAlerts(values: Map<String, Double>): List<Alert> {
        val s = _settings.value
        val a = mutableListOf<Alert>()
        val rpm = values["rpm"] ?: 0.0
        values["cool"]?.let { if (it >= s.tempLimit) a += Alert("cool", Level.BAD, "Le moteur chauffe trop (${it.toInt()} °C). Arrêtez-vous dès que possible et laissez refroidir avant de vérifier le liquide.") }
        values["oil"]?.let { if (it >= 135) a += Alert("oil", Level.BAD, "Huile moteur très chaude (${it.toInt()} °C). Levez le pied.") }
        values["volt"]?.let {
            if (rpm > 500 && it < 13.0) a += Alert("alt", Level.BAD, "L'alternateur ne recharge plus la batterie (${"%.1f".format(it)} V). Faites-le contrôler rapidement.")
            if (rpm < 100 && it < s.batteryLowV) a += Alert("bat", Level.WARN, "Batterie faible (${"%.1f".format(it)} V) : le prochain démarrage pourrait être difficile.")
        }
        if (rpm >= s.rpmLimit) a += Alert("rpm", Level.WARN, "Régime élevé : passez la vitesse supérieure.")
        val range = values["range"]
        if ((range != null && range <= s.reserveKm) || (range == null && (values["fuel"] ?: 100.0) <= 12))
            a += Alert("fuel", Level.WARN, "Vous êtes sur la réserve${range?.let { " (≈ ${it.toInt()} km)" } ?: ""} : pensez à faire le plein.")
        if (s.speedAlertKmh > 0) values["speed"]?.let { if (it >= s.speedAlertKmh) a += Alert("speed", Level.WARN, "Vitesse : ${it.toInt()} km/h (alerte à ${s.speedAlertKmh}).") }
        if (s.breakMinutes > 0 && sessionStart > 0 && System.currentTimeMillis() - sessionStart > s.breakMinutes * 60_000L)
            a += Alert("break", Level.WARN, "Vous roulez depuis plus de ${s.breakMinutes / 60} h${if (s.breakMinutes % 60 > 0) " ${s.breakMinutes % 60}" else ""} : pensez à faire une pause.")
        _live.value.dtcs?.let { if (it.isNotEmpty()) a += Alert("dtc", Level.WARN, "${it.size} code(s) défaut enregistré(s). Voir le diagnostic.") }
        val now = System.currentTimeMillis()
        val v = activeVehicle()
        val odo = v.odometerKm + dist
        v.maintenance.map { it.status(odo, now) }.firstOrNull { it.overdue }?.let {
            a += Alert("maint", Level.WARN, "Entretien à faire : ${it.item.name}.")
        }
        return a
    }

    // ---------------------------------------------------------------- reconnexion

    private fun onConnectionLost() {
        conn.close()
        if (!_settings.value.autoReconnect) { endSession("La connexion avec le boîtier a été perdue."); return }
        val address = _settings.value.lastDevice ?: run { endSession("Connexion perdue."); return }
        _live.update { it.copy(conn = Conn.RECONNECTING, message = null) }
        pollJob = scope.launch {
            val until = System.currentTimeMillis() + 5 * 60_000
            var wait = 2000L
            while (isActive && System.currentTimeMillis() < until) {
                delay(wait)
                try {
                    @SuppressLint("MissingPermission")
                    val d = adapter?.getRemoteDevice(address) ?: break
                    openAndInit(d)
                    _live.update { it.copy(conn = Conn.CONNECTED, message = "Reconnecté au boîtier") }
                    startPolling()
                    return@launch
                } catch (e: Exception) {
                    conn.close()
                    wait = minOf(wait * 2, 15_000)
                }
            }
            endSession("Le boîtier ne répond plus. Trajet enregistré dans l'historique.")
        }
    }

    // ---------------------------------------------------------------- fin de trajet

    fun disconnect() {
        pollJob?.cancel()
        sessionJob?.cancel()
        conn.close()
        endSession(null)
    }

    private fun endSession(msg: String?) {
        pollJob?.cancel()
        val file = logger?.stop()
        logger = null
        if (dist > 0.3 && sessionStart > 0) {
            val t = TripSummary(
                vehicleId = activeVehicle().id, start = sessionStart,
                durationS = (System.currentTimeMillis() - sessionStart) / 1000,
                distanceKm = dist, fuelL = fuel, maxSpeed = maxSpeed,
                avgSpeed = if (speedN > 0) speedSum / speedN else 0.0,
                ecoScore = eco.score, harshAcc = eco.harshAcc, harshBrake = eco.harshBrake, file = file ?: recFile,
            )
            _trips.value = listOf(t) + _trips.value
            store.saveTrips(_trips.value)
            if (series.isNotEmpty()) store.saveSeries(t.id, series.toList())
            val d = dist
            updateActive { it.copy(odometerKm = it.odometerKm + d) }
        }
        val lastFuel = _live.value.values["fuel"]
        val avg = _live.value.stats.avgConsumption
        if (lastFuel != null || avg != null) updateActive { it.copy(lastFuelPct = lastFuel ?: it.lastFuelPct, lastAvgCons = avg ?: it.lastAvgCons) }
        series.clear()
        gps.stop()
        recFile = null
        sessionStart = 0
        dist = 0.0
        _live.value = Live(conn = Conn.IDLE, message = msg)
        _ecu.value = EcuScan()
        pollPaused = false
        _battery.value = BatteryTest()
        ObdService.stop(ctx)
        CarWidget.refresh(ctx)
    }

    // ---------------------------------------------------------------- enregistrement CSV

    fun toggleRecording() {
        val l = logger
        if (l != null) {
            recFile = l.stop()
            logger = null
            _live.update { it.copy(recording = false, message = "Détail du trajet enregistré (CSV)") }
        } else {
            val nl = DataLogger(store.logsDir)
            nl.start(_live.value.supported.map { "${it.def.name} (${it.def.unit})" } + listOf("Batterie (V)", "Consommation") +
                activeVehicle().customPids.map { "${it.name} (${it.unit})" })
            logger = nl
            _live.update { it.copy(recording = true, recStart = System.currentTimeMillis()) }
        }
    }

    // ---------------------------------------------------------------- diagnostic

    fun readDiagnostics() {
        if (_live.value.conn != Conn.CONNECTED || _live.value.diagLoading) return
        scope.launch {
            _live.update { it.copy(diagLoading = true) }
            try {
                val dtcs = ObdParser.parseDtcs(conn.send("03", 8000), conn.isCan, "43").map { ObdParser.explain(it) }
                val pending = ObdParser.parseDtcs(conn.send("07", 8000), conn.isCan, "47")
                    .filterNot { p -> dtcs.any { it.code == p } }.map { ObdParser.explain(it) }
                val ready = ObdParser.parseReadiness(conn.send("0101", 4000))
                var freeze: FreezeFrame? = null
                if (dtcs.isNotEmpty()) {
                    val fd = ObdParser.parseFreezeDtc(conn.send("020200", 4000))
                    val vals = mutableListOf<Pair<String, String>>()
                    for (code in listOf("0D", "0C", "05", "04", "0B", "11", "0F")) {
                        val p = Pids.byCode(code) ?: continue
                        ObdParser.parseMode02(conn.send("02${code}00", 3000), code, p.bytes)?.let {
                            vals += p.def.name to "${fmt(p.decode(it))} ${p.def.unit}"
                        }
                    }
                    if (fd != null || vals.isNotEmpty()) freeze = FreezeFrame(fd, vals)
                }
                _live.update { it.copy(diagLoading = false, dtcs = dtcs, pending = pending, readiness = ready, freeze = freeze) }
            } catch (e: IOException) {
                _live.update { it.copy(diagLoading = false, message = "Lecture impossible : ${e.message}") }
            }
        }
    }

    fun clearDtcs() {
        scope.launch {
            try {
                conn.send("04", 8000)
                _live.update { it.copy(dtcs = emptyList(), pending = emptyList(), freeze = null, message = "Codes effacés, le voyant moteur va s'éteindre") }
                readDiagnostics()
            } catch (e: IOException) {
                _live.update { it.copy(message = "Effacement impossible : ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- tests par calculateur

    /** Met la lecture en direct en pause, exécute [block], puis remet l'adaptateur dans son état normal. */
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        pollPaused = true
        delay(300)
        try {
            return block()
        } finally {
            restoreAdapter()
            pollPaused = false
        }
    }

    private suspend fun restoreAdapter() {
        runCatching {
            conn.send("ATD", 3000)
            for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) conn.send(c)
            conn.send(if (conn.isStn) "ATAT2" else "ATAT1")
            conn.send("0100", 8000)
        }
    }

    /**
     * Recherche des calculateurs.
     * Rapide : les calculateurs OBD (moteur, boîte…) qui répondent à la requête générale.
     * Approfondie : interroge chaque adresse CAN 0x600–0x7F7 avec « Tester Present » (3E 00).
     */
    fun scanEcus(deep: Boolean, opt: ScanOptions = ScanOptions()) {
        if (_live.value.conn != Conn.CONNECTED || _ecu.value.running) return
        scope.launch {
            _ecu.update { it.copy(running = true, progress = 0f, label = "Recherche des calculateurs OBD…") }
            val found = LinkedHashMap<String, Ecu>()
            _ecu.value.ecus.forEach { found[it.net.name + it.resp] = it }
            fun add(e: Ecu) { val k = e.net.name + e.resp; if (!found.containsKey(k)) found[k] = e }
            fun progress(p: Float, label: String) = _ecu.update { it.copy(progress = p, label = label) }
            try {
                exclusive {
                    if (conn.isCan) {
                        conn.send("ATH1")
                        for (r in EcuParser.responders(conn.send("0100", 5000))) {
                            val id = r.toInt(16)
                            if (id in 0x7E8..0x7EF) add(Ecu("%03X".format(id - 8), r, EcuParser.obdName(r), obd = true))
                        }
                        if (deep && opt.hs11) {
                            conn.send("ATCF000"); conn.send("ATCM000"); conn.send("ATST19")
                            val ids = EcuParser.SCAN_IDS
                            ids.forEachIndexed { i, id ->
                                if (i % 8 == 0) progress(i.toFloat() / ids.size * part(opt, 0), "CAN principal · adresse %03X…".format(id))
                                conn.send("ATSH%03X".format(id))
                                val raw = try { conn.send("3E00", 600) } catch (e: IOException) { "" }
                                EcuParser.testerPresentReplies(raw).firstOrNull { it != "%03X".format(id) }?.let {
                                    add(Ecu("%03X".format(id), it, "Calculateur $it", obd = false))
                                }
                            }
                        }
                        if (deep && opt.hs29) {
                            // Adressage normalisé 29 bits : requête 18 DA tt F1, réponse 18 DA F1 tt
                            conn.send("ATSP7"); conn.send("ATH1"); conn.send("ATCP18")
                            conn.send("ATCF18DAF100"); conn.send("ATCM1FFFFF00"); conn.send("ATST19")
                            for (t in 0..0xFF) {
                                if (t == 0xF1) continue
                                if (t % 8 == 0) progress(part(opt, 1) + t / 255f * part(opt, 2), "Adresses 29 bits · %02X…".format(t))
                                conn.send("ATSHDA%02XF1".format(t))
                                val raw = try { conn.send("3E00", 600) } catch (e: IOException) { "" }
                                EcuParser.testerPresentReplies29(raw).firstOrNull()?.let { tt ->
                                    add(Ecu("18DA${tt}F1", "18DAF1$tt", "Calculateur 29 bits $tt", obd = false, net = Net.HS29))
                                }
                            }
                        }
                        if (deep && opt.ms && conn.isStn) {
                            // OBDLink : réseau confort sur les broches 3-11 (Opel ≈ 95 kbit/s, Ford 125 kbit/s)
                            conn.send("STP53"); conn.send("STPBR${opt.msBaud}"); conn.send("ATH1")
                            conn.send("ATCF000"); conn.send("ATCM000"); conn.send("ATST19")
                            val ids = EcuParser.SCAN_IDS
                            ids.forEachIndexed { i, id ->
                                if (i % 8 == 0) progress(part(opt, 3) + i.toFloat() / ids.size * part(opt, 4), "Réseau confort · adresse %03X…".format(id))
                                conn.send("ATSH%03X".format(id))
                                val raw = try { conn.send("3E00", 600) } catch (e: IOException) { "" }
                                EcuParser.testerPresentReplies(raw).firstOrNull { it != "%03X".format(id) }?.let {
                                    add(Ecu("%03X".format(id), it, "Confort $it", obd = false, net = Net.MS))
                                }
                            }
                        }
                    } else {
                        add(Ecu("", "MOT", "Moteur (ECM)", obd = true))
                    }
                }
                val list = found.values.toList()
                _ecu.update {
                    it.copy(running = false, progress = 1f, ecus = list, deepDone = it.deepDone || deep,
                        label = "${list.size} calculateur${if (list.size > 1) "s" else ""} trouvé${if (list.size > 1) "s" else ""}" +
                            if (deep && opt.ms && !conn.isStn) " · réseau confort non disponible (boîtier sans puce STN)" else "")
                }
            } catch (e: IOException) {
                _ecu.update { it.copy(running = false, label = "Recherche interrompue : ${e.message}") }
            }
        }
    }

    /** Répartition de la barre de progression entre les réseaux choisis. idx pair = début, impair = largeur. */
    private fun part(o: ScanOptions, idx: Int): Float {
        val w = listOf(if (o.hs11) 2f else 0f, if (o.hs29) 1f else 0f, if (o.ms) 2f else 0f)
        val tot = w.sum().coerceAtLeast(1f)
        return when (idx) {
            0 -> w[0] / tot
            1 -> w[0] / tot
            2 -> w[1] / tot
            3 -> (w[0] + w[1]) / tot
            else -> w[2] / tot
        }
    }

    fun testEcu(e: Ecu) {
        if (_live.value.conn != Conn.CONNECTED || _ecu.value.testing != null) return
        scope.launch { runEcuTest(e) }
    }

    fun testAllEcus() {
        if (_live.value.conn != Conn.CONNECTED || _ecu.value.testing != null) return
        scope.launch { for (e in _ecu.value.ecus) runEcuTest(e) }
    }

    private suspend fun runEcuTest(e: Ecu) {
        _ecu.update { it.copy(testing = e.net.name + e.resp) }
        val res = try {
            exclusive { if (e.obd) testObdEcu(e) else testDiagEcu(e) }
        } catch (ex: IOException) {
            EcuResult(e, false, 0, "—", null, emptyList(), note = "Erreur : ${ex.message}")
        }
        _ecu.update { s ->
            val ecus = if (res.name != null && !e.obd) s.ecus.map { if (it.resp == e.resp && it.net == e.net) it.copy(name = res.name) else it } else s.ecus
            s.copy(testing = null, results = s.results + (e.net.name + e.resp to res), ecus = ecus)
        }
    }

    private suspend fun target(e: Ecu) {
        when (e.net) {
            Net.HS -> if (e.req.isNotEmpty()) { conn.send("ATSH${e.req}"); conn.send("ATCRA${e.resp}") }
            Net.HS29 -> {
                conn.send("ATSP7"); conn.send("ATCP18")
                conn.send("ATSH${e.req.substring(2)}")   // DA tt F1
                conn.send("ATCRA${e.resp}")
            }
            Net.MS -> {
                conn.send("STP53"); conn.send("STPBR95238")
                conn.send("ATSH${e.req}"); conn.send("ATCRA${e.resp}")
            }
        }
    }

    private suspend fun testObdEcu(e: Ecu): EcuResult {
        target(e)
        val t0 = System.currentTimeMillis()
        val r03 = conn.send("03", 5000)
        val ms = System.currentTimeMillis() - t0
        if (ObdParser.isError(r03)) return EcuResult(e, false, ms, "OBD", null, emptyList(), note = "Ne répond pas")
        val dtcs = ObdParser.parseDtcs(r03, conn.isCan, "43").map { EcuDtc(it, null) }
        val pending = ObdParser.parseDtcs(conn.send("07", 5000), conn.isCan, "47")
        val permanent = ObdParser.parseDtcs(conn.send("0A", 5000), conn.isCan, "4A")
        val ready = ObdParser.parseReadiness(conn.send("0101", 3000))
        val name = EcuParser.ascii(conn.send("090A", 3000), "490A01")
        return EcuResult(e, true, ms, "OBD", name, dtcs, pending, permanent, ready)
    }

    private suspend fun testDiagEcu(e: Ecu): EcuResult {
        target(e)
        val t0 = System.currentTimeMillis()
        var raw = conn.send("1902FF", 5000)
        val ms = System.currentTimeMillis() - t0
        var proto = "UDS"
        var dtcs = EcuParser.udsDtcs(raw)
        if (dtcs == null) {
            raw = conn.send("1802FF00", 5000)
            dtcs = EcuParser.kwpDtcs(raw)
            proto = "KWP2000"
        }
        val name = EcuParser.ascii(conn.send("22F197", 3000), "62F197")
            ?: EcuParser.ascii(conn.send("22F18C", 3000), "62F18C")
        if (dtcs == null) {
            return EcuResult(e, true, ms, "Constructeur", name, emptyList(),
                note = "Le calculateur répond, mais avec un langage propre à la marque : lecture des codes impossible ici.")
        }
        return EcuResult(e, true, ms, proto, name, dtcs)
    }

    fun clearEcu(e: Ecu) {
        if (_live.value.conn != Conn.CONNECTED) return
        scope.launch {
            val ok = try {
                exclusive {
                    target(e)
                    when {
                        e.obd -> EcuParser.positive(conn.send("04", 8000), "44")
                        _ecu.value.results[e.net.name + e.resp]?.protocol == "KWP2000" -> EcuParser.positive(conn.send("14FF00", 8000), "54")
                        else -> EcuParser.positive(conn.send("14FFFFFF", 8000), "54")
                    }
                }
            } catch (ex: IOException) { false }
            _live.update { it.copy(message = if (ok) "Codes effacés : ${e.name}" else "Le calculateur ${e.name} a refusé l'effacement (moteur à couper ?)") }
            if (ok) runEcuTest(e)
        }
    }

    // ---------------------------------------------------------------- défauts intermittents

    private suspend fun monitorDtcs() {
        val now = (ObdParser.parseDtcs(conn.send("03", 3000), conn.isCan, "43") +
            ObdParser.parseDtcs(conn.send("07", 3000), conn.isCan, "47")).toSet()
        val known = knownDtcs
        knownDtcs = now
        if (known == null) return
        val fresh = now - known
        if (fresh.isNotEmpty()) {
            val d = ObdParser.explain(fresh.first())
            val s = _settings.value
            alerts.play(false, s.sound, s.vibration)
            _live.update {
                it.copy(message = "Nouveau défaut détecté : ${d.code} · ${d.title}",
                    pending = (it.pending + fresh.map { c -> ObdParser.explain(c) }).distinctBy { x -> x.code })
            }
        }
    }

    // ---------------------------------------------------------------- test batterie

    /** Tension au repos, chute pendant le démarrage, puis charge de l'alternateur. */
    fun startBatteryTest() {
        if (_live.value.conn != Conn.CONNECTED || _battery.value.phase in setOf("rest", "crank", "charge")) return
        scope.launch {
            pollPaused = true
            delay(300)
            try {
                _battery.value = BatteryTest("rest", message = "Mesure au repos… laissez le moteur coupé, contact mis.")
                val rest = average(2500)
                _battery.value = _battery.value.copy(rest = rest, phase = "crank", message = "Démarrez le moteur maintenant.")
                val samples = ArrayList<Float>()
                var min = rest ?: 14.0
                val until = System.currentTimeMillis() + 12_000
                while (System.currentTimeMillis() < until) {
                    val v = try { ObdParser.parseVoltage(conn.send("ATRV", 800)) } catch (e: IOException) { null }
                    if (v != null) {
                        samples += v.toFloat(); min = minOf(min, v)
                        _battery.value = _battery.value.copy(samples = samples.toList(), crankMin = min)
                    }
                    delay(40)
                }
                _battery.value = _battery.value.copy(phase = "charge", message = "Moteur au ralenti, mesure de la charge…")
                delay(3000)
                val charge = average(3000)
                _battery.value = _battery.value.copy(phase = "done", charge = charge, message = "")
            } catch (e: Exception) {
                _battery.value = _battery.value.copy(phase = "error", message = "Test interrompu : ${e.message}")
            } finally {
                pollPaused = false
            }
        }
    }

    fun resetBatteryTest() { if (_battery.value.phase in setOf("done", "error")) _battery.value = BatteryTest() }

    private suspend fun average(ms: Long): Double? {
        val vals = ArrayList<Double>()
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            runCatching { ObdParser.parseVoltage(conn.send("ATRV", 800)) }.getOrNull()?.let { vals += it }
            delay(100)
        }
        return if (vals.isEmpty()) null else vals.average()
    }

    // ---------------------------------------------------------------- carburant & chronos

    fun addFuel(e: FuelEntry) {
        _fuel.value = (_fuel.value + e).sortedByDescending { it.date }
        store.saveFuel(_fuel.value)
        if (e.odometerKm > activeVehicle().odometerKm) updateVehicle(e.vehicleId) { it.copy(odometerKm = e.odometerKm) }
        calibrate(e.vehicleId)
    }

    /** Litres consommés depuis le dernier plein complet (null si aucun plein noté). */
    private fun litersSinceFull(v: Vehicle): Double? {
        val last = _fuel.value.filter { it.vehicleId == v.id && it.full }.maxByOrNull { it.date } ?: return null
        return _trips.value.filter { it.vehicleId == v.id && it.start > last.date }.sumOf { it.fuelL }
    }

    /**
     * Ajuste l'estimation de consommation de l'appli sur la consommation réelle mesurée entre deux pleins complets.
     * Le facteur reste entre 0,7 et 1,4 pour écarter les erreurs de saisie.
     */
    private fun calibrate(vehicleId: String) {
        val fills = _fuel.value.filter { it.vehicleId == vehicleId }.sortedBy { it.date }
        val full = fills.filter { it.full }
        if (full.size < 2) return
        val a = full[full.size - 2]; val b = full.last()
        val realL = fills.filter { it.date > a.date && it.date <= b.date }.sumOf { it.liters }
        val estL = _trips.value.filter { it.vehicleId == vehicleId && it.start > a.date && it.start <= b.date }.sumOf { it.fuelL }
        if (realL <= 0 || estL < 5) return
        val v = _vehicles.value.firstOrNull { it.id == vehicleId } ?: return
        // les trajets ont été estimés avec l'ancien facteur : on corrige de façon cumulative
        val f = (v.consFactor * realL / estL).coerceIn(0.7, 1.4)
        updateVehicle(vehicleId) { it.copy(consFactor = f) }
        _live.update { it.copy(message = "Consommation recalée sur vos pleins (×${"%.2f".format(f)})") }
    }

    fun deleteFuel(id: String) {
        _fuel.value = _fuel.value.filterNot { it.id == id }
        store.saveFuel(_fuel.value)
    }

    fun deletePerf(id: String) {
        _perf.value = _perf.value.filterNot { it.id == id }
        store.savePerf(_perf.value)
    }

    fun series(tripId: String): List<TripPoint> = store.loadSeries(tripId)

    // ---------------------------------------------------------------- sauvegarde

    fun exportBackup(): String = store.exportAll()

    fun importBackup(json: String): Boolean {
        if (_live.value.conn != Conn.IDLE) return false
        if (!store.importAll(json)) return false
        _settings.value = store.loadSettings()
        _vehicles.value = store.loadVehicles().ifEmpty { _vehicles.value }
        _trips.value = store.loadTrips()
        _fuel.value = store.loadFuel()
        _perf.value = store.loadPerf()
        if (_vehicles.value.none { it.id == _settings.value.activeVehicleId }) updateSettings { it.copy(activeVehicleId = _vehicles.value.first().id) }
        CarWidget.refresh(ctx)
        return true
    }

    /** Supprime les trajets plus anciens que la durée de conservation choisie. */
    fun pruneTrips() {
        val days = _settings.value.keepTripsDays
        if (days <= 0) return
        val limit = System.currentTimeMillis() - days * 86_400_000L
        _trips.value.filter { it.start < limit }.forEach { deleteTrip(it) }
    }

    fun forgetDevice() = updateSettings { it.copy(lastDevice = null) }

    /** Efface toutes les données et repart d'une appli neuve (boîtier déconnecté uniquement). */
    fun wipeAll(): Boolean {
        if (_live.value.conn != Conn.IDLE) return false
        store.wipe()
        _settings.value = Settings()
        val v = Vehicle(name = "Ma voiture", maintenance = Presets.defaultMaintenance(0.0, System.currentTimeMillis()))
        _vehicles.value = listOf(v); store.saveVehicles(_vehicles.value)
        updateSettings { it.copy(activeVehicleId = v.id) }
        _trips.value = emptyList(); _fuel.value = emptyList(); _perf.value = emptyList()
        CarWidget.refresh(ctx)
        return true
    }

    /** Derniers échanges avec le boîtier, avec un en-tête utile pour un diagnostic à distance. */
    fun journalText(): String {
        val l = _live.value
        val head = listOf(
            "SimCarDiag ${Updater.currentName(ctx)} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
            "Boîtier : ${l.device ?: "—"} · état : ${l.conn} · protocole : ${l.protocol.ifEmpty { "—" }} · STN : ${l.isStn} · lecture groupée : ${l.multi}",
            "Capteurs : ${l.supported.joinToString(" ") { it.code }.ifEmpty { "—" }}",
            "Véhicule : ${activeVehicle().name} ${activeVehicle().vin ?: ""}",
            "",
        )
        return (head + l.log.map { "[connexion] $it" } + conn.journal()).joinToString("\n")
    }

    fun consumeMessage() = _live.update { it.copy(message = null) }
    fun clearError() = _live.update { it.copy(error = null) }

    fun fmt(v: Double): String =
        if (v.isNaN()) "—" else if (kotlin.math.abs(v) >= 100) String.format(java.util.Locale.FRANCE, "%.0f", v)
        else String.format(java.util.Locale.FRANCE, "%.1f", v)
}
