package com.riptv.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/**
 * Búsqueda por texto: se ve como un botón con el texto actual y solo al pulsar OK pasa a ser un campo
 * editable (y sale el teclado). Con «Buscar» del teclado o Atrás se cierra la edición y el foco
 * vuelve al botón.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SearchField(vm: MainViewModel, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler(enabled = editing) { editing = false }
    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        } else if (wasEditing) {
            buttonFocus.requestFocus()
        }
        wasEditing = editing
    }

    if (editing) {
        // Si el foco sale del campo (p. ej. con las flechas) se cierra la edición.
        var focused by remember { mutableStateOf(false) }
        Box(modifier.width(280.dp).background(Color(0xFF22222A)).padding(12.dp)) {
            BasicTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { editing = false }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        if (it.isFocused) focused = true
                        else if (focused) editing = false
                    },
            )
        }
    } else {
        Button(onClick = { editing = true }, modifier = modifier.width(280.dp).focusRequester(buttonFocus)) {
            if (vm.query.isEmpty()) Text("Buscar…", color = Color.Gray)
            else Text(vm.query, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Búsqueda por voz con el reconocedor del sistema; el texto reconocido pasa a ser la búsqueda. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VoiceSearchButton(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.query = it }
    }
    Button(
        modifier = modifier.width(48.dp).semantics { contentDescription = "Buscar por voz" },
        contentPadding = PaddingValues(0.dp),
        onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre del canal")
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "Este dispositivo no admite la búsqueda por voz", Toast.LENGTH_LONG).show()
            }
        },
    ) { Icon(MicIcon, contentDescription = null, modifier = Modifier.size(20.dp)) }
}

/** Micrófono (trazo del icono «mic» de Material), dibujado a mano para no añadir la librería de iconos. */
private val MicIcon: ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
            "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28" +
            "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
    ),
    fill = SolidColor(Color.Black),
).build()
