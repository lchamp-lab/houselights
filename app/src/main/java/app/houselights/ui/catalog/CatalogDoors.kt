package app.houselights.ui.catalog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.components.CutPosters
import app.houselights.ui.design.components.DoorFacts
import app.houselights.ui.design.components.DoorFigures
import app.houselights.ui.design.components.DoorNote
import app.houselights.ui.design.components.DoorPosters
import app.houselights.ui.design.components.DoorProgress
import app.houselights.ui.design.components.DoorSagas
import app.houselights.ui.design.components.DoorStill
import app.houselights.ui.design.components.DoorWall
import app.houselights.ui.design.components.HeadNote
import app.houselights.ui.design.components.HomeDoor
import app.houselights.ui.design.components.HomeHead
import app.houselights.ui.design.components.ResumeCase
import app.houselights.ui.design.components.StateFact
import app.houselights.ui.design.components.caseHeight
import app.houselights.ui.design.components.doorWidth
import app.houselights.ui.design.components.rememberCutPosters
import app.houselights.ui.design.components.rememberFigureSize
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.StatusState

/** Home's grid, part by part: its head, its case, and each door at its size, every picture from the app's resources. */
internal fun LazyListScope.catalogDoors() {
    item {
        Group("Home's head: the lockup, then a passing line at its end: Back's, an update installed, one that was not; or a note") {
            HomeHead(null)
            HomeHead(stringResource(R.string.back_again_to_quit))
            HomeHead(stringResource(R.string.update_installed_home, "0.4.1"), statusState = StatusState.Done)
            HomeHead(stringResource(R.string.update_not_installed, "0.4.1"), statusState = StatusState.Attention)
            HomeHead(null, end = { HeldNote() })
        }
    }
    item { Group("Home's case: resuming, then a film not seen; its action fills white with the case's focus") { Cases() } }
    item { Group("Home's case after a failed play: why in a word in its facts' room, its plate doing what fixes it") { FailedCases() } }
    item { Group("Doors of what is watched: the next episode and its season, the latest titles") { WatchDoors() } }
    item { Group("Library doors: one size of figure for all, cut in posters, in ink while they load") { LibraryDoors() } }
    item { Group("Tool doors: sagas stacked, the search's wall out, the app's state as a case's back") { ToolDoors() } }
}

/** A film with artwork from the app's resources, in progress or not. */
@Composable
private fun doorFilm(title: String, positionMs: Long = 0, kind: MediaKind = MediaKind.MOVIE): MediaItem {
    val art = shippedImage(R.drawable.banner)
    return MediaItem(
        id = "catalog-door-$title",
        kind = kind,
        title = title,
        year = 2022,
        runtimeMs = 7_800_000,
        officialRating = "TP",
        communityRating = 8.2,
        genres = listOf("Action", "Comédie", "Romance"),
        backdrop = art,
        poster = art,
        user = UserState(positionMs = positionMs),
        seasonNumber = 1.takeIf { kind == MediaKind.EPISODE },
        episodeNumber = 4.takeIf { kind == MediaKind.EPISODE },
        seriesTitle = "MobLand".takeIf { kind == MediaKind.EPISODE },
    )
}

@Composable
private fun Cases() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        Specimen("Reprendre: progress and time left", width = HomeGridDefaults.case) {
            ResumeCase(
                doorFilm("Top Gun : Maverick", positionMs = 4_900_000),
                stringResource(R.string.resume),
                {},
                Modifier.size(HomeGridDefaults.case, caseHeight()),
            )
        }
        Specimen("Pas encore vu: its genres", width = HomeGridDefaults.case) {
            ResumeCase(
                doorFilm("The Fall Guy"),
                stringResource(R.string.play),
                {},
                Modifier.size(HomeGridDefaults.case, caseHeight()),
                label = stringResource(R.string.home_unseen),
            )
        }
    }
}

/** The case saying why it did not play: a new try may fix it, the server refused the app, nothing a try fixes. */
@Composable
private fun FailedCases() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        Specimen("Réessayer: the NAS out of reach", width = HomeGridDefaults.case) {
            FailedCase(R.string.error_offline_nas_title, stringResource(R.string.retry), HlIcons.Refresh)
        }
        Specimen("Se reconnecter: the server refused the app", width = HomeGridDefaults.case) {
            FailedCase(R.string.error_denied_title, stringResource(R.string.error_action_reconnect), icon = null)
        }
        Specimen("Its own plate: nothing a try fixes", width = HomeGridDefaults.case) {
            FailedCase(R.string.play_failed_player_changed, stringResource(R.string.resume), HlIcons.Play)
        }
    }
}

@Composable
private fun FailedCase(@StringRes reason: Int, action: String, icon: ImageVector?) {
    ResumeCase(
        doorFilm("Top Gun : Maverick", positionMs = 4_900_000),
        action,
        {},
        Modifier.size(HomeGridDefaults.case, caseHeight()),
        icon = icon,
        onLongClick = {},
        says = {
            HlStatus(StatusState.Attention, stringResource(R.string.play_failed), detail = stringResource(reason), maxLines = 1)
        },
    )
}

@Composable
private fun WatchDoors() {
    val episode = doorFilm("Épisode", kind = MediaKind.EPISODE)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        HomeDoor(
            stringResource(R.string.section_next_up),
            HlIcons.Next,
            {},
            Modifier.size(doorWidth(2), HomeGridDefaults.watchRow),
            note = DoorNote.Mark("S1 · É4"),
            progress = DoorProgress.Episodes(listOf(true, true, true, false, false, false, false, false, false, false)),
        ) { DoorStill(episode) }
        HomeDoor(
            stringResource(R.string.home_news),
            HlIcons.Sparkles,
            {},
            Modifier.size(doorWidth(2), HomeGridDefaults.watchRow),
            note = DoorNote.News("7 cette semaine"),
        ) { DoorPosters((1..6).map { n -> doorFilm("Ajout $n") }) }
    }
}

@Composable
private fun LibraryDoors() {
    val posters = (1..4).map { doorFilm("Affiche $it") }
    val counts = listOf("412", "1 204")
    val figure = rememberFigureSize(counts, listOf(2, 2), HomeGridDefaults.libraryRow)
    val cut = rememberCutPosters(posters)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        HomeDoor(
            stringResource(R.string.library_movies),
            HlIcons.Film,
            {},
            Modifier.size(doorWidth(2), HomeGridDefaults.libraryRow),
            note = DoorNote.News("4 nouveaux"),
            glowTones = { cut.tones },
        ) { lit -> DoorFigures(counts[0], cut, figure, lit) }
        HomeDoor(
            stringResource(R.string.library_shows),
            HlIcons.Tv,
            {},
            Modifier.size(doorWidth(2), HomeGridDefaults.libraryRow),
            note = DoorNote.News("3 épisodes nouveaux"),
        ) { lit -> DoorFigures(counts[1], CutPosters(), figure, lit) }
    }
}

@Composable
private fun ToolDoors() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        HomeDoor(
            stringResource(R.string.library_collections),
            HlIcons.Layers,
            {},
            Modifier.size(doorWidth(1), HomeGridDefaults.toolRow),
            note = DoorNote.Mark("24"),
        ) { DoorSagas(listOf(doorFilm("Saga 1"), doorFilm("Saga 2"))) }
        HomeDoor(stringResource(R.string.search), HlIcons.Search, {}, Modifier.size(doorWidth(1), HomeGridDefaults.toolRow)) {
            DoorWall((1..8).map { n -> doorFilm("Mur $n") })
        }
        HomeDoor(stringResource(R.string.nav_settings), HlIcons.Settings, {}, Modifier.size(doorWidth(2), HomeGridDefaults.toolRow)) {
            DoorFacts(
                listOf(
                    StateFact(stringResource(R.string.home_state_source), stringResource(R.string.source_local), detail = "NAS"),
                    StateFact(stringResource(R.string.home_state_trakt), stringResource(R.string.trakt_connected), done = true),
                    StateFact(stringResource(R.string.home_state_scan), "Il y a 2 heures", wide = true),
                ),
            )
        }
    }
}

/** Titles of a folder not found on the NAS, said where home says how things stand, by the ring, never ember. */
@Composable
private fun HeldNote() {
    val count = 12
    HeadNote(pluralStringResource(R.plurals.held_home, count, count, "Films"), stringResource(R.string.held_see), onAction = {})
}
