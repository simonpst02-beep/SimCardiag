package com.example.obdanalyzer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.obdanalyzer.MainActivity
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service au premier plan : garde la connexion et l'enregistrement actifs écran éteint,
 * et affiche les valeurs principales dans la notification.
 */
class ObdService : Service() {

    companion object {
        private const val CHANNEL = "obd_live"
        private const val ID = 42
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObdService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Connexion OBD", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Affiche l'état de la connexion pendant la conduite"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ObdManager.disconnect()
            return START_NOT_STICKY
        }
        val locOk = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (locOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        ServiceCompat.startForeground(this, ID, build("Connexion au boîtier…"), type)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                delay(2000)
                val l = ObdManager.live.value
                val text = when (l.conn) {
                    Conn.CONNECTED -> buildString {
                        l.values["speed"]?.let { append("${it.toInt()} km/h") }
                        l.values["cool"]?.let { append(" · ${it.toInt()} °C") }
                        append(" · ${"%.1f".format(l.stats.distanceKm)} km")
                        if (l.recording) append(" · ● enregistrement")
                        l.alerts.firstOrNull()?.let { append("\n⚠ ${it.text}") }
                    }
                    Conn.RECONNECTING -> "Connexion perdue, nouvelle tentative…"
                    else -> "Connexion au boîtier…"
                }
                nm.notify(ID, build(text))
            }
        }
        return START_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OBD Analyzer")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Déconnecter", stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
