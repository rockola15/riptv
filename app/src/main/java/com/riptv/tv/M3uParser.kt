package com.riptv.tv

import java.io.BufferedReader

object M3uParser {
    private val groupRegex = Regex("group-title=\"([^\"]*)\"")
    private val nameRegex = Regex("tvg-name=\"([^\"]*)\"")
    private val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")

    /** [onProgress] recibe el número de elementos leídos hasta el momento. */
    fun parse(reader: BufferedReader, onProgress: (Int) -> Unit = {}): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        var logo = ""
        reader.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    group = groupRegex.find(line)?.groupValues?.get(1).orEmpty()
                    logo = logoRegex.find(line)?.groupValues?.get(1).orEmpty()
                    val display = line.substringAfterLast(',', "").trim()
                    name = display.ifEmpty { nameRegex.find(line)?.groupValues?.get(1).orEmpty() }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    // Solo canales en directo: se descartan las películas y series de listas Xtream.
                    if ("/movie/" !in line && "/series/" !in line) {
                        out += Channel(name ?: line, group.ifEmpty { "Sin grupo" }, line, out.size + 1, logo)
                        if (out.size % 250 == 0) onProgress(out.size)
                    }
                    name = null
                    logo = ""
                }
            }
        }
        onProgress(out.size)
        return out
    }
}
