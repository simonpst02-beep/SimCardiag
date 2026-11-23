package com.example.obdanalyzer.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Une version publiée sur GitHub (Releases). */
data class Release(
    val tag: String,
    val build: Int,
    val title: String,
    val notes: String,
    val date: String,
    val downloadUrl: String,   // lien public (dépôt public)
    val apiAssetUrl: String,   // lien API (dépôt privé, avec jeton)
    val size: Long,
)

data class UpdateState(
    val checking: Boolean = false,
    val latest: Release? = null,
    val available: Boolean = false,
    val error: String? = null,
    val progress: Float? = null,     // téléchargement en cours (0..1)
    val lastCheck: Long = 0,
)

/**
 * Mises à jour depuis les versions publiées sur GitHub par la compilation automatique.
 * Dépôt public : aucun réglage. Dépôt privé : un jeton GitHub en lecture seule est nécessaire.
 */
object Updater {

    const val OWNER = "simonpst02-beep"
    const val REPO = "SimCardiag"
    const val PAGE = "https://github.com/$OWNER/$REPO/releases"

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun currentBuild(ctx: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    }.getOrDefault(0L)

    fun currentName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun open(url: String, token: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SimCarDiag")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

    suspend fun check(ctx: Context, token: String?) {
        _state.update { it.copy(checking = true, error = null) }
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val c = open("https://api.github.com/repos/$OWNER/$REPO/releases/latest", token, "application/vnd.github+json")
                when (c.responseCode) {
                    200 -> {}
                    401 -> error("Jeton GitHub refusé : vérifiez-le dans cette section.")
                    404 -> error(if (token.isNullOrBlank()) "Dépôt privé ou aucune version publiée : rendez le dépôt public ou ajoutez un jeton GitHub."
                                 else "Aucune version publiée pour l'instant, ou le jeton n'a pas accès au dépôt.")
                    403 -> error("GitHub limite les vérifications : réessayez dans une heure.")
                    else -> error("GitHub répond ${c.responseCode}.")
                }
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = o.getString("tag_name")
                val assets = o.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                    ?: error("Cette version ne contient pas d'APK.")
                Release(
                    tag = tag,
                    build = tag.substringAfterLast('.').filter(Char::isDigit).toIntOrNull() ?: 0,
                    title = o.optString("name", tag),
                    notes = o.optString("body", ""),
                    date = o.optString("published_at", "").take(10),
                    downloadUrl = apk.getString("browser_download_url"),
                    apiAssetUrl = apk.getString("url"),
                    size = apk.optLong("size"),
                )
            }
        }
        res.onSuccess { r ->
            _state.update { it.copy(checking = false, latest = r, available = r.build > currentBuild(ctx), lastCheck = System.currentTimeMillis()) }
        }.onFailure { e ->
            _state.update { it.copy(checking = false, error = e.message ?: "Pas de connexion internet.", lastCheck = System.currentTimeMillis()) }
        }
    }

    /** Télécharge l'APK puis ouvre l'installeur Android. */
    suspend fun downloadAndInstall(ctx: Context, token: String?) {
        val r = _state.value.latest ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            // Autorisation « installer des applis inconnues » à donner une fois
            ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.update { it.copy(error = "Autorisez SimCarDiag à installer des applis, puis touchez à nouveau « Installer ».") }
            return
        }
        _state.update { it.copy(progress = 0f, error = null) }
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "SimCarDiag-${r.build}.apk")
                // Dépôt privé : l'API renvoie vers une adresse de téléchargement temporaire, à suivre sans le jeton
                var c = if (token.isNullOrBlank()) open(r.downloadUrl, null, "application/octet-stream")
                        else open(r.apiAssetUrl, token, "application/octet-stream").apply { instanceFollowRedirects = false }
                var hops = 0
                while (c.responseCode in 300..399 && hops < 5) {
                    val loc = c.getHeaderField("Location") ?: break
                    c = open(loc, null, "application/octet-stream")
                    hops++
                }
                if (c.responseCode != 200) error("Téléchargement impossible (code ${c.responseCode}).")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) _state.update { it.copy(progress = (done.toFloat() / total).coerceAtMost(1f)) }
                        }
                    }
                }
                out
            }
        }
        file.onSuccess { f ->
            _state.update { it.copy(progress = null) }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { e ->
            _state.update { it.copy(progress = null, error = e.message ?: "Téléchargement interrompu.") }
        }
    }
}
