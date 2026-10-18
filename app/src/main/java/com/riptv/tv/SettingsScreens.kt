package com.riptv.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import java.text.DateFormat
import java.util.Date

private enum class Page {
    MAIN, LISTS, REFRESH, GROUPS, GROUP_ORDER, GROUP_CUSTOM, SORT, HIDDEN, SEARCH,
    START, ZAP, EPG, EPG_SHIFT, BUFFER, NUMBERS, TEXT_SIZE, ASPECT, PARENTAL, USER_AGENT, INFO, UPDATE,
}

private fun sortLabel(o: SortOrder) = when (o) {
    SortOrder.LIST -> "Número de canal (orden de la lista)"
    SortOrder.NAME_ASC -> "Nombre (A-Z)"
    SortOrder.NAME_DESC -> "Nombre (Z-A)"
}

private fun refreshLabel(m: RefreshMode) = when (m) {
    RefreshMode.MANUAL -> "Manual"
    RefreshMode.ON_START -> "Al iniciar la app"
    RefreshMode.DAILY -> "Cada día"
    RefreshMode.WEEKLY -> "Cada semana"
}

private fun groupOrderLabel(o: GroupOrder) = when (o) {
    GroupOrder.ALPHABETICAL -> "Alfabético (A-Z)"
    GroupOrder.LIST -> "Orden de la lista"
    GroupOrder.CUSTOM -> "Personalizado"
}

private fun textSizeLabel(t: TextSize) = when (t) {
    TextSize.NORMAL -> "Normal"
    TextSize.LARGE -> "Grande"
    TextSize.EXTRA_LARGE -> "Muy grande"
}

private fun bufferLabel(m: BufferMode) = when (m) {
    BufferMode.FAST -> "Rápida"
    BufferMode.BALANCED -> "Equilibrada"
    BufferMode.STABLE -> "Estable"
}

internal fun aspectLabel(m: AspectMode) = when (m) {
    AspectMode.FIT -> "Ajustar"
    AspectMode.ZOOM -> "Ampliar"
    AspectMode.STRETCH -> "Estirar"
}

internal fun epgShiftLabel(minutes: Int) = when {
    minutes == 0 -> "Sin ajuste"
    minutes % 60 == 0 -> "%+d h".format(minutes / 60).replace("-", "−")
    else -> "%+d min".format(minutes).replace("-", "−")
}

private fun yesNo(b: Boolean) = if (b) "Sí" else "No"

private fun dateTime(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/**
 * Ajustes: un menú con una opción por fila (título grande y valor actual debajo) y una subpantalla
 * por opción. Se maneja solo con el mando; Atrás vuelve al menú y, desde el menú, a la app.
 */
@Composable
internal fun SettingsScreen(vm: MainViewModel, onUpdate: (addNew: Boolean) -> Unit, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var focusIndex by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val toMenu = { page = Page.MAIN }
    val settings = vm.settings

    BackHandler { if (page == Page.MAIN) onClose() else toMenu() }

    when (page) {
        Page.MAIN -> SettingsMenu(
            vm = vm,
            tab = tab,
            onTab = { tab = it },
            focusIndex = focusIndex,
            onUpdate = { focusIndex = 0; onUpdate(false) },
            onOpen = { index, target -> focusIndex = index; page = target },
        )
        Page.LISTS -> ListsPage(vm, onAdd = { onUpdate(true) })
        Page.GROUPS -> GroupsPage(vm)
        Page.GROUP_CUSTOM -> CustomGroupOrderPage(vm)
        Page.HIDDEN -> HiddenPage(vm)
        Page.PARENTAL -> ParentalPage(vm, toMenu)
        Page.INFO -> InfoPage(vm)
        Page.UPDATE -> UpdatePage()
        Page.USER_AGENT -> UserAgentPage(vm, toMenu)
        Page.REFRESH -> ChoicePage(
            title = "Actualización automática",
            description = "Elige cuándo se descarga de nuevo la lista de canales.",
            options = listOf(
                Triple(RefreshMode.MANUAL, "Manual", "Solo se actualiza cuando tú lo pides desde «Actualizar lista»."),
                Triple(RefreshMode.ON_START, "Al iniciar la app", "Se descarga de nuevo cada vez que abres la app."),
                Triple(RefreshMode.DAILY, "Cada día", "Al abrir la app, si han pasado más de 24 horas desde la última actualización."),
                Triple(RefreshMode.WEEKLY, "Cada semana", "Al abrir la app, si han pasado más de 7 días desde la última actualización."),
            ),
            selected = settings.refreshMode,
            onSelect = { settings.refreshMode = it },
            onBack = toMenu,
            footer = "Si no hay conexión al actualizar, se usa la última copia guardada.",
        )
        Page.GROUP_ORDER -> ChoicePage(
            title = "Orden de los grupos",
            description = "Elige en qué orden aparecen los grupos.",
            options = listOf(
                Triple(GroupOrder.ALPHABETICAL, groupOrderLabel(GroupOrder.ALPHABETICAL), ""),
                Triple(GroupOrder.LIST, groupOrderLabel(GroupOrder.LIST), "Tal como los ordena tu proveedor."),
                Triple(GroupOrder.CUSTOM, groupOrderLabel(GroupOrder.CUSTOM), "Tú decides el orden de cada grupo."),
            ),
            selected = settings.groupOrder,
            onSelect = { settings.groupOrder = it; page = if (it == GroupOrder.CUSTOM) Page.GROUP_CUSTOM else Page.MAIN },
            onBack = toMenu,
            goBackAfter = false,
        )
        Page.SORT -> ChoicePage(
            title = "Ordenación de los canales",
            description = "Elige en qué orden se muestran los canales de cada grupo.",
            options = SortOrder.entries.map {
                Triple(it, sortLabel(it), if (it == SortOrder.LIST) "Tal como los ordena tu proveedor, normalmente por número de canal." else "")
            },
            selected = settings.sortOrder,
            onSelect = { settings.sortOrder = it },
            onBack = toMenu,
        )
        Page.SEARCH -> ChoicePage(
            title = "Dónde busca",
            description = "Elige si la búsqueda mira solo el grupo abierto o todos los grupos.",
            options = listOf(
                Triple(SearchScope.GROUP, "Solo en el grupo abierto", ""),
                Triple(SearchScope.ALL, "En todos los grupos", "Los grupos ocultos o bloqueados siguen sin aparecer."),
            ),
            selected = settings.searchScope,
            onSelect = { settings.searchScope = it },
            onBack = toMenu,
        )
        Page.START -> ChoicePage(
            title = "Al iniciar la app",
            description = "Elige qué ocurre al abrir la app.",
            options = listOf(
                Triple(StartMode.LIST, "Mostrar la lista de canales", ""),
                Triple(StartMode.LAST_CHANNEL, "Reanudar el último canal", "Se abre directamente a pantalla completa el último canal que viste."),
            ),
            selected = settings.startMode,
            onSelect = { settings.startMode = it },
            onBack = toMenu,
        )
        Page.ZAP -> ChoicePage(
            title = "Cambio de canal con las flechas",
            description = "Elige qué hacen las flechas arriba y abajo mientras ves un canal.",
            options = listOf(
                Triple(false, "Normal", "Arriba va al canal anterior y abajo al siguiente."),
                Triple(true, "Invertido", "Arriba va al canal siguiente y abajo al anterior."),
            ),
            selected = settings.invertZap,
            onSelect = { settings.invertZap = it },
            onBack = toMenu,
            footer = "También puedes teclear el número del canal con el mando.",
        )
        Page.EPG -> ChoicePage(
            title = "Guía de programación",
            description = "Muestra el programa actual y el siguiente al ver un canal, si tu proveedor ofrece la guía.",
            options = listOf(
                Triple(true, "Mostrar", ""),
                Triple(false, "No mostrar", ""),
            ),
            selected = settings.showEpg,
            onSelect = { settings.showEpg = it },
            onBack = toMenu,
        )
        Page.EPG_SHIFT -> ChoicePage(
            title = "Desfase de la guía",
            description = "Si la guía del proveedor no coincide con lo que se emite, corrígelo aquí. Si la guía va con retraso " +
                "(pone un programa que aún no ha empezado), elige un valor negativo; si va adelantada, uno positivo.",
            options = listOf(-120, -60, 0, 60, 120).map { m -> Triple(m, epgShiftLabel(m), "") },
            selected = settings.epgShiftMinutes,
            onSelect = { settings.epgShiftMinutes = it; vm.epgShiftChanged() },
            onBack = toMenu,
        )
        Page.BUFFER -> ChoicePage(
            title = "Estabilidad de la reproducción",
            description = "Elige cuánto vídeo se guarda de antemano. Más colchón aguanta mejor los cortes cuando el servidor va saturado, pero el canal tarda algo más en arrancar.",
            options = listOf(
                Triple(BufferMode.FAST, "Rápida", "Arranca enseguida (1,5 s) con poco colchón. Para cambiar de canal muy rápido."),
                Triple(BufferMode.BALANCED, "Equilibrada", "Arranca en unos 3 s. Recomendada."),
                Triple(BufferMode.STABLE, "Estable", "Espera unos 8 s al empezar pero guarda hasta un minuto de colchón. Para conexiones o servidores con cortes."),
            ),
            selected = settings.bufferMode,
            onSelect = { settings.bufferMode = it },
            onBack = toMenu,
            footer = "Se aplica al abrir el siguiente canal. Si la emisión se corta, la app reconecta sola.",
        )
        Page.ASPECT -> ChoicePage(
            title = "Formato de imagen",
            description = "Cómo se adapta la imagen del canal a la pantalla. También se cambia desde el panel del canal (flecha izquierda).",
            options = listOf(
                Triple(AspectMode.FIT, "Ajustar", "Se ve la imagen entera; puede haber barras negras."),
                Triple(AspectMode.ZOOM, "Ampliar", "Llena la pantalla recortando los bordes, sin deformar."),
                Triple(AspectMode.STRETCH, "Estirar", "Llena la pantalla deformando la imagen."),
            ),
            selected = settings.aspectMode,
            onSelect = { settings.aspectMode = it },
            onBack = toMenu,
        )
        Page.NUMBERS -> ChoicePage(
            title = "Número de canal",
            description = "Muestra el número delante del nombre en las listas de canales.",
            options = listOf(
                Triple(true, "Mostrar", "Útil para teclear el número del canal con el mando."),
                Triple(false, "No mostrar", ""),
            ),
            selected = settings.showNumbers,
            onSelect = { settings.showNumbers = it },
            onBack = toMenu,
        )
        Page.TEXT_SIZE -> ChoicePage(
            title = "Tamaño del texto",
            description = "Elige el tamaño de los textos de toda la app.",
            options = TextSize.entries.map { Triple(it, textSizeLabel(it), "") },
            selected = settings.textSize,
            onSelect = { settings.textSize = it },
            onBack = toMenu,
        )
    }
}

/** Contenedor común: título grande como encabezado y contenido con un ancho máximo cómodo de leer. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, scrollable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Column(
            Modifier
                .widthIn(max = 900.dp)
                .weight(1f)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

private val settingsTabs = listOf("Lista", "Canales", "Reproducción", "Interfaz", "Avanzado")

private class MenuEntry(val tab: Int, val title: String, val value: String, val page: Page?)

/**
 * Menú de ajustes dividido en pestañas (Lista, Canales, Reproducción, Interfaz y Avanzado) para que cada
 * una quepa en pantalla sin desplazarse. Con izquierda/derecha se cambia de pestaña y con abajo se
 * entra en sus opciones.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsMenu(
    vm: MainViewModel,
    tab: Int,
    onTab: (Int) -> Unit,
    focusIndex: Int,
    onUpdate: () -> Unit,
    onOpen: (Int, Page) -> Unit,
) {
    val s = vm.settings
    val updated = vm.lastUpdated?.let(::dateTime)
    val totalGroups = vm.orderedGroups.size
    val entries = listOf(
        MenuEntry(
            0, "Actualizar lista",
            when {
                vm.source.isBlank() -> "Sin lista. Pulsa para añadir una."
                updated != null -> "Última actualización: $updated"
                else -> "Pulsa para actualizar"
            },
            null,
        ),
        MenuEntry(
            0, "Mis listas",
            vm.lists.firstOrNull { it.id == vm.activeListId }?.let { "${it.name} · ${vm.lists.size} guardadas" } ?: "Ninguna guardada",
            Page.LISTS,
        ),
        MenuEntry(0, "Actualización automática", refreshLabel(s.refreshMode), Page.REFRESH),
        MenuEntry(0, "Desfase de la guía", epgShiftLabel(s.epgShiftMinutes), Page.EPG_SHIFT),
        MenuEntry(
            1, "Grupos a mostrar",
            if (vm.selectedGroupCount == 0) "Todos los grupos" + if (totalGroups > 0) " ($totalGroups)" else ""
            else "${vm.selectedGroupCount} de $totalGroups grupos",
            Page.GROUPS,
        ),
        MenuEntry(1, "Orden de los grupos", groupOrderLabel(s.groupOrder), Page.GROUP_ORDER),
        MenuEntry(1, "Ordenación de los canales", sortLabel(s.sortOrder), Page.SORT),
        MenuEntry(1, "Canales ocultos", if (s.hidden.isEmpty()) "Ninguno" else "${s.hidden.size} ocultos", Page.HIDDEN),
        MenuEntry(
            2, "Al iniciar la app",
            if (s.startMode == StartMode.LAST_CHANNEL) "Reanudar el último canal" else "Mostrar la lista de canales",
            Page.START,
        ),
        MenuEntry(2, "Cambio de canal con las flechas", if (s.invertZap) "Invertido" else "Normal", Page.ZAP),
        MenuEntry(2, "Guía de programación", if (s.showEpg) "Mostrar" else "No mostrar", Page.EPG),
        MenuEntry(2, "Estabilidad de la reproducción", bufferLabel(s.bufferMode), Page.BUFFER),
        MenuEntry(
            3, "Búsqueda",
            if (s.searchScope == SearchScope.ALL) "En todos los grupos" else "Solo en el grupo abierto",
            Page.SEARCH,
        ),
        MenuEntry(3, "Número de canal", if (s.showNumbers) "Mostrar" else "No mostrar", Page.NUMBERS),
        MenuEntry(3, "Tamaño del texto", textSizeLabel(s.textSize), Page.TEXT_SIZE),
        MenuEntry(3, "Formato de imagen", aspectLabel(s.aspectMode), Page.ASPECT),
        MenuEntry(
            4, "Control parental",
            if (!s.hasPin) "Desactivado" else "Activado · ${s.lockedGroups.size} grupos bloqueados",
            Page.PARENTAL,
        ),
        MenuEntry(
            4, "Agente de usuario",
            when (s.userAgent) {
                DEFAULT_USER_AGENT -> "VLC (recomendado)"
                BROWSER_USER_AGENT -> "Navegador web"
                else -> "Personalizado"
            },
            Page.USER_AGENT,
        ),
        MenuEntry(4, "Buscar actualizaciones", "Descarga e instala la última versión de RipTV", Page.UPDATE),
        MenuEntry(4, "Información y mantenimiento", "Versión, espacio usado, borrar caché y restablecer", Page.INFO),
    )
    val focus = remember { List(entries.size) { FocusRequester() } }
    val tabFocus = remember { List(settingsTabs.size) { FocusRequester() } }
    val visible = entries.withIndex().filter { it.value.tab == tab }
    // El foco vuelve a la fila desde la que se entró (si es de esta pestaña) o va a la primera de la pestaña.
    LaunchedEffect(Unit) {
        val target = if (entries.getOrNull(focusIndex)?.tab == tab) focusIndex else visible.firstOrNull()?.index
        target?.let { runCatching { focus[it].requestFocus() } }
    }

    // Izquierda/derecha sobre una opción cambian de pestaña; el foco pasa a la primera opción de la nueva.
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (!refocus) return@LaunchedEffect
        val target = entries.indexOfFirst { it.tab == tab }
        // Las filas de la lista perezosa se colocan un instante después de componerse.
        repeat(5) {
            if (runCatching { focus[target].requestFocus() }.isSuccess) {
                refocus = false
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        refocus = false
    }
    fun switchTab(delta: Int) {
        val next = tab + delta
        if (next in settingsTabs.indices) {
            refocus = true
            onTab(next)
        }
    }

    SettingsPage("Ajustes") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                settingsTabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        // Al cambiar de pestaña con izquierda/derecha la fila enfocada desaparece y el foco cae un instante en la
                        // primera pestaña: mientras se recoloca, ese foco no debe volver a seleccionarla.
                        onFocus = { if (!refocus) onTab(i) },
                        onClick = { onTab(i) },
                        modifier = Modifier.focusRequester(tabFocus[i]),
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(visible, key = { _, item -> item.value.title }) { pos, (i, e) ->
                SettingRow(
                    title = e.title,
                    value = e.value,
                    // Desde la primera fila, «arriba» vuelve a la pestaña seleccionada y no a la más cercana
                    // (como las pestañas se seleccionan al recibir el foco, si no cambiaría de pestaña).
                    modifier = Modifier
                        .focusRequester(focus[i])
                        .onPreviewKeyEvent { e ->
                            val delta = when (e.key) {
                                Key.DirectionLeft -> -1
                                Key.DirectionRight -> 1
                                else -> 0
                            }
                            if (delta == 0) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) switchTab(delta)
                            true
                        }
                        .then(if (pos == 0) Modifier.focusProperties { up = tabFocus[tab] } else Modifier),
                    onClick = { if (e.page == null) onUpdate() else onOpen(i, e.page) },
                )
            }
        }
    }
}

/** Fila de ajuste: título grande, valor actual en texto (no solo con color) y flecha de "abrir". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingRow(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleLarge) },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = { Text("›", style = MaterialTheme.typography.headlineMedium) },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * Elección única entre opciones, con descripción de cada una. Al elegir se aplica y, salvo que
 * [goBackAfter] sea false, se vuelve al menú.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun <T> ChoicePage(
    title: String,
    description: String,
    options: List<Triple<T, String, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onBack: () -> Unit,
    goBackAfter: Boolean = true,
    footer: String? = null,
) {
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val requesters = remember { List(options.size) { FocusRequester() } }
    LaunchedEffect(Unit) { requesters[selectedIndex].requestFocus() }

    SettingsPage(title, scrollable = true) {
        Text(description, style = MaterialTheme.typography.bodyLarge)
        options.forEachIndexed { i, (value, label, detail) ->
            val on = value == selected
            ListItem(
                selected = on,
                onClick = { onSelect(value); if (goBackAfter) onBack() },
                leadingContent = { Text(if (on) "●" else "○", style = MaterialTheme.typography.titleLarge) },
                headlineContent = { Text(label, style = MaterialTheme.typography.titleLarge) },
                supportingContent = if (detail.isNotEmpty()) {
                    { Text(detail, style = MaterialTheme.typography.bodyLarge) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requesters[i])
                    .semantics {
                        role = Role.RadioButton
                        this.selected = on
                        stateDescription = if (on) "Seleccionado" else "No seleccionado"
                    },
            )
        }
        footer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0B0C0)) }
    }
}

/** Marcar los grupos que se quieren ver; sin ninguno marcado se muestran todos. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupsPage(vm: MainViewModel) {
    val groups = vm.orderedGroups
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    SettingsPage("Grupos a mostrar") {
        Text(
            "Marca los grupos que quieres ver. Si no marcas ninguno, se muestran todos.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            if (vm.selectedGroupCount == 0) "Ningún grupo marcado: se muestran todos"
            else "${vm.selectedGroupCount} de ${groups.size} grupos marcados",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::showAllGroups, modifier = Modifier.focusRequester(first)) { Text("Mostrar todos") }
        }
        val selected = vm.settings.enabledGroups
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(groups, key = { it }) { g ->
                CheckRow(g, g in selected) { vm.toggleGroup(g) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CheckRow(label: String, on: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = on,
        onClick = onClick,
        leadingContent = { Text(if (on) "☑" else "☐", style = MaterialTheme.typography.titleLarge) },
        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth().semantics {
            role = Role.Checkbox
            stateDescription = if (on) "Marcado" else "Sin marcar"
        },
    )
}

/** Ordenar los grupos a mano: OK coge un grupo, ▲▼ lo mueven y OK lo suelta. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CustomGroupOrderPage(vm: MainViewModel) {
    val groups = vm.orderedGroups
    var moving by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    // El primer grupo es una fila de la lista perezosa: se espera a que se coloque para darle el foco.
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    // Si el grupo que se mueve sale de la zona visible, la lista lo sigue.
    LaunchedEffect(moving, groups) {
        val index = groups.indexOf(moving)
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
    }
    BackHandler(enabled = moving != null) { moving = null }

    SettingsPage("Orden de los grupos") {
        Text(
            if (moving == null) "Pulsa OK sobre un grupo para cogerlo, muévelo con las flechas arriba y abajo y pulsa OK para soltarlo."
            else "Moviendo «$moving»: usa las flechas arriba y abajo y pulsa OK para soltarlo.",
            style = MaterialTheme.typography.bodyLarge,
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(groups, key = { it }) { g ->
                val picked = g == moving
                ListItem(
                    selected = picked,
                    onClick = { moving = if (picked) null else g },
                    leadingContent = { Text(if (picked) "⇅" else "≡", style = MaterialTheme.typography.titleLarge) },
                    headlineContent = { Text(g, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (g == groups.firstOrNull()) Modifier.focusRequester(first) else Modifier)
                        .onPreviewKeyEvent { e ->
                            if (!picked || e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                Key.DirectionUp -> { vm.moveGroup(g, -1); true }
                                Key.DirectionDown -> { vm.moveGroup(g, 1); true }
                                else -> false
                            }
                        }
                        .semantics { stateDescription = if (picked) "Moviendo" else "" },
                )
            }
        }
    }
}

/** Lista de canales ocultos; al pulsar uno vuelve a mostrarse. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HiddenPage(vm: MainViewModel) {
    val hidden = vm.hiddenChannels
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    SettingsPage("Canales ocultos") {
        Text(
            if (hidden.isEmpty()) "No has ocultado ningún canal. Para ocultar uno, mantén pulsado OK sobre él en la lista."
            else "Pulsa un canal para volver a mostrarlo.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hidden.isNotEmpty()) Button(onClick = vm::showAllHidden, modifier = Modifier.focusRequester(first)) { Text("Mostrar todos") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(hidden, key = { it.key }) { ch ->
                ListItem(
                    selected = false,
                    onClick = { vm.unhide(ch.key) },
                    headlineContent = { Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(ch.group, style = MaterialTheme.typography.bodyMedium, maxLines = 1) },
                    trailingContent = { Text("Mostrar") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private enum class PinStep { NONE, NEW, CONFIRM }

/** Control parental: PIN de 4 dígitos y grupos bloqueados. Si ya hay PIN, hay que introducirlo para entrar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ParentalPage(vm: MainViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var verified by remember { mutableStateOf(!s.hasPin || vm.unlocked) }
    var gateError by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(PinStep.NONE) }
    var firstPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    if (!verified) {
        PinDialog("Introduce el PIN", gateError, onDismiss = onBack) { pin ->
            if (vm.unlock(pin)) verified = true else gateError = "PIN incorrecto"
        }
        return
    }
    LaunchedEffect(Unit) { first.requestFocus() }

    when (step) {
        PinStep.NEW -> PinDialog("Nuevo PIN (4 dígitos)", pinError, onDismiss = { step = PinStep.NONE }) { pin ->
            firstPin = pin
            pinError = null
            step = PinStep.CONFIRM
        }
        PinStep.CONFIRM -> PinDialog("Repite el PIN", pinError, onDismiss = { step = PinStep.NONE }) { pin ->
            if (pin == firstPin) {
                s.setPin(pin)
                step = PinStep.NONE
            } else {
                pinError = "Los PIN no coinciden"
                step = PinStep.NEW
            }
        }
        PinStep.NONE -> Unit
    }
    if (confirmRemove) {
        ActionDialog(
            title = "Quitar el PIN",
            message = "Se desactivará el control parental y se desbloquearán todos los grupos.",
            actions = listOf("Quitar PIN" to { vm.removePin() }),
            onDismiss = { confirmRemove = false },
        )
    }

    SettingsPage("Control parental") {
        if (!s.hasPin) {
            Text("Crea un PIN de 4 dígitos para bloquear los grupos que elijas. Los grupos bloqueados no aparecen hasta introducir el PIN.", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { pinError = null; step = PinStep.NEW }, modifier = Modifier.focusRequester(first)) { Text("Crear PIN") }
            }
        } else {
            Text("Marca los grupos que quieres bloquear. Se desbloquean al introducir el PIN y vuelven a bloquearse al cerrar la app.", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { pinError = null; step = PinStep.NEW }, modifier = Modifier.focusRequester(first)) { Text("Cambiar PIN") }
                Button(onClick = { confirmRemove = true }) { Text("Quitar PIN") }
            }
            val locked = s.lockedGroups
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(vm.orderedGroups, key = { it }) { g -> CheckRow(g, g in locked) { vm.toggleLocked(g) } }
            }
        }
    }
}

/** Agente de usuario con el que se descargan la lista y los canales: algunos proveedores solo aceptan ciertos. */
@Composable
private fun UserAgentPage(vm: MainViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var editing by remember { mutableStateOf(false) }
    val current = when (s.userAgent) {
        DEFAULT_USER_AGENT, BROWSER_USER_AGENT -> s.userAgent
        else -> "custom"
    }
    if (editing) {
        TextInputDialog(
            title = "Agente de usuario personalizado",
            initial = if (current == "custom") s.userAgent else "",
            onDismiss = { editing = false },
            onSave = { s.userAgent = it.ifBlank { DEFAULT_USER_AGENT }; onBack() },
        )
    }
    ChoicePage(
        title = "Agente de usuario",
        description = "Cómo se identifica la app ante el servidor. Cámbialo solo si tu proveedor rechaza la lista o los canales.",
        options = listOf(
            Triple(DEFAULT_USER_AGENT, "VLC (recomendado)", "Lo aceptan casi todos los proveedores."),
            Triple(BROWSER_USER_AGENT, "Navegador web", ""),
            Triple("custom", "Personalizado…", if (current == "custom") s.userAgent else "Escribe el que necesites."),
        ),
        selected = current,
        onSelect = { if (it == "custom") editing = true else s.userAgent = it },
        onBack = onBack,
        goBackAfter = false,
    )
}

/** Datos de la app y acciones de mantenimiento (con confirmación). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoPage(vm: MainViewModel) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    LaunchedEffect(Unit) { vm.loadAccount() }
    var confirm by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    when (confirm) {
        "cache" -> ActionDialog(
            title = "Borrar la caché",
            message = "Se borran las listas descargadas y se vuelve a descargar la lista activa.",
            actions = listOf("Borrar y descargar de nuevo" to { vm.clearCache() }),
            onDismiss = { confirm = null },
        )
        "reset" -> ActionDialog(
            title = "Restablecer ajustes",
            message = "Los ajustes vuelven a sus valores de fábrica. No se borran las listas, los favoritos, los canales ocultos ni el PIN.",
            actions = listOf("Restablecer" to { vm.settings.resetSettings() }),
            onDismiss = { confirm = null },
        )
    }

    SettingsPage("Información y mantenimiento") {
        val active = vm.lists.firstOrNull { it.id == vm.activeListId }
        val lines = listOf(
            "Versión de la app: $version",
            "Lista activa: ${active?.name ?: "ninguna"}",
            "Canales: ${vm.all.size} en ${vm.orderedGroups.size} grupos",
            "Última actualización: ${vm.lastUpdated?.let(::dateTime) ?: "—"}",
            "Favoritos: ${vm.settings.favorites.size} · Ocultos: ${vm.settings.hidden.size}",
            "Espacio de caché: %.1f MB".format(vm.cacheSize / 1_048_576.0),
        ) + listOfNotNull(
            vm.accountInfo?.let { a ->
                "Suscripción: " + when {
                    !a.active -> "no activa"
                    a.expiresAtMs == null -> "sin caducidad"
                    else -> "hasta el ${dateTime(a.expiresAtMs)}" + (if (a.trial) " (prueba)" else "")
                }
            },
            vm.accountInfo?.let { a -> a.maxConnections?.let { "Conexiones: ${a.activeConnections ?: 0} en uso de $it" } },
        )
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { confirm = "cache" }, modifier = Modifier.focusRequester(first)) { Text("Borrar caché") }
            Button(onClick = vm::clearRecents) { Text("Borrar recientes") }
            Button(onClick = { confirm = "reset" }) { Text("Restablecer ajustes") }
        }
    }
}

private sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object NoRelease : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int?) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Busca la última release en GitHub y, si es más nueva que la instalada, la descarga y lanza el instalador. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UpdatePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0" }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var permissionHint by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    suspend fun check() {
        state = UpdateState.Checking
        state = try {
            val release = Updater.latest()
            when {
                release == null -> UpdateState.NoRelease
                Updater.isNewer(release.version, version) -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: e.toString())
        }
    }

    fun install(release: Release) {
        scope.launch {
            permissionHint = false
            state = UpdateState.Downloading(release, null)
            state = try {
                val apk = Updater.download(context, release) { state = UpdateState.Downloading(release, it) }
                permissionHint = !Updater.install(context, apk)
                UpdateState.Available(release)
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: e.toString())
            }
        }
    }

    LaunchedEffect(Unit) { check() }
    // Los botones cambian con el estado: el foco va al primero que haya.
    LaunchedEffect(state) { runCatching { first.requestFocus() } }

    SettingsPage("Buscar actualizaciones") {
        Text("Versión instalada: $version", style = MaterialTheme.typography.bodyLarge)
        when (val s = state) {
            UpdateState.Checking -> Text("Buscando la última versión…", style = MaterialTheme.typography.titleMedium)
            UpdateState.UpToDate -> Text("Tienes la última versión.", style = MaterialTheme.typography.titleMedium)
            UpdateState.NoRelease -> Text("Todavía no hay ninguna versión publicada.", style = MaterialTheme.typography.titleMedium)
            is UpdateState.Available -> {
                Text("Hay una versión nueva: ${s.release.version}", style = MaterialTheme.typography.titleMedium)
                if (s.release.notes.isNotBlank()) Text(s.release.notes.take(600), style = MaterialTheme.typography.bodyMedium, maxLines = 8)
                if (permissionHint) {
                    Text(
                        "Permite a RipTV instalar aplicaciones en la pantalla que se ha abierto, vuelve atrás y pulsa de nuevo.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            is UpdateState.Downloading -> Text(
                "Descargando ${s.release.version}…" + (s.percent?.let { " $it%" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            is UpdateState.Failed -> Text("No se pudo completar: ${s.message}", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFF8A80))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = state
            if (s is UpdateState.Available) Button(onClick = { install(s.release) }, modifier = Modifier.focusRequester(first)) { Text("Descargar e instalar") }
            if (s !is UpdateState.Checking && s !is UpdateState.Downloading) {
                Button(
                    onClick = { scope.launch { check() } },
                    modifier = if (s is UpdateState.Available) Modifier else Modifier.focusRequester(first),
                ) { Text("Buscar de nuevo") }
            }
        }
    }
}

/** Listas guardadas: pulsar una ofrece usarla o eliminarla. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListsPage(vm: MainViewModel, onAdd: () -> Unit) {
    var selected by remember { mutableStateOf<SavedList?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    selected?.let { list ->
        ActionDialog(
            title = list.name,
            actions = buildList {
                if (list.id != vm.activeListId) add("Usar esta lista" to { vm.selectList(list.id) })
                add("Eliminar lista" to { vm.removeList(list.id) })
            },
            onDismiss = { selected = null },
        )
    }

    SettingsPage("Mis listas") {
        Text(
            if (vm.lists.isEmpty()) "Todavía no hay listas guardadas."
            else "Pulsa una lista para usarla o eliminarla. La lista activa está marcada.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAdd, modifier = Modifier.focusRequester(first)) { Text("Añadir lista") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(vm.lists, key = { it.id }) { list ->
                val active = list.id == vm.activeListId
                ListItem(
                    selected = active,
                    onClick = { selected = list },
                    leadingContent = { Text(if (active) "●" else "○", style = MaterialTheme.typography.titleLarge) },
                    headlineContent = { Text(list.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(if (active) "Lista activa" else "Guardada", style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        role = Role.RadioButton
                        this.selected = active
                        stateDescription = if (active) "Activa" else "No activa"
                    },
                )
            }
        }
    }
}
