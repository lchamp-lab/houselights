package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.yearOf
import java.text.Normalizer

/** What TMDB says of a film or a show, in the terms both share. */
data class TmdbDetails(
    val id: Int,
    val title: String,
    val originalTitle: String?,
    val overview: String?,
    val tagline: String?,
    val year: Int?,
    val runtimeMin: Int?,
    val certification: String?,
    val rating: Double?,
    /** The votes [rating] stands on. */
    val votes: Int? = null,
    val genres: List<String>,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    /** Films only. */
    val directors: List<String> = emptyList(),
    /** Shows only: the year of the last episode aired, and whether more are coming. */
    val endYear: Int? = null,
    val continuing: Boolean? = null,
)

/** [certification] and [logo] depend on the device's region and language: the client picks them. */
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

/** Artwork found next to the video or the show, already copied into the app. */
data class LocalArt(
    val poster: String? = null,
    val fanart: String? = null,
    val logo: String? = null,
    val thumb: String? = null,
) {
    companion object {
        /** What [item] already uses from local files. */
        fun of(item: ItemEntity) =
            LocalArt(item.posterPath.local(), item.backdropPath.local(), item.logoPath.local(), item.stillPath.local())

        private fun String?.local() = this?.takeIf { it.isCachedArtwork() }
    }
}

/**
 * How a film's or a show's details are chosen, without touching the network or the database:
 * what the NFO says (what the user's media manager decided), then TMDB, then what was known.
 * Artwork: files next to the video, then the NFO's addresses, then TMDB.
 */
object MetadataMerge {
    /** Articles titles start with, which sorting passes over; l' and l’ besides, glued to their word. */
    internal const val ARTICLE_WORDS = "the|a|an|le|la|les|un|une|des"

    /** A leading article, l' and l’ glued to the word ("L'Arnaque") as well. */
    private val ARTICLES = Regex("""(?i)^(?:(?:$ARTICLE_WORDS)\s+|l['’]\s*)""")
    private val MARKS = Regex("""\p{M}+""")

    /** Letters that NFD does not take apart into a base letter and its accent. */
    private val LIGATURES = mapOf('œ' to "oe", 'æ' to "ae", 'ß' to "ss", 'ø' to "o", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th")

    /**
     * Lower case, without a leading article, accents and ligatures folded: "The Batman" sorts with B,
     * "L'Arnaque" with A, "Ça" with C and "Œdipe" with O, not after Z as their UTF-8 bytes would.
     */
    fun sortKey(title: String): String = fold(ARTICLES.replace(title.trim(), ""))

    /**
     * [text] in lower case, accents dropped and ligatures spelled out: what [sortKey] does past the
     * article, alone (see LibraryDatabase's migration 9 to 10, which folds the keys already stored).
     */
    internal fun fold(text: String): String {
        val bare = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
        return if (bare.none { it in LIGATURES }) bare else buildString { bare.forEach { append(LIGATURES[it] ?: it) } }
    }

    /**
     * [base] with the details of [nfo] and [tmdb]. The NFO must already be one that describes this
     * title ([describesOther]).
     */
    @Suppress("CyclomaticComplexMethod") // One chain per field, NFO then TMDB then what was known: read across.
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
            // The votes of the rating kept: the NFO's when it gives the rating, as it may not say how many.
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
     * True when the NFO names another title than the one the user matched the file with:
     * it then no longer describes this file. Its TMDB id tells; else its IMDb id, else its TVDB id
     * (a show's NFO by Sonarr often has only those two), against the ones TMDB gives the match
     * ([imdbId], [tvdbId]). An NFO no id tells of is kept.
     */
    fun describesOther(nfo: NfoInfo, userTmdbId: Int?, imdbId: String?, tvdbId: Int? = null): Boolean {
        val nfoTvdbId = nfo.tvdbId?.trim()?.toIntOrNull()
        return when {
            userTmdbId == null -> false
            nfo.tmdbId != null -> nfo.tmdbId != userTmdbId
            nfo.imdbId != null && !imdbId.isNullOrBlank() -> !nfo.imdbId.equals(imdbId, ignoreCase = true)
            nfoTvdbId != null && tvdbId != null && tvdbId > 0 -> nfoTvdbId != tvdbId
            else -> false
        }
    }

    /** Only what the file or folder name says: details of a previous match are dropped. */
    fun withoutDetails(item: ItemEntity, title: String, year: Int?) = item.copy(
        tmdbId = null,
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
