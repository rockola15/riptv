package com.riptv.tv

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Una película o una serie del catálogo del proveedor. [id] es el de la película o el de la serie. */
data class VodItem(
    val id: String,
    val name: String,
    val category: String,
    val poster: String,
    val ext: String,
    val rating: String = "",
    val genre: String = "",
    val year: String = "",
    val durationMin: Int = 0,
)

data class VodCategory(val id: String, val name: String)

data class VodCatalog(val categories: List<VodCategory>, val items: List<VodItem>)

data class Episode(val id: String, val title: String, val season: Int, val number: Int, val ext: String)

/** Ficha de una película o serie. */
data class VodInfo(
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val rating: String,
    val year: String,
    val durationSecs: Int,
    val trailer: String,
    val cover: String,
)

data class SeriesDetail(val info: VodInfo?, val episodes: List<Episode>)

/** Suscripción del proveedor: [expiresAtMs] es null si no caduca. */
data class AccountInfo(
    val expiresAtMs: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val trial: Boolean,
    val active: Boolean,
)

/** Lo que se reproduce de las secciones Películas y Series. */
data class VodPlay(val title: String, val url: String)

/**
 * Catálogo de películas y series pedido a la API del servidor, deduciendo el servidor, el usuario y la
 * contraseña de la URL de un canal de la lista (igual que la guía). Si el servidor no es de este tipo,
 * [Account.from] devuelve null y las secciones avisan de que la lista no trae contenido.
 */
object Vod {
    class Account(val base: String, val user: String, val pass: String) {
        fun movieUrl(item: VodItem) = "$base/movie/$user/$pass/${item.id}.${item.ext}"
        fun episodeUrl(ep: Episode) = "$base/series/$user/$pass/${ep.id}.${ep.ext}"

        internal fun api(action: String, extra: String = ""): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            return "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (if (action.isEmpty()) "" else "&action=$action") + extra
        }

        companion object {
            private val pattern = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)")

            fun from(streamUrl: String?): Account? {
                val match = pattern.find(streamUrl ?: return null) ?: return null
                val (base, user, pass) = match.destructured
                return Account(base, user, pass)
            }
        }
    }

    suspend fun movies(account: Account, userAgent: String): VodCatalog = withContext(Dispatchers.IO) {
        VodCatalog(
            categories(account, "get_vod_categories", userAgent),
            items(account.api("get_vod_streams"), userAgent, idKey = "stream_id", posterKey = "stream_icon"),
        )
    }

    suspend fun series(account: Account, userAgent: String): VodCatalog = withContext(Dispatchers.IO) {
        VodCatalog(
            categories(account, "get_series_categories", userAgent),
            items(account.api("get_series"), userAgent, idKey = "series_id", posterKey = "cover"),
        )
    }

    /** Ficha y episodios (ordenados por temporada y número) de una serie; null si falló la petición. */
    suspend fun seriesDetail(account: Account, seriesId: String, userAgent: String): SeriesDetail? = withContext(Dispatchers.IO) {
        try {
            val text = fetchText(account.api("get_series_info", "&series_id=$seriesId"), userAgent)
            val episodes = readEpisodes(JsonReader(StringReader(text))).sortedWith(compareBy({ it.season }, { it.number }))
            SeriesDetail(parseInfo(parseObject(text).optJSONObject("info")), episodes)
        } catch (e: Exception) {
            null
        }
    }

    /** Ficha de una película (sinopsis, reparto, valoración…); null si el servidor no la da o falló la petición. */
    suspend fun movieInfo(account: Account, vodId: String, userAgent: String): VodInfo? = withContext(Dispatchers.IO) {
        try {
            parseInfo(parseObject(fetchText(account.api("get_vod_info", "&vod_id=$vodId"), userAgent)).optJSONObject("info"))
        } catch (e: Exception) {
            null
        }
    }

    /** Datos de la suscripción (caducidad y conexiones); null si el servidor no los da o falló la petición. */
    suspend fun accountInfo(account: Account, userAgent: String): AccountInfo? = withContext(Dispatchers.IO) {
        try {
            val user = parseObject(fetchText(account.api(""), userAgent)).optJSONObject("user_info") ?: return@withContext null
            AccountInfo(
                expiresAtMs = user.optString("exp_date").toLongOrNull()?.times(1000),
                maxConnections = user.optString("max_connections").toIntOrNull(),
                activeConnections = user.optString("active_cons").toIntOrNull(),
                trial = user.optString("is_trial") == "1",
                active = user.optString("status").equals("Active", ignoreCase = true),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** El proveedor a veces entrega el JSON sin la última llave de cierre: se completa antes de darlo por inválido. */
    private fun parseObject(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: org.json.JSONException) {
        JSONObject(text.trimEnd() + "}")
    }

    private fun parseInfo(i: JSONObject?): VodInfo? {
        i ?: return null
        fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> i.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } }.orEmpty()
        return VodInfo(
            plot = s("plot", "description"),
            cast = s("cast", "actors"),
            director = s("director"),
            genre = s("genre"),
            rating = s("rating", "rating_5based").takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }.orEmpty(),
            year = s("releasedate", "releaseDate", "release_date").take(4),
            durationSecs = i.optString("duration_secs").toIntOrNull() ?: 0,
            trailer = s("youtube_trailer"),
            cover = s("cover_big", "movie_image", "cover"),
        )
    }

    private fun fetchText(url: String, userAgent: String): String = retrying {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { if (it.isBlank()) throw java.io.IOException("Respuesta vacía") }
        } finally {
            conn.disconnect()
        }
    }

    private fun categories(account: Account, action: String, userAgent: String): List<VodCategory> = try {
        open(account.api(action), userAgent) { r ->
            val out = ArrayList<VodCategory>()
            r.beginArray()
            while (r.hasNext()) {
                var id = ""
                var name = ""
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "category_id" -> id = r.text()
                    "category_name" -> name = r.text()
                    else -> r.skipValue()
                }
                r.endObject()
                if (id.isNotEmpty()) out += VodCategory(id, name.ifBlank { id })
            }
            r.endArray()
            out
        }
    } catch (e: Exception) {
        emptyList() // sin categorías se muestra todo junto
    }

    private fun items(url: String, userAgent: String, idKey: String, posterKey: String): List<VodItem> =
        open(url, userAgent) { r ->
            val out = ArrayList<VodItem>()
            r.beginArray()
            while (r.hasNext()) {
                var id = ""
                var name = ""
                var category = ""
                var poster = ""
                var rating = ""
                var genre = ""
                var year = ""
                var durationMin = 0
                var ext = "mp4"
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    idKey -> id = r.text()
                    "name" -> name = r.text()
                    "category_id" -> category = r.text()
                    posterKey -> poster = r.text()
                    "rating" -> rating = r.text()
                    "genre" -> genre = r.text()
                    "releaseDate", "releasedate" -> year = r.text().take(4)
                    "episode_run_time" -> durationMin = r.text().toIntOrNull() ?: 0
                    "container_extension" -> ext = r.text().ifBlank { "mp4" }
                    else -> r.skipValue()
                }
                r.endObject()
                if (id.isNotEmpty() && name.isNotBlank()) out += VodItem(id, name.trim(), category, poster, ext, rating.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }.orEmpty(), genre.trim(), year, durationMin)
            }
            r.endArray()
            out
        }

    /** «episodes» es un objeto por temporada ({"1": [...]}) o, según el servidor, una lista de listas. */
    private fun readEpisodes(r: JsonReader): List<Episode> {
        val out = ArrayList<Episode>()
        fun readSeason(fallbackSeason: Int) {
            r.beginArray()
            while (r.hasNext()) {
                var id = ""
                var title = ""
                var season = fallbackSeason
                var number = 0
                var ext = "mp4"
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "id" -> id = r.text()
                    "title" -> title = r.text()
                    "season" -> season = r.text().toIntOrNull() ?: fallbackSeason
                    "episode_num" -> number = r.text().toIntOrNull() ?: 0
                    "container_extension" -> ext = r.text().ifBlank { "mp4" }
                    else -> r.skipValue()
                }
                r.endObject()
                if (id.isNotEmpty()) out += Episode(id, title.ifBlank { "Episodio $number" }, season, number, ext)
            }
            r.endArray()
        }
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "episodes") { r.skipValue(); continue }
            when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) readSeason(r.nextName().toIntOrNull() ?: 1)
                    r.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    var n = 1
                    while (r.hasNext()) readSeason(n++)
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return out
    }

    /** El proveedor limita las conexiones y a veces contesta con el cuerpo vacío: se reintenta antes de dar el error. */
    private fun <T> retrying(block: () -> T): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (attempt < 2) Thread.sleep(1_200L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException()
    }

    private fun <T> open(url: String, userAgent: String, read: (JsonReader) -> T): T = retrying {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000 // el catálogo completo puede tardar en llegar
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            JsonReader(conn.inputStream.bufferedReader()).use(read)
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonReader.text(): String =
        if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()
}
