package com.example.obdanalyzer.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.example.obdanalyzer.MainActivity
import com.example.obdanalyzer.core.Conn
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.service.ObdService

/**
 * Autoradio et téléphone :
 * - au démarrage de l'appareil (contact mis sur un autoradio), ouvre l'appli et se connecte au boîtier ;
 * - quand le boîtier mémorisé se connecte en Bluetooth, lance la connexion OBD.
 */
class AutoStartReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        ObdManager.init(context)
        val s = ObdManager.settings.value
        if (ObdManager.live.value.conn != Conn.IDLE) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                if (!s.autoStartBoot) return
                // Ouvrir l'écran : possible depuis l'arrière-plan seulement avec l'autorisation « par-dessus les autres applis »
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || AndroidSettings.canDrawOverlays(context)) {
                    runCatching {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                if (s.lastDevice != null) ObdService.autoConnect(context)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!s.autoConnectBt) return
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (dev != null && dev.address == s.lastDevice) ObdService.autoConnect(context)
            }
        }
    }
}
