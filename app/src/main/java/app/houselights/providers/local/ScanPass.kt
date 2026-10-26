package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser

/** Per-source context a pass hands to the indexers. */
internal class ScanPass(
    val source: SourceEntity,
    val sidecars: Sidecars,
    val stacks: Stacks,
    val tmdb: TmdbPass,
    /** TMDB seasons, each fetched once per pass. */
    val seasons: ShowIndexer.SeasonCache,
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
 * ([ItemEntity.hintKey]) so that name is not asked by again; [byName], the TMDB id of the title one of them named.
 */
internal class Looked<out T>(val value: T?, val hint: String? = null, val byName: Int? = null)

/** This row, merged, with what [lookup] made of it ([MetadataMerge.matchState]) and the name's id it tried ([Looked.hint]). */
internal fun ItemEntity.lookedUp(base: ItemEntity, lookup: TmdbAnswer<Looked<*>>?): ItemEntity =
    copy(matchState = MetadataMerge.matchState(this, lookup), hintKey = lookup?.value?.hint ?: base.hintKey)

/** The row's stored NFO, parsed; null without one. */
internal fun ItemEntity.nfoInfo(): NfoInfo? = nfo?.let(NfoParser::parse)

/** Caches [local]'s images ([ScanFiles.copyArtwork]); null where missing or the copy failed. */
internal suspend fun ScanFiles.copyArt(source: SourceEntity, local: VideoSidecars): LocalArt = LocalArt(
    poster = local.poster?.let { copyArtwork(source, it) },
    fanart = local.fanart?.let { copyArtwork(source, it) },
    logo = local.logo?.let { copyArtwork(source, it) },
    thumb = local.thumb?.let { copyArtwork(source, it) },
)

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

/**
 * The first title of [steps] TMDB has ([fetch]), one by a name's id only when [trusted] ([NameIds.trusts]); an id TMDB
 * has no title for gives way to the next, as does one not trusted, and one tried before is not asked again. Null when
 * none is found; else it carries [hint], the name's id, the steps having tried it.
 */
internal suspend fun <T : Any> lookUp(
    hint: String?,
    steps: List<IdStep>,
    fetch: suspend (Int) -> T?,
    id: (T) -> Int,
    trusted: suspend (T, IdKind) -> Boolean,
): Looked<T>? {
    val tried = HashMap<Int, T?>()
    for (step in steps) {
        val found = step.id()?.let { key -> if (key in tried) tried[key] else fetch(key).also { tried[key] = it } }
        if (found != null) {
            val by = step.byName ?: return Looked(found, hint)
            if (trusted(found, by)) return Looked(found, hint, byName = id(found))
        }
    }
    return null
}
