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
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.core.SearchResults
import app.houselights.core.Typeset
import app.houselights.core.Version
import app.houselights.core.yearOf
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** [Catalog] read from scan output: a few queries per call whatever the library size, mapped off the main thread. */
internal class LocalCatalog(
    private val context: Context,
    private val dao: LibraryDao,
    private val provider: LocalProvider,
    /** Minimum owned films for a collection to be shown; 0 hides collections. */
    private val collectionMinFilms: () -> Int,
) : Catalog {
    private fun itemKey(item: MediaItem): Long? = (LocalId.parse(item.id) as? LocalId.Item)?.key

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

    /** TMDB's parts in release order, plus owned films TMDB does not list (an NFO set), inserted by year. */
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
                    poster = item?.poster ?: part.posterPath?.let { LocalProvider.image(it, ImageType.POSTER) },
                    releaseDate = part.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                )
            }
            val listedIds = parts.mapNotNullTo(HashSet()) { it.itemId }
            val unlisted = films.filter { it.id !in listedIds }.mapNotNull { items[it.id.toString()] }
                .map { SagaPart(it, it.title, it.year, overview = it.overview, backdrop = it.backdrop) }
            Saga(shown, merged(listed, unlisted))
        }
    }

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
                    // Each copy held as its own file is, the film as the copy Lire plays ([LocalMapper]).
                    item = mapped.copy(missingSince = copy.missingSince),
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

    override suspend fun numbers(): LibraryNumbers {
        val files = dao.fileFacts()
        val shows = dao.count(ItemKind.SERIES)
        val directors = dao.topPeople(Job.DIRECTOR, ItemKind.MOVIE, PERSON_MIN_TITLES, NUMBERS_DIRECTORS)
        return withContext(Dispatchers.Default) { LibraryCount.of(files, shows, directors) }
    }

    /**
     * Prefix search where every typed word must start a name word; one query per kind, intersecting primary-key
     * ranges. Names starting with the query rank first, then rating, title count or film count.
     */
    override suspend fun search(query: String, limit: Int): SearchResults {
        val terms = SearchWords.query(query).take(MAX_TERMS).ifEmpty { return SearchResults() }
        val typed = terms.joinToString(" ")
        val titleIds = refs(SearchKind.TITLE, terms).mapNotNull { it.toLongOrNull() }
        // Titles starting with the query are fetched separately, as a common word like "le" fills the cap.
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

    private suspend fun refs(kind: SearchKind, terms: List<String>): List<String> {
        val one = "SELECT DISTINCT ref FROM search_words WHERE kind = ? AND word >= ? AND word < ?"
        val sql = terms.joinToString(" INTERSECT ") { one } + " LIMIT $MAX_HITS"
        val args = terms.flatMap { listOf(kind.name, it, SearchWords.upperBound(it)) }
        return dao.searchRefs(SimpleSQLiteQuery(sql, args.toTypedArray())).map { it.ref }
    }

    private suspend fun sagas(ids: List<Long>, typed: String, limit: Int): List<MediaItem> {
        val min = collectionMinFilms().takeIf { it > 0 } ?: return emptyList()
        val collections = ids.chunked(SQL_IN_LIMIT).flatMap { dao.collections(it) }
        val members = provider.membersOf(collections.map { it.id })
        val shown = collections.filter { members[it.id].orEmpty().size >= min }
            .sortedWith(compareBy<CollectionEntity>({ rank(it.name, typed) }, { -members[it.id].orEmpty().size }, { it.sortTitle }))
            .take(limit)
        return provider.collectionItems(shown)
    }

    private fun rank(title: ItemEntity, typed: String): Int = minOf(rank(title.title, typed), rank(title.originalTitle, typed))

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
        /** Maximum query words used; more would not narrow results further. */
        const val MAX_TERMS = 6

        /** Cap on refs per kind before ranking, as a single letter matches most of a library. */
        const val MAX_HITS = 400

        const val NO_MATCH = 2

        const val NUMBERS_DIRECTORS = 12
    }
}
