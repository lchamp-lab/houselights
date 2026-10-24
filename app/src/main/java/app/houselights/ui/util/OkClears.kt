package app.houselights.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** The first OK pressed within does [clear], not its repeats: held, OK is still one action and leaves what it caused. */
fun Modifier.okClears(clear: () -> Unit) = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown && it.key in OK_KEYS && it.nativeKeyEvent.repeatCount == 0) clear()
    false
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
