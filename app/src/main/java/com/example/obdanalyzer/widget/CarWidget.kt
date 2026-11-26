package com.example.obdanalyzer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.obdanalyzer.MainActivity
import com.example.obdanalyzer.R
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.status
import kotlin.math.roundToInt

/** Widget d'écran d'accueil : état de la voiture, autonomie, prochain entretien. */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ObdManager.init(context)
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, CarWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { m.updateAppWidget(it, views) }
        }

        private fun build(context: Context): RemoteViews {
            val v = ObdManager.activeVehicle()
            val l = ObdManager.live.value
            val connected = l.conn == Conn.CONNECTED || l.conn == Conn.RECONNECTING
            val bad = l.alerts.firstOrNull()
            val status = when {
                connected && bad != null -> "⚠ ${bad.text.substringBefore('.')}"
                connected -> "Connecté · ${l.values["speed"]?.roundToInt() ?: 0} km/h"
                else -> "Non connecté"
            }
            val fuelPct = l.values["fuel"] ?: v.lastFuelPct
            val avg = l.stats.avgConsumption ?: v.lastAvgCons ?: 6.5
            val autonomy = fuelPct?.let { "Autonomie ≈ ${((it / 100 * v.tankL) / (avg / 100)).roundToInt()} km (${it.roundToInt()} %)" }
                ?: "Autonomie : branchez le boîtier une fois"
            val now = System.currentTimeMillis()
            val next = v.maintenance.map { it.status(v.odometerKm, now) }
                .sortedBy { s -> minOf(s.kmLeft ?: Double.MAX_VALUE, (s.daysLeft ?: Long.MAX_VALUE / 2).toDouble() * 40) }
                .firstOrNull()
            val maint = next?.let { s ->
                val parts = listOfNotNull(
                    s.kmLeft?.let { if (it <= 0) "dépassé" else "${it.roundToInt()} km" },
                    s.daysLeft?.let { if (it <= 0) "échu" else "$it j" })
                "${s.item.name} : ${parts.joinToString(" / ")}"
            } ?: "Aucun entretien programmé"

            return RemoteViews(context.packageName, R.layout.widget_car).apply {
                setTextViewText(R.id.w_title, v.name)
                setTextViewText(R.id.w_status, status)
                setTextViewText(R.id.w_autonomy, autonomy)
                setTextViewText(R.id.w_maint, maint)
                val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.w_root, pi)
            }
        }
    }
}
