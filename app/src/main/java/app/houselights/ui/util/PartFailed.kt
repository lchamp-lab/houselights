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
 * A part that could not load (episodes, a wall's next page): why, titled as the screens say it ([titleText]), and the
 * fixing action ([ErrorFixButton]), none when nothing fixes it; [actionModifier] is that button's modifier.
 */
@Composable
fun PartFailed(
    error: UiError,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
) {
    Column(modifier) {
        HlStatus(StatusState.Attention, error.titleText(), detail = stringResource(error.message))
        val fixedBy = error.action ?: return@Column
        Spacer(Modifier.height(HlSpace.s))
        ErrorFixButton(fixedBy, retrying, onRetry, actionModifier)
    }
}

/**
 * What fixes an error, [fixedBy]: Réessayer, which runs [onRetry] and stays busy and focused while [retrying]; else
 * where it is fixed (Se reconnecter), which the screen's [LocalErrorFix] leads to.
 */
@Composable
fun ErrorFixButton(fixedBy: ErrorAction, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val fix = LocalErrorFix.current
    HlButton(
        stringResource(fixedBy.label),
        onClick = { fixedBy.run(onRetry, fix) },
        modifier = modifier,
        icon = HlIcons.Refresh.takeIf { fixedBy == ErrorAction.RETRY },
        busy = retrying && fixedBy == ErrorAction.RETRY,
        busyLabel = stringResource(R.string.retrying),
    )
}

/** Runs this action: the screen retries itself ([onRetry]); the others lead where it is fixed ([fix]). */
internal fun ErrorAction.run(onRetry: () -> Unit, fix: (ErrorAction) -> Unit) = if (this == ErrorAction.RETRY) onRetry() else fix(this)
