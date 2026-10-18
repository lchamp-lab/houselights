package app.houselights.ui.design.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.revealOnFocus
import java.util.Locale

/** One craft of the end credits: its [job] as credited ("Musique") and its [names] in billing order. */
data class Craft(val job: String, val names: List<String>)

/**
 * A title's end credits: each job in small condensed type against a central gutter, then up to
 * [CRAFT_NAMES] names in condensed capitals; rows are focusable to be read. Draws nothing without names.
 */
@Composable
fun EndCredits(crafts: List<Craft>, modifier: Modifier = Modifier) {
    val shown = crafts.filter { it.names.isNotEmpty() }
    if (shown.isEmpty()) return
    Column(modifier.widthIn(max = HlLayout.readable)) {
        // The heading comes into view with the first row.
        Column(Modifier.revealOnFocus()) {
            SectionHeader(stringResource(R.string.end_credits), Modifier.padding(start = HlInset.card, bottom = HlSpace.xs))
            CraftRow(shown.first())
        }
        shown.drop(1).forEach { CraftRow(it) }
    }
}

/** At most this many names per craft, the first in billing order, as on a poster. */
const val CRAFT_NAMES = 3

@Composable
private fun CraftRow(craft: Craft) {
    val c = Hl.colors
    val t = Hl.type
    val locale = Locale.getDefault()
    val names = craft.names.take(CRAFT_NAMES)
    CreditsRow(readAs = (listOf(craft.job) + names).joinToString(", ")) { focused ->
        val ink = LocalContentColor.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Text(
                craft.job,
                style = t.billingJoin,
                color = if (focused) ink else c.contentTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            Spacer(Modifier.width(HlSpace.l))
            Column(Modifier.weight(1f).alignByBaseline()) {
                names.forEach {
                    Text(
                        it.uppercase(locale),
                        style = t.billing,
                        color = if (focused) ink else c.contentSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** An end credits row, focusable so it is read as [readAs]; white on focus. */
@Composable
private fun CreditsRow(readAs: String, content: @Composable (focused: Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    HlRowSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = readAs
            this.focused = focused
        },
        interactionSource = interaction,
    ) { _ ->
        Box(Modifier.fillMaxWidth().padding(horizontal = HlInset.card, vertical = HlSpace.xs)) { content(focused) }
    }
}
