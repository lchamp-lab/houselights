package app.houselights.ui.testing

import androidx.compose.ui.platform.SoftwareKeyboardController

/** The keyboard as a text field and Compose's own ask it to show; Back hides it without telling them ([shown] set false). */
class TestKeyboard : SoftwareKeyboardController {
    var shown = false

    override fun show() {
        shown = true
    }

    override fun hide() {
        shown = false
    }
}
