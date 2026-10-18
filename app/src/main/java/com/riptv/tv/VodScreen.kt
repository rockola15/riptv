package com.riptv.tv

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay

/**
 * Contenido de las pestañas Películas y Series: categorías a la izquierda y títulos a la derecha. El catálogo
 * se pide al abrir la pestaña (no antes) y se guarda hasta cambiar de lista.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VodSection(vm: MainViewModel, series: Boolean) {
    val catalog = if (series) vm.seriesCatalog else vm.movies
    val error = if (series) vm.seriesError else vm.moviesError
    val noun = if (series) "series" else "películas"
    var openSeries by remember { mutableStateOf<VodItem?>(null) }
    var openMovie by remember { mutableStateOf<VodItem?>(null) }

    // Un pequeño retardo evita descargar el catálogo entero solo por pasar el foco por la pestaña.
    LaunchedEffect(series) {
        delay(400)
        if (series) vm.loadSeries() else vm.loadMovies()
    }

    openSeries?.let { show ->
        EpisodesDialog(vm, show) { openSeries = null }
    }
    openMovie?.let { movie ->
        MovieDialog(vm, movie) { openMovie = null }
    }

    when {
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(error)
            Button(onClick = { vm.retryVod(series) }) { Text("Reintentar") }
        }
        catalog == null -> Text("Cargando $noun…")
        catalog.items.isEmpty() -> Text("No hay $noun en esta lista")
        else -> VodBrowser(vm, catalog, series) { item ->
            if (series) openSeries = item else openMovie = item
        }
    }
}

private fun play(vm: MainViewModel, item: VodItem) {
    val account = vm.vodAccount ?: return
    vm.lastVodId = item.id
    vm.playingVod = VodPlay(item.name, account.movieUrl(item))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VodBrowser(vm: MainViewModel, catalog: VodCatalog, series: Boolean, onOpen: (VodItem) -> Unit) {
    val category = if (series) vm.seriesCategory else vm.movieCategory
    val query = vm.query
    // Con una búsqueda se mira en todas las categorías, como la búsqueda «en todos los grupos» de En directo.
    val shown = remember(catalog, category, query) {
        catalog.items.filter { (query.isNotBlank() || category == null || it.category == category) && it.name.contains(query, ignoreCase = true) }
    }
    val restoreId = remember { vm.lastVodId }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = shown.indexOfFirst { it.id == restoreId }.coerceAtLeast(0))
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (shown.any { it.id == restoreId }) runCatching { restoreFocus.requestFocus() }
    }
    // Al elegir otra categoría la lista vuelve al principio (no al abrir la pantalla).
    val shownCategory = remember { arrayOf(category) }
    LaunchedEffect(category) {
        if (shownCategory[0] != category) {
            shownCategory[0] = category
            listState.scrollToItem(0)
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (catalog.categories.isNotEmpty()) {
            LazyColumn(Modifier.width(300.dp).fillMaxHeight()) {
                item {
                    ListItem(
                        selected = category == null,
                        onClick = { setCategory(vm, series, null) },
                        headlineContent = { Text("Todas") },
                    )
                }
                items(catalog.categories, key = { it.id }) { c ->
                    ListItem(
                        selected = category == c.id,
                        onClick = { setCategory(vm, series, c.id) },
                        headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            Text("Sin resultados")
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(shown, key = { it.id }) { item ->
                    // La ficha (género, duración) se pide solo para lo que se ve en pantalla, sin saturar al proveedor.
                    if (!series) {
                        LaunchedEffect(item.id) {
                            delay(300)
                            vm.loadMovieInfo(item.id)
                        }
                    }
                    val details = itemDetails(item, if (series) null else vm.movieInfoFor(item.id))
                    ListItem(
                        selected = false,
                        onClick = { onOpen(item) },
                        modifier = if (item.id == restoreId) Modifier.focusRequester(restoreFocus) else Modifier,
                        leadingContent = { Poster(item.poster, vm.settings.textSize.scale) },
                        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = details?.let { line -> { Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    )
                }
            }
        }
    }
}

private fun setCategory(vm: MainViewModel, series: Boolean, id: String?) {
    if (series) vm.seriesCategory = id else vm.movieCategory = id
}

/** Cartel vertical; si no hay o falla la descarga, un recuadro liso. */
@Composable
private fun Poster(url: String, scale: Float) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(width = 56.dp * scale, height = 84.dp * scale).clip(shape).background(Color(0xFF34343F))) {
        if (url.isNotBlank()) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = {},
                error = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Temporadas y episodios de una serie; pulsar un episodio lo reproduce. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesDialog(vm: MainViewModel, show: VodItem, onDismiss: () -> Unit) {
    val account = vm.vodAccount
    val detail by produceState<SeriesDetail?>(null, show) {
        value = if (account == null) SeriesDetail(null, emptyList()) else Vod.seriesDetail(account, show.id, vm.settings.userAgent) ?: SeriesDetail(null, emptyList())
    }
    val loaded = detail?.episodes
    val first = remember { FocusRequester() }
    LaunchedEffect(loaded) {
        if (!loaded.isNullOrEmpty()) runCatching { first.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(820.dp).height(560.dp).background(Color(0xFF1A1A22)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(show.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.info?.let { info ->
                metaLine(info)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0C0)) }
                if (info.plot.isNotBlank()) Text(info.plot, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when {
                loaded == null -> Text("Cargando episodios…")
                loaded.isEmpty() -> Text("No se pudieron cargar los episodios de esta serie")
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    var lastSeason = -1
                    loaded.forEachIndexed { index, ep ->
                        if (ep.season != lastSeason) {
                            lastSeason = ep.season
                            item(key = "season-${ep.season}") {
                                Text(
                                    "Temporada ${ep.season}",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }
                        item(key = "ep-${ep.id}") {
                            ListItem(
                                selected = false,
                                onClick = {
                                    account?.let {
                                        vm.lastVodId = show.id
                                        vm.playingVod = VodPlay("${show.name} · T${ep.season}E${ep.number}", it.episodeUrl(ep))
                                    }
                                    onDismiss()
                                },
                                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                headlineContent = { Text("${ep.number}. ${ep.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Detalles de una fila de la lista: lo que dé la lista del proveedor, completado con la ficha si ya llegó. */
private fun itemDetails(item: VodItem, info: VodInfo?): String? {
    val titleYear = Regex("(?:19|20)\\d\\d").findAll(item.name).lastOrNull()?.value.orEmpty()
    return metaLine(
        VodInfo(
            plot = "", cast = "", director = "",
            genre = info?.genre?.takeIf { it.isNotBlank() } ?: item.genre,
            rating = info?.rating?.takeIf { it.isNotBlank() } ?: item.rating,
            year = info?.year?.takeIf { it.isNotBlank() } ?: item.year.ifBlank { titleYear },
            durationSecs = info?.durationSecs?.takeIf { it > 0 } ?: (item.durationMin * 60),
            trailer = "", cover = "",
        ),
    )
}

/** «2023 · 1 h 29 min · Documental · ★ 7.1», con lo que haya; null si no hay nada. */
private fun metaLine(info: VodInfo): String? {
    val duration = info.durationSecs.takeIf { it > 0 }?.let { secs ->
        val minutes = secs / 60
        if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
    return listOfNotNull(
        info.year.takeIf { it.isNotBlank() },
        duration,
        info.genre.takeIf { it.isNotBlank() },
        info.rating.toDoubleOrNull()?.let { "★ " + "%.1f".format(java.util.Locale.US, it).removeSuffix(".0") },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** Ficha de una película con su sinopsis, reparto y valoración; desde aquí se reproduce. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MovieDialog(vm: MainViewModel, item: VodItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val account = vm.vodAccount
    val state by produceState<Pair<Boolean, VodInfo?>>(false to null, item) {
        value = true to account?.let { Vod.movieInfo(it, item.id, vm.settings.userAgent) }
    }
    val (loaded, info) = state
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
    val poster = info?.cover?.takeIf { it.isNotBlank() } ?: item.poster

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.width(900.dp).height(360.dp).background(Color(0xFF1A1A22)).padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(Modifier.size(width = 200.dp, height = 300.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF34343F))) {
                if (poster.isNotBlank()) {
                    SubcomposeAsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        loading = {},
                        error = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when {
                    !loaded -> Text("Cargando ficha…", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0C0))
                    info == null -> Text("Este título no tiene ficha.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0C0))
                    else -> {
                        metaLine(info)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB0B0C0)) }
                        if (info.director.isNotBlank()) Text("Dirección: ${info.director}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (info.cast.isNotBlank()) Text("Reparto: ${info.cast}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (info.plot.isNotBlank()) Text(info.plot, style = MaterialTheme.typography.bodyMedium, maxLines = 7, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onDismiss(); play(vm, item) }, modifier = Modifier.focusRequester(play)) { Text("Reproducir") }
                    info?.trailer?.takeIf { it.isNotBlank() }?.let { trailer ->
                        Button(onClick = {
                            val url = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Tráiler") }
                    }
                }
            }
        }
    }
}
