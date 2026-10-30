package app.houselights.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.isOk
import kotlinx.coroutines.delay

/** The first OK pressed within does [clear], not its repeats: held, OK is still one action and leaves what it caused. */
fun Modifier.okClears(clear: () -> Unit) = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown && it.isOk() && it.nativeKeyEvent.repeatCount == 0) clear()
    false
}

/**
 * OK does nothing for [HlMotion.SETTLE] once [trigger] changes, from the first moment when [calmAtFirst], and as focus
 * arrives here when [onArrival]; nor do a taken press's repeats, nor a release but that of a press that went down here,
 * under the same [trigger] and, with [onArrival], since focus arrived. A second OK given too soon, meant for what was there before, a key
 * held, or one let go over what changed under it never chooses what has just come under focus unseen.
 */
@Composable
fun Modifier.calmOk(trigger: Any?, calmAtFirst: Boolean, onArrival: Boolean = false): Modifier {
    val calm = remember { OkCalm(calmAtFirst, trigger) }
    var arrivals by remember { mutableIntStateOf(0) }
    LaunchedEffect(trigger, arrivals) {
        if (!calm.begins(trigger, arrivals)) return@LaunchedEffect
        calm.on = true
        delay(HlMotion.SETTLE)
        calm.on = false
    }
    val arriving = if (onArrival) Modifier.onFocusChanged { if (calm.focus(it.isFocused)) arrivals++ } else Modifier
    return then(arriving).onPreviewKeyEvent { event ->
        when {
            !event.isOk() -> false
            event.type == KeyEventType.KeyDown -> calm.pressed(trigger, event.nativeKeyEvent.repeatCount)
            event.type == KeyEventType.KeyUp -> calm.released(trigger)
            else -> false
        }
    }
}

/**
 * [on] while too short a while has passed, from the first moment when [calmAtFirst]; [seen], the trigger and arrivals
 * last calmed for, none at first when calm then; [swallowed], a press it took, its repeats and release with it; [down],
 * OK went down here, under [downOn]; [focused], focus is here. Read by key events, never drawn.
 */
private class OkCalm(calmAtFirst: Boolean, trigger: Any?) {
    var on = calmAtFirst
    private var seen: Any? = if (calmAtFirst) Unseen else trigger
    private var arrivalsSeen = 0
    private var focused = false
    private var swallowed = false
    private var down = false
    private var downOn: Any? = null

    /** Whether a calm begins: [trigger] or the [arrivals] other than those last calmed for. */
    fun begins(trigger: Any?, arrivals: Int): Boolean {
        if (trigger == seen && arrivals == arrivalsSeen) return false
        seen = trigger
        arrivalsSeen = arrivals
        return true
    }

    /** Focus come here or gone ([now]); true when it just arrived, calm from then, its earlier press forgotten. */
    fun focus(now: Boolean): Boolean {
        if (now == focused) return false
        focused = now
        down = false
        if (now) on = true
        return now
    }

    /** OK going down here under [shown], its [repeat] count: taken, doing nothing, while calm and through a press taken. */
    fun pressed(shown: Any?, repeat: Int): Boolean {
        if (on || swallowed) {
            swallowed = true
            return true
        }
        if (repeat == 0) {
            down = true
            downOn = shown
        }
        return false
    }

    /** OK let go here under [shown]: taken, doing nothing, unless its press went down here, under [shown], untaken. */
    fun released(shown: Any?): Boolean = (swallowed || !down || downOn != shown).also {
        swallowed = false
        down = false
        downOn = null
    }
}

/** No trigger yet: what a calm from the first moment differs from. */
private object Unseen
