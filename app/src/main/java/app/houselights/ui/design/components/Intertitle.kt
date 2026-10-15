package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
 * A card that speaks, set like the intertitle of a silent film and the one-sheet of a title without
 * artwork: a short rule, ember when it calls for [attention]; one line in the voice of the poster
 * ([title]); one line to read ([message], in the narrow reading measure); then what to do ([actions]:
 * at most one action and a quieter one, or the Back hint). Every message of the app takes this form:
 * an empty screen, a failure, a question asked in a sheet. It starts where its zone starts, on the
 * screen's margin, and is never centred.
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
            Spacer(Modifier.height(HlSpace.xs))
            Text(message, style = Hl.type.body, color = c.contentSecondary, modifier = Modifier.widthIn(max = HlLayout.readableNarrow))
        }
        if (actions != null) {
            Spacer(Modifier.height(HlSpace.l))
            actions()
        }
    }
}

/** The short rule heading an intertitle: the grey of a one-sheet's, ember for attention. */
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
