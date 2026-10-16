package app.houselights.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.util.UiError

// The error intertitle lives in the design system ([HlErrorState]). These two keep its former names
// for the callers outside home not moved yet (the settings that cannot be read, the detail page, the
// NFO); they go once those call HlErrorState.

/** An [HlErrorState] where a screen's content would start: on its margin, under its header. */
@Composable
fun ErrorMessage(error: UiError, onRetry: () -> Unit, retrying: Boolean = false) =
    HlErrorState(error, onRetry, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l), retrying)

/** [HlErrorState], by its former name. */
@Composable
fun ErrorIntertitle(error: UiError, onRetry: () -> Unit, modifier: Modifier = Modifier, retrying: Boolean = false) =
    HlErrorState(error, onRetry, modifier, retrying)
