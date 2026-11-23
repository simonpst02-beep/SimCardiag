package com.example.obdanalyzer

import android.app.Application
import com.example.obdanalyzer.core.ObdManager

class ObdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ObdManager.init(this)
        // Fond de carte OpenStreetMap
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
    }
}
