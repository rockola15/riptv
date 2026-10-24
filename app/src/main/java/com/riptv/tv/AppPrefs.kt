package com.riptv.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.security.MessageDigest
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Orden de los canales: el de la lista (su número de canal) o alfabético. */
enum class SortOrder { LIST, NAME_ASC, NAME_DESC }

/** Orden de los grupos: alfabético, el de la lista o uno elegido a mano. */
enum class GroupOrder { ALPHABETICAL, LIST, CUSTOM }

enum class RefreshMode { MANUAL, ON_START, DAILY, WEEKLY }

enum class StartMode { LIST, LAST_CHANNEL }

enum class SearchScope { GROUP, ALL }

/** Cómo se ajusta la imagen del canal a la pantalla. */
enum class AspectMode { FIT, ZOOM, STRETCH }

/** Cuánto colchón de vídeo se guarda antes de reproducir: más colchón aguanta mejor los microcortes. */
enum class BufferMode(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int) {
    FAST(8_000, 20_000, 1_500, 3_000),
    BALANCED(15_000, 40_000, 3_000, 6_000),
    STABLE(30_000, 60_000, 8_000, 15_000),
}

enum class TextSize(val scale: Float) { NORMAL(1f), LARGE(1.25f), EXTRA_LARGE(1.5f) }

const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

/** Propiedad observable por Compose que guarda su valor en las preferencias al cambiar. */
private class PrefState<T>(initial: T, private val save: (T) -> Unit) : ReadWriteProperty<Any?, T> {
    private var state by mutableStateOf(initial)
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state = value
        save(value)
    }
}

/** Todos los ajustes de la app. Al ser observables, la interfaz se actualiza sola al cambiarlos. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("riptv", Context.MODE_PRIVATE)

    private fun <T> state(initial: T, save: (T) -> Unit) = PrefState(initial, save)

    private inline fun <reified E : Enum<E>> enumState(key: String, default: E) =
        state(enumValues<E>().firstOrNull { it.name == prefs.getString(key, null) } ?: default) {
            prefs.edit().putString(key, it.name).apply()
        }

    private fun boolState(key: String, default: Boolean) =
        state(prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }

    private fun setState(key: String) =
        state(prefs.getStringSet(key, emptySet()).orEmpty().toSet()) { prefs.edit().putStringSet(key, it).apply() }

    private fun stringState(key: String, default: String?) =
        state(prefs.getString(key, default)) {
            prefs.edit().apply { if (it == null) remove(key) else putString(key, it) }.apply()
        }

    // Lista
    var refreshMode by state(
        when {
            prefs.contains("refresh_mode") ->
                RefreshMode.entries.firstOrNull { it.name == prefs.getString("refresh_mode", null) } ?: RefreshMode.MANUAL
            prefs.getBoolean("refresh_on_start", false) -> RefreshMode.ON_START // ajuste de versiones anteriores
            else -> RefreshMode.MANUAL
        },
    ) { prefs.edit().putString("refresh_mode", it.name).apply() }
    var userAgent: String by state(prefs.getString("user_agent", null) ?: DEFAULT_USER_AGENT) {
        prefs.edit().putString("user_agent", it).apply()
    }

    /** Página de la que se leen los partidos de la pestaña Acestream; vacía hasta que el usuario la pone. */
    var aceUrl: String by state(prefs.getString("ace_url", null).orEmpty()) { prefs.edit().putString("ace_url", it).apply() }

    // Canales y grupos
    var sortOrder by enumState("sort_order", SortOrder.LIST)
    var groupOrder by enumState("group_order", GroupOrder.ALPHABETICAL)
    var customGroupOrder: List<String> by state(
        prefs.getString("custom_group_order", null)?.let { json ->
            JSONArray(json).let { arr -> List(arr.length()) { arr.getString(it) } }
        } ?: emptyList(),
    ) { prefs.edit().putString("custom_group_order", JSONArray(it).toString()).apply() }
    var enabledGroups by setState("enabled_groups")
    var favorites by setState("favorites")
    var hidden by setState("hidden_channels")

    // Búsqueda, zapping y aspecto
    var searchScope by enumState("search_scope", SearchScope.GROUP)
    var startMode by enumState("start_mode", StartMode.LIST)
    var bufferMode by enumState("buffer_mode", BufferMode.BALANCED)
    var lastChannelKey: String? by stringState("last_channel", null)
    var aspectMode by enumState("aspect_mode", AspectMode.FIT)
    /** Claves de los últimos canales vistos, el más reciente primero. */
    var recents: List<String> by state(
        prefs.getString("recents", null)?.let { json -> JSONArray(json).let { arr -> List(arr.length()) { arr.getString(it) } } } ?: emptyList(),
    ) { prefs.edit().putString("recents", JSONArray(it).toString()).apply() }
    var invertZap by boolState("invert_zap", false)
    var showNumbers by boolState("show_numbers", false)
    var showEpg by boolState("show_epg", true)
    /** Minutos que se suman a las horas de la guía (negativo si la guía del proveedor va con retraso). */
    var epgShiftMinutes by state(prefs.getInt("epg_shift_minutes", 0)) { prefs.edit().putInt("epg_shift_minutes", it).apply() }
    var textSize by enumState("text_size", TextSize.NORMAL)

    // Control parental
    var lockedGroups by setState("locked_groups")
    private var pinHash: String? by stringState("pin_hash", null)

    val hasPin: Boolean get() = pinHash != null

    fun checkPin(pin: String) = pinHash == hash(pin)

    fun setPin(pin: String?) {
        pinHash = pin?.let(::hash)
        if (pin == null) lockedGroups = emptySet()
    }

    private fun hash(pin: String) =
        MessageDigest.getInstance("SHA-256").digest("riptv:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Vuelve a los valores de fábrica los ajustes de uso; no toca listas, favoritos, ocultos ni el PIN. */
    fun resetSettings() {
        refreshMode = RefreshMode.MANUAL
        userAgent = DEFAULT_USER_AGENT
        sortOrder = SortOrder.LIST
        groupOrder = GroupOrder.ALPHABETICAL
        customGroupOrder = emptyList()
        enabledGroups = emptySet()
        searchScope = SearchScope.GROUP
        startMode = StartMode.LIST
        bufferMode = BufferMode.BALANCED
        invertZap = false
        showNumbers = false
        showEpg = true
        epgShiftMinutes = 0
        textSize = TextSize.NORMAL
        aspectMode = AspectMode.FIT
    }
}
