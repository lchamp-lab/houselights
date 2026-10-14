package app.houselights.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import android.text.format.DateUtils
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
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
