package com.riptv.tv

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Program(val title: String, val startMs: Long, val endMs: Long)

/**
 * Guía de programación (programa actual y siguiente) pedida a la API del servidor, deduciendo el
 * servidor, el usuario, la contraseña y el id del canal de su propia URL. Si el servidor no es de este
 * tipo o el canal no tiene guía, simplemente no hay información.
 */
object Epg {
    private val pattern = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)")

    // La lista pide la guía de varios canales a la vez: se limitan las peticiones simultáneas.
    private val gate = Semaphore(2)

    /** Programas en emisión o por emitir (máx. 2); lista vacía si el canal no tiene guía; null si falló la petición. */
    suspend fun nowNext(streamUrl: String, userAgent: String, shiftMs: Long = 0): List<Program>? =
        gate.withPermit { fetch(streamUrl, userAgent, until = null, limit = 2, shiftMs = shiftMs) }

    /** Programación de las próximas [hours] horas (el programa en emisión incluido), para ver el día completo. */
    suspend fun schedule(streamUrl: String, userAgent: String, hours: Int, shiftMs: Long = 0): List<Program>? =
        gate.withPermit { fetch(streamUrl, userAgent, until = System.currentTimeMillis() + hours * 3_600_000L, limit = 300, shiftMs = shiftMs) }

    private suspend fun fetch(streamUrl: String, userAgent: String, until: Long?, limit: Int, shiftMs: Long): List<Program>? = withContext(Dispatchers.IO) {
        val (base, user, pass, id) = pattern.find(streamUrl)?.destructured ?: return@withContext emptyList()
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        // «get_short_epg» solo da lo que aún no ha empezado; la tabla completa incluye el programa en emisión.
        val url = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_simple_data_table&stream_id=$id"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", userAgent)
            try {
                JsonReader(conn.inputStream.bufferedReader()).use { readPrograms(it, until, limit, shiftMs) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lee la tabla y se queda con los primeros [limit] programas que no han terminado y empiezan antes de [until]. */
    private fun readPrograms(r: JsonReader, until: Long?, limit: Int, shiftMs: Long): List<Program> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Program>()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "epg_listings") { r.skipValue(); continue }
            r.beginArray()
            while (r.hasNext()) {
                var title = ""
                var start = 0L
                var stop = 0L
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "title" -> title = r.text()
                    "start_timestamp" -> start = r.text().toLongOrNull() ?: 0L
                    "stop_timestamp" -> stop = r.text().toLongOrNull() ?: 0L
                    else -> r.skipValue()
                }
                r.endObject()
                val from = start * 1000 + shiftMs
                val to = stop * 1000 + shiftMs
                if (stop > 0 && to > now && (until == null || from < until)) out += Program(title, from, to)
            }
            r.endArray()
        }
        r.endObject()
        return out.sortedBy { it.startMs }.take(limit).map { it.copy(title = decode(it.title)) }
    }

    private fun JsonReader.text(): String =
        if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()

    // Los títulos llegan codificados en Base64.
    private fun decode(s: String) = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        s
    }
}

/** Hora de [ms] en la zona horaria y el formato (12/24 h) del dispositivo. */
fun formatClock(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
