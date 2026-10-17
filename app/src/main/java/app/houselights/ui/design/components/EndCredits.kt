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

/** One craft of the end credits: its job as the credits name it ("Musique"), and who did it, billing order. */
data class Craft(val job: String, val names: List<String>)

/**
 * The end credits of a title's page, as a film ends: its overview whole (the hero cuts it at
 * three lines), then its [crafts] on two columns that meet on a gutter at the middle of the reading
 * column: each job in small condensed words set against the gutter, then up to [CRAFT_NAMES] names
 * in condensed capitals, one a line. The billing block at length, the other place capitals are
 * set. Each row takes focus to be read, white as every row; OK does nothing. Nothing to say, it
 * draws nothing.
 */
@Composable
fun EndCredits(overview: String?, crafts: List<Craft>, modifier: Modifier = Modifier) {
    val story = overview?.takeIf { it.isNotBlank() }
    val shown = crafts.filter { it.names.isNotEmpty() }
    if (story == null && shown.isEmpty()) return
    // The overview first, when there is one, then each craft.
    val count = shown.size + if (story != null) 1 else 0
    @Composable
    fun line(i: Int) = if (story != null && i == 0) StoryRow(story) else CraftRow(shown[i - if (story != null) 1 else 0])
    Column(modifier.widthIn(max = HlLayout.readable)) {
        // The heading comes into view with the first row.
        Column(Modifier.revealOnFocus()) {
            SectionHeader(stringResource(R.string.end_credits), Modifier.padding(start = HlInset.card, bottom = HlSpace.xs))
            line(0)
        }
        for (i in 1 until count) line(i)
    }
}

/** Names a craft shows at most, as a poster's credits keep the first. */
const val CRAFT_NAMES = 3

/** The overview, whole, in the voice of reading. */
@Composable
private fun StoryRow(story: String) {
    CreditsRow(readAs = story) { focused ->
        Text(story, style = Hl.type.body, color = if (focused) LocalContentColor.current else Hl.colors.contentSecondary)
    }
}

/** A craft: its job against the gutter, its names after it. */
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

/** A row of the end credits: focusable to be read, as [readAs]; white with focus, as every row. */
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
