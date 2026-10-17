package app.houselights.providers.local

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import app.houselights.core.Catalog
import app.houselights.core.Credits
import app.houselights.core.Job
import app.houselights.core.LibraryNumbers
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.PERSON_MIN_TITLES
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.core.PersonTitle
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.core.SearchResults
import app.houselights.core.Typeset
import app.houselights.core.Version
import app.houselights.core.yearOf
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the local library tells beyond its titles ([Catalog]): its credits, people, sagas whole, copies of a
 * film, numbers and search, read from what scans wrote ([CreditEntity], [SagaPartEntity], [SearchWordEntity],
 * [ItemEntity.versionOf]). Each read is a few queries whatever the library's size, and its rows are made into
 * titles off the main thread.
 */
internal class LocalCatalog(
    private val context: Context,
    private val dao: LibraryDao,
    private val provider: LocalProvider,
    /** Films of a collection the library needs to show it; 0 hides collections. */
    private val collectionMinFilms: () -> Int,
) : Catalog {
    private fun itemKey(item: MediaItem): Long? = (LocalId.parse(item.id) as? LocalId.Item)?.key

    /** [item]'s row as cards read it ([CARD_ROW]). */
    private suspend fun row(item: MediaItem): ItemEntity? = itemKey(item)?.let { dao.rows(listOf(it)).singleOrNull() }

    override suspend fun credits(item: MediaItem): Credits {
        val row = row(item) ?: return Credits()
        val owner = if (row.kind == ItemKind.MOVIE || row.kind == ItemKind.SERIES) row.id else row.seriesId ?: return Credits()
        val credits = dao.creditsOf(owner)
        return withContext(Dispatchers.Default) {
            val byJob = credits.groupBy { it.job }.mapValues { (_, people) -> people.sortedBy { it.ord }.map(::person) }
            val crew = Job.entries.filter { it != Job.CAST }.mapNotNull { job -> byJob[job]?.let { job to it } }.toMap()
            Credits(cast = byJob[Job.CAST].orEmpty(), crew = crew)
        }
    }

    override suspend fun person(id: String): PersonPage? {
        val credits = dao.creditsOfPerson(id).ifEmpty { return null }
        val rows = credits.map { it.itemId }.distinct().chunked(SQL_IN_LIMIT).flatMap { dao.rows(it) }
            .sortedWith(compareBy<ItemEntity>({ it.year == null }, { it.year }, { it.sortTitle }, { it.id }))
        val items = LocalMapper(dao, context).mapAll(rows)
        return withContext(Dispatchers.Default) {
            val byTitle = credits.groupBy { it.itemId }
            val titles = rows.zip(items).map { (row, item) ->
                val mine = byTitle.getValue(row.id)
                PersonTitle(item, mine.map { it.job }.distinct().sorted(), Typeset.text(mine.firstOrNull { it.job == Job.CAST }?.character))
            }
            val jobs = credits.groupBy { it.job }.mapValues { (_, c) -> c.map { it.itemId }.distinct().size }.entries
                .sortedWith(compareByDescending<Map.Entry<Job, Int>> { it.value }.thenBy { it.key }).associate { it.key to it.value }
            val name = credits.groupingBy { it.name }.eachCount().maxBy { it.value }.key
            val portrait = credits.firstNotNullOfOrNull { it.profilePath }?.let { LocalProvider.image(it, ImageType.PROFILE) }
            PersonPage(Person(Typeset.text(name), null, portrait, id, titles.size), jobs, titles)
        }
    }

    override suspend fun people(job: Job, kind: MediaKind?, minTitles: Int, limit: Int): List<Person> {
        val rows = when (kind) {
            null -> null
            MediaKind.MOVIE -> ItemKind.MOVIE
            MediaKind.SERIES -> ItemKind.SERIES
            else -> return emptyList()
        }
        val people = dao.topPeople(job, rows, minTitles, limit)
        return withContext(Dispatchers.Default) { people.map(::person) }
    }

    override suspend fun bestRated(kind: MediaKind, minVotes: Int, limit: Int): List<MediaItem> {
        val rows = when (kind) {
            MediaKind.MOVIE -> ItemKind.MOVIE
            MediaKind.SERIES -> ItemKind.SERIES
            else -> return emptyList()
        }
        return LocalMapper(dao, context).mapAll(dao.bestRated(rows, minVotes, limit))
    }

    /**
     * The saga whole: the films TMDB lists of it, held or not, in the order they came out, and those the library
     * holds in it that TMDB does not list (an NFO's set), at their year. A saga TMDB was not asked for (an NFO's
     * own, or not yet) is the films the library holds.
     */
    override suspend fun saga(collection: MediaItem): Saga? {
        val key = (LocalId.parse(collection.id) as? LocalId.Collection)?.key ?: return null
        val entity = dao.collection(key) ?: return null
        val parts = dao.sagaParts(key)
        val films = dao.collectionFilms(key)
        val inSaga = films.mapTo(HashSet()) { it.id }
        val elsewhere = parts.mapNotNull { it.itemId }.filter { it !in inSaga }.chunked(SQL_IN_LIMIT).flatMap { dao.rows(it) }
        val rows = films + elsewhere
        val items = LocalMapper(dao, context).mapAll(rows).associateBy { it.id }
        val shown = provider.collectionItems(listOf(entity)).single()
        return withContext(Dispatchers.Default) {
            val today = CollectionRules.today()
            val listed = parts.map { (part, itemId) ->
                val item = itemId?.let { items[it.toString()] }
                SagaPart(
                    item = item,
                    title = item?.title ?: Typeset.text(part.title),
                    year = item?.year ?: yearOf(part.releaseDate),
                    released = part.releaseDate?.let { it <= today } ?: false,
                    overview = item?.overview ?: Typeset.text(part.overview),
                    backdrop = item?.backdrop ?: part.backdropPath?.let { LocalProvider.image(it, ImageType.BACKDROP) },
                )
            }
            val listedIds = parts.mapNotNullTo(HashSet()) { it.itemId }
            val unlisted = films.filter { it.id !in listedIds }.mapNotNull { items[it.id.toString()] }
                .map { SagaPart(it, it.title, it.year, overview = it.overview, backdrop = it.backdrop) }
            Saga(shown, merged(listed, unlisted))
        }
    }

    /** [unlisted] films among [listed] ones, each after the last whose year is not later than its own. */
    private fun merged(listed: List<SagaPart>, unlisted: List<SagaPart>): List<SagaPart> {
        val all = listed.toMutableList()
        for (film in unlisted) {
            val year = film.year
            val at = if (year == null) all.size else all.indexOfLast { (it.year ?: Int.MAX_VALUE) <= year } + 1
            all.add(at, film)
        }
        return all
    }

    override suspend fun versions(item: MediaItem): List<Version> {
        val row = row(item)?.takeIf { it.kind == ItemKind.MOVIE } ?: return emptyList()
        val titleId = row.versionOf ?: row.id
        val copies = dao.copies(titleId).takeIf { it.size > 1 } ?: return emptyList()
        val items = LocalMapper(dao, context).mapAll(copies)
        return withContext(Dispatchers.Default) {
            val best = VersionRank.best(copies)
            val choice = copies.firstOrNull { it.id == titleId }?.versionChoice?.takeIf { id -> copies.any { it.id == id } } ?: best
            copies.zip(items).map { (copy, mapped) ->
                Version(
                    item = mapped,
                    edition = copy.edition,
                    label = null,
                    media = mapped.media,
                    sizeBytes = mapped.media?.sizeBytes ?: copy.fileSize,
                    runtimeMs = mapped.runtimeMs,
                    chosen = copy.id == choice,
                    best = copy.id == best,
                    key = copy.id.toString(),
                )
            }.sortedWith(compareByDescending<Version> { it.chosen }.thenByDescending { it.best })
        }
    }

    override suspend fun chooseVersion(item: MediaItem, version: Version?) {
        val row = row(item)?.takeIf { it.kind == ItemKind.MOVIE } ?: return
        dao.setVersionChoice(row.versionOf ?: row.id, version?.key?.toLongOrNull())
    }

    /** This copy itself, whichever the film's title would play. */
    override suspend fun playback(version: Version, resume: Boolean, options: PlaybackOptions): PlaybackPlan =
        provider.play(version.key.toLong(), part = null, resume)

    override suspend fun numbers(): LibraryNumbers {
        val files = dao.fileFacts()
        val shows = dao.count(ItemKind.SERIES)
        val directors = dao.topPeople(Job.DIRECTOR, ItemKind.MOVIE, PERSON_MIN_TITLES, NUMBERS_DIRECTORS)
        return withContext(Dispatchers.Default) { LibraryCount.of(files, shows, directors) }
    }

    /**
     * Each word typed starts a word of a title's names (its title, its original title), a person's or a saga's:
     * one query a kind, each word's range read through the words' primary key, the kinds' words intersected. Then
     * the best first: what starts with all that was typed, the best rated titles, the people in the most titles,
     * the sagas of the most films.
     */
    override suspend fun search(query: String, limit: Int): SearchResults {
        val terms = SearchWords.query(query).take(MAX_TERMS).ifEmpty { return SearchResults() }
        val typed = terms.joinToString(" ")
        val titleIds = refs(SearchKind.TITLE, terms).mapNotNull { it.toLongOrNull() }
        // Those whose title starts with what is typed are found whatever the cap: a word as common as "le" fills it.
        val prefix = MetadataMerge.sortKey(query)
        val starting = if (prefix.isEmpty()) emptyList() else dao.titlesStarting(prefix, SearchWords.upperBound(prefix), limit)
        val titles = (starting + titleIds.chunked(SQL_IN_LIMIT).flatMap { dao.rows(it) }).distinctBy { it.id }
            .filter { (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) && it.versionOf == null }
            .sortedWith(compareBy({ rank(it, typed) }, { -(it.rating ?: 0.0) }, { it.sortTitle }))
            .take(limit)
        val people = refs(SearchKind.PERSON, terms).chunked(SQL_IN_LIMIT).flatMap { dao.people(it) }
            .sortedWith(compareBy<PersonSummary>({ rank(it.name, typed) }, { -it.titles }, { it.name })).take(limit)
        val sagas = sagas(refs(SearchKind.SAGA, terms).mapNotNull { it.toLongOrNull() }, typed, limit)
        val mapped = LocalMapper(dao, context).mapAll(titles)
        return withContext(Dispatchers.Default) { SearchResults(mapped, people.map(::person), sagas) }
    }

    /** The refs of [kind] each of whose [terms] starts one of its words, [MAX_HITS] at most. */
    private suspend fun refs(kind: SearchKind, terms: List<String>): List<String> {
        val one = "SELECT DISTINCT ref FROM search_words WHERE kind = ? AND word >= ? AND word < ?"
        val sql = terms.joinToString(" INTERSECT ") { one } + " LIMIT $MAX_HITS"
        val args = terms.flatMap { listOf(kind.name, it, SearchWords.upperBound(it)) }
        return dao.searchRefs(SimpleSQLiteQuery(sql, args.toTypedArray())).map { it.ref }
    }

    /** The sagas [ids] the library shows (enough of their films held), best first. */
    private suspend fun sagas(ids: List<Long>, typed: String, limit: Int): List<MediaItem> {
        val min = collectionMinFilms().takeIf { it > 0 } ?: return emptyList()
        val collections = ids.chunked(SQL_IN_LIMIT).flatMap { dao.collections(it) }
        val members = provider.membersOf(collections.map { it.id })
        val shown = collections.filter { members[it.id].orEmpty().size >= min }
            .sortedWith(compareBy<CollectionEntity>({ rank(it.name, typed) }, { -members[it.id].orEmpty().size }, { it.sortTitle }))
            .take(limit)
        return provider.collectionItems(shown)
    }

    /** How a title ranks for what was [typed]: by the better of its names. */
    private fun rank(title: ItemEntity, typed: String): Int = minOf(rank(title.title, typed), rank(title.originalTitle, typed))

    /** How a name ranks for what was [typed]: the same words first, then those starting so, then any. */
    private fun rank(name: String?, typed: String): Int {
        val words = SearchWords.query(name ?: return NO_MATCH).joinToString(" ")
        return when {
            words == typed -> 0
            words.startsWith(typed) -> 1
            else -> NO_MATCH
        }
    }

    private fun person(c: TitleCredit) =
        Person(Typeset.text(c.name), Typeset.text(c.character), portrait(c.profilePath), c.personKey, c.titles)

    private fun person(p: PersonSummary) = Person(Typeset.text(p.name), null, portrait(p.profilePath), p.personKey, p.titles)

    private fun portrait(path: String?) = path?.let { LocalProvider.image(it, ImageType.PROFILE) }

    private companion object {
        /** Words of a search read at most: more narrow nothing a title holds. */
        const val MAX_TERMS = 6

        /** Refs a kind's words give at most, ranked after: a letter alone finds most of a library. */
        const val MAX_HITS = 400

        /** The rank of a name that does not start with what was typed. */
        const val NO_MATCH = 2

        /** Directors the library in numbers names at most. */
        const val NUMBERS_DIRECTORS = 12
    }
}
