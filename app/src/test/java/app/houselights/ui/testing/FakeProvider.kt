package app.houselights.ui.testing

import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.Catalog
import app.houselights.core.HomeSection
import app.houselights.core.Identity
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.MetadataEditor
import app.houselights.core.NfoDocument
import app.houselights.core.NotFoundException
import app.houselights.core.Page
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.ProviderCapabilities
import app.houselights.core.SortOption
import kotlinx.coroutines.CompletableDeferred

/** A source whose answers each test sets: calls go to [calls], those in [gates] wait, those in [fail] throw. */
class FakeProvider : MediaProvider {
    override var capabilities = ProviderCapabilities(markPlayed = true, favorites = true, similar = false, pathMapping = false)

    val items = mutableMapOf<String, MediaItem>()
    var libraries = listOf(Library("films", LibraryKind.MOVIES, "Films"))
    var home = listOf<HomeSection>()

    /** The wall of every library, in order. */
    var wall = listOf<MediaItem>()
    var facets = LibraryFacets()
    var seasons = mapOf<String, List<MediaItem>>()

    var episodes = mapOf<String, List<MediaItem>>()
    var nfo: NfoDocument? = null
    var identity: Identity? = null
    var results = listOf<MatchCandidate>()
    var choices: ArtworkChoices? = null

    /** The source's catalog ([FakeCatalog]); none by default, like a source that only provides the cast. */
    override var catalog: Catalog? = null

    /** What the title was last matched with, once the match is written. */
    var identified: MatchCandidate? = null

    val calls = mutableListOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var fail: (call: String) -> Throwable? = { null }

    /** Holds [call] until the gate returned is completed. */
    fun gate(call: String) = CompletableDeferred<Unit>().also { gates[call] = it }

    private suspend fun call(name: String) {
        calls += name
        gates[name]?.await()
        fail(name)?.let { throw it }
    }

    override suspend fun libraries(): List<Library> {
        call("libraries")
        return libraries
    }

    override suspend fun home(): List<HomeSection> {
        call("home")
        return home
    }

    override suspend fun libraryPage(library: Library, sort: SortOption, filter: LibraryFilter, start: Int, limit: Int): Page {
        call("page $start $limit")
        return Page(wall.drop(start).take(limit), wall.size)
    }

    override suspend fun libraryFacets(library: Library): LibraryFacets {
        call("facets")
        return facets
    }

    override suspend fun item(id: String): MediaItem {
        call("item $id")
        return items[id] ?: throw NotFoundException(id)
    }

    override suspend fun seasons(series: MediaItem): List<MediaItem> {
        call("seasons ${series.id}")
        return seasons[series.id].orEmpty()
    }

    override suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem> {
        call("episodes ${season.id}")
        return episodes[season.id].orEmpty()
    }

    override suspend fun nextUp(series: MediaItem): MediaItem? {
        call("nextUp ${series.id}")
        return null
    }

    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        call("played ${item.id} $played")
        items[item.id] = item.copy(user = item.user.copy(played = played))
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        call("favorite ${item.id} $favorite")
        items[item.id] = item.copy(user = item.user.copy(favorite = favorite))
    }

    override suspend fun nfo(item: MediaItem): NfoDocument? {
        call("nfo ${item.id}")
        return nfo
    }

    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan = error("Not played here")

    override val editor: MetadataEditor = Editor()

    private inner class Editor : MetadataEditor {
        override fun canEdit(item: MediaItem) = true

        override suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices {
            call("artwork ${item.id} $kind")
            return choices ?: error("No choices set")
        }

        override suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption) {
            call("setArtwork ${item.id} $kind")
        }

        override suspend fun identity(item: MediaItem): Identity {
            call("identity ${item.id}")
            return identity ?: error("No identity set")
        }

        override suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate> {
            call("search $query")
            return results
        }

        override suspend fun identify(item: MediaItem, candidate: MatchCandidate?) {
            call("identify ${item.id} ${candidate?.id}")
            identified = candidate
        }
    }
}

fun film(id: String, title: String = id) = MediaItem(id, MediaKind.MOVIE, title)

fun series(id: String) = MediaItem(id, MediaKind.SERIES, id)

fun season(id: String, number: Int, seriesId: String) =
    MediaItem(id, MediaKind.SEASON, "Season $number", seasonNumber = number, seriesId = seriesId)

fun episode(id: String, seasonId: String, number: Int) =
    MediaItem(id, MediaKind.EPISODE, id, seasonId = seasonId, episodeNumber = number)
