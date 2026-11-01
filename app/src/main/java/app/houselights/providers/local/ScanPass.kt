package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser

/** Per-source context a pass hands to the indexers. */
@Suppress("LongParameterList") // One per part of the pass the indexers read.
internal class ScanPass(
    val source: SourceEntity,
    val sidecars: Sidecars,
    val stacks: Stacks,
    val tmdb: TmdbPass,
    /** TMDB films, each fetched once per pass. */
    val films: MovieIndexer.FilmCache,
    /** TMDB seasons, each fetched once per pass. */
    val seasons: ShowIndexer.SeasonCache,
    /** How TMDB numbers each show, read once per pass ([EpisodeOrders]). */
    val orders: EpisodeOrders,
    /** "Refresh all information": every title read is looked up on TMDB again. */
    val refreshMetadata: Boolean,
    /** Folders holding one film, whose names' ids are its own ([NameIds.ofFilm]). */
    val singleTitleFolders: Set<String> = emptySet(),
    /** Films and shows looked up by the ids their names give, other than those their last lookups tried ([NameIds.dueFor]). */
    val hinted: Set<Long> = emptySet(),
)

/**
 * A film's or show's lookup ([MovieIndexer], [ShowIndexer]): the title TMDB named, null for none. [hint] is the id its
 * name gives ([NameIds.key]) once the lookup tried the name's ids, whatever they found, which the row keeps
 * ([ItemEntity.hintKey]) so that name is not asked by again; [byName], the TMDB id of the title one of them named;
 * [offered], the candidates of a title TMDB hesitated on, the best first ([MatchScore.offered]).
 */
internal class Looked<out T>(
    val value: T?,
    val hint: String? = null,
    val byName: Int? = null,
    val offered: List<Scored> = emptyList(),
)

/** This row, merged, with what [lookup] made of it ([MetadataMerge.matchState]) and the name's id it tried ([Looked.hint]). */
internal fun ItemEntity.lookedUp(base: ItemEntity, lookup: TmdbAnswer<Looked<*>>?): ItemEntity =
    copy(matchState = MetadataMerge.matchState(this, lookup), hintKey = lookup?.value?.hint ?: base.hintKey)

/**
 * Keeps, in the transaction writing [item], the candidates [lookup] offered while TMDB hesitates on it
 * ([MatchState.UNSURE]), none once it is matched or not found; an unanswered lookup leaves them.
 */
internal suspend fun LibraryDao.offer(item: ItemEntity, lookup: TmdbAnswer<Looked<*>>?) {
    if (lookup !is TmdbAnswer.Given && item.matchState != MatchState.MATCHED) return
    val offered = lookup?.value?.offered.orEmpty().takeIf { item.matchState == MatchState.UNSURE }.orEmpty()
    replaceSuggestions(item.id, suggestionRows(item.id, offered))
}

/**
 * Writes what asking TMDB again ([Reidentify]) made of [item], still without a TMDB id: unsure with the candidates
 * [offered], else not found, asked [at]; nothing else of it changes. True when its state or its candidates did.
 */
internal suspend fun LibraryDao.rechecked(item: ItemEntity, offered: List<Scored>, at: Long): Boolean {
    val state = if (offered.isEmpty()) MatchState.NOT_FOUND else MatchState.UNSURE
    val before = suggestions(item.id).map { it.tmdbId }
    setMatchState(item.id, state, at)
    replaceSuggestions(item.id, suggestionRows(item.id, offered))
    return state != item.matchState || before != offered.map { it.candidate.id }
}

/** [offered] as [item]'s suggestions, the best first ([SuggestionEntity.rank] 0). */
internal fun suggestionRows(item: Long, offered: List<Scored>): List<SuggestionEntity> = offered.mapIndexed { rank, scored ->
    val result = scored.candidate.result
    SuggestionEntity(
        itemId = item, rank = rank, tmdbId = scored.candidate.id, title = result.title ?: result.name ?: scored.candidate.titles.first(),
        originalTitle = result.originalTitle ?: result.originalName, year = scored.candidate.year, overview = result.overview,
        posterPath = result.posterPath, score = scored.score,
    )
}

/** Row [id]'s stored NFO ([NfoDao.nfoText]), parsed; null without one. */
internal suspend fun LibraryDao.nfoInfo(id: Long): NfoInfo? = nfoText(id)?.let(NfoParser::parse)

/** Keeps the NFO a pass [read] as row [id]'s ([NfoDao.setNfo]); null leaves the stored one. */
internal suspend fun LibraryDao.keepNfo(id: Long, read: KeptNfo?) {
    if (read != null) setNfo(id, read.text)
}

/** Caches [local]'s images ([ScanFiles.copyArtwork]); null where missing or the copy failed. */
internal suspend fun ScanFiles.copyArt(source: SourceEntity, local: VideoSidecars): LocalArt {
    // In the order asked, one request for all.
    val copies = copyArtwork(source, listOf(local.poster, local.fanart, local.logo, local.thumb)).iterator()
    return LocalArt(poster = copies.next(), fanart = copies.next(), logo = copies.next(), thumb = copies.next())
}

/** The sidecars actually read, for [SidecarKey]; a failed NFO or image is left out so the next pass retries it. */
internal fun ScanFiles.sidecarsRead(local: VideoSidecars, nfo: KeptNfo, art: LocalArt): VideoSidecars = VideoSidecars(
    nfo = local.nfo.takeUnless { nfo.stale },
    poster = examined(local.poster, art.poster),
    fanart = examined(local.fanart, art.fanart),
    logo = examined(local.logo, art.logo),
    thumb = examined(local.thumb, art.thumb),
)

/** An id a lookup tries ([lookUp]): one the title's name gives, of kind [byName], else its NFO's or its own. */
internal class IdStep(val byName: IdKind?, val id: suspend () -> Int?)

/** What a lookup's ids found ([lookUp]): the title, else the first title a name's id found but did not trust. */
internal class ByIds<out T>(val found: Looked<T>?, val untrusted: T?)

/**
 * The first title of [steps] TMDB has ([fetch]), one by a name's id only when [trusted] ([NameIds.trusts]); an id TMDB
 * has no title for gives way to the next, as does one not trusted, and one tried before is not asked again. The title
 * found carries [hint], the name's id, the steps having tried it.
 */
internal suspend fun <T : Any> lookUp(
    hint: String?,
    steps: List<IdStep>,
    fetch: suspend (Int) -> T?,
    id: (T) -> Int,
    trusted: suspend (T, IdKind) -> Boolean,
): ByIds<T> {
    val tried = HashMap<Int, T?>()
    var untrusted: T? = null
    for (step in steps) {
        val found = step.id()?.let { key -> if (key in tried) tried[key] else fetch(key).also { tried[key] = it } }
        if (found != null) {
            val by = step.byName ?: return ByIds(Looked(found, hint), untrusted)
            if (trusted(found, by)) return ByIds(Looked(found, hint, byName = id(found)), untrusted)
            untrusted = untrusted ?: found
        }
    }
    return ByIds(null, untrusted)
}
