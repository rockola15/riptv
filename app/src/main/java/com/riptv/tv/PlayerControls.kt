package com.riptv.tv

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor

/** Salto de una pulsación corta en películas y series. */
internal const val TAP_SEEK_MS = 10_000L

/** Salto de cada paso mientras se mantiene pulsado (cada [HOLD_SEEK_INTERVAL_MS]): retroceso o avance rápido. */
private const val HOLD_SEEK_MS = 30_000L
private const val HOLD_SEEK_INTERVAL_MS = 250L

private val ButtonSize = 52.dp
private val MainButtonSize = 68.dp

/** Pasa a la siguiente relación de aspecto y avisa de cuál es, ya que la barra solo lleva iconos. */
internal fun cycleAspect(vm: MainViewModel, context: Context) {
    val next = AspectMode.entries[(vm.settings.aspectMode.ordinal + 1) % AspectMode.entries.size]
    vm.settings.aspectMode = next
    Toast.makeText(context, "Formato de imagen: ${aspectLabel(next)}", Toast.LENGTH_SHORT).show()
}

/**
 * Barra de reproducción inferior, solo con iconos. De izquierda a derecha: programación, relación de aspecto,
 * audio, anterior, reproducir/pausa, siguiente, favorito y ocultar canal. Los botones cuyo callback es null
 * no se muestran.
 *
 * Con [seekBy] (películas y series) anterior/siguiente retroceden/adelantan 10 s con una pulsación y de
 * forma rápida manteniendo pulsado; sin él (canales en directo) cambian de canal con [onPrev] y [onNext].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onAudio: () -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onEpg: (() -> Unit)? = null,
    onAspect: (() -> Unit)? = null,
    seekBy: ((Long) -> Unit)? = null,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onEpg?.let { ControlButton(EpgIcon, "Programación", it) }
        onAspect?.let { ControlButton(AspectIcon, "Relación de aspecto", it) }
        ControlButton(AudioIcon, "Audio y subtítulos", onAudio)
        Spacer(Modifier.width(22.dp))
        if (seekBy != null) {
            SeekButton(RewindIcon, "Retroceder 10 segundos", -1, seekBy)
        } else {
            ControlButton(PreviousIcon, "Canal anterior", onPrev)
        }
        ControlButton(
            if (isPlaying) PauseIcon else PlayIcon,
            if (isPlaying) "Pausa" else "Reproducir",
            onPlayPause,
            Modifier.focusRequester(playFocus),
            main = true,
        )
        if (seekBy != null) {
            SeekButton(ForwardIcon, "Adelantar 10 segundos", 1, seekBy)
        } else {
            ControlButton(NextIcon, "Canal siguiente", onNext)
        }
        if (onFavorite != null || onHide != null) Spacer(Modifier.width(22.dp))
        onFavorite?.let {
            ControlButton(
                if (favorite) StarIcon else StarBorderIcon,
                if (favorite) "Quitar de favoritos" else "Añadir a favoritos",
                it,
                tint = if (favorite) Color(0xFFFFC107) else null,
            )
        }
        onHide?.let { ControlButton(HideIcon, "Ocultar canal", it) }
    }
}

/** Botones circulares translúcidos que se rellenan de blanco al enfocarse; el de reproducir/pausa es mayor. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun controlColors(main: Boolean) = ButtonDefaults.colors(
    containerColor = if (main) Color(0x40FFFFFF) else Color(0x26FFFFFF),
    contentColor = Color.White,
    focusedContainerColor = Color.White,
    focusedContentColor = Color.Black,
    pressedContainerColor = Color(0xFFDDDDDD),
    pressedContentColor = Color.Black,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    main: Boolean = false,
    tint: Color? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(if (main) MainButtonSize else ButtonSize).semantics { contentDescription = description },
        shape = ButtonDefaults.shape(CircleShape),
        colors = controlColors(main),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: LocalContentColor.current, modifier = Modifier.size(if (main) MainButtonSize else ButtonSize).padding(if (main) 15.dp else 12.dp))
    }
}

/**
 * Botón de salto: una pulsación corta salta 10 s; mantenido, salta de 30 s en 30 s cada cuarto de segundo.
 * Las teclas OK se gestionan aquí (no con onClick) para distinguir la pulsación corta de la mantenida.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeekButton(icon: ImageVector, description: String, direction: Int, seekBy: (Long) -> Unit) {
    var held by remember { mutableStateOf(false) }
    var lastHoldSeek by remember { mutableLongStateOf(0L) }
    Button(
        onClick = { seekBy(direction * TAP_SEEK_MS) },
        modifier = Modifier
            .size(ButtonSize)
            .semantics { contentDescription = description }
            .onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount > 0) {
                            held = true
                            val now = SystemClock.uptimeMillis()
                            if (now - lastHoldSeek >= HOLD_SEEK_INTERVAL_MS) {
                                lastHoldSeek = now
                                seekBy(direction * HOLD_SEEK_MS)
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (!held) seekBy(direction * TAP_SEEK_MS)
                        held = false
                        true
                    }
                    else -> false
                }
            },
        shape = ButtonDefaults.shape(CircleShape),
        colors = controlColors(false),
        contentPadding = PaddingValues(0.dp),
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonSize).padding(12.dp)) }
}

// Iconos de Material dibujados a mano para no añadir la librería de iconos.
private fun materialIcon(path: String): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()

private val PlayIcon = materialIcon("M8,5v14l11,-7z")
private val PauseIcon = materialIcon("M6,19h4V5H6v14zM14,5v14h4V5h-4z")
private val PreviousIcon = materialIcon("M6,6h2v12H6zM9.5,12l8.5,6V6z")
private val NextIcon = materialIcon("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
private val RewindIcon = materialIcon("M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z")
private val ForwardIcon = materialIcon("M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z")
private val AudioIcon = materialIcon("M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
private val AspectIcon = materialIcon(
    "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z",
)
private val EpgIcon = materialIcon(
    "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5z" +
        "M4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,19h14v-2H7v2zM7,13h14v-2H7v2zM7,5v2h14V5H7z",
)
private val StarIcon = materialIcon("M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z")
private val StarBorderIcon = materialIcon(
    "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
)
private val HideIcon = materialIcon(
    "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
        "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
        "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
        "M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
)
