package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.SortKeys
import app.houselights.core.yearOf

/** TMDB details of a film or show, in a shape common to both. */
data class TmdbDetails(
    val id: Int,
    val title: String?,
    val originalTitle: String?,
    val overview: String?,
    val tagline: String?,
    val year: Int?,
    val runtimeMin: Int?,
    val certification: String?,
    val rating: Double?,
    /** Number of votes behind [rating]. */
    val votes: Int? = null,
    val genres: List<String>,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val directors: List<String> = emptyList(),
    /** Shows only: year of the last aired episode, and whether more are coming. */
    val endYear: Int? = null,
    val continuing: Boolean? = null,
)

/** [certification] and [logo] depend on the client's region and language, so the client picks them. */
fun TmdbMovie.details(certification: String?, logo: String?) = TmdbDetails(
    id = id,
    title = title,
    originalTitle = originalTitle,
    overview = overview?.ifBlank { null },
    tagline = tagline?.ifBlank { null },
    year = yearOf(releaseDate),
    runtimeMin = runtime?.takeIf { it > 0 },
    certification = certification,
    rating = voteAverage?.takeIf { it > 0 },
    votes = voteCount?.takeIf { voteAverage != null && voteAverage > 0 },
    genres = genres.map { it.name },
    posterPath = posterPath,
    backdropPath = backdropPath,
    logoPath = logo,
    directors = credits.crew.filter { it.job == "Director" }.map { it.name },
)

fun TmdbTv.details(certification: String?, logo: String?) = TmdbDetails(
    id = id,
    title = name,
    originalTitle = originalName,
    overview = overview?.ifBlank { null },
    tagline = tagline?.ifBlank { null },
    year = yearOf(firstAirDate),
    runtimeMin = episodeRunTime.firstOrNull(),
    certification = certification,
    rating = voteAverage?.takeIf { it > 0 },
    votes = voteCount?.takeIf { voteAverage != null && voteAverage > 0 },
    genres = genres.map { it.name },
    posterPath = posterPath,
    backdropPath = backdropPath,
    logoPath = logo,
    endYear = yearOf(lastAirDate),
    continuing = status == "Returning Series" || status == "In Production",
)

/** Sidecar artwork of the video or show, already cached by the app. */
data class LocalArt(
    val poster: String? = null,
    val fanart: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
) {
    companion object {
        fun of(item: ItemEntity) =
            LocalArt(item.posterPath.local(), item.backdropPath.local(), item.logoPath.local(), item.stillPath.local())

        private fun String?.local() = this?.takeIf { it.isCachedArtwork() }
    }
}

/** Pure detail rules: NFO (the media manager's choice), then TMDB, then stored values; artwork: sidecars, NFO, TMDB. */
object MetadataMerge {
    /** Leading articles ignored when sorting (see [SortKeys]). */
    internal const val ARTICLE_WORDS = SortKeys.ARTICLE_WORDS

    fun sortKey(title: String): String = SortKeys.sortKey(title)

    /** [text] folded as [sortKey] folds it after the article ([SortKeys.fold]). */
    internal fun fold(text: String): String = SortKeys.fold(text)

    /** The caller must have checked that [nfo] describes this title ([describesOther]). */
    @Suppress("CyclomaticComplexMethod") // One fallback chain per field: NFO, TMDB, stored value.
    fun merge(base: ItemEntity, nfo: NfoInfo?, tmdb: TmdbDetails?, art: LocalArt): ItemEntity {
        val title = nfo?.title ?: tmdb?.title ?: base.title
        val show = base.kind == ItemKind.SERIES
        return base.copy(
            tmdbId = tmdb?.id ?: nfo?.tmdbId ?: base.tmdbId,
            title = title,
            sortTitle = sortKey(nfo?.sortTitle ?: title),
            originalTitle = nfo?.originalTitle ?: tmdb?.originalTitle ?: base.originalTitle,
            overview = nfo?.plot ?: tmdb?.overview ?: base.overview,
            tagline = nfo?.tagline ?: tmdb?.tagline ?: base.tagline,
            year = nfo?.year ?: yearOf(nfo?.premiered) ?: tmdb?.year ?: base.year,
            endYear = tmdb?.endYear ?: base.endYear,
            continuing = if (show) continuing(nfo, tmdb) ?: base.continuing else base.continuing,
            runtimeMin = nfo?.runtimeMin ?: tmdb?.runtimeMin ?: base.runtimeMin,
            certification = nfo?.certification ?: tmdb?.certification ?: base.certification,
            rating = nfo?.rating ?: tmdb?.rating ?: base.rating,
            // Votes follow the chosen rating: the NFO's when it gives the rating, even if it has no vote count.
            votes = if (nfo?.rating != null) nfo.votes else tmdb?.votes ?: base.votes,
            genres = nfo?.genres?.takeIf { it.isNotEmpty() } ?: tmdb?.genres ?: base.genres,
            directors = if (show) base.directors else directors(nfo, tmdb) ?: base.directors,
            posterPath = art.poster ?: nfo?.posterUrl ?: tmdb?.posterPath ?: base.posterPath,
            backdropPath = art.fanart ?: nfo?.fanartUrl ?: tmdb?.backdropPath ?: base.backdropPath,
            logoPath = art.logo ?: nfo?.logoUrl ?: tmdb?.logoPath ?: base.logoPath,
            stillPath = art.thumb ?: base.stillPath,
        )
    }

    private fun continuing(nfo: NfoInfo?, tmdb: TmdbDetails?): Boolean? =
        nfo?.status?.let { it.contains("Continuing", ignoreCase = true) || it.contains("Returning", ignoreCase = true) }
            ?: tmdb?.continuing

    private fun directors(nfo: NfoInfo?, tmdb: TmdbDetails?): List<String>? =
        nfo?.directors?.takeIf { it.isNotEmpty() } ?: tmdb?.directors

    /**
     * True when the NFO describes another title than the one chosen, the user's match or the one its name's id named
     * ([chosenTmdbId]), by TMDB id, else IMDb, else TVDB id (often all a Sonarr NFO has). Without a comparable id, the
     * NFO is kept.
     */
    fun describesOther(nfo: NfoInfo, chosenTmdbId: Int?, imdbId: String?, tvdbId: Int? = null): Boolean {
        val nfoTvdbId = nfo.tvdbId?.trim()?.toIntOrNull()
        return when {
            chosenTmdbId == null -> false
            nfo.tmdbId != null -> nfo.tmdbId != chosenTmdbId
            nfo.imdbId != null && !imdbId.isNullOrBlank() -> !nfo.imdbId.equals(imdbId, ignoreCase = true)
            nfoTvdbId != null && tvdbId != null && tvdbId > 0 -> nfoTvdbId != tvdbId
            else -> false
        }
    }

    /**
     * True when [nfo] names, by its TMDB id, another title than the one [item] was matched with automatically: a match
     * corrected in a media manager, which only TMDB's answer describes; a user's match decides alone ([describesOther]).
     */
    fun namesAnother(nfo: NfoInfo?, item: ItemEntity): Boolean {
        val named = nfo?.tmdbId ?: return false
        return item.userTmdbId == null && item.tmdbId != null && named != item.tmdbId
    }

    /**
     * What TMDB made of [item], merged ([MatchState]): matched with a TMDB id, TMDB's or its NFO's; else, once TMDB
     * answered [lookup], unsure when it offered candidates ([Looked.offered]), else not found; else as it was.
     */
    internal fun matchState(item: ItemEntity, lookup: TmdbAnswer<Looked<*>>?): MatchState? = when {
        item.tmdbId != null -> MatchState.MATCHED
        lookup !is TmdbAnswer.Given -> item.matchState
        lookup.value?.offered.isNullOrEmpty() -> MatchState.NOT_FOUND
        else -> MatchState.UNSURE
    }

    /** Keeps only what the file or folder name gives, dropping details of a previous match and what TMDB made of it. */
    fun withoutDetails(item: ItemEntity, title: String, year: Int?) = item.copy(
        tmdbId = null,
        matchState = null,
        title = title,
        sortTitle = sortKey(title),
        originalTitle = null,
        overview = null,
        tagline = null,
        year = year,
        endYear = null,
        runtimeMin = null,
        certification = null,
        rating = null,
        votes = null,
        genres = emptyList(),
        directors = emptyList(),
        posterPath = null,
        backdropPath = null,
        logoPath = null,
        stillPath = null,
    )
}
