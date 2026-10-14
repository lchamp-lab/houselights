package app.houselights.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.Score

/** Every glyph of the app; what tungsten never says. */
internal fun LazyListScope.catalogIcons() {
    item { Group("Icons: one line, 1.75 on a 24 grid, at 24 and 20; Play the one filled glyph", still = true) { IconSheet() } }
    item { Group("Tungsten, states only: a score is a fact; attention sits beside what it concerns", still = true) { Facts() } }
}

/** The glyphs in the order of [HlIcons], each on the room and on projector white; Play last. */
@Composable
private fun IconSheet() {
    val glyphs = listOf(
        HlIcons.Folder, HlIcons.Server, HlIcons.Display, HlIcons.Playback, HlIcons.Sort, HlIcons.Filter,
        HlIcons.Info, HlIcons.Document, HlIcons.Storage, HlIcons.Edit, HlIcons.Logo, HlIcons.Search,
        HlIcons.Check, HlIcons.Restart, HlIcons.Back, HlIcons.Chevron, HlIcons.Settings, HlIcons.Add,
        HlIcons.Refresh, HlIcons.Test, HlIcons.Close, HlIcons.Heart, HlIcons.HeartFilled, HlIcons.Star,
        HlIcons.Attention, Icons.Filled.PlayArrow,
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
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(HlSize.discIcon))
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(HlSize.icon))
    }
}

/** A score never lights: it is a fact. What needs attention lights, beside what it concerns. */
@Composable
private fun Facts() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Case("a score: the line star, quiet") { Score(8.2) }
        Case("attention, beside what it concerns", Modifier.widthIn(max = HlLayout.sideColumnWide)) {
            AttentionNote("Le dossier n’a pas pu être lu. Réessayez, ou vérifiez le partage sur le NAS.")
        }
    }
}

/** One state, named underneath. */
@Composable
private fun Case(caption: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Box { content() }
        Text(caption, style = Hl.type.caption, color = Hl.colors.contentTertiary)
    }
}
