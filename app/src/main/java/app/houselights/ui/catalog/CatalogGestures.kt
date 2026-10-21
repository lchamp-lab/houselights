package app.houselights.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.BackHint
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.DefinitionList
import app.houselights.ui.design.components.DefinitionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.InfoBlock
import app.houselights.ui.design.components.Intertitle
import app.houselights.ui.design.components.PosterStrip
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.components.SpecTable
import app.houselights.ui.design.components.SpecTableRow
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.UiError
import app.houselights.ui.util.formatDimensions

/** The three small-surface gestures (case back, intertitle, counter), side by side, then at screen widths. */
internal fun LazyListScope.catalogGestures() {
    item { Group("Gestures: the back of a case, the intertitle, the counter") { SideBySide() } }
    // On a screen, HlEmptyState: the same intertitle, its action focused as it appears.
    item { Group("Intertitle: neutral, attention, Back as the way out, no action") { Intertitles() } }
    item { Group("Error state: what fixes each error, on the spot or where it is fixed") { ErrorStates() } }
    item { Group("Counter: home, a folder's card, counting, a row") { Counters() } }
    item { Group("Back of a case: Affichage, À propos") { Jackets() } }
    item { Group("Empty frames and poster strips", still = true) { Frames() } }
}

@Composable
private fun SideBySide() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("back of a case: blocks to read, the dot on what is in service", Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                InfoBlock(stringResource(R.string.current_output)) { CurrentOutput() }
                InfoBlock(stringResource(R.string.label_modes)) {
                    SpecTable(
                        listOf(
                            SpecTableRow("Ultra HD", listOf(formatDimensions(3840, 2160)), inUse = true),
                            SpecTableRow("Full HD", listOf(formatDimensions(1920, 1080))),
                        ),
                        columns = emptyList(),
                    )
                }
            }
        }
        Specimen("intertitle: a short rule, ember for attention, one action", Modifier.weight(1f)) {
            Intertitle(
                stringResource(R.string.error_offline_title),
                message = "192.168.1.20 ne répond pas. Vérifiez que le NAS est allumé.",
                attention = true,
            ) { HlButton(stringResource(R.string.retry), {}) }
        }
        Specimen("counter: the ember line, the count, the file", Modifier.weight(1f)) {
            ScanProgress(3, 9, label = stringResource(R.string.home_scanning), current = "Bugonia (2025).mkv")
        }
    }
}

@Composable
private fun Intertitles() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("neutral: what to do, and a quieter way", Modifier.weight(1f)) {
            Intertitle(stringResource(R.string.home_empty_title), message = stringResource(R.string.home_empty_local)) {
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    HlButton(stringResource(R.string.add_folder), {})
                    HlButton(stringResource(R.string.home_see_folders), {})
                }
            }
        }
        Specimen("attention: the action that fixes it", Modifier.weight(1f)) {
            Intertitle(
                stringResource(R.string.error_tmdb_key_refused_title),
                message = stringResource(R.string.error_tmdb_key_refused),
                attention = true,
            ) { HlButton(stringResource(R.string.error_action_edit_tmdb_key), {}) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("nothing fixes it: Back is the way out", Modifier.weight(1f)) {
            Intertitle(stringResource(R.string.error_gone_title), message = stringResource(R.string.error_gone), attention = true) {
                BackHint()
            }
        }
        Specimen("no action: the search field above is the way", Modifier.weight(1f)) {
            Intertitle(stringResource(R.string.search_empty), message = stringResource(R.string.search_empty_hint))
        }
    }
}

/** HlErrorState per error action, and with none; here actions do nothing and take no focus as they appear. */
@Composable
private fun ErrorStates() {
    CompositionLocalProvider(LocalErrorFix provides {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
            Specimen("try again, on the spot", Modifier.weight(1f)) { ErrorSpecimen(UiError.OFFLINE) }
            Specimen("trying again: it stays, its button at work", Modifier.weight(1f)) {
                ErrorSpecimen(UiError.OFFLINE, retrying = true)
            }
            Specimen("nothing fixes it: Back is the way out", Modifier.weight(1f)) { ErrorSpecimen(UiError.GONE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
            Specimen("sign in again", Modifier.weight(1f)) { ErrorSpecimen(UiError.DENIED) }
            Specimen("add a TMDB key, in the settings", Modifier.weight(1f)) { ErrorSpecimen(UiError.NO_TMDB_KEY) }
            Specimen("change the key TMDB refuses", Modifier.weight(1f)) { ErrorSpecimen(UiError.TMDB_KEY_REFUSED) }
        }
    }
}

@Composable
private fun ErrorSpecimen(error: UiError, retrying: Boolean = false) =
    HlErrorState(error, onRetry = {}, retrying = retrying, focusOnAppear = false)

@Composable
private fun Counters() {
    Specimen("first scan, on home: what is read, a line a reading column long, the file") {
        ScanProgress(
            41,
            120,
            label = stringResource(R.string.home_scanning),
            current = "Mobland.S01E04.2160p.mkv",
            lineWidth = HlLayout.readableNarrow,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("a folder's card: one line, the file after the count", Modifier.width(HlLayout.readableNarrow)) {
            ScanProgress(3, 9, current = "Disclosure Day (2026).mkv")
        }
        Specimen("counting the files: a sliver, no count yet") { ScanProgress(0, 0) }
    }
    Specimen("the row that starts a scan: the same count", Modifier.width(HlLayout.readableNarrow)) {
        HlSettingRow(
            stringResource(R.string.scan_now),
            value = stringResource(R.string.scan_progress, 3, 9),
            valueLit = true,
            onClick = {},
        )
    }
}

@Composable
private fun Jackets() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("Affichage, on a TV that lists Ultra HD: the row that acts comes last", Modifier.width(HlLayout.readableNarrow)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                Text(stringResource(R.string.section_display), style = Hl.type.subject, color = Hl.colors.content)
                InfoBlock(stringResource(R.string.current_output)) { CurrentOutput() }
                InfoBlock(stringResource(R.string.label_render)) {
                    Text(formatDimensions(1920, 1080), style = Hl.type.meta, color = Hl.colors.content)
                }
                InfoBlock(stringResource(R.string.label_modes)) {
                    SpecTable(
                        listOf(
                            SpecTableRow(
                                "Ultra HD",
                                listOf(formatDimensions(3840, 2160), rates("23,976", "24", "50", "59,94", "60")),
                                inUse = true,
                            ),
                            SpecTableRow("Full HD", listOf(formatDimensions(1920, 1080), rates("24", "50", "60"))),
                            SpecTableRow("HD", listOf(formatDimensions(1280, 720), rates("50", "60"))),
                        ),
                        columns = listOf(HlLayout.specFormat),
                    )
                }
                HlSettingRow(stringResource(R.string.ui_resolution), value = stringResource(R.string.ui_4k), onClick = {})
            }
        }
        Specimen("Affichage on the Zidoo: the player sets its output") {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                InfoBlock(stringResource(R.string.current_output)) {
                    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        Text(stringResource(R.string.output_by_player), style = Hl.type.meta, color = Hl.colors.content)
                        Text(stringResource(R.string.output_by_player_hint), style = Hl.type.caption, color = Hl.colors.contentSecondary)
                    }
                }
                InfoBlock(stringResource(R.string.label_render)) {
                    Text(formatDimensions(1920, 1080), style = Hl.type.meta, color = Hl.colors.content)
                }
            }
        }
    }
    Specimen("À propos: states with their light, then the credits", Modifier.width(HlLayout.readableNarrow)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
            InfoBlock(stringResource(R.string.about_status)) {
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                    DefinitionRow(stringResource(R.string.label_player)) { HlStatus(StatusState.Done, stringResource(R.string.detected)) }
                    DefinitionRow(stringResource(R.string.label_api)) { HlStatus(StatusState.Pending, stringResource(R.string.checking)) }
                }
            }
            InfoBlock(stringResource(R.string.about_credits)) {
                DefinitionList(
                    listOf(
                        stringResource(R.string.credit_data) to "TMDB",
                        stringResource(R.string.credit_font) to stringResource(R.string.credit_font_value),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Frames() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("a folder's card: one poster that fails, five free places", Modifier.width(HlLayout.readableNarrow)) {
            PosterStrip(listOf(BROKEN), slots = 6)
        }
        Specimen("a sheet's header: three places, as tall as its title block") {
            Row(Modifier.height(HlSize.posterThumb), horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                PosterThumb(BROKEN, width = null)
                PosterThumb(null, width = null)
                PosterThumb(null, width = null)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("a folder with no poster yet: its name, like a one-sheet", Modifier.width(HlLayout.readableNarrow)) {
            PosterStrip(emptyList(), slots = 6, name = "Concerts")
        }
        Specimen("loading cards: empty frames, at a poster and a thumbnail") {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Bottom) {
                CardSkeleton(HlSize.poster, HlSize.POSTER_RATIO)
                CardSkeleton(HlSize.posterThumb, HlSize.POSTER_RATIO)
            }
        }
    }
}

/** "Ultra HD 60 Hz" in the technical voice, its frame size beside it, as Affichage sets it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentOutput() {
    // Short of room, the frame size goes under, whole: a number never breaks.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
        Text(
            "Ultra HD 60${NBSP}Hz",
            style = Hl.type.spec,
            color = Hl.colors.content,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            formatDimensions(3840, 2160),
            style = Hl.type.meta,
            color = Hl.colors.contentSecondary,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

private fun rates(vararg hz: String) = hz.joinToString(FACT_GAP) + "${NBSP}Hz"

/** A poster that fails to load. */
private val BROKEN = ImageRef("catalog-gestures-broken") { "file:///nonexistent/gesture-poster.jpg" }
