package app.houselights.ui.design.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.ui.design.HlIcons
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.UiError

/**
 * An [error] in place of the content that failed to load, as an attention [HlEmptyState]: retry runs
 * [onRetry] (busy while [retrying]); other actions open where the problem is fixed ([LocalErrorFix]).
 */
@Composable
fun HlErrorState(
    error: UiError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retrying: Boolean = false,
    focusOnAppear: Boolean = true,
) {
    val action = error.action
    val fix = LocalErrorFix.current
    HlEmptyState(
        stringResource(error.title),
        modifier,
        message = stringResource(error.message),
        attention = true,
        action = action?.let { stringResource(it.label) },
        actionIcon = HlIcons.Refresh.takeIf { action == ErrorAction.RETRY },
        onAction = { if (action == ErrorAction.RETRY) onRetry() else action?.let(fix) },
        backHint = action == null,
        busy = stringResource(R.string.retrying).takeIf { retrying && action == ErrorAction.RETRY },
        focusOnAppear = focusOnAppear,
    )
}
