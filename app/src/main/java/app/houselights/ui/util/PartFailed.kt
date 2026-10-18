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
 * A part that could not load (episodes, a wall's next page): why, and the fixing action, none when nothing
 * fixes it. While [retrying], its button stays busy and focused; [action] is that button's modifier.
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

/** Runs this action: the screen retries itself ([onRetry]); the others lead where it is fixed ([fix]). */
internal fun ErrorAction.run(onRetry: () -> Unit, fix: (ErrorAction) -> Unit) = if (this == ErrorAction.RETRY) onRetry() else fix(this)
