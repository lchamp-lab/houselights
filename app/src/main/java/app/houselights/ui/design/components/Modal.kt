package app.houselights.ui.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/**
 * A panel over the dimmed room, for details on demand or a short list of actions. Focus
 * stays inside, on "Close" first unless [initialFocus] names another element; Back or
 * "Close" dismisses it.
 */
@Composable
fun HlModal(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = HlLayout.dialog,
    initialFocus: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The room is dimmed by our own scrim, not the platform's grey one.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }

        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { (initialFocus ?: closeFocus).requestFocus() } }
        val c = Hl.colors

        Box(Modifier.fillMaxSize().background(c.background.copy(alpha = HlAlpha.SCRIM)), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn(tween(HlMotion.STANDARD)) + scaleIn(tween(HlMotion.STANDARD, easing = HlMotion.emphasized), initialScale = HlMotion.ENTER_SCALE),
            ) {
                HlPanel(modifier.width(width), level = PanelLevel.Raised, padding = HlSpace.xl) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = Hl.type.subject, color = c.content, modifier = Modifier.weight(1f))
                        HlButton(stringResource(R.string.close), onDismiss, Modifier.focusRequester(closeFocus), icon = Icons.Filled.Close)
                    }
                    Spacer(Modifier.height(HlSpace.l))
                    content()
                }
            }
        }
    }
}
