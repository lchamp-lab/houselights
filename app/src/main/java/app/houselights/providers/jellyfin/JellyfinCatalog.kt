package app.houselights.providers.jellyfin

import android.util.Log
import app.houselights.core.Catalog
import app.houselights.core.Credits
import app.houselights.core.Definition
import app.houselights.core.Editions
import app.houselights.core.Job
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryNumbers
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.core.PersonTitle
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.Quality
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.core.SearchResults
import app.houselights.core.Tally
import app.houselights.core.Typeset
import app.houselights.core.Version
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.VersionRank
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * What a Jellyfin server tells beyond its titles ([Catalog]), as its API gives it: a title's People (cast and
 * crew, no cinematographer among Jellyfin's kinds), a person's titles (the server filters by person), the copies
 * of a film (its media sources), a box set's films (the server knows no film a set lacks), titles counted and
 * searched by the server. It counts no person's titles from a title's page (one request each): their counts are
 * unknown ([Person.titleCount] null), nor the room or formats of the library ([ProviderCapabilities.formatNumbers]).
 */
internal class JellyfinCatalog(
    private val api: JellyfinApi,
    private val mapper: JellyfinMapper,
    private val userId: String,
    private val provider: JellyfinProvider,
    /** Where answers are made into titles, off the main thread. */
    private val mapping: CoroutineContext,
) : Catalog {
    override suspend fun credits(item: MediaItem): Credits {
        val owner = if (item.kind == MediaKind.EPISODE || item.kind == MediaKind.SEASON) item.seriesId ?: item.id else item.id
        val people = api.item(owner, userId).people
        return withContext(mapping) {
            val byJob = people.mapNotNull { p -> jobOf(p.type)?.let { it to p } }.groupBy({ it.first }, { it.second })
                .mapValues { (job, list) -> list.distinctBy { it.id ?: it.name }.map { person(it, job) } }
            Credits(cast = byJob[Job.CAST].orEmpty(), crew = byJob - Job.CAST)
        }
    }

    private fun person(p: BaseItemPerson, job: Job) = Person(
        name = Typeset.text(p.name.orEmpty()),
        role = if (job == Job.CAST) Typeset.text(p.role) else null,
        image = mapper.image(p.id, "Primary", p.primaryImageTag),
        id = p.id,
    )

    override suspend fun person(id: String): PersonPage? = coroutineScope {
        // A person the server no longer has: their titles say who they were, or there is no page.
        val who = async { suspendRunCatching { api.item(id, userId) }.getOrNull() }
        val titles = api.items(
            userId = userId, parentId = null, includeItemTypes = "Movie,Series", recursive = true,
            sortBy = "ProductionYear,PremiereDate,SortName", sortOrder = "Ascending", startIndex = 0, limit = PERSON_TITLES,
            fields = "$ROW_FIELDS,People", personIds = id,
        ).items
        val person = who.await()
        if (titles.isEmpty()) return@coroutineScope null
        withContext(mapping) {
            val shown = titles.map { title ->
                val mine = title.people.filter { it.id == id }
                val jobs = mine.mapNotNull { jobOf(it.type) }.distinct().sorted()
                PersonTitle(mapper.toMedia(title), jobs, Typeset.text(mine.firstNotNullOfOrNull { it.role }))
            }
            val jobs = shown.flatMap { it.jobs }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Job, Int>> { it.value }.thenBy { it.key }).associate { it.key to it.value }
            val image = person?.let { mapper.image(it.id, "Primary", it.imageTags["Primary"]) }
            val name = person?.name ?: titles.firstNotNullOfOrNull { title -> title.people.firstOrNull { it.id == id }?.name }
            PersonPage(Person(Typeset.text(name.orEmpty()), null, image, id, shown.size), jobs, shown)
        }
    }

    /** Jellyfin does not rank people by their titles: none. */
    override suspend fun people(job: Job, kind: MediaKind?, minTitles: Int, limit: Int): List<Person> = emptyList()

    /** The best rated by the server's community rating; it does not say on how many votes. */
    override suspend fun bestRated(kind: MediaKind, minVotes: Int, limit: Int): List<MediaItem> {
        val type = when (kind) {
            MediaKind.MOVIE -> "Movie"
            MediaKind.SERIES -> "Series"
            else -> return emptyList()
        }
        val items = api.items(userId, null, type, true, "CommunityRating,SortName", "Descending", 0, limit, ROW_FIELDS).items
        return withContext(mapping) { items.filter { it.communityRating != null }.map(mapper::toMedia) }
    }

    /** A box set holds the films the server has, and only them. */
    override suspend fun saga(collection: MediaItem): Saga {
        val films = provider.collectionItems(collection)
        return Saga(collection, films.map { SagaPart(it, it.title, it.year, overview = it.overview, backdrop = it.backdrop) })
    }

    /** The media sources of the film: each a copy, the first the one the server plays, the best ranked as for a local film. */
    override suspend fun versions(item: MediaItem): List<Version> {
        if (item.kind != MediaKind.MOVIE) return emptyList()
        val full = api.item(item.id, userId)
        val sources = full.mediaSources.takeIf { it.size > 1 } ?: return emptyList()
        return withContext(mapping) {
            val title = mapper.toMedia(full)
            val copies = sources.map { source -> source to JellyfinMapper.mediaInfo(full, source) }
            val best = copies.indices.maxWith(compareBy(VersionRank.comparator) { i ->
                copies[i].let { (source, media) -> VersionRank.Copy(media?.badges().orEmpty(), media, source.size) }
            })
            copies.mapIndexed { i, (source, media) ->
                Version(
                    item = title.copy(media = media, badges = media?.badges().orEmpty(), runtimeMs = media?.durationMs ?: title.runtimeMs),
                    edition = source.name?.let(Editions::find),
                    label = Typeset.text(source.name),
                    media = media,
                    sizeBytes = source.size,
                    runtimeMs = media?.durationMs,
                    // The server keeps no choice: the copy it plays first is the one "Lire" plays.
                    chosen = i == 0,
                    best = i == best,
                    key = source.id.orEmpty(),
                )
            }
        }
    }

    /** The server keeps no choice of a copy ([ProviderCapabilities.versionChoice]). */
    override suspend fun chooseVersion(item: MediaItem, version: Version?) = Unit

    override suspend fun playback(version: Version, resume: Boolean, options: PlaybackOptions): PlaybackPlan =
        provider.play(version.item.id, version.key.ifEmpty { null }, resume, options)

    /** Titles as the server counts them, and its 4K films and shows: nothing of their room nor their hours. */
    override suspend fun numbers(): LibraryNumbers = coroutineScope {
        suspend fun count(type: String, is4K: Boolean? = null) =
            api.items(userId, null, type, true, "SortName", "Ascending", 0, 0, "", is4K = is4K).totalRecordCount
        val films = async { count("Movie") }
        val shows = async { count("Series") }
        val episodes = async { count("Episode") }
        val uhd = async { suspendRunCatching { count("Movie", is4K = true) }.onFailure { Log.w(TAG, "4K count failed", it) }.getOrNull() }
        val uhdFilms = uhd.await()
        LibraryNumbers(
            films = films.await(), shows = shows.await(), episodes = episodes.await(), sizeBytes = null, runtimeMs = null,
            picture = listOfNotNull(
                uhdFilms?.takeIf { it > 0 }?.let { Tally(Definition.ULTRA_HD.token, it, 0, LibraryFilter(quality = Quality.UHD_4K)) },
            ),
        )
    }

    /** The server's search, of titles, people and box sets, each asked apart, at once. */
    override suspend fun search(query: String, limit: Int): SearchResults = coroutineScope {
        val term = query.trim().ifEmpty { return@coroutineScope SearchResults() }
        suspend fun found(types: String) =
            api.items(userId, null, types, true, "SortName", "Ascending", 0, limit, ROW_FIELDS, searchTerm = term).items
        val titles = async { found("Movie,Series") }
        val sagas = async { found("BoxSet") }
        val people = api.persons(userId, term, limit).items
        withContext(mapping) {
            SearchResults(
                titles = titles.await().map(mapper::toMedia),
                people = people.map {
                    Person(Typeset.text(it.name.orEmpty()), null, mapper.image(it.id, "Primary", it.imageTags["Primary"]), it.id)
                },
                sagas = sagas.await().map(mapper::toMedia),
            )
        }
    }

    private companion object {
        const val TAG = "Jellyfin"
        const val PERSON_TITLES = 200
        const val ROW_FIELDS = "Overview,Genres,ProductionYear,OfficialRating,CommunityRating,DateCreated"

        /** Jellyfin's kinds of person, as the jobs of the end credits. */
        fun jobOf(type: String?): Job? = when (type) {
            "Actor", "GuestStar" -> Job.CAST
            "Director" -> Job.DIRECTOR
            "Creator" -> Job.CREATOR
            "Writer" -> Job.WRITER
            "Composer" -> Job.COMPOSER
            "Editor" -> Job.EDITING
            else -> null
        }
    }
}
