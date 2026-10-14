package app.houselights.providers.local

import app.houselights.core.NfoActor
import app.houselights.core.NfoInfo
import app.houselights.core.yearOf

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
    private val ARTICLES = Regex("""(?i)^(the|a|an|le|la|les|l'|un|une|des)\s+""")
    private const val CAST_SIZE = 30

    /** Lower case, without a leading article: "The Batman" sorts with B. */
    fun sortKey(title: String): String = ARTICLES.replace(title.trim(), "").lowercase()

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
     * it then no longer describes this file.
     */
    fun describesOther(nfo: NfoInfo, userTmdbId: Int?, imdbId: String?): Boolean = when {
        userTmdbId == null -> false
        nfo.tmdbId != null -> nfo.tmdbId != userTmdbId
        nfo.imdbId != null && imdbId != null -> !nfo.imdbId.equals(imdbId, ignoreCase = true)
        else -> false
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
        genres = emptyList(),
        directors = emptyList(),
        posterPath = null,
        backdropPath = null,
        logoPath = null,
        stillPath = null,
    )

    /** The NFO's actors when it lists any, else TMDB's: the first [CAST_SIZE] in billing order. */
    fun cast(itemId: Long, nfo: NfoInfo?, credits: TmdbCredits?): List<CastEntity>? = when {
        nfo != null && nfo.actors.isNotEmpty() -> castOf(itemId, nfo.actors)
        credits != null -> castOf(itemId, credits)
        else -> null
    }

    private fun castOf(itemId: Long, actors: List<NfoActor>) =
        actors.sortedBy { it.order ?: Int.MAX_VALUE }.take(CAST_SIZE).mapIndexed { i, a ->
            CastEntity(itemId, i, a.name, a.role?.ifBlank { null }, a.thumb)
        }

    private fun castOf(itemId: Long, credits: TmdbCredits) =
        credits.cast.sortedBy { it.order }.take(CAST_SIZE).mapIndexed { i, c ->
            CastEntity(itemId, i, c.name, c.character?.ifBlank { null }, c.profilePath)
        }
}
