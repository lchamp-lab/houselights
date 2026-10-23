package app.houselights.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.TrackPlan
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.tracksHint
import app.houselights.ui.util.formatDuration

/** An episode's card opens options when it has one: a mark the source keeps, a start over, a choice of tracks. */
internal fun episodeHasOptions(episode: MediaItem, canMark: Boolean, tracks: TrackPlan?): Boolean =
    canMark || episode.user.positionMs > 0 || tracks != null

/** What an episode's options do; null hides an option. */
@Immutable
internal class EpisodeActions(
    val onMark: ((MediaItem) -> Unit)?,
    val onPlayFromStart: (MediaItem) -> Unit,
    /** Opens the show's audio and subtitles. */
    val onTracks: () -> Unit,
)

/**
 * The options of the episode [episodeId] of [state]'s season, in a side sheet: its name, its season, number and length,
 * then marking it watched or not (focused, so Menu then OK marks it), playing it from its start once started, and the
 * show's audio and subtitles where they apply, saying what Lire plays. An action closes the sheet first; an episode no
 * longer shown closes it for good.
 */
@Composable
internal fun EpisodeOptions(state: DetailState, episodeId: String?, actions: EpisodeActions, onDismiss: () -> Unit) {
    if (episodeId == null) return
    val episode = state.episodes.firstOrNull { it.id == episodeId }
    if (episode == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val tracks = state.tracksToChoose
    val watched = episode.user.played
    val options = listOfNotNull(
        actions.onMark?.let { mark ->
            SheetOption(
                stringResource(if (watched) R.string.mark_unwatched else R.string.mark_watched),
                if (watched) HlIcons.CheckFilled else HlIcons.Check,
            ) { mark(episode) }
        },
        SheetOption(stringResource(R.string.play_from_start), HlIcons.Restart) { actions.onPlayFromStart(episode) }
            .takeIf { episode.user.positionMs > 0 },
        tracks?.let { SheetOption(stringResource(R.string.tracks), HlIcons.Captions, tracksHint(it), actions.onTracks) },
    )
    val seasonItem = state.seasons.firstOrNull { it.id == episode.seasonId }
    // Said in the app's language beside the number and length; specials, season 0, keep the source's name.
    val season = (episode.seasonNumber ?: seasonItem?.seasonNumber)?.takeIf { it > 0 }
        ?.let { stringResource(R.string.season_number, it) }
        ?: seasonItem?.title
    OptionsSheet(
        episode.title,
        listOf(season, episodeNumber(episode), episode.runtimeMs?.let(::formatDuration)),
        options,
        onDismiss,
    )
}

/**
 * The watched tile's options on a show with a season track: the [season] shown, then the whole [show], each watched or
 * not as it stands; the first focused.
 */
@Composable
internal fun ShowMarks(
    show: MediaItem,
    season: MediaItem?,
    seasons: Int,
    open: Boolean,
    onMarkSeason: () -> Unit,
    onMarkShow: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return
    if (season == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val seasonDone = seasonWatched(season)
    // Specials, season 0, go without a number.
    val number = season.seasonNumber?.takeIf { it > 0 }
    val seasonLabel = when {
        number == null -> stringResource(if (seasonDone) R.string.mark_season_unwatched else R.string.mark_season_watched)
        seasonDone -> stringResource(R.string.mark_season_number_unwatched, number)
        else -> stringResource(R.string.mark_season_number_watched, number)
    }
    val showDone = show.user.played
    OptionsSheet(
        show.title,
        listOf(pluralStringResource(R.plurals.season_count, seasons, seasons)),
        listOf(
            SheetOption(seasonLabel, if (seasonDone) HlIcons.CheckFilled else HlIcons.Check, action = onMarkSeason),
            SheetOption(
                stringResource(if (showDone) R.string.mark_series_unwatched else R.string.mark_series_watched),
                if (showDone) HlIcons.CheckFilled else HlIcons.Check,
                action = onMarkShow,
            ),
        ),
        onDismiss,
    )
}

/** "Épisode 2", or "Épisodes 1-2" for a file holding two; null without a number. */
@Composable
private fun episodeNumber(episode: MediaItem): String? {
    val first = episode.episodeNumber ?: return null
    val last = episode.lastEpisodeNumber?.takeIf { it > first }
    return if (last != null) stringResource(R.string.episode_numbers, first, last) else stringResource(R.string.episode_number, first)
}

/** One action of an options sheet: what it does, its icon (what stands), what it says more. */
private class SheetOption(val title: String, val icon: ImageVector, val description: String? = null, val action: () -> Unit)

/** A sheet of [options] about [title], each a row that closes the sheet, then acts; the first one focused. */
@Composable
private fun OptionsSheet(title: String, facts: List<String?>, options: List<SheetOption>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    HlSideSheet(title, onDismiss, facts = facts, initialFocus = first) { sheet ->
        HlSettingGroup(null) {
            options.forEachIndexed { i, option ->
                HlSettingRow(
                    option.title,
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                    description = option.description,
                    icon = option.icon,
                    onClick = { sheet.dismiss(option.action) },
                )
            }
        }
    }
}
