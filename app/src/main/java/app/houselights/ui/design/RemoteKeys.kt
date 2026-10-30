package app.houselights.ui.design

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_F12
import android.view.KeyEvent.KEYCODE_MENU
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent

/** OK on the remote: its centre, Enter, and the keypad's Enter (`KeyEvent` codes). */
val OK_KEYCODES = setOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER)

/** Menu, and the Zidoo remote's Pop Menu, which its firmware sends as F12 (`KeyEvent` codes). */
val MENU_KEYCODES = setOf(KEYCODE_MENU, KEYCODE_F12)

/** Whether this key is one of OK's ([OK_KEYCODES]), going down or up. */
fun KeyEvent.isOk(): Boolean = nativeKeyEvent.keyCode in OK_KEYCODES

/**
 * Menu or Pop Menu ([MENU_KEYCODES]) does [open] on its way up, if it went down here while focus was [held]; without
 * [open], it goes on. Remotes that send no repeats open options so, as holding OK does on others.
 */
@Composable
fun Modifier.onMenuKey(open: (() -> Unit)?, held: (FocusState) -> Boolean): Modifier {
    var down by remember { mutableStateOf(false) }
    return onFocusChanged { if (!held(it)) down = false }.onKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (open == null || native.keyCode !in MENU_KEYCODES) return@onKeyEvent false
        when (native.action) {
            ACTION_DOWN -> if (native.repeatCount == 0) down = true
            ACTION_UP -> if (down) {
                down = false
                open()
            }
        }
        true
    }
}
