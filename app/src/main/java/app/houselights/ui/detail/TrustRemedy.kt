package app.houselights.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.UiError

// The player changed (#58): on a title's page, what fixes it takes Lire's place while the failure is said.

/**
 * Holds « Faire confiance au nouveau lecteur » in Lire's place ([TrustPlayerButton]) from the OK that goes down on it,
 * which clears the failure first (the page's `okClears`), until its click: the press opens the trust sheet, never Lire.
 */
@Stable
internal class RemedyHold {
    var pressed by mutableStateOf(false)

    /** Whether the remedy is in Lire's place: its failure said ([asked]), or OK down on it. */
    fun shows(asked: Boolean): Boolean = asked || pressed
}

/** Whether trusting the player fixes [error]: the firmware's player updated, or an app in its place (#58). */
internal fun trustsPlayer(error: UiError?): Boolean = error?.action == ErrorAction.TRUST_PLAYER

/**
 * « Faire confiance au nouveau lecteur » in Lire's place, keyed as Lire by its caller so focus stays: OK opens the trust
 * sheet ([ErrorAction.TRUST_PLAYER]) and trusts nothing, Lire coming back under it. No glyph, as Se reconnecter.
 */
@Composable
internal fun TrustPlayerButton(hold: RemedyHold, modifier: Modifier = Modifier) {
    val fix = LocalErrorFix.current
    HlButton(
        stringResource(R.string.trust_player),
        onClick = {
            hold.pressed = false
            fix(ErrorAction.TRUST_PLAYER)
        },
        modifier = modifier
            .onFocusChanged { if (!it.isFocused) hold.pressed = false }
            .onPreviewKeyEvent { event ->
                val ok = event.type == KeyEventType.KeyDown && event.key in OK_KEYS
                if (ok && event.nativeKeyEvent.repeatCount == 0) hold.pressed = true
                false
            },
    )
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
