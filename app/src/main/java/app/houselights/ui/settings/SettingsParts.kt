package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import android.text.format.DateUtils
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.revealOnFocus

/**
 * First group of a pane: the space aligning it with the navigation, its title and first
 * settings, revealed together so going back up always shows the title.
 */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.revealOnFocus()) {
        Spacer(Modifier.height(HlLayout.paneTop))
        Text(title, style = Hl.type.title, color = Hl.colors.content)
        Spacer(Modifier.height(HlSpace.l))
        content()
    }
}

/**
 * An action that cannot be undone, asked in its sheet: the [question], what follows, then Cancel,
 * focused, beside the action.
 */
@Composable
internal fun Confirm(question: String, consequence: String, action: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    Text(question, style = Hl.type.lead, color = Hl.colors.content)
    Spacer(Modifier.height(HlSpace.xs))
    Text(consequence, style = Hl.type.body, color = Hl.colors.contentSecondary)
    Spacer(Modifier.height(HlSpace.l))
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        HlButton(stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.focusRequester(cancel))
        HlButton(action, onClick = onConfirm)
    }
}

/** A line of explanation under a group, in the reading measure. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = modifier.widthIn(max = HlLayout.readable))
}

/** "il y a 2 minutes", "à l'instant", "jamais". */
@Composable
internal fun relativeTime(at: Long): String {
    val now = System.currentTimeMillis()
    return when {
        at <= 0 -> stringResource(R.string.never)
        now - at < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.just_now)
        else -> DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}
