package app.houselights.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.houselights.ui.design.HlMotion
import kotlinx.coroutines.delay

/** The first OK pressed within does [clear], not its repeats: held, OK is still one action and leaves what it caused. */
fun Modifier.okClears(clear: () -> Unit) = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown && it.key in OK_KEYS && it.nativeKeyEvent.repeatCount == 0) clear()
    false
}

/**
 * OK does nothing for [HlMotion.SETTLE] once [shown] changes, nor the release of a press given meanwhile: a second OK
 * given too soon, meant for what was there before, never chooses what has just come under focus unseen.
 */
@Composable
fun Modifier.calmAfter(shown: Any?): Modifier {
    val calm = remember { Calm() }
    LaunchedEffect(shown) {
        calm.on = true
        delay(HlMotion.SETTLE)
        calm.on = false
    }
    return onPreviewKeyEvent { event ->
        if (event.key !in OK_KEYS) return@onPreviewKeyEvent false
        when {
            event.type == KeyEventType.KeyDown && (calm.on || calm.swallowed) -> true.also { calm.swallowed = true }
            event.type == KeyEventType.KeyUp && calm.swallowed -> true.also { calm.swallowed = false }
            else -> false
        }
    }
}

/** While [on], OK does nothing; [swallowed], a press it took, its release with it. Read by key events, never drawn. */
private class Calm {
    var on = false
    var swallowed = false
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
