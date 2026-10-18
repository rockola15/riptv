package com.riptv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Última versión publicada: su [version], el APK a descargar y las notas de la release. */
data class Release(val version: String, val apkUrl: String, val apkName: String, val notes: String)

/**
 * Busca la última release de https://github.com/rockola15/riptv/releases, descarga su APK y lanza el
 * instalador del sistema. Android solo deja actualizar si el APK está firmado con la misma clave que la
 * app instalada, así que sirve para las versiones release firmadas con el mismo almacén de claves.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/rockola15/riptv/releases/latest"
    private val versionPattern = Regex("\\d+(?:\\.\\d+)*")

    /** La última release con un APK; null si no hay ninguna publicada. Lanza una excepción si falla la red. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "RipTV-updater")
        try {
            if (conn.responseCode == 404) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            val name = apk.getString("name")
            val tag = json.optString("tag_name")
            // La versión es la de la etiqueta de la release (v1.0.0); si no tiene números, la del nombre del APK.
            val version = versionPattern.find(tag)?.value ?: versionPattern.find(name)?.value ?: tag
            Release(version, apk.getString("browser_download_url"), name, json.optString("body").trim())
        } finally {
            conn.disconnect()
        }
    }

    /** Verdadero si [candidate] es una versión posterior a [current] (se comparan los números uno a uno). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = versionPattern.find(v)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Descarga el APK a la caché de la app, avisando del avance (0-100, o null si no se conoce el tamaño). */
    suspend fun download(context: Context, release: Release, onProgress: (Int?) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, release.apkName)
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "RipTV-updater")
        try {
            val total = conn.contentLengthLong
            var done = 0L
            var last = -1
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else null
                        if (percent != last) { last = percent ?: -1; onProgress(percent) }
                    }
                }
            }
            if (total > 0 && done != total) {
                file.delete()
                throw java.io.IOException("Descarga incompleta")
            }
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Abre el instalador del sistema con el APK. Si la app aún no tiene permiso para instalar aplicaciones,
     * abre la pantalla donde concedérselo y devuelve false: al volver hay que pulsar de nuevo.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
