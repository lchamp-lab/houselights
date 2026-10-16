package app.houselights.ui.util

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.StatusState

/**
 * A part of a screen could not load (a season's episodes, the next page of a wall): why, in its
 * place, and the action that fixes it, as an intertitle says it for a whole screen. Trying again is
 * [onRetry]: while it runs ([retrying]) the part stays, its button at work and still focused. The
 * other actions lead where the problem is fixed ([LocalErrorFix]); an error no action fixes has no
 * button. [action] names the button, for the screen to find it again.
 */
@Composable
fun PartFailed(error: UiError, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier, action: Modifier = Modifier) {
    val fix = LocalErrorFix.current
    Column(modifier) {
        HlStatus(StatusState.Attention, stringResource(error.title), detail = stringResource(error.message))
        val fixedBy = error.action ?: return@Column
        Spacer(Modifier.height(HlSpace.s))
        HlButton(
            stringResource(fixedBy.label),
            onClick = { fixedBy.run(onRetry, fix) },
            modifier = action,
            icon = HlIcons.Refresh.takeIf { fixedBy == ErrorAction.RETRY },
            busy = retrying && fixedBy == ErrorAction.RETRY,
            busyLabel = stringResource(R.string.retrying),
        )
    }
}

/** Does what this action names: the screen tries again itself ([onRetry]); the others lead where it is fixed ([fix]). */
internal fun ErrorAction.run(onRetry: () -> Unit, fix: (ErrorAction) -> Unit) = if (this == ErrorAction.RETRY) onRetry() else fix(this)
