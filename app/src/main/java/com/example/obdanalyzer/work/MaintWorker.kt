package com.example.obdanalyzer.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.obdanalyzer.MainActivity
import com.example.obdanalyzer.R
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.core.status
import java.util.concurrent.TimeUnit

/**
 * Une fois par jour, même appli fermée : prévient des entretiens à faire (vidange, contrôle technique, assurance…).
 * Chaque rappel n'est notifié qu'une fois par état (bientôt, puis en retard) pour ne pas harceler.
 */
class MaintWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        ObdManager.init(ctx)
        if (!ObdManager.settings.value.maintNotif) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val prefs = ctx.getSharedPreferences("maint_notif", Context.MODE_PRIVATE)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels d'entretien", NotificationManager.IMPORTANCE_DEFAULT))
        val now = System.currentTimeMillis()
        var n = 0
        for (v in ObdManager.vehicles.value) for (m in v.maintenance) {
            val s = m.status(v.odometerKm, now)
            val state = when { s.overdue -> "late"; s.soon -> "soon"; else -> "ok" }
            val key = "${v.id}/${m.id}/${m.lastDate}"
            if (state == "ok" || prefs.getString(key, null) == state) continue
            prefs.edit().putString(key, state).apply()
            val detail = listOfNotNull(
                s.kmLeft?.let { if (it <= 0) "dépassé de ${(-it).toInt()} km" else "dans ${it.toInt()} km" },
                s.daysLeft?.let { if (it <= 0) "en retard de ${-it} jours" else "dans $it jours" },
            ).joinToString(" ou ")
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(1000 + (key.hashCode() and 0xFFFF), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("${v.name} : ${m.name}")
                .setContentText(if (s.overdue) "À faire : $detail" else "Bientôt : $detail")
                .setContentIntent(open).setAutoCancel(true).build())
            n++
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "maint"
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("maint", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
