package app.houselights.ui.testing

import app.houselights.core.Catalog
import app.houselights.core.Credits
import app.houselights.core.Job
import app.houselights.core.LibraryNumbers
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.Saga
import app.houselights.core.SearchResults
import app.houselights.core.Version

/** A catalog whose answers each test sets: calls go to [calls], those [fail] picks throw. */
class FakeCatalog : Catalog {
    /** Person pages, by person id. */
    var pages = mapOf<String, PersonPage>()

    /** Home's directors and best rated films. */
    var directors = listOf<Person>()
    var best = listOf<MediaItem>()

    /** Sagas whole, by collection id. */
    var sagas = mapOf<String, Saga>()

    /** The copies of each film, by its id; the one [chooseVersion] is told is chosen after. */
    var copies = mapOf<String, List<Version>>()
    var numbers = LibraryNumbers(films = 0, shows = 0, episodes = 0, sizeBytes = null, runtimeMs = null)

    /** What any search finds. */
    var found = SearchResults()

    val calls = mutableListOf<String>()
    var fail: (call: String) -> Throwable? = { null }

    private fun call(name: String) {
        calls += name
        fail(name)?.let { throw it }
    }

    override suspend fun credits(item: MediaItem): Credits = Credits().also { call("credits ${item.id}") }

    override suspend fun person(id: String): PersonPage? = pages[id].also { call("person $id") }

    override suspend fun people(job: Job, kind: MediaKind?, minTitles: Int, limit: Int): List<Person> =
        directors.take(limit).also { call("people $job") }

    override suspend fun bestRated(kind: MediaKind, minVotes: Int, limit: Int): List<MediaItem> = best.take(limit).also { call("best") }

    override suspend fun saga(collection: MediaItem): Saga? = sagas[collection.id].also { call("saga ${collection.id}") }

    override suspend fun versions(item: MediaItem): List<Version> = copies[item.id].orEmpty().also { call("versions ${item.id}") }

    override suspend fun chooseVersion(item: MediaItem, version: Version?) {
        call("choose ${version?.key}")
        val chosen = version?.key
        copies = copies + (item.id to copies[item.id].orEmpty().map { it.copy(chosen = if (chosen == null) it.best else it.key == chosen) })
    }

    override suspend fun playback(version: Version, resume: Boolean, options: PlaybackOptions): PlaybackPlan = error("Not played here")

    override suspend fun numbers(): LibraryNumbers = numbers.also { call("numbers") }

    override suspend fun search(query: String, limit: Int): SearchResults = found.also { call("search $query") }
}
