package app.houselights.providers.trakt

import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.SyncRow
import java.time.Instant
import java.time.format.DateTimeParseException

/** A title as both Trakt and the library name it: a film by its TMDB id, an episode by its show's and its numbers. */
sealed interface TraktTitle {
    /** What it is queued under ([app.houselights.providers.local.TraktPendingEntity.title]). */
    val key: String

    data class Movie(val tmdb: Int) : TraktTitle {
        override val key: String get() = "$MOVIE:$tmdb"
    }

    data class Episode(val show: Int, val season: Int, val number: Int) : TraktTitle {
        override val key: String get() = "$EPISODE:$show:$season:$number"
    }

    companion object {
        private const val MOVIE = "movie"
        private const val EPISODE = "episode"

        /** A show's id, a season and a number. */
        private const val EPISODE_NUMBERS = 3

        fun parse(key: String): TraktTitle? {
            val parts = key.split(':')
            val numbers = parts.drop(1).map { it.toIntOrNull() ?: return null }
            return when {
                parts.first() == MOVIE && numbers.size == 1 -> Movie(numbers[0])
                parts.first() == EPISODE && numbers.size == EPISODE_NUMBERS -> Episode(numbers[0], numbers[1], numbers[2])
                else -> null
            }
        }
    }
}

/** Most episodes a file is taken to hold: a typo such as S01E01-E99 never sends a season. */
private const val MAX_EPISODES_PER_FILE = 20

/** What a file plays as Trakt names it: a film, one episode or several; nothing without a TMDB id or numbers. */
internal fun SyncRow.titles(): List<TraktTitle> {
    val tmdb = tmdbId ?: return emptyList()
    return when (kind) {
        ItemKind.MOVIE -> listOf(TraktTitle.Movie(tmdb))
        ItemKind.EPISODE -> {
            val season = seasonNumber ?: return emptyList()
            val first = episodeNumber ?: return emptyList()
            val last = lastEpisodeNumber?.coerceIn(first, first + MAX_EPISODES_PER_FILE - 1) ?: first
            (first..last).map { TraktTitle.Episode(tmdb, season, it) }
        }
        else -> emptyList()
    }
}

/** Trakt's films by when they were last watched (ms; 0 when Trakt says not). */
@JvmName("watchedMovies")
internal fun List<WatchedMovie>.watched(): Map<TraktTitle, Long> =
    mapNotNull { m -> m.movie?.ids?.tmdb?.let { TraktTitle.Movie(it) to (millisOf(m.lastWatchedAt) ?: 0L) } }.toMap()

/** Trakt's episodes by when they were last watched, for shows it knows by a TMDB id and episodes it numbers. */
@JvmName("watchedEpisodes")
internal fun List<WatchedShow>.watched(): Map<TraktTitle, Long> = buildMap {
    for (show in this@watched) {
        val tmdb = show.show?.ids?.tmdb ?: continue
        for (season in show.seasons) {
            val number = season.number ?: continue
            for (episode in season.episodes) {
                val at = millisOf(episode.lastWatchedAt ?: show.lastWatchedAt) ?: 0L
                episode.number?.let { put(TraktTitle.Episode(tmdb, number, it), at) }
            }
        }
    }
}

/** The title a paused playback is of, when Trakt names it by TMDB ids and numbers. */
internal fun PlaybackProgress.title(): TraktTitle? = when (type) {
    "movie" -> movie?.ids?.tmdb?.let { TraktTitle.Movie(it) }
    "episode" -> {
        val show = show?.ids?.tmdb
        val season = episode?.season
        val number = episode?.number
        if (show != null && season != null && number != null) TraktTitle.Episode(show, season, number) else null
    }
    else -> null
}

/** Trakt's date for a play whose own is unknown: the title's release, never the day it is sent. */
private const val RELEASED = "released"

/**
 * [titles] dated (ms, 0 when unknown) as history items, a show's episodes by season; [dated] false sends no date,
 * which removing reads as every play.
 */
internal fun historyOf(titles: List<Pair<TraktTitle, Long>>, dated: Boolean = true): HistoryItems {
    fun date(at: Long) = if (!dated) null else if (at > 0) isoOf(at) else RELEASED
    val movies = titles.mapNotNull { (title, at) -> (title as? TraktTitle.Movie)?.let { HistoryMovie(TraktIds(tmdb = it.tmdb), date(at)) } }
    val shows = titles.mapNotNull { (title, at) -> (title as? TraktTitle.Episode)?.let { it to at } }
        .groupBy { it.first.show }
        .map { (show, episodes) ->
            val seasons = episodes.groupBy { it.first.season }.map { (season, list) ->
                HistorySeason(season, list.map { (episode, at) -> HistorySeasonEpisode(episode.number, date(at)) })
            }
            HistoryShow(TraktIds(tmdb = show), seasons)
        }
    return HistoryItems(movies = movies, shows = shows)
}

/** A stop at [progress] percent of [title]: an episode by its show and numbers. */
internal fun scrobbleOf(title: TraktTitle, progress: Float): ScrobbleRequest = when (title) {
    is TraktTitle.Movie -> ScrobbleRequest(progress, movie = TraktMovie(TraktIds(tmdb = title.tmdb)))
    is TraktTitle.Episode -> ScrobbleRequest(
        progress,
        episode = TraktEpisode(season = title.season, number = title.number),
        show = TraktShow(TraktIds(tmdb = title.show)),
    )
}

/** Trakt's ISO 8601 date in ms; null when absent or unreadable. */
internal fun millisOf(date: String?): Long? = date?.let {
    try {
        Instant.parse(it).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun isoOf(ms: Long): String = Instant.ofEpochMilli(ms).toString()
