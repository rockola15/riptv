package com.riptv.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Reproductor de películas y episodios. Mando: OK pausa o reanuda y muestra la barra inferior, izquierda/derecha
 * también la muestran y mueven el foco entre sus botones (retroceso y avance de 10 s, rápido si se mantiene
 * pulsado), arriba/abajo saltan 1 minuto y Atrás vuelve a la lista.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VodPlayerScreen(vm: MainViewModel) {
    val item = vm.playingVod ?: return
    val context = LocalContext.current
    val player = remember { createPlayer(context, vm.settings) }
    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var panelOpen by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    BackHandler { vm.playingVod = null }
    BackHandler(enabled = panelOpen) { panelOpen = false }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }


            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) failed = null
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                paused = !playing && player.playbackState == Player.STATE_READY
            }

            override fun onPlayerError(error: PlaybackException) { failed = error.errorCodeName }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(item) {
        failed = null
        player.setMediaItem(MediaItem.fromUri(item.url))
        player.prepare()
    }
    // La barra se oculta sola a los segundos, salvo en pausa o con el panel de audio abierto, y reaparece al pulsar una tecla.
    LaunchedEffect(interaction, paused, panelOpen) {
        overlay = true
        if (!paused && !panelOpen) {
            delay(3_000)
            overlay = false
        }
    }
    // El foco va a la barra mientras se ve y vuelve a la pantalla al ocultarse (o al cerrar el panel).
    LaunchedEffect(overlay, panelOpen) {
        if (panelOpen) return@LaunchedEffect
        runCatching { if (overlay) playFocus.requestFocus() else focus.requestFocus() }
    }
    LaunchedEffect(overlay) {
        while (overlay) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    fun seek(deltaMs: Long) {
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, end))
        position = player.currentPosition
    }

    fun togglePlayback() {
        when {
            failed != null -> { failed = null; player.prepare() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) interaction++
                false
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || panelOpen) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter -> { togglePlayback(); true }
                    // Con la barra visible, izquierda/derecha mueven el foco entre sus botones.
                    Key.DirectionLeft, Key.DirectionRight, Key.Menu -> !overlay
                    Key.DirectionUp -> { seek(60_000); true }
                    Key.DirectionDown -> { seek(-60_000); true }
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
        if (overlay && !panelOpen) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(start = 48.dp, end = 48.dp, top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF33333D))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
                    }
                    Text(
                        "${formatDuration(position)} / ${formatDuration(duration)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFBBBBBB),
                    )
                }
                PlayerControls(
                    isPlaying = isPlaying,
                    onPlayPause = { togglePlayback() },
                    onAudio = { panelOpen = true },
                    playFocus = playFocus,
                    onAspect = { cycleAspect(vm, context) },
                    seekBy = { seek(it) },
                )
            }
        }
        if (panelOpen) {
            TracksPanel(player, tracks, vm, Modifier.align(Alignment.CenterEnd))
        }
        val status = when {
            failed != null -> "No se puede reproducir ($failed). Pulsa OK para reintentar."
            buffering -> "Cargando…"
            else -> null
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
