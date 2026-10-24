package com.riptv.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Grupo virtual con los canales favoritos. */
const val FAVORITES_GROUP = "★ Favoritos"

/** Grupo virtual con los últimos canales vistos. */
const val RECENTS_GROUP = "Recientes"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PlaylistRepository(app)
    val settings = AppPrefs(app)

    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<LoadProgress?>(null); private set
    var all by mutableStateOf<List<Channel>>(emptyList()); private set

    /** Grupos de la lista en el orden en que aparecen en ella. */
    private var listGroups by mutableStateOf<List<String>>(emptyList())

    var lists by mutableStateOf(repo.lists); private set
    var activeListId by mutableStateOf(repo.active?.id); private set

    /** URL o ruta de la lista activa; vacío si no hay ninguna. */
    var source by mutableStateOf(repo.source); private set

    /** Momento (ms) de la última actualización de la lista activa, o null si no hay lista. */
    var lastUpdated by mutableStateOf(repo.lastUpdated); private set

    var group by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    /** Verdadero tras introducir el PIN: los grupos bloqueados se ven hasta cerrar la app. */
    var unlocked by mutableStateOf(false); private set

    private var playingState by mutableStateOf<Channel?>(null)

    /** Canal en reproducción; null si se está navegando. */
    var playing: Channel?
        get() = playingState
        set(value) {
            playingState = value
            value?.let { ch ->
                settings.lastChannelKey = ch.key
                settings.recents = (listOf(ch.key) + settings.recents.filter { it != ch.key }).take(MAX_RECENTS)
            }
        }

    private var startupPending = true

    // ---- Películas y series ----

    /** Pestaña de la pantalla principal: 0 = En directo, 1 = Películas, 2 = Series, 3 = Acestream. */
    var homeTab by mutableIntStateOf(0)

    /** Película o episodio en reproducción; null si no se está viendo ninguno. */
    var playingVod by mutableStateOf<VodPlay?>(null)

    var movies by mutableStateOf<VodCatalog?>(null); private set
    var seriesCatalog by mutableStateOf<VodCatalog?>(null); private set
    var moviesError by mutableStateOf<String?>(null); private set
    var seriesError by mutableStateOf<String?>(null); private set
    private var moviesLoading = false
    private var seriesLoading = false

    /** Categoría elegida y último elemento abierto en cada sección, para recuperarlos al volver del reproductor. */
    var movieCategory by mutableStateOf<String?>(null)
    var seriesCategory by mutableStateOf<String?>(null)
    var lastVodId by mutableStateOf<String?>(null)

    /** Cuenta del proveedor deducida de un canal de la lista; null si la lista no es de un servidor con catálogo. */
    val vodAccount: Vod.Account? get() = Vod.Account.from(all.firstOrNull()?.url)

    fun loadMovies() {
        if (movies != null || moviesLoading) return
        val account = vodAccount ?: run { moviesError = NO_CATALOG; return }
        moviesLoading = true
        moviesError = null
        viewModelScope.launch {
            try {
                movies = Vod.movies(account, settings.userAgent)
            } catch (e: Exception) {
                moviesError = e.message ?: e.toString()
            }
            moviesLoading = false
        }
    }

    fun loadSeries() {
        if (seriesCatalog != null || seriesLoading) return
        val account = vodAccount ?: run { seriesError = NO_CATALOG; return }
        seriesLoading = true
        seriesError = null
        viewModelScope.launch {
            try {
                seriesCatalog = Vod.series(account, settings.userAgent)
            } catch (e: Exception) {
                seriesError = e.message ?: e.toString()
            }
            seriesLoading = false
        }
    }

    /** Suscripción del proveedor (caducidad y conexiones); null mientras no se conozca. */
    var accountInfo by mutableStateOf<AccountInfo?>(null); private set

    fun loadAccount() {
        val account = vodAccount ?: return
        viewModelScope.launch { Vod.accountInfo(account, settings.userAgent)?.let { accountInfo = it } }
    }

    /** Fichas de películas ya pedidas (género y duración no vienen en la lista), por id; se piden solo las que se ven en pantalla. */
    private val movieInfoCache = mutableStateMapOf<String, VodInfo>()
    private val movieInfoGate = Semaphore(1)

    fun movieInfoFor(id: String): VodInfo? = movieInfoCache[id]

    suspend fun loadMovieInfo(id: String) {
        if (id in movieInfoCache) return
        val account = vodAccount ?: return
        movieInfoGate.withPermit {
            if (id in movieInfoCache) return
            Vod.movieInfo(account, id, settings.userAgent)?.let { movieInfoCache[id] = it }
        }
    }

    // ---- Acestream (no depende de la lista) ----

    var aceMatches by mutableStateOf<List<AceMatch>?>(null); private set
    var aceError by mutableStateOf<String?>(null); private set
    var aceDate by mutableStateOf<String?>(null)
    var aceLoading by mutableStateOf(false); private set

    fun loadAcestream() {
        val url = settings.aceUrl
        if (url.isBlank() || aceMatches != null || aceLoading) return
        aceLoading = true
        aceError = null
        viewModelScope.launch {
            try {
                aceMatches = Acestream.matches(url, settings.userAgent)
            } catch (e: Exception) {
                aceError = e.message ?: e.toString()
            }
            aceLoading = false
        }
    }

    fun retryAcestream() {
        aceError = null
        loadAcestream()
    }

    /** Cambia la URL de la agenda y la vuelve a descargar. Sin «http» delante se supone https. */
    fun applyAceUrl(url: String) {
        if (aceLoading) return
        settings.aceUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        aceMatches = null
        aceError = null
        aceDate = null
        loadAcestream()
    }

    /** Vuelve a pedir el catálogo (tras fallar o al cambiar de lista). */
    private fun resetVod() {
        movieInfoCache.clear()
        movies = null
        seriesCatalog = null
        moviesError = null
        seriesError = null
        movieCategory = null
        seriesCategory = null
        lastVodId = null
    }

    fun retryVod(series: Boolean) {
        if (series) { seriesError = null; loadSeries() } else { moviesError = null; loadMovies() }
    }

    // ---- Logos y guía por canal (se piden en segundo plano, solo para lo que se ve) ----

    /** Índices de logos; el logo de cada canal se busca solo cuando el canal se muestra en pantalla. */
    private var logoIndex by mutableStateOf<Logos.Index?>(null)
    private var logoJob: Job? = null

    fun logoFor(ch: Channel): String = ch.logo.ifBlank { logoIndex?.urlFor(ch).orEmpty() }

    /** Lo que emite un canal ahora y lo siguiente; ambos null = la guía no tiene información. */
    class NowPlaying(val current: Program?, val next: Program?, val validUntilMs: Long)

    private val nowPlayingCache = mutableStateMapOf<Int, NowPlaying>()

    fun nowPlaying(ch: Channel): NowPlaying? = nowPlayingCache[ch.number]

    private fun epgShiftMs() = settings.epgShiftMinutes * 60_000L

    /** Hay que volver a pedir lo que emite cada canal cuando cambia el desfase de la guía. */
    fun epgShiftChanged() = nowPlayingCache.clear()

    suspend fun loadNowPlaying(ch: Channel) {
        val now = System.currentTimeMillis()
        if ((nowPlayingCache[ch.number]?.validUntilMs ?: 0) > now) return
        // Si la petición falla (el servidor limita las conexiones simultáneas) se reintenta una vez.
        var programs = Epg.nowNext(ch.url, settings.userAgent, epgShiftMs())
        if (programs == null) {
            delay(1_000)
            programs = Epg.nowNext(ch.url, settings.userAgent, epgShiftMs())
        }
        val current = programs?.firstOrNull { it.startMs <= now && it.endMs > now }
        val next = programs?.firstOrNull { it.startMs > now }
        // Con información vale hasta que acabe el programa; sin ella (o tras un fallo) se vuelve a probar más tarde.
        val retryIn = if (programs == null) 30_000L else 10 * 60_000L
        nowPlayingCache[ch.number] = NowPlaying(current, next, current?.endMs ?: next?.startMs ?: (now + retryIn))
    }

    private fun loadLogoIndex(sampleUrl: String?) {
        logoJob?.cancel()
        logoIndex = null
        val id = repo.active?.id
        val agent = settings.userAgent
        logoJob = viewModelScope.launch {
            logoIndex = withContext(Dispatchers.IO) { Logos.load(getApplication<Application>().filesDir, id, sampleUrl, agent) }
        }
    }

    // Si falla la actualización al arrancar (p. ej. sin conexión), se usa la última copia guardada.
    init { reload(refresh = repo.shouldRefresh(settings.refreshMode), fallbackToCache = true) }

    // ---- Grupos ----

    /** Grupos en el orden elegido en ajustes. */
    val orderedGroups: List<String>
        get() = when (settings.groupOrder) {
            GroupOrder.LIST -> listGroups
            GroupOrder.ALPHABETICAL -> listGroups.sortedWith(String.CASE_INSENSITIVE_ORDER)
            GroupOrder.CUSTOM -> {
                val existing = listGroups.toSet()
                val custom = settings.customGroupOrder.filter { it in existing }
                custom + listGroups.filter { it !in custom }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        }

    /** Grupos marcados que existen en la lista cargada; null si no hay ninguno (se muestran todos). */
    private fun enabledSet(): Set<String>? {
        val existing = listGroups.toSet()
        return settings.enabledGroups.filterTo(HashSet()) { it in existing }.takeIf { it.isNotEmpty() }
    }

    /** Cuántos grupos de la lista actual están marcados en ajustes. */
    val selectedGroupCount: Int get() = enabledSet()?.size ?: 0

    /** Grupos que se muestran en la pantalla principal. */
    val groups: List<String>
        get() = enabledSet().let { enabled -> orderedGroups.filter { enabled == null || it in enabled } }

    fun toggleGroup(g: String) {
        val current = settings.enabledGroups
        settings.enabledGroups = if (g in current) current - g else current + g
        if (group != null && group != FAVORITES_GROUP && group != RECENTS_GROUP && group !in groups) group = null
    }

    fun showAllGroups() {
        settings.enabledGroups = emptySet()
    }

    fun moveGroup(g: String, delta: Int) {
        val order = orderedGroups.toMutableList()
        val from = order.indexOf(g)
        val to = from + delta
        if (from < 0 || to !in order.indices) return
        order.add(to, order.removeAt(from))
        settings.customGroupOrder = order
    }

    // ---- Canales ----

    private fun lockedNow(): Set<String> =
        if (unlocked || !settings.hasPin) emptySet() else settings.lockedGroups

    val visible: List<Channel>
        get() {
            val enabled = enabledSet()
            val favorites = settings.favorites
            val hidden = settings.hidden
            val locked = lockedNow()
            val favoritesView = group == FAVORITES_GROUP
            val searchAll = query.isNotBlank() && settings.searchScope == SearchScope.ALL
            if (group == RECENTS_GROUP) {
                // Los recientes van en orden de visto, no en el orden elegido para el resto de listas.
                return settings.recents.mapNotNull { key -> all.firstOrNull { it.key == key } }.filter { ch ->
                    ch.key !in hidden && ch.group !in locked && (query.isBlank() || ch.name.contains(query, ignoreCase = true))
                }
            }
            val list = all.filter { ch ->
                ch.key !in hidden && ch.group !in locked &&
                    (if (favoritesView) ch.key in favorites
                    else (searchAll || group == null || ch.group == group) && (enabled == null || ch.group in enabled)) &&
                    (query.isBlank() || ch.name.contains(query, ignoreCase = true))
            }
            return when (settings.sortOrder) {
                SortOrder.LIST -> list
                SortOrder.NAME_ASC -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                SortOrder.NAME_DESC -> list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }

    fun isFavorite(ch: Channel) = ch.key in settings.favorites

    fun toggleFavorite(ch: Channel) {
        settings.favorites = if (isFavorite(ch)) settings.favorites - ch.key else settings.favorites + ch.key
        if (group == FAVORITES_GROUP && settings.favorites.isEmpty()) group = null
    }

    fun hide(ch: Channel) {
        settings.hidden = settings.hidden + ch.key
    }

    fun unhide(key: String) {
        settings.hidden = settings.hidden - key
    }

    fun clearRecents() {
        settings.recents = emptyList()
        if (group == RECENTS_GROUP) group = null
    }

    fun showAllHidden() {
        settings.hidden = emptySet()
    }

    val hiddenChannels: List<Channel>
        get() = settings.hidden.let { hidden -> all.filter { it.key in hidden } }

    // ---- Control parental ----

    fun isGroupLocked(g: String) = g in lockedNow()

    fun unlock(pin: String): Boolean = settings.checkPin(pin).also { if (it) unlocked = true }

    fun toggleLocked(g: String) {
        settings.lockedGroups = if (g in settings.lockedGroups) settings.lockedGroups - g else settings.lockedGroups + g
    }

    fun removePin() {
        settings.setPin(null)
        unlocked = false
    }

    // ---- Reproducción ----

    private fun canWatch(ch: Channel) = ch.key !in settings.hidden && ch.group !in lockedNow()

    /** Cambia de canal dentro de la lista visible (arriba/abajo en el mando). */
    fun zap(delta: Int) {
        val list = visible
        val i = list.indexOf(playing)
        val step = if (settings.invertZap) -delta else delta
        if (i >= 0 && list.isNotEmpty()) playing = list[(i + step).mod(list.size)]
    }

    /** Salta al canal con ese número en la lista; false si no existe o no se puede ver. */
    fun zapToNumber(number: Int): Boolean {
        val ch = all.firstOrNull { it.number == number && canWatch(it) } ?: return false
        playing = ch
        return true
    }

    // ---- Listas ----

    /** Cambia la URL de la lista activa, o añade una nueva lista, y la descarga. Si una lista nueva falla, no se guarda. */
    fun applyUrl(url: String, addNew: Boolean) {
        val previousId = repo.active?.id
        if (addNew) {
            val added = repo.addList(url)
            refreshListState()
            reload(refresh = true, onFailure = {
                repo.removeList(added.id)
                previousId?.let { repo.selectList(it) }
                refreshListState()
            })
        } else {
            repo.setSource(url)
            refreshListState()
            reload(refresh = true)
        }
    }

    fun selectList(id: String) {
        repo.selectList(id)
        refreshListState()
        reload(refresh = false)
    }

    fun removeList(id: String) {
        repo.removeList(id)
        refreshListState()
        reload(refresh = false)
    }

    private fun refreshListState() {
        lists = repo.lists
        activeListId = repo.active?.id
        source = repo.source
    }

    fun clearCache() {
        repo.clearCache()
        lastUpdated = repo.lastUpdated
        if (source.isNotBlank()) reload(refresh = true)
    }

    val cacheSize: Long get() = repo.cacheSize

    /** [onFailure] se ejecuta si la carga falla o la lista no tiene canales (la lista anterior sigue cargada). */
    fun reload(refresh: Boolean, fallbackToCache: Boolean = false, onFailure: (() -> Unit)? = null) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                progress = null
                val agent = settings.userAgent
                all = withContext(Dispatchers.IO) {
                    try {
                        repo.load(refresh, agent) { progress = it }
                    } catch (e: Exception) {
                        if (!(refresh && fallbackToCache)) throw e
                        repo.load(refresh = false, agent) { progress = it }
                    }
                }.also { if (onFailure != null && it.isEmpty()) throw IllegalStateException("La lista no contiene canales") }
                listGroups = all.map { it.group }.distinct()
                group = null
                resetVod()
                accountInfo = null
                loadAccount()
                nowPlayingCache.clear()
                loadLogoIndex(all.firstOrNull()?.url)
                lastUpdated = repo.lastUpdated
                if (all.isEmpty() && repo.source.isNotBlank()) error = "La lista no contiene canales"
                resumeLastChannel()
            } catch (e: Exception) {
                onFailure?.invoke()
                error = e.message ?: e.toString()
            }
            startupPending = false
            loading = false
        }
    }

    /** Si así se pidió en ajustes, al arrancar se abre directamente el último canal visto. */
    private fun resumeLastChannel() {
        if (!startupPending || settings.startMode != StartMode.LAST_CHANNEL) return
        val key = settings.lastChannelKey ?: return
        all.firstOrNull { it.key == key && canWatch(it) }?.let { playing = it }
    }
}

private const val MAX_RECENTS = 20

private const val NO_CATALOG = "Esta lista no incluye películas ni series (el servidor no ofrece catálogo)."
