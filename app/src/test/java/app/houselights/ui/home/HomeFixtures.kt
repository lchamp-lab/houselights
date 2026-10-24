package app.houselights.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.core.UserState
import app.houselights.providers.local.ScanState
import app.houselights.ui.testing.FakeProvider
import kotlinx.coroutines.flow.MutableStateFlow

/** Home's grid over [state], no scan running, every action doing nothing unless [actions] says. */
@Composable
internal fun HomeGrid(state: HomeState, actions: HomeActions = HomeActions(onOpenSearch = {})) =
    HomeGrid(state, remember { MutableStateFlow(ScanState()) }, actions)

/** What home asked the source for its rows, leaving out what its doors read. */
internal val FakeProvider.rowCalls: List<String> get() = calls.filter { it == "home" || it == "libraries" }

internal fun title(id: String, kind: MediaKind = MediaKind.MOVIE) =
    MediaItem(id = id, kind = kind, title = id.replaceFirstChar { it.uppercase() }, year = 2020, runtimeMs = 6_000_000)

/** The films, shows and sagas libraries of a home with everything. */
internal val films = Library("movies", LibraryKind.MOVIES, null)
internal val shows = Library("shows", LibraryKind.SHOWS, null)
internal val sagaLibrary = Library("collections", LibraryKind.COLLECTIONS, null)

/** A film in progress, then another. */
internal val resuming = listOf(title("heat").copy(user = UserState(positionMs = 1_000_000)), title("alien"))

/** The next episode of a show. */
internal val nextEpisode = title("mobland-s1e4", MediaKind.EPISODE).copy(seasonNumber = 1, episodeNumber = 4, seriesTitle = "MobLand")

/**
 * A home with everything: a film in progress and another, a next episode, the latest films and shows, the films',
 * shows' and sagas' doors read (412 films, 38 shows, 3 new episodes, 24 sagas).
 */
internal fun fullHome(
    sections: List<HomeSection> = listOf(
        HomeSection(SectionType.CONTINUE, resuming, landscape = true),
        HomeSection(SectionType.NEXT_UP, listOf(nextEpisode), landscape = true),
        HomeSection(SectionType.LATEST, (1..12).map { title("film$it") }, landscape = false, libraryId = films.id),
        HomeSection(SectionType.LATEST, (1..6).map { title("show$it", MediaKind.SERIES) }, landscape = false, libraryId = shows.id),
    ),
    doors: HomeDoors = HomeDoors(
        shelves = listOf(Shelf(films, 412, emptyList()), Shelf(shows, 38, emptyList(), newEpisodes = 3)),
        sagas = Shelf(sagaLibrary, 24, emptyList()),
    ),
    status: HomeStatus = HomeStatus(StatusSource.Folders(listOf("nas"), 1), trakt = true, scannedAt = 0L),
) = HomeState(listOf(films, shows, sagaLibrary), HomeContent.Rows(sections), doors, status)
