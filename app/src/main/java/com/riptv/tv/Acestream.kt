package com.riptv.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Un enlace Ace Stream de un partido; [label] es el nombre que le da la web (calidad y origen). */
data class AceLink(val hash: String, val label: String) {
    /** La web deja en la lista enlaces marcados como caídos; se muestran los últimos. */
    val stale: Boolean get() = label.contains("DETENIDO", ignoreCase = true) || label.contains("disabled", ignoreCase = true)
}

/** Un partido de Primera División con las emisiones disponibles. [date] es «dd/MM/yyyy». */
data class AceMatch(val date: String, val time: String, val title: String, val channels: String, val links: List<AceLink>)

/**
 * Partidos de Primera División con enlaces Ace Stream. La página (su URL la pone el usuario en ajustes) no tiene
 * API: es una página estática con una tabla por día, así que se lee el HTML. Si cambia su maquetación hay que
 * ajustar las expresiones de aquí.
 */
object Acestream {
    private val token = Regex(
        "<h2 class='fecha'>\\s*(\\d{2}/\\d{2}/\\d{4})|<tr>\\s*<td data-label=\"Hora\">(.*?)</td>(.*?)</tr>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val competition = Regex("data-label=\"Competición\">(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val event = Regex("data-label=\"Evento\">(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val channels = Regex("data-label=\"Canales\"><span>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private val link = Regex("<a href='acestream://([0-9a-fA-F]{40})'>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val tags = Regex("<[^>]*>")

    suspend fun matches(url: String, userAgent: String): List<AceMatch> = withContext(Dispatchers.IO) {
        parse(download(url, userAgent))
    }

    internal fun parse(html: String): List<AceMatch> {
        var date = ""
        val out = ArrayList<AceMatch>()
        for (m in token.findAll(html)) {
            if (m.groupValues[1].isNotEmpty()) {
                date = m.groupValues[1]
                continue
            }
            val row = m.groupValues[3]
            if (text(competition.find(row)?.groupValues?.get(1)) != "Primera") continue
            val links = link.findAll(row)
                .map { AceLink(it.groupValues[1].lowercase(), text(it.groupValues[2])) }
                .distinctBy { it.hash }
                .sortedBy { it.stale } // estable: dentro de cada grupo se mantiene el orden de la web
                .toList()
            if (links.isEmpty()) continue
            out += AceMatch(
                date = date,
                time = text(m.groupValues[2]),
                title = text(event.find(row)?.groupValues?.get(1)),
                channels = text(channels.find(row)?.groupValues?.get(1)),
                links = links,
            )
        }
        return out
    }

    /** Texto plano de un trozo de HTML: sin etiquetas, con las entidades habituales y los espacios compactados. */
    private fun text(html: String?): String = tags.replace(html.orEmpty(), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun download(url: String, userAgent: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
