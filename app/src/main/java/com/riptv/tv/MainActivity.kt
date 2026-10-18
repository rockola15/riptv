package com.riptv.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si la última vez la app se cerró por un error, se enseña antes de arrancar (sin tocar el ViewModel).
        val lastCrash = CrashReport.consume(this)
        setContent {
            var crash by remember { mutableStateOf(lastCrash) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    val report = crash
                    if (report != null) CrashScreen(report) { crash = null } else AppContent(vm) { finish() }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppContent(vm: MainViewModel, onExit: () -> Unit) {
    val density = LocalDensity.current
    var showSettings by remember { mutableStateOf(false) }
    // null = ventana cerrada; false = actualizar la lista activa; true = añadir otra lista.
    var updateDialog by remember { mutableStateOf<Boolean?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    // Atrás en la pantalla principal pide confirmación, para no salir de la app sin querer.
    BackHandler(enabled = vm.playing == null && vm.playingVod == null && !showSettings && updateDialog == null) { confirmExit = true }
    if (confirmExit) {
        ActionDialog(
            title = "¿Salir de la aplicación?",
            actions = listOf("Salir" to onExit),
            onDismiss = { confirmExit = false },
            focusCancel = true,
        )
    }
    // Al terminar la carga inicial sin canales, se abre directamente la ventana para cargar una lista.
    LaunchedEffect(vm.loading) {
        if (!vm.loading && vm.all.isEmpty()) updateDialog = false
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * vm.settings.textSize.scale),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
            when {
                vm.playing != null -> PlayerScreen(vm)
                vm.playingVod != null -> VodPlayerScreen(vm)
                showSettings -> SettingsScreen(vm, { addNew -> updateDialog = addNew }) { showSettings = false }
                else -> BrowseScreen(vm) { showSettings = true }
            }
            updateDialog?.let { addNew -> UpdateListDialog(vm, addNew) { updateDialog = null } }
        }
    }
}

/** Pantalla que se muestra tras un cierre inesperado, con el motivo, para poder comunicarlo. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CrashScreen(report: String, onContinue: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("La app se cerró por un error", style = MaterialTheme.typography.headlineLarge)
        Text("Este es el motivo (cuéntaselo a quien mantiene la app):", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onContinue, modifier = Modifier.focusRequester(first)) { Text("Continuar") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A22)).padding(16.dp)) {
            items(report.lines().take(40)) { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val s = vm.settings
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    var pinGroup by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var scheduleChannel by remember { mutableStateOf<Channel?>(null) }

    menuChannel?.let { ch ->
        ActionDialog(
            title = ch.name,
            actions = listOf(
                (if (vm.isFavorite(ch)) "Quitar de favoritos" else "Añadir a favoritos") to { vm.toggleFavorite(ch) },
                "Ver programación" to { scheduleChannel = ch },
                "Ocultar canal" to { vm.hide(ch) },
            ),
            onDismiss = { menuChannel = null },
        )
    }
    scheduleChannel?.let { ch -> ScheduleDialog(vm, ch) { scheduleChannel = null } }
    pinGroup?.let { g ->
        PinDialog("Grupo bloqueado", pinError, onDismiss = { pinGroup = null; pinError = null }) { pin ->
            if (vm.unlock(pin)) {
                pinGroup = null
                pinError = null
                vm.group = g
            } else {
                pinError = "PIN incorrecto"
            }
        }
    }

    // La búsqueda de una pestaña no se arrastra a otra (pero sí se conserva al volver del reproductor).
    val shownTab = remember { arrayOf(vm.homeTab) }
    LaunchedEffect(vm.homeTab) {
        if (shownTab[0] != vm.homeTab) {
            shownTab[0] = vm.homeTab
            vm.query = ""
        }
    }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        HomeTabs(vm, onSettings)
        // Altura fija para que el contenido no salte al cambiar de pestaña.
        Box(Modifier.heightIn(min = 28.dp).padding(top = 8.dp)) {
            val warning = subscriptionWarning(vm.accountInfo)
            if (warning != null) {
                Text(warning, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFC107))
            } else if (vm.homeTab == 0) {
                Text(
                    "Mantén pulsado OK sobre un canal para sus opciones",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0C0),
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            when {
                vm.loading -> LoadingStatus(vm.progress, Modifier.width(480.dp))
                vm.homeTab != 0 -> VodSection(vm, series = vm.homeTab == 2)
                vm.error != null -> Text("Error: ${vm.error}")
                vm.all.isEmpty() -> Text(
                    if (vm.source.isBlank()) "No hay ninguna lista. Ve a Ajustes → Actualizar lista para añadir una."
                    else "La lista no contiene canales",
                )
                else -> {
                    val groups = vm.groups
                    // Al volver del reproductor se recupera el grupo y el canal que se estaban viendo.
                    val extraItems = (if (s.favorites.isNotEmpty()) 1 else 0) + (if (s.recents.isNotEmpty()) 1 else 0)
                    val groupIndex = when (val g = vm.group) {
                        null -> 0
                        FAVORITES_GROUP -> 1
                        RECENTS_GROUP -> if (s.favorites.isNotEmpty()) 2 else 1
                        else -> groups.indexOf(g).takeIf { it >= 0 }?.let { it + 1 + extraItems } ?: 0
                    }
                    val groupState = rememberLazyListState(initialFirstVisibleItemIndex = groupIndex)
                    val channelList = vm.visible
                    val restoreKey = remember { s.lastChannelKey }
                    val channelState = rememberLazyListState(
                        initialFirstVisibleItemIndex = channelList.indexOfFirst { it.key == restoreKey }.coerceAtLeast(0),
                    )
                    // Al elegir otro grupo la lista de canales vuelve al principio (no al abrir la pantalla).
                    val shownGroup = remember { arrayOf(vm.group) }
                    LaunchedEffect(vm.group) {
                        if (shownGroup[0] != vm.group) {
                            shownGroup[0] = vm.group
                            channelState.scrollToItem(0)
                        }
                    }
                    val restoreFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        if (channelList.any { it.key == restoreKey }) runCatching { restoreFocus.requestFocus() }
                    }
                    LazyColumn(Modifier.width(300.dp).fillMaxHeight(), state = groupState) {
                        item {
                            ListItem(
                                selected = vm.group == null,
                                onClick = { vm.group = null },
                                headlineContent = { Text("Todos") },
                            )
                        }
                        if (s.favorites.isNotEmpty()) {
                            item {
                                ListItem(
                                    selected = vm.group == FAVORITES_GROUP,
                                    onClick = { vm.group = FAVORITES_GROUP },
                                    headlineContent = { Text(FAVORITES_GROUP) },
                                )
                            }
                        }
                        if (s.recents.isNotEmpty()) {
                            item {
                                ListItem(
                                    selected = vm.group == RECENTS_GROUP,
                                    onClick = { vm.group = RECENTS_GROUP },
                                    headlineContent = { Text(RECENTS_GROUP) },
                                )
                            }
                        }
                        items(groups, key = { it }) { g ->
                            val locked = vm.isGroupLocked(g)
                            ListItem(
                                selected = vm.group == g,
                                onClick = { if (locked) { pinError = null; pinGroup = g } else vm.group = g },
                                headlineContent = { Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (locked) {
                                    { Text("🔒 Bloqueado · pide el PIN", maxLines = 1) }
                                } else null,
                            )
                        }
                    }
                    val list = channelList
                    LazyColumn(Modifier.fillMaxSize(), state = channelState) {
                        items(list, key = { it.number }) { ch ->
                            val prefix = (if (s.showNumbers) "${ch.number}  " else "") + (if (vm.isFavorite(ch)) "★ " else "")
                            // La guía solo se pide para los canales que se están viendo en pantalla.
                            LaunchedEffect(ch.number, s.showEpg, s.epgShiftMinutes) {
                                if (s.showEpg) {
                                    delay(250)
                                    vm.loadNowPlaying(ch)
                                }
                            }
                            val now = vm.nowPlaying(ch)
                            // OK corto = reproducir; OK mantenido = opciones. Se gestiona aquí con las
                            // teclas (y no con la pulsación larga de la librería) para que el resultado sea el mismo con cualquier
                            // mando y no se reproduzca el canal al soltar tras abrir el menú.
                            var pressed by remember { mutableStateOf(false) }
                            var heldHandled by remember { mutableStateOf(false) }
                            ListItem(
                                selected = false,
                                onClick = { vm.playing = ch },
                                onLongClick = { menuChannel = ch },
                                modifier = (if (ch.key == restoreKey) Modifier.focusRequester(restoreFocus) else Modifier).onPreviewKeyEvent { e ->
                                    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                                    when {
                                        isOk && e.type == KeyEventType.KeyDown -> {
                                            if (e.nativeKeyEvent.repeatCount == 0) {
                                                pressed = true
                                                heldHandled = false
                                            } else if (pressed && !heldHandled) {
                                                heldHandled = true
                                                menuChannel = ch
                                            }
                                            true
                                        }
                                        isOk && e.type == KeyEventType.KeyUp -> {
                                            val short = pressed && !heldHandled
                                            pressed = false
                                            heldHandled = false
                                            if (short) vm.playing = ch
                                            true
                                        }
                                        else -> false
                                    }
                                },
                                leadingContent = { ChannelLogo(vm.logoFor(ch), ch.number, s.textSize.scale) },
                                headlineContent = { Text(prefix + ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    when {
                                        !s.showEpg -> Text(ch.group, maxLines = 1)
                                        now == null -> Text("Cargando guía…", maxLines = 1)
                                        now.current == null && now.next == null -> Text("Sin información de la guía", maxLines = 1)
                                        else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                now.current?.let { "Ahora: ${it.title}" } ?: "Ahora: sin información",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            now.current?.let { ProgramProgress(it) }
                                            now.next?.let {
                                                Text(
                                                    "Después: ${it.title} · ${formatClock(it.startMs)}",
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Aviso si la suscripción está inactiva, caducada o caduca en menos de una semana; null si todo va bien. */
internal fun subscriptionWarning(info: AccountInfo?, now: Long = System.currentTimeMillis()): String? {
    info ?: return null
    if (!info.active) return "Tu suscripción no está activa"
    val expires = info.expiresAtMs ?: return null
    val days = Math.ceil((expires - now) / 86_400_000.0).toInt()
    return when {
        days < 0 -> "Tu suscripción ha caducado"
        days == 0 -> "Tu suscripción caduca hoy"
        days <= 7 -> "Tu suscripción caduca en $days ${if (days == 1) "día" else "días"}"
        else -> null
    }
}

private val homeTabs = listOf("En directo", "Películas", "Series")

/** Barra superior: las secciones de contenido, la búsqueda y, a la derecha del todo, Ajustes. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeTabs(vm: MainViewModel, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Botones normales en vez de TabRow: la navegación con el mando es la de cualquier otra fila y el texto
        // se pinta con los colores del botón (con TabRow el texto de la pestaña enfocada quedaba blanco sobre blanco).
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            homeTabs.forEachIndexed { i, label ->
                val selected = vm.homeTab == i
                Button(
                    onClick = { vm.homeTab = i },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) Color(0xFF4A4A5C) else Color.Transparent,
                        contentColor = if (selected) Color.White else Color(0xFFC8C8D4),
                    ),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.width(16.dp))
        // Con el texto grande las pestañas necesitan sitio: el campo de búsqueda se estrecha.
        SearchField(vm, Modifier.width(if (vm.settings.textSize.scale > 1f) 200.dp else 280.dp))
        Spacer(Modifier.width(12.dp))
        VoiceSearchButton(vm)
        Spacer(Modifier.width(24.dp))
        Button(onClick = onSettings) { Text("Ajustes") }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UpdateListDialog(vm: MainViewModel, addNew: Boolean, onDismiss: () -> Unit) {
    val current = vm.lists.firstOrNull { it.id == vm.activeListId }
    // Sin ninguna lista guardada, «actualizar» y «añadir» son lo mismo.
    val adding = addNew || current == null
    var text by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var qrAddress by remember { mutableStateOf<String?>(null) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    // El foco empieza en un botón para que el teclado no se abra hasta pulsar el campo.
    val applyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { applyFocus.requestFocus() }

    fun apply(url: String) {
        if (vm.loading) return
        val clean = url.trim()
        if (clean.isEmpty()) {
            inputError = "Escribe la URL o la ruta de la lista"
            return
        }
        inputError = null
        text = clean
        applying = true
        vm.applyUrl(clean, addNew)
    }

    // El servidor recibe en un hilo propio; la carga se lanza desde el hilo principal.
    val server = remember {
        val main = Handler(Looper.getMainLooper())
        UrlServer { url -> main.post { apply(url) } }
    }
    DisposableEffect(Unit) { onDispose { server.close() } }
    LaunchedEffect(Unit) {
        val address = server.address
        when {
            address == null -> serverError = "No hay conexión a una red local para usar el QR."
            !server.start() -> serverError = "No se pudo iniciar el servidor local para el QR."
            else -> qrAddress = address
        }
    }

    // Al terminar de cargar sin error, se cierra sola; con error, queda el mensaje.
    LaunchedEffect(applying, vm.loading) {
        if (applying && !vm.loading && vm.error == null) onDismiss()
    }

    Dialog(onDismissRequest = { if (!vm.loading) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(820.dp).background(Color(0xFF1A1A22)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Cabecera: qué se está haciendo y, si se actualiza una lista existente, volver a descargarla tal cual.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (adding) (if (current == null) "Añade tu lista" else "Añadir otra lista") else "Actualizar la lista",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (!adding) Text("Lista actual: ${current?.name}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0C0))
                }
                if (!adding) {
                    Button(
                        onClick = { if (!vm.loading) { inputError = null; applying = true; vm.reload(refresh = true) } },
                        modifier = Modifier.focusRequester(applyFocus),
                    ) { Text("Descargar de nuevo") }
                }
            }

            // Opción 1: desde el móvil o el PC.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                qrAddress?.let { QrCode(it, size = 170.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (qrAddress != null) {
                        Text("Desde el móvil", style = MaterialTheme.typography.titleMedium)
                        Text("Escanea el QR y envía ahí la URL de la lista.", style = MaterialTheme.typography.bodyLarge)
                        Text("¿No puedes escanear? Abre esta dirección en el navegador:", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0C0))
                        Text(qrAddress.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(serverError.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Opción 2: con el mando. El campo ocupa varias líneas para que se vea la URL entera.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Con el mando: escribe o pega la URL o la ruta de la lista", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).background(Color(0xFF22222A)).padding(14.dp)) {
                        if (text.isEmpty()) Text("http://…", color = Color.Gray)
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it.replace("\n", "") },
                            enabled = !vm.loading,
                            minLines = 2,
                            maxLines = 3,
                            textStyle = TextStyle(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        onClick = { apply(text) },
                        modifier = if (adding) Modifier.focusRequester(applyFocus) else Modifier,
                    ) { Text(if (adding) "Añadir" else "Aplicar") }
                }
            }

            when {
                vm.loading -> LoadingStatus(vm.progress)
                inputError != null -> Text(inputError.orEmpty(), color = Color(0xFFFF8A80))
                applying && vm.error != null -> Text("Error: ${vm.error}", color = Color(0xFFFF8A80))
            }
        }
    }
}

/**
 * Barra fina con lo que lleva emitido el programa; el porcentaje también se dice para lectores de pantalla.
 * Usa el color del texto de la fila (claro sobre fondo oscuro, oscuro sobre la fila seleccionada, que es clara)
 * para que siempre se distinga: pista tenue y avance en color pleno.
 */
@Composable
internal fun ProgramProgress(program: Program, modifier: Modifier = Modifier.fillMaxWidth(0.6f)) {
    val total = (program.endMs - program.startMs).coerceAtLeast(1)
    val fraction = ((System.currentTimeMillis() - program.startMs).toFloat() / total).coerceIn(0f, 1f)
    val color = LocalContentColor.current
    Box(
        modifier
            .height(4.dp)
            .background(color.copy(alpha = 0.3f))
            .semantics { contentDescription = "Lleva emitido el ${(fraction * 100).toInt()} por ciento" },
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
    }
}

/**
 * Logo del canal, cuadrado y del alto de la fila (crece con el tamaño del texto). Mientras carga, si no
 * hay logo o si falla la descarga, muestra el número del canal.
 */
@Composable
private fun ChannelLogo(url: String, number: Int, scale: Float) {
    val size = 64.dp * scale
    val shape = RoundedCornerShape(8.dp)
    @Composable
    fun NumberBadge() {
        Box(Modifier.fillMaxSize().background(Color(0xFF34343F)), contentAlignment = Alignment.Center) {
            Text("$number", maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
    Box(Modifier.size(size).clip(shape)) {
        if (url.isBlank()) {
            NumberBadge()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                loading = { NumberBadge() },
                error = { NumberBadge() },
                success = { SubcomposeAsyncImageContent(Modifier.fillMaxSize().background(Color(0xFFEDEDF2)).padding(4.dp)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Barra y texto con el porcentaje y los elementos cargados. */
@Composable
private fun LoadingStatus(progress: LoadProgress?, modifier: Modifier = Modifier) {
    val percent = progress?.percent
    val label = if (progress == null) "Cargando lista…" else {
        val what = when (progress.phase) {
            LoadProgress.Phase.DOWNLOAD -> "Descargando lista…"
            LoadProgress.Phase.PARSE -> "Cargando canales…"
        }
        what + (percent?.let { " $it%" } ?: "") + if (progress.items > 0) " · ${progress.items} elementos" else ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label)
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF33333D))) {
            if (percent != null) {
                Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(Color.White))
            }
        }
    }
}

/** Dibuja [text] como código QR (negro sobre blanco, con margen). */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier.size(size).background(Color.White).padding(12.dp)) {
        val cell = this.size.width / matrix.width
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            if (matrix[x, y]) {
                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
