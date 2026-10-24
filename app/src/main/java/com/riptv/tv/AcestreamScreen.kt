package com.riptv.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay

/**
 * Pestaña Acestream: los partidos de Primera División con emisión Ace Stream, por día a la izquierda. Pulsar un
 * partido muestra sus enlaces; pulsar un enlace lo abre en la app Ace Stream del dispositivo.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AcestreamSection(vm: MainViewModel, onConfigure: () -> Unit) {
    val matches = vm.aceMatches
    var open by remember { mutableStateOf<AceMatch?>(null) }

    // Mismo retardo que en Películas y Series: pasar el foco por la pestaña no descarga nada.
    LaunchedEffect(Unit) {
        delay(400)
        vm.loadAcestream()
    }

    open?.let { LinksDialog(it) { open = null } }

    when {
        vm.settings.aceUrl.isBlank() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Falta la URL de la agenda de partidos.")
            Button(onClick = onConfigure) { Text("Añadir URL") }
        }
        vm.aceError != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No se pudo cargar la agenda: ${vm.aceError}")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.retryAcestream() }) { Text("Reintentar") }
                Button(onClick = onConfigure) { Text("Cambiar URL") }
            }
        }
        matches == null -> Text("Cargando partidos…")
        matches.isEmpty() -> Text("No hay partidos de Primera con emisión Acestream")
        else -> {
            val dates = matches.map { it.date }.distinct()
            val date = vm.aceDate?.takeIf { it in dates }
            val query = vm.query
            val shown = matches.filter { (query.isNotBlank() || date == null || it.date == date) && it.title.contains(query, ignoreCase = true) }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LazyColumn(Modifier.width(300.dp).fillMaxHeight()) {
                    item {
                        ListItem(selected = date == null, onClick = { vm.aceDate = null }, headlineContent = { Text("Todos") })
                    }
                    items(dates, key = { it }) { d ->
                        ListItem(selected = date == d, onClick = { vm.aceDate = d }, headlineContent = { Text(d) })
                    }
                }
                if (shown.isEmpty()) {
                    Text("Sin resultados")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { "${it.date}-${it.time}-${it.title}" }) { match ->
                            ListItem(
                                selected = false,
                                onClick = { open = match },
                                headlineContent = { Text("${match.time}  ${match.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    val day = if (date == null || query.isNotBlank()) "${match.date} · " else ""
                                    Text("$day${match.channels} · ${match.links.size} enlaces", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LinksDialog(match: AceMatch, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(820.dp).height(560.dp).background(Color(0xFF1A1A22)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(match.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${match.date} · ${match.time} · ${match.channels}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0B0C0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFC107)) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(match.links.size, key = { match.links[it].hash }) { i ->
                    val link = match.links[i]
                    ListItem(
                        selected = false,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("acestream://${link.hash}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(intent)
                                message = null
                            } catch (e: ActivityNotFoundException) {
                                message = "Instala la app Ace Stream para reproducir estos enlaces"
                            }
                        },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        headlineContent = {
                            Text(
                                link.label + if (link.stale) "  (caído)" else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (link.stale) Color(0xFF808090) else Color.Unspecified,
                            )
                        },
                    )
                }
            }
        }
    }
}
