package com.example.obdanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.example.obdanalyzer.core.ObdManager
import com.example.obdanalyzer.ui.AppTheme
import com.example.obdanalyzer.ui.ObdRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PermissionGate { ObdRoot() } }
        handleIncoming(intent)
        // Plein écran (autoradio) : barres d'Android masquées, un glissement depuis le bord les fait réapparaître
        lifecycleScope.launch {
            ObdManager.settings.map { it.fullscreen }.distinctUntilChanged().collect { full ->
                val c = WindowCompat.getInsetsController(window, window.decorView)
                if (full) {
                    c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c.hide(WindowInsetsCompat.Type.systemBars())
                } else c.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Fichier de synchronisation ouvert ou partagé vers SimCarDiag depuis un autre appareil. */
    private fun handleIncoming(i: Intent?) {
        val uri: Uri = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        val txt = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        if (txt == null || !txt.contains("simcardiag-backup")) {
            ObdManager.toast("Ce fichier n'est pas une sauvegarde SimCarDiag.")
            return
        }
        ObdManager.offerImport(txt)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ObdManager.settings.value.fullscreen)
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
}

private val NEEDED: Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    // pour arrêter les recherches Bluetooth de l'autoradio, qui ralentissent et font décrocher la liaison avec le boîtier
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
}.toTypedArray()

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun btGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(btGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = btGranted()
        if (granted) ObdManager.autoConnect()
    }
    LaunchedEffect(Unit) {
        if (NEEDED.isNotEmpty() && NEEDED.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }) launcher.launch(NEEDED)
        else ObdManager.autoConnect()
    }
    if (granted) content()
    else AppTheme(ObdManager.settings.value.theme, ObdManager.settings.value.accent) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Autorisation Bluetooth", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("L'appli en a besoin pour parler à votre boîtier OBDLink. Les notifications affichent le trajet en cours écran éteint, et la position sert à tracer vos trajets sur une carte (facultatif).")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(NEEDED) }) { Text("Autoriser") }
            }
        }
    }
}
