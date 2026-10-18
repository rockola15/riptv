package com.riptv.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val dialogColor = Color(0xFF1A1A22)

/** Diálogo con un título y una columna de botones; el primero recibe el foco. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ActionDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    message: String? = null,
    focusCancel: Boolean = false,
) {
    val first = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (focusCancel) cancel else first).requestFocus() }
    // El menú puede abrirse mientras se mantiene pulsado OK: esa misma pulsación (repeticiones y soltar) no debe
    // activar un botón. Se ignora el OK hasta que se suelta, o como mucho 0,7 s.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(700)
        armed = true
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .background(dialogColor)
                .padding(32.dp)
                .onPreviewKeyEvent { e ->
                    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (isOk && !armed) {
                        if (e.type == KeyEventType.KeyUp) armed = true
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            actions.forEachIndexed { i, (label, action) ->
                Button(
                    onClick = { onDismiss(); action() },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                ) { Text(label) }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().focusRequester(cancel)) { Text("Cancelar") }
        }
    }
}

/**
 * Teclado numérico para introducir un PIN de 4 dígitos con el mando (también acepta las teclas
 * numéricas). Al completar el cuarto dígito llama a [onComplete]; [error] se muestra y vacía el PIN.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PinDialog(title: String, error: String?, onDismiss: () -> Unit, onComplete: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    LaunchedEffect(error) { if (error != null) pin = "" }

    fun add(d: Int) {
        if (pin.length >= 4) return
        pin += d
        if (pin.length == 4) onComplete(pin)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(420.dp)
                .background(dialogColor)
                .padding(32.dp)
                .onKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    val digit = when (code) {
                        in 7..16 -> code - 7
                        in 144..153 -> code - 144
                        else -> null
                    }
                    if (e.type == KeyEventType.KeyDown && digit != null) { add(digit); true } else false
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                List(4) { if (it < pin.length) "●" else "○" }.joinToString("  "),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { contentDescription = "PIN: ${pin.length} de 4 dígitos" },
            )
            Text(error ?: " ", color = Color(0xFFFF8A80))
            listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { c, d ->
                        Button(
                            onClick = { add(d) },
                            modifier = Modifier.width(88.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                        ) { Text("$d") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { pin = pin.dropLast(1) }, modifier = Modifier.width(88.dp)) { Text("⌫") }
                Button(onClick = { add(0) }, modifier = Modifier.width(88.dp)) { Text("0") }
                Button(onClick = onDismiss, modifier = Modifier.width(88.dp)) { Text("Salir") }
            }
        }
    }
}

/** Diálogo de una sola línea de texto con Guardar / Cancelar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(720.dp).background(dialogColor).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Box(Modifier.fillMaxWidth().background(Color(0xFF22222A)).padding(16.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDismiss(); onSave(text.trim()) }) { Text("Guardar") }
                Button(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    }
}
