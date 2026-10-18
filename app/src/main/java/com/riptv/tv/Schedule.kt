package com.riptv.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private const val HORIZON_HOURS = 36

/** Programación del canal para hoy y mañana, con el programa en emisión marcado. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ScheduleDialog(vm: MainViewModel, channel: Channel, onDismiss: () -> Unit) {
    var programs by remember { mutableStateOf<List<Program>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(channel) {
        var result = Epg.schedule(channel.url, vm.settings.userAgent, HORIZON_HOURS, vm.settings.epgShiftMinutes * 60_000L)
        if (result == null) {
            delay(1_000)
            result = Epg.schedule(channel.url, vm.settings.userAgent, HORIZON_HOURS, vm.settings.epgShiftMinutes * 60_000L)
        }
        failed = result == null
        programs = result.orEmpty()
    }
    // El foco empieza en el programa en emisión (o el primero) para poder desplazarse con el mando.
    LaunchedEffect(programs) {
        if (programs.isNullOrEmpty()) return@LaunchedEffect
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(860.dp).fillMaxHeight(0.88f).background(Color(0xFF1A1A22)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Programación · ${channel.name}",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            val list = programs
            when {
                list == null -> Text("Cargando programación…", style = MaterialTheme.typography.bodyLarge)
                list.isEmpty() -> Text(
                    if (failed) "No se pudo cargar la programación. Inténtalo de nuevo en un momento."
                    else "Este canal no tiene información de programación.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> {
                    // Se intercalan cabeceras de día («Hoy», «Mañana») entre los programas.
                    val entries = remember(list) {
                        buildList<Any> {
                            var lastDay = ""
                            for (p in list) {
                                val day = dayLabel(p.startMs)
                                if (day != lastDay) { add(day); lastDay = day }
                                add(p)
                            }
                        }
                    }
                    val now = System.currentTimeMillis()
                    val focusTarget = entries.filterIsInstance<Program>().let { all -> all.firstOrNull { it.startMs <= now && it.endMs > now } ?: all.firstOrNull() }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(entries) { e ->
                            if (e is String) {
                                Text(
                                    e,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFFB0B0C0),
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
                                )
                            } else if (e is Program) {
                                val onAir = e.startMs <= now && e.endMs > now
                                ListItem(
                                    selected = onAir,
                                    onClick = {},
                                    headlineContent = { Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            "${formatClock(e.startMs)} - ${formatClock(e.endMs)}" + if (onAir) " · En emisión" else "",
                                            maxLines = 1,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().then(if (e == focusTarget) Modifier.focusRequester(first) else Modifier),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** «Hoy», «Mañana», «Ayer» o la fecha, según el día de [ms] en la zona horaria del dispositivo. */
private fun dayLabel(ms: Long): String {
    fun dayStart(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = Math.round((dayStart(ms) - dayStart(System.currentTimeMillis())) / 86_400_000.0).toInt()
    return when (days) {
        -1 -> "Ayer"
        0 -> "Hoy"
        1 -> "Mañana"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }
}
