package app.houselights.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TwoPaneDefaults
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.CheckMark
import app.houselights.ui.design.components.ChoiceCheck
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.Score
import app.houselights.ui.design.components.SelectedMark
import app.houselights.ui.design.components.StatusState

/** Every glyph of the app; the marks that say done and your choice; what ember never says. */
internal fun LazyListScope.catalogIcons() {
    item { Group("Icons: Lucide, stroke 2 on a 24 grid, at 24 and 20; Play filled as on a remote", still = true) { IconSheet() } }
    item { Group("Done and your choice: one check, two inks; in words, a light") { Marks() } }
    item { Group("Ember, states only: a score is a fact; attention sits beside what it concerns", still = true) { Facts() } }
}

/** The glyphs in the order of [HlIcons], each on the room and on projector white. */
@Composable
private fun IconSheet() {
    val glyphs = listOf(
        HlIcons.Folder, HlIcons.Server, HlIcons.Display, HlIcons.Playback, HlIcons.Play, HlIcons.Versions, HlIcons.Sort, HlIcons.Filter,
        HlIcons.Info, HlIcons.Document, HlIcons.Storage, HlIcons.Numbers, HlIcons.Edit, HlIcons.Search,
        HlIcons.Check, HlIcons.CheckFilled, HlIcons.Restart, HlIcons.Back, HlIcons.Erase, HlIcons.Swap, HlIcons.Settings, HlIcons.Add,
        HlIcons.Refresh, HlIcons.Test, HlIcons.Heart, HlIcons.HeartFilled, HlIcons.Star,
        HlIcons.Attention, HlIcons.History,
    )
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        glyphs.chunked(ICONS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                row.forEach { Glyph(it, Modifier.weight(1f)) }
                repeat(ICONS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val ICONS_PER_ROW = 8

/** One glyph at 24 and 20 dp: at rest on the room, then in a focused control; its name under it. */
@Composable
private fun Glyph(icon: ImageVector, modifier: Modifier) {
    val c = Hl.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        Sizes(icon, c.content, Modifier.padding(HlSpace.xs))
        Sizes(icon, c.onFocus, Modifier.clip(HlShapes.row).background(c.focus).padding(HlSpace.xs))
        val name = icon.name.substringAfterLast('.')
        Text(name, style = Hl.type.caption, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Sizes(icon: ImageVector, ink: Color, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(HlSize.largeIcon))
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(HlSize.icon))
    }
}

/** One check for done (white) and your choice (ember), plated on artwork and bare in a list. */
@Composable
private fun Marks() {
    val c = Hl.colors
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("watched") { CheckMark() }
        Specimen("your choice") { SelectedMark() }
        Specimen("on a white image") {
            Row(
                Modifier.background(c.content).padding(HlSpace.s),
                horizontalArrangement = Arrangement.spacedBy(HlSpace.s),
            ) {
                CheckMark()
                SelectedMark()
            }
        }
        Specimen("in words: its light") {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                HlStatus(StatusState.On, stringResource(R.string.identify_manual))
                HlStatus(StatusState.On, stringResource(R.string.result_current))
            }
        }
        Specimen("how it was done: a plain word") {
            Text(stringResource(R.string.identify_automatic), style = Hl.type.meta, color = c.contentSecondary)
        }
    }
    Specimen("the current choice of a sheet: ember at rest", Modifier.width(TwoPaneDefaults.sideWide)) {
        HlSettingRow(
            stringResource(R.string.source_local),
            description = "3 dossiers",
            onClick = {},
            trailing = { ChoiceCheck() },
        )
    }
}

/** A score never lights: it is a fact. What needs attention lights, beside what it concerns. */
@Composable
private fun Facts() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("a score: the line star, quiet") { Score(8.2) }
        Specimen("attention, beside what it concerns", Modifier.widthIn(max = TwoPaneDefaults.sideWide)) {
            AttentionNote(stringResource(R.string.error_scan_other))
        }
    }
}

