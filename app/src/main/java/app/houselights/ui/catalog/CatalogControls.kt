package app.houselights.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.components.ArtworkImage
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HeaderControls
import app.houselights.ui.design.components.HlActionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.SegmentDetail
import app.houselights.ui.design.components.TaskTitle
import app.houselights.ui.util.NBSP
import kotlinx.coroutines.delay

/** Buttons, tiles, tracks and headers in their states, on the room and over a picture. */
internal fun LazyListScope.catalogControls() {
    item {
        Group("Controls: the main action in the ember light, framed by focus; plates turn white") {
            ButtonStates()
            ControlStates()
        }
    }
    item { Group("Titles: a place's name in the ember light, a task's in white") { Titles() } }
    item { Group("Tiles: 48 on a page, 40 in a header; a chosen state lights the glyph") { TileStates() } }
    item { Group("Over a picture: plates in its own dark, edged with a hairline") { OverAPicture() } }
    item { Group("Tracks: words in the poster's voice, the place in ember; focus is white") { Tracks() } }
}

@Composable
private fun ButtonStates() {
    HlActionRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        Specimen("main action", gap = HlSpace.xs) {
            HlButton(stringResource(R.string.play), {}, icon = HlIcons.Play, detail = "1${NBSP}h${NBSP}53", emphasis = Emphasis.Primary)
        }
        Specimen("Lire becomes Reprendre", gap = HlSpace.xs) {
            HlButton(
                stringResource(R.string.resume),
                {},
                icon = HlIcons.Play,
                detail = stringResource(R.string.time_left, "1${NBSP}h${NBSP}26"),
                emphasis = Emphasis.Primary,
            )
        }
        Specimen("main, disabled: its well, ringed", gap = HlSpace.xs) {
            HlButton(stringResource(R.string.play), {}, icon = HlIcons.Play, emphasis = Emphasis.Primary, enabled = false)
        }
        Specimen("secondary", gap = HlSpace.xs) { HlButton(stringResource(R.string.view_series), {}) }
    }
}

/** Buttons and setting rows in their states, each named underneath. */
@Composable
private fun ColumnScope.ControlStates() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("detail lit: a state it names", gap = HlSpace.xs) {
            HlButton(
                stringResource(R.string.filter),
                {},
                icon = HlIcons.Filter,
                detail = stringResource(R.string.filter_unwatched),
                detailLit = true,
            )
        }
        Specimen("disabled: focus outlines it", gap = HlSpace.xs) {
            HlButton(stringResource(R.string.add), {}, icon = HlIcons.Folder, enabled = false)
        }
        Specimen("busy: the screen's words", gap = HlSpace.xs) {
            HlButton(
                stringResource(R.string.test_connection),
                {},
                icon = HlIcons.Test,
                busy = true,
                busyLabel = stringResource(R.string.checking),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(busy) {
            if (busy) {
                delay(CATALOG_BUSY_MS)
                busy = false
            }
        }
        Specimen("OK: busy for 3 s, its width kept", gap = HlSpace.xs) {
            HlButton(
                stringResource(R.string.sign_in),
                { busy = true },
                busy = busy,
                busyLabel = stringResource(R.string.signing_in),
            )
        }
        Specimen("in a header: 44, the value chosen lit", gap = HlSpace.xs) {
            HeaderControls {
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    HlButton(stringResource(R.string.filter), {}, icon = HlIcons.Filter)
                    val title = stringResource(R.string.sort_title)
                    HlButton(stringResource(R.string.sort), {}, icon = HlIcons.Sort, detail = title, detailLit = true)
                }
            }
        }
    }
    Specimen("a label longer than a reading column stops with an ellipsis", gap = HlSpace.xs) {
        HlButton("Ajouter « Le Seigneur des anneaux : la Communauté de l’anneau, version longue » à vos favoris", {})
    }
    Column(Modifier.widthIn(max = HlLayout.readable)) {
        Specimen("setting row, disabled: tertiary words, focus outlines it; never in a raised sheet", gap = HlSpace.xs) {
            HlSettingRow(
                stringResource(R.string.scan_now),
                description = "Aucun dossier à analyser",
                onClick = {},
                enabled = false,
            )
        }
    }
}

@Composable
private fun Titles() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxl)) {
        Specimen("a place", gap = HlSpace.xs) { ScreenTitle(stringResource(R.string.settings_title)) }
        Specimen("a task or a form", gap = HlSpace.xs) { TaskTitle(stringResource(R.string.add_folder)) }
    }
}

@Composable
private fun TileStates() {
    HlActionRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), hinted = true) {
        Specimen("rest", gap = HlSpace.xs) { HlIconButton(HlIcons.Restart, stringResource(R.string.from_start), {}) }
        // Watched is done, not a choice: the check fills white.
        Specimen("done", gap = HlSpace.xs) { HlIconButton(HlIcons.CheckFilled, stringResource(R.string.mark_unwatched), {}) }
        // A show's season tile: Menu opens its options, which the key under its label says.
        Specimen("options: the Menu key", gap = HlSpace.xs) {
            HlIconButton(
                HlIcons.Check,
                stringResource(R.string.mark_season_watched),
                {},
                onLongClick = {},
                onLongClickLabel = stringResource(R.string.more_options),
            )
        }
        Specimen("chosen", gap = HlSpace.xs) { HlIconButton(HlIcons.HeartFilled, stringResource(R.string.remove_favorite), {}, lit = true) }
        Specimen("disabled", gap = HlSpace.xs) { HlIconButton(HlIcons.Edit, stringResource(R.string.edit), {}, enabled = false) }
        Specimen("in a header", gap = HlSpace.xs) {
            HeaderControls { HlIconButton(HlIcons.Search, stringResource(R.string.search), {}) }
        }
    }
}

/** A detail page's actions over its picture, as it lays them out. */
@Composable
private fun OverAPicture() {
    val art = shippedImage(R.drawable.banner)
    Box {
        ArtworkImage(art.url(0), null, Modifier.matchParentSize())
        OnArtwork {
            HlActionRow(Modifier.padding(HlSpace.l)) {
                HlButton(stringResource(R.string.play), {}, icon = HlIcons.Play, detail = "2${NBSP}h${NBSP}28", emphasis = Emphasis.Primary)
                HlButton(stringResource(R.string.parts), {}, icon = HlIcons.Playback, detail = "3")
                HlIconButton(HlIcons.Check, stringResource(R.string.mark_watched), {})
                HlIconButton(HlIcons.Info, stringResource(R.string.tech_details), {})
                HlIconButton(HlIcons.HeartFilled, stringResource(R.string.remove_favorite), {}, lit = true)
                HlIconButton(HlIcons.Edit, stringResource(R.string.edit), {})
            }
        }
    }
}

@Composable
private fun Tracks() {
    var season by remember { mutableIntStateOf(2) }
    Specimen("seasons: chosen as focus moves, how far each is seen", gap = HlSpace.xs) {
        HlSegmented(
            (1..12).map { stringResource(R.string.season_number, it) },
            selected = season,
            onSelect = { season = it },
            selectOnFocus = true,
            scrollable = true,
            // Two done, the third begun.
            details = listOf(SegmentDetail.Done, SegmentDetail.Done, SegmentDetail.Count("6$NBSP/${NBSP}10")),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        listOf("Ultra HD", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1", "12").forEach { Badge(it) }
    }
    FactRow(listOf("2026", "1 h 53", "8,2"))
}

/** How long the catalogue's busy buttons work before they give their answer. */
internal const val CATALOG_BUSY_MS = 3_000L
