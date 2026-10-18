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

/** A catalog for tests: [credits], person [pages], home's [directors] and [best] films; requests go to [calls]. */
class FakeCatalog : Catalog {
    var credits = mapOf<String, Credits>()
    var pages = mapOf<String, PersonPage>()
    var directors = listOf<Person>()
    var best = listOf<MediaItem>()

    /** Sagas whole, by collection id. */
    var sagas = mapOf<String, Saga>()

    /** The copies of each film, by its id; the one [chooseVersion] is told is chosen after. */
    var copies = mapOf<String, List<Version>>()
    val calls = mutableListOf<String>()

    override suspend fun credits(item: MediaItem): Credits = credits[item.id] ?: Credits()

    override suspend fun person(id: String): PersonPage? = pages[id].also { calls += "person $id" }

    override suspend fun people(job: Job, kind: MediaKind?, minTitles: Int, limit: Int): List<Person> =
        directors.take(limit).also { calls += "people $job" }

    override suspend fun bestRated(kind: MediaKind, minVotes: Int, limit: Int): List<MediaItem> = best.take(limit).also { calls += "best" }

    override suspend fun saga(collection: MediaItem): Saga? = sagas[collection.id]

    override suspend fun versions(item: MediaItem): List<Version> = copies[item.id].orEmpty()

    override suspend fun chooseVersion(item: MediaItem, version: Version?) {
        calls += "choose ${version?.key}"
        val chosen = version?.key
        copies = copies + (item.id to copies[item.id].orEmpty().map { it.copy(chosen = if (chosen == null) it.best else it.key == chosen) })
    }

    override suspend fun playback(version: Version, resume: Boolean, options: PlaybackOptions): PlaybackPlan = error("no playback")

    override suspend fun numbers(): LibraryNumbers = LibraryNumbers(0, 0, 0, null, null)

    override suspend fun search(query: String, limit: Int): SearchResults = SearchResults()
}
