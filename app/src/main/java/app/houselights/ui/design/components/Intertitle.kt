package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight

/**
 * The form of every app message (DESIGN.md, Gestures): a short rule (ember for [attention]), the [title],
 * a [message] in the narrow measure, then [actions]; it starts at its zone's margin, never centred.
 */
@Composable
internal fun Intertitle(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    attention: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = Hl.colors
    Column(modifier.widthIn(max = HlLayout.readable)) {
        IntertitleRule(attention)
        Spacer(Modifier.height(HlSpace.m))
        Text(title, style = Hl.type.subject, color = c.content)
        if (message != null) {
            // Its first baseline a title's line under the title's.
            Text(
                message,
                style = Hl.type.body,
                color = c.contentSecondary,
                modifier = Modifier.widthIn(max = HlLayout.readableNarrow).paddingFromBaseline(top = sentenceUnder(Hl.type.subject)),
            )
        }
        if (actions != null) {
            Spacer(Modifier.height(HlSpace.l))
            actions()
        }
    }
}

/** The short rule heading an intertitle: grey like a one-sheet's, ember for attention. */
@Composable
private fun IntertitleRule(attention: Boolean) {
    val c = Hl.colors
    Box(
        Modifier
            .width(HlSize.intertitleRule)
            .height(HlSize.intertitleRuleWeight)
            .then(if (attention) Modifier.background(c.emberLight()) else Modifier.background(c.contentSecondary)),
    )
}
