package com.riptv.tv

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Busca el logo de cada canal en internet. Orden de preferencia:
 *  1. el icono que da el propio proveedor para ese canal (su API, por id de canal);
 *  2. un repositorio público de logos (tv-logo/tv-logos), casando por el nombre del canal;
 *  3. ninguno: la interfaz muestra el número del canal.
 * Los índices se descargan una vez y se guardan en caché.
 */
object Logos {
    private const val LOGOS_BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
    private const val LOGOS_TREE = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    private val account = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)")

    /**
     * Índices de logos ya cargados. El logo de un canal se busca solo cuando la interfaz lo pide (es decir,
     * cuando el canal está en pantalla) y se recuerda; así no se trabaja con los canales filtrados fuera.
     * Se usa solo desde el hilo principal.
     */
    class Index internal constructor(
        private val provider: Map<String, String>,
        private val repo: Map<String, List<Entry>>,
    ) {
        private val found = HashMap<Int, String>()

        /** URL del logo del canal, o vacío si no se encontró ninguno. */
        fun urlFor(ch: Channel): String = found.getOrPut(ch.number) {
            val id = account.find(ch.url)?.groupValues?.get(4)
            id?.let { provider[it] }?.takeIf { it.isNotBlank() } ?: matchRepo(repo, ch).orEmpty()
        }
    }

    /** Carga (descargando la primera vez) los índices de logos; si algo falla, ese origen queda vacío. */
    fun load(dir: File, listId: String?, sampleUrl: String?, userAgent: String): Index {
        val provider = sampleUrl?.let { runCatching { providerIcons(dir, listId, it, userAgent) }.getOrNull() }.orEmpty()
        val repo = runCatching { repoIndex(dir, userAgent) }.getOrDefault(emptyMap())
        return Index(provider, repo)
    }

    // ---- Iconos del proveedor (API Xtream: stream_id → stream_icon) ----

    private fun providerIcons(dir: File, listId: String?, sampleUrl: String, userAgent: String): Map<String, String> {
        val (base, user, pass) = account.find(sampleUrl)?.destructured ?: return emptyMap()
        val file = File(dir, "icons_${listId ?: "x"}.json")
        if (!file.exists() || age(file) > WEEK_MS) {
            fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
            download("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_live_streams", file, userAgent)
        }
        val out = HashMap<String, String>()
        JsonReader(file.reader(Charsets.UTF_8)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                var id = ""
                var icon = ""
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "stream_id" -> id = r.text()
                    "stream_icon" -> icon = r.text()
                    else -> r.skipValue()
                }
                r.endObject()
                if (id.isNotEmpty() && icon.isNotBlank()) out[id] = icon
            }
            r.endArray()
        }
        return out
    }

    // ---- Repositorio público de logos ----

    class Entry internal constructor(val country: String, val path: String)

    /** slug del nombre → logos con ese nombre (uno por país). */
    private fun repoIndex(dir: File, userAgent: String): Map<String, List<Entry>> {
        val file = File(dir, "logo_index.txt")
        if (!file.exists() || age(file) > MONTH_MS) {
            val tree = File(dir, "logo_tree.json")
            download(LOGOS_TREE, tree, userAgent)
            val paths = ArrayList<String>()
            JsonReader(tree.reader(Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "tree") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var path = ""
                        r.beginObject()
                        while (r.hasNext()) if (r.nextName() == "path") path = r.text() else r.skipValue()
                        r.endObject()
                        if (path.startsWith("countries/") && path.endsWith(".png")) paths += path
                    }
                    r.endArray()
                }
                r.endObject()
            }
            file.writeText(paths.joinToString("\n"))
            tree.delete()
        }
        val index = HashMap<String, MutableList<Entry>>()
        file.forEachLine { path ->
            val base = path.substringAfterLast('/').removeSuffix(".png")
            val suffix = base.substringAfterLast('-', "")
            val hasCountry = suffix.length == 2 && base.length > 3
            val slug = if (hasCountry) base.dropLast(3) else base
            index.getOrPut(slug) { ArrayList() } += Entry(if (hasCountry) suffix else "", path)
        }
        return index
    }

    private fun matchRepo(index: Map<String, List<Entry>>, ch: Channel): String? {
        val candidates = index[slug(ch.name)] ?: return null
        val code = countryCode(ch)
        val chosen = candidates.firstOrNull { code != null && it.country == code }
            ?: candidates.singleOrNull()
            ?: return null
        return LOGOS_BASE + chosen.path
    }

    /** Código de país por el grupo «[ES] …» o por el nombre «|NL| …». */
    private fun countryCode(ch: Channel): String? =
        Regex("\\[(\\w{2})]|\\|(\\w{2})\\|").find(ch.group + " " + ch.name)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.lowercase()

    /** Nombre en minúsculas, sin acentos, etiquetas ni calidad («|NL| Nickelodeon HD» → «nickelodeon»). */
    internal fun slug(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        s = s.replace(Regex("\\[[^]]*]|\\|[^|]*\\||\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\b(hd|fhd|uhd|sd|4k|8k|hevc|raw|backup|\\d{3,4}p)\\b"), " ")
        return s.replace("&", "and").replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    // ---- Utilidades ----

    private fun JsonReader.text(): String =
        if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()

    private fun age(f: File) = System.currentTimeMillis() - f.lastModified()

    private fun download(url: String, dest: File, userAgent: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", userAgent)
        val tmp = File(dest.path + ".tmp")
        try {
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.copyTo(dest, overwrite = true)
        } finally {
            tmp.delete()
            conn.disconnect()
        }
    }
}
