package com.riptv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Avance de carga: [percent] global (null si no se conoce el tamaño) y canales leídos hasta ahora. */
data class LoadProgress(val phase: Phase, val percent: Int?, val items: Int) {
    enum class Phase { DOWNLOAD, PARSE }
}

/** Una lista guardada: su [url] (o ruta de archivo) y un [name] para reconocerla. */
data class SavedList(val id: String, val name: String, val url: String)

class PlaylistRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("riptv", Context.MODE_PRIVATE)

    init { migrateSingleList() }

    private var cachedLists: List<SavedList>? = null

    val lists: List<SavedList>
        get() {
            cachedLists?.let { return it }
            val raw = prefs.getString("lists", "[]").orEmpty()
            // Si no se puede descifrar (p. ej. se cambió de dispositivo o se reinició el almacén de claves) no hay listas.
            val json = SecretBox.decrypt(raw) ?: "[]"
            val arr = JSONArray(json)
            val parsed = List(arr.length()) { i ->
                arr.getJSONObject(i).let { SavedList(it.getString("id"), it.getString("name"), it.getString("url")) }
            }
            cachedLists = parsed
            // Datos guardados en claro por versiones anteriores: se vuelven a guardar cifrados.
            if (!SecretBox.isEncrypted(raw) && parsed.isNotEmpty()) writeLists(parsed)
            return parsed
        }

    val active: SavedList?
        get() = lists.let { all -> all.firstOrNull { it.id == prefs.getString("active_list", null) } ?: all.firstOrNull() }

    /** URL o ruta de la lista activa; vacío si no hay ninguna. */
    val source: String get() = active?.url.orEmpty()

    /** Cambia la URL de la lista activa, o crea la primera si no hay ninguna. */
    fun setSource(url: String) {
        val current = active
        if (current == null) {
            addList(url)
            return
        }
        writeLists(lists.map { if (it.id == current.id) it.copy(name = nameFor(url, it.id), url = url.trim()) else it })
    }

    /** Añade una lista y la deja como activa. */
    fun addList(url: String): SavedList {
        val id = System.currentTimeMillis().toString(36)
        val list = SavedList(id, nameFor(url, id), url.trim())
        writeLists(lists + list)
        prefs.edit().putString("active_list", id).apply()
        return list
    }

    fun selectList(id: String) {
        prefs.edit().putString("active_list", id).apply()
    }

    fun removeList(id: String) {
        cacheFor(id).delete()
        writeLists(lists.filter { it.id != id })
        if (prefs.getString("active_list", null) == id) prefs.edit().remove("active_list").apply()
    }

    /** Borra las copias descargadas de todas las listas. */
    fun clearCache() {
        context.filesDir.listFiles { f -> f.name.startsWith("playlist") || f.name.startsWith("vod_") }?.forEach { it.delete() }
    }

    /** Tamaño (bytes) de las copias descargadas. */
    val cacheSize: Long
        get() = context.filesDir.listFiles { f -> f.name.startsWith("playlist") }?.sumOf { it.length() } ?: 0L

    /** Momento (ms) en que se obtuvo la lista activa, o null si no hay. */
    val lastUpdated: Long?
        get() {
            val src = source
            val file = when {
                isRemote(src) -> active?.let { cacheFor(it.id) }
                src.isNotEmpty() -> File(src)
                else -> null
            }
            return file?.takeIf { it.exists() }?.lastModified()
        }

    /** Si según [mode] toca descargar de nuevo la lista al arrancar. */
    fun shouldRefresh(mode: RefreshMode): Boolean {
        val updated = lastUpdated
        val age = if (updated == null) Long.MAX_VALUE else System.currentTimeMillis() - updated
        return when (mode) {
            RefreshMode.MANUAL -> false
            RefreshMode.ON_START -> true
            RefreshMode.DAILY -> age >= DAY_MS
            RefreshMode.WEEKLY -> age >= 7 * DAY_MS
        }
    }

    /** Carga la lista activa; si [refresh] es false y hay copia descargada, la reutiliza. */
    fun load(refresh: Boolean, userAgent: String, onProgress: (LoadProgress) -> Unit = {}): List<Channel> {
        val list = active ?: return emptyList()
        val src = list.url
        return when {
            isRemote(src) -> {
                val cache = cacheFor(list.id)
                val downloaded = refresh || !cache.exists()
                if (downloaded) download(src, cache, userAgent, onProgress)
                parse(cache.inputStream(), cache.length(), if (downloaded) 50 else 0, onProgress)
            }
            src.isNotEmpty() -> {
                val file = File(src)
                parse(file.inputStream(), file.length(), 0, onProgress)
            }
            else -> emptyList()
        }
    }

    private fun isRemote(src: String) = src.startsWith("http://") || src.startsWith("https://")

    private fun cacheFor(id: String) = File(context.filesDir, "playlist_$id.m3u")

    private fun writeLists(lists: List<SavedList>) {
        val arr = JSONArray()
        lists.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        // La URL lleva usuario y contraseña: se guarda cifrada.
        prefs.edit().putString("lists", SecretBox.encrypt(arr.toString())).apply()
        cachedLists = lists
    }

    /** Nombre legible (el servidor o el archivo); si ya existe otro igual se le añade un número. */
    private fun nameFor(url: String, ownId: String): String {
        val u = url.trim()
        val base = when {
            isRemote(u) -> runCatching { URI(u).host }.getOrNull() ?: u
            else -> File(u).name.ifEmpty { u }
        }
        val taken = lists.filter { it.id != ownId }.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { it !in taken }
    }

    /** Versiones anteriores guardaban una sola lista en «source»; se convierte en la primera lista guardada. */
    private fun migrateSingleList() {
        if (prefs.contains("lists")) return
        val legacy = prefs.getString("source", "").orEmpty()
        prefs.edit().putString("lists", "[]").remove("source").apply()
        if (legacy.isNotBlank()) {
            val list = SavedList("1", "", legacy)
            writeLists(listOf(list.copy(name = nameFor(legacy, "1"))))
            prefs.edit().putString("active_list", "1").apply()
            File(context.filesDir, "playlist.m3u").takeIf { it.exists() }?.renameTo(cacheFor("1"))
        }
    }

    /** Lee [input]; el porcentaje avanza de [base] a 100 según los bytes leídos de [total]. */
    private fun parse(input: InputStream, total: Long, base: Int, onProgress: (LoadProgress) -> Unit): List<Channel> {
        val counting = CountingInputStream(input)
        return counting.bufferedReader().use { reader ->
            M3uParser.parse(reader) { items ->
                val percent = if (total > 0) base + ((100 - base) * counting.count / total).toInt() else null
                onProgress(LoadProgress(LoadProgress.Phase.PARSE, percent?.coerceAtMost(100), items))
            }
        }
    }

    private fun download(url: String, dest: File, userAgent: String, onProgress: (LoadProgress) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", userAgent)
        val tmp = File(dest.path + ".tmp")
        try {
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (50 * done / total).toInt() else null
                        onProgress(LoadProgress(LoadProgress.Phase.DOWNLOAD, percent, 0))
                    }
                }
            }
            tmp.copyTo(dest, overwrite = true)
        } finally {
            tmp.delete()
            conn.disconnect()
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
