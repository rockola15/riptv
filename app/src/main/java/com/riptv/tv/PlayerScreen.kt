package com.riptv.tv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Reproductor con la configuración de la app (red, colchón y decodificadores), compartido por canales y películas. */
@OptIn(UnstableApi::class)
internal fun createPlayer(context: Context, settings: AppPrefs): ExoPlayer {
    // OkHttp en lugar del cliente básico de Android: reutiliza conexiones, sigue las redirecciones entre
    // http y https y reintenta solo si una conexión reutilizada resulta estar cerrada.
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    val http = OkHttpDataSource.Factory(client).setUserAgent(settings.userAgent)
    // Más colchón de vídeo = más aguante ante los microcortes de un servidor saturado.
    val buffer = settings.bufferMode
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(buffer.minMs, buffer.maxMs, buffer.startMs, buffer.rebufferMs)
        .build()
    return ExoPlayer.Builder(context)
        .setRenderersFactory(AppRenderersFactory(context))
        .setLoadControl(loadControl)
        // Antes de dar un error por perdido un fragmento, reintenta varias veces con espera creciente.
        .setMediaSourceFactory(DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8)))
        .build()
        .apply { playWhenReady = true }
}

/**
 * Reproductor a pantalla completa. Mando: arriba/abajo cambian de canal, los números saltan a un
 * canal, OK pausa o reanuda y muestra la barra inferior; izquierda/derecha también la muestran y, una vez
 * visible, mueven el foco entre sus botones.
 */
@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
internal fun PlayerScreen(vm: MainViewModel) {
    val channel = vm.playing ?: return
    val context = LocalContext.current
    val player = remember { createPlayer(context, vm.settings) }
    var buffering by remember { mutableStateOf(true) }
    // Fallos seguidos desde la última vez que el canal se reprodujo bien; 0 = todo en orden.
    var attempt by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var panelOpen by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var programs by remember(channel) { mutableStateOf<List<Program>>(emptyList()) }
    var interaction by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    BackHandler { vm.playing = null }
    BackHandler(enabled = controlsVisible && !panelOpen && !showSchedule) { controlsVisible = false }
    BackHandler(enabled = panelOpen) { panelOpen = false }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) attempt = 0
            }

            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }

            override fun onPlayerError(error: PlaybackException) { attempt++ }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(channel) {
        attempt = 0
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
    }
    // Avisos de que hay red: si se había perdido la conexión, se reconecta en cuanto vuelve en vez de esperar.
    val networkBack = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkBack.tryEmit(Unit) }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val registered = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
        onDispose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    // Reconexión automática tras un fallo: espera 1, 2, 4 y luego 8 s entre intentos, sin límite de intentos,
    // pero reintenta antes si la red vuelve durante la espera.
    LaunchedEffect(attempt) {
        if (attempt > 0) {
            val wait = 1_000L shl (attempt - 1).coerceAtMost(3)
            if (withTimeoutOrNull(wait) { networkBack.first() } != null) delay(500) // margen para que la red se estabilice
            vm.playing?.let {
                player.setMediaItem(MediaItem.fromUri(it.url))
                player.prepare()
            }
        }
    }
    // Vigilante: si lleva 20 s cargando sin avanzar (conexión colgada), se reconecta.
    LaunchedEffect(buffering, channel, attempt) {
        if (buffering) {
            delay(20_000)
            if (player.playbackState == Player.STATE_BUFFERING) attempt++
        }
    }
    LaunchedEffect(channel) {
        if (vm.settings.showEpg) programs = Epg.nowNext(channel.url, vm.settings.userAgent, vm.settings.epgShiftMinutes * 60_000L).orEmpty()
    }
    // El cartel con el canal se oculta solo a los segundos y reaparece al pulsar cualquier tecla.
    LaunchedEffect(channel, interaction) {
        bannerVisible = true
        delay(3_000)
        bannerVisible = false
    }
    // Número de canal tecleado: salta a él cuando se deja de pulsar o con OK.
    fun goToTypedNumber() {
        digits.toIntOrNull()?.let { vm.zapToNumber(it) }
        digits = ""
    }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1_800)
            goToTypedNumber()
        }
    }
    // La barra se oculta sola a los segundos sin pulsar nada (no mientras hay un panel o la programación abiertos).
    LaunchedEffect(controlsVisible, controlsTick, panelOpen, showSchedule) {
        if (controlsVisible && !panelOpen && !showSchedule) {
            delay(3_000)
            controlsVisible = false
        }
    }
    // El foco va a la barra mientras se ve y vuelve a la pantalla al ocultarse (o al cerrar un panel).
    LaunchedEffect(controlsVisible, panelOpen, showSchedule) {
        if (panelOpen || showSchedule) return@LaunchedEffect
        runCatching { if (controlsVisible) playFocus.requestFocus() else focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) controlsTick++
                false
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || panelOpen) return@onKeyEvent false
                interaction++
                val code = e.nativeKeyEvent.keyCode
                val digit = when (code) {
                    in 7..16 -> code - 7
                    in 144..153 -> code - 144
                    else -> null
                }
                when {
                    digit != null -> { digits = (digits + digit).takeLast(4); true }
                    e.key == Key.DirectionUp || e.key == Key.ChannelUp -> { vm.zap(-1); true }
                    e.key == Key.DirectionDown || e.key == Key.ChannelDown -> { vm.zap(1); true }
                    // Con la barra visible, izquierda/derecha mueven el foco entre sus botones.
                    (e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.Menu) && !controlsVisible -> {
                        controlsVisible = true
                        true
                    }
                    e.key == Key.DirectionCenter || e.key == Key.Enter -> {
                        if (digits.isNotEmpty()) goToTypedNumber()
                        else {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { PlayerView(it).apply { this.player = player; useController = false; keepScreenOn = true } },
            update = {
                it.resizeMode = when (vm.settings.aspectMode) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            },
        )
        if (bannerVisible || panelOpen) Banner(channel, programs, Modifier.align(Alignment.TopStart))
        if (attempt > 0 || buffering) {
            Text(
                if (attempt > 0) "Reconectando… (intento $attempt)" else "Cargando…",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.BottomCenter).padding(48.dp).background(Color(0xCC000000)).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        if (digits.isNotEmpty()) {
            Text(
                "Canal $digits",
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).background(Color(0xCC000000)).padding(16.dp),
            )
        }
        if (controlsVisible && !panelOpen) {
            PlayerControls(
                isPlaying = isPlaying,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onAudio = { panelOpen = true },
                playFocus = playFocus,
                modifier = Modifier.align(Alignment.BottomCenter),
                onEpg = { showSchedule = true },
                onAspect = { cycleAspect(vm, context) },
                onPrev = { vm.zap(-1) },
                onNext = { vm.zap(1) },
                favorite = vm.isFavorite(channel),
                onFavorite = { vm.toggleFavorite(channel) },
                onHide = {
                    // Si el canal oculto es el que suena, se pasa al siguiente (o se sale si no hay otro).
                    vm.zap(1)
                    if (vm.playing == channel) vm.playing = null
                    vm.hide(channel)
                },
            )
        }
        if (showSchedule) ScheduleDialog(vm, channel) { showSchedule = false }
        if (panelOpen) {
            TracksPanel(player, tracks, vm, Modifier.align(Alignment.CenterEnd))
        }
    }
}

private fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Banner(channel: Channel, programs: List<Program>, modifier: Modifier) {
    // IntrinsicSize.Max: el cartel mide lo que el texto más ancho y la barra de progreso se ajusta a ese ancho.
    Column(
        modifier.padding(24.dp).background(Color(0xCC000000)).padding(16.dp).width(IntrinsicSize.Max),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${channel.number} · ${channel.name}", style = MaterialTheme.typography.titleLarge)
        programs.getOrNull(0)?.let {
            Text("Ahora: ${it.title} (${clock(it.startMs)} - ${clock(it.endMs)})", style = MaterialTheme.typography.bodyLarge)
            if (it.startMs <= System.currentTimeMillis()) ProgramProgress(it, Modifier.fillMaxWidth())
        }
        programs.getOrNull(1)?.let {
            Text("Después: ${it.title} (${clock(it.startMs)})", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private class TrackOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

private fun trackLabel(f: Format, index: Int): String {
    val language = f.language?.takeIf { it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ?.replaceFirstChar { it.uppercase() }
        ?.takeIf { it.isNotBlank() }
    val base = f.label ?: language ?: "Pista ${index + 1}"
    return if (f.channelCount > 2) "$base · ${f.channelCount} canales" else base
}

/** Pistas reproducibles del tipo [type] (audio o texto) con lo necesario para elegirlas. */
private fun trackOptions(player: ExoPlayer, tracks: Tracks, type: Int): List<TrackOption> {
    var n = 0
    return tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it, true) }.map { i ->
            TrackOption(trackLabel(group.getTrackFormat(i), n++), group.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
        }
    }
}

/** Panel lateral: pista de audio y subtítulos de lo que se está viendo. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TracksPanel(
    player: ExoPlayer,
    tracks: Tracks,
    vm: MainViewModel,
    modifier: Modifier,
) {
    val audio = trackOptions(player, tracks, C.TRACK_TYPE_AUDIO)
    val subtitles = trackOptions(player, tracks, C.TRACK_TYPE_TEXT)
    val noSubtitles = TrackOption("Desactivados", subtitles.none { it.selected }) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(
        modifier.width(460.dp).fillMaxHeight().background(Color(0xE6101014)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Audio", style = MaterialTheme.typography.titleMedium) }
        if (audio.isEmpty()) {
            val unsupported = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
            item {
                Text(
                    if (unsupported) "El audio de este canal no es compatible con este dispositivo"
                    else "Este canal no ofrece pistas de audio",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        itemsIndexed(audio) { i, o ->
            PanelRow(o.label, o.selected, if (i == 0) Modifier.focusRequester(first) else Modifier) { o.onSelect() }
        }
        item { Text("Subtítulos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        item {
            PanelRow(noSubtitles.label, noSubtitles.selected, if (audio.isEmpty()) Modifier.focusRequester(first) else Modifier) { noSubtitles.onSelect() }
        }
        items(subtitles) { o -> PanelRow(o.label, o.selected) { o.onSelect() } }
    }
}

/** Fila del panel; [selected] null = no es una opción de elección única. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelRow(label: String, selected: Boolean?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = selected == true,
        onClick = onClick,
        leadingContent = selected?.let { { Text(if (it) "●" else "○") } },
        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
        modifier = modifier.fillMaxWidth().let {
            if (selected == null) it else it.semantics {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "Seleccionado" else "No seleccionado"
            }
        },
    )
}
