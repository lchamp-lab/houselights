package app.houselights.providers.local

import app.houselights.core.lenientJson
import app.houselights.core.yearOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

/** An episode as TMDB numbers it in its own seasons; or, as a place, where an order puts one. */
internal data class TmdbNumber(val season: Int, val episode: Int)

/** Whether a place of a show is held by an episode whose file names another ([EpisodeNumbering.place]). */
internal typealias Taken = suspend (TmdbNumber) -> Boolean

/** One season of an order: its [number], its name for a group's (null for TMDB's own), and its TMDB episodes in order. */
internal class OrderSeason(val number: Int, val name: String?, val episodes: List<TmdbNumber>) {
    /** The TMDB season all its episodes come from, whose details it may show; null when they come from several. */
    val tmdbSeason: Int? get() = episodes.map { it.season }.distinct().singleOrNull()
}

/** One of TMDB's seasons as `tv/{id}` lists it ([OrderPlaces]): its number, its episodes counted, and when it began. */
@Serializable
internal data class AiredSeason(val n: Int, val c: Int, val a: String? = null)

/** One season of a group, as kept ([OrderPlaces]): its number, its name, and its episodes, TMDB's season and number in turn. */
@Serializable
internal data class GroupSeason(val n: Int, val name: String? = null, val e: List<Int> = emptyList())

/**
 * The way a show's episodes are placed: TMDB's own seasons ([Aired]), or one of its episode groups ([Group]), whose
 * seasons are numbered by their order and episodes from 1, as Kodi and Jellyfin number them, so files named for them
 * stay right.
 */
internal sealed interface EpisodeOrder {
    /** [EpisodeOrderEntity.AIRED], or the group's id. */
    val id: String

    /** The TMDB episode at season [season], episode [episode] of this order; null where it has none. */
    fun at(season: Int, episode: Int): TmdbNumber?

    /** Where this order places TMDB episode [episode]; null when it leaves it out. */
    fun placeOf(episode: TmdbNumber): TmdbNumber?

    /** How many episodes this order lists in season [season]; null when it does not know. */
    fun count(season: Int): Int?

    /** TMDB's own seasons, by their counts ([seasons]); their places are TMDB's numbers. */
    class Aired(val seasons: List<AiredSeason>) : EpisodeOrder {
        override val id: String get() = EpisodeOrderEntity.AIRED
        private val counts = seasons.associate { it.n to it.c }

        override fun at(season: Int, episode: Int) = TmdbNumber(season, episode)

        override fun placeOf(episode: TmdbNumber) = episode

        override fun count(season: Int): Int? = counts[season]

        /**
         * The TMDB season a year's season stands for ("S2023E01", "Season 2023"): the one that began that year, when
         * TMDB has no season of that number; null otherwise.
         */
        fun ofYear(season: Int): Int? {
            if (season < EpisodeOrder.FIRST_YEAR || season in counts) return null
            return seasons.filter { it.n > 0 && yearOf(it.a) == season }.minOfOrNull { it.n }
        }

        /** TMDB's seasons from the first, episode by episode, specials never counted: a number from its start's place. */
        fun absolute(): List<TmdbNumber> =
            seasons.filter { it.n > 0 }.sortedBy { it.n }.flatMap { s -> (1..s.c).map { TmdbNumber(s.n, it) } }
    }

    /** An episode group ([type] as TMDB numbers it), its [seasons] in their order. */
    class Group(override val id: String, val type: Int, val seasons: List<OrderSeason>) : EpisodeOrder {
        private val byNumber = seasons.associateBy { it.number }
        private val places = seasons.flatMap { s -> s.episodes.mapIndexed { i, e -> e to TmdbNumber(s.number, i + 1) } }.toMap()

        override fun at(season: Int, episode: Int): TmdbNumber? = byNumber[season]?.episodes?.getOrNull(episode - 1)

        override fun placeOf(episode: TmdbNumber): TmdbNumber? = places[episode]

        override fun count(season: Int): Int? = byNumber[season]?.episodes?.size

        /** Its episodes from its first season's first, in its order: an absolute group's numbering. */
        fun sequence(): List<TmdbNumber> = seasons.sortedBy { it.number }.flatMap { it.episodes }
    }

    companion object {
        /** A season numbered past this is a year's ([Aired.ofYear]). */
        const val FIRST_YEAR = 1900
    }
}

/**
 * Where a show's episodes are placed, as [EpisodeNumbering] reads it: the [order] in use; whether numbers past their
 * season may be read from the show's start ([automatic], neither the user nor the NFO chose an order); and the show's
 * episodes from its first ([absolute], its absolute group's, else TMDB's seasons'), null while TMDB's counts are unknown.
 */
internal class ShowNumbering(
    val order: EpisodeOrder,
    val automatic: Boolean,
    val absolute: List<TmdbNumber>?,
    /** TMDB's own seasons, for counts and years whatever the order. */
    val aired: EpisodeOrder.Aired?,
) {
    companion object {
        /** A show TMDB does not know, or whose counts are not known yet: its files' numbers as they are. */
        val UNKNOWN = ShowNumbering(EpisodeOrder.Aired(emptyList()), automatic = true, absolute = null, aired = null)
    }
}

/**
 * Where an episode goes: its place in its show's order ([season], [episode], [last]), the TMDB episode it shows there
 * ([tmdb], null while TMDB lists none) and the last of a file of several ([tmdbLast]); and its number from its show's
 * start, when it was placed by it ([absolute]).
 */
internal data class Placement(
    val season: Int,
    val episode: Int,
    val last: Int?,
    val tmdb: TmdbNumber?,
    val tmdbLast: Int?,
    val absolute: Int?,
) {
    /** The episode's place as [EpisodePlace] writes it, sorted as the scan sorts it. */
    val sortTitle: String get() = sortTitleOf(season, episode)

    companion object {
        fun sortTitleOf(season: Int, episode: Int): String = "%03d-%04d".format(java.util.Locale.ROOT, season, episode)
    }
}

/**
 * Places episodes in their show's order, from what their names say ([ParsedEpisode]) and how TMDB numbers the show
 * ([ShowNumbering]); pure but for asking who holds a place (its `taken`). The rules, in turn:
 * 1. A number from the show's start ("One Piece - 1071") is the show's 1071st episode, specials never counted, by its
 *    absolute group when TMDB has one, else by its seasons' counts: placed where the order puts that TMDB episode.
 *    TMDB listing none there yet, or another file holding that place, it waits in season 1 under its number.
 * 2. In TMDB's seasons and automatically only, a number past its season's count from 100 ("S01E1071") is read so too,
 *    when it falls in season 1 or in its own season, and nothing else holds that place; one TMDB lists none for yet
 *    in season 1 waits as one. A three-digit number alone in a season's folder whose first digit is that season
 *    ("Season 1/101 - Pilot") is first read as season and episode; an anime sequel's past its season ("S2 - 13") as
 *    one from the show's start.
 * 3. Otherwise it stays where its name puts it, showing the TMDB episode the order has there.
 */
internal object EpisodeNumbering {
    /** Numbers from which one past its season is read from the show's start: below, a season TMDB lists in part. */
    const val ABSOLUTE_FROM = 100

    /** A three-digit number in a season's folder: its season, then its episode ("101" is S01E01). */
    private const val SEASON_DIGITS = 100

    /**
     * Where [parsed] goes in its show, numbered as [numbering] says; [taken] tells whether a place of the show is held by
     * an episode whose file names another ([LibraryDao.episodesAt]).
     */
    suspend fun place(parsed: ParsedEpisode, numbering: ShowNumbering, taken: Taken): Placement =
        when (parsed.numbering) {
            Numbering.ABSOLUTE -> fromStart(parsed.episode, parsed.lastEpisode, numbering, taken)
                ?: waiting(parsed.episode, parsed.lastEpisode)
            else -> pastItsSeason(parsed, numbering, taken) ?: asNamed(parsed, numbering)
        }

    /** Whether [parsed] needs its show's numbering from its start: a number from it, or one that may be. */
    fun needsAbsolute(parsed: ParsedEpisode): Boolean =
        parsed.numbering == Numbering.ABSOLUTE || parsed.episode >= ABSOLUTE_FROM || parsed.numbering == Numbering.SEQUEL

    /** Rule 1: the [first]th episode of the show (to [last]); null while unknown or held. */
    private suspend fun fromStart(first: Int, last: Int?, numbering: ShowNumbering, taken: Taken): Placement? {
        val sequence = numbering.absolute ?: return null
        val tmdb = sequence.getOrNull(first - 1) ?: return null
        val place = numbering.order.placeOf(tmdb) ?: tmdb
        if (taken(place)) return null
        val lastTmdb = last?.let { sequence.getOrNull(it - 1) }
        val lastPlace = lastTmdb?.let { numbering.order.placeOf(it) ?: it }?.takeIf { it.season == place.season }
        return Placement(
            place.season,
            place.episode,
            lastPlace?.episode,
            tmdb,
            lastTmdb?.takeIf { it.season == tmdb.season }?.episode,
            absolute = first,
        )
    }

    /** A number from the show's start no TMDB episode answers yet: in season 1 under it, its TMDB episode to come. */
    private fun waiting(first: Int, last: Int?) = Placement(1, first, last, tmdb = null, tmdbLast = null, absolute = first)

    /** Rule 2: a number past its season's count read as season and episode, or from the show's start; null otherwise. */
    private suspend fun pastItsSeason(parsed: ParsedEpisode, numbering: ShowNumbering, taken: Taken): Placement? {
        val count = countPast(parsed, numbering) ?: return null
        if (parsed.numbering == Numbering.FOLDER) seasonThenEpisode(parsed.season, parsed.episode, count)?.let { return it }
        return fromItsStart(parsed, numbering, taken)
    }

    /** The count of [parsed]'s season when its number is past it, in TMDB's seasons followed automatically; else null. */
    private fun countPast(parsed: ParsedEpisode, numbering: ShowNumbering): Int? {
        val automatic = numbering.automatic && numbering.order is EpisodeOrder.Aired
        val count = numbering.aired?.takeIf { automatic }?.count(parsed.season)
        return count?.takeIf { parsed.episode > it }
    }

    /**
     * [parsed] read from its show's start: from [ABSOLUTE_FROM], or an anime sequel's past its season, landing in season
     * 1 or its own; past what TMDB lists, one of season 1 waits there.
     */
    private suspend fun fromItsStart(parsed: ParsedEpisode, numbering: ShowNumbering, taken: Taken): Placement? {
        val from = if (parsed.numbering == Numbering.SEQUEL) 1 else ABSOLUTE_FROM
        val sequence = numbering.absolute?.takeIf { parsed.episode >= from } ?: return null
        val season = parsed.season
        val tmdb = sequence.getOrNull(parsed.episode - 1)
            ?: return waiting(parsed.episode, parsed.lastEpisode).takeIf { season == 1 }
        if (season != 1 && tmdb.season != season) return null
        return fromStart(parsed.episode, parsed.lastEpisode, numbering, taken)
    }

    /** "Season 1/101 - Pilot": season 1, episode 1, when its first digit is the folder's season and it fits. */
    private fun seasonThenEpisode(season: Int, number: Int, count: Int): Placement? {
        val episode = number % SEASON_DIGITS
        if (number / SEASON_DIGITS != season || episode !in 1..count) return null
        return Placement(season, episode, null, TmdbNumber(season, episode), null, absolute = null)
    }

    /** Rule 3: where its name puts it; a year's season shows its year's TMDB season ([EpisodeOrder.Aired.ofYear]). */
    private fun asNamed(parsed: ParsedEpisode, numbering: ShowNumbering): Placement {
        val season = parsed.season
        val order = numbering.order
        val year = (order as? EpisodeOrder.Aired)?.let { numbering.aired?.ofYear(season) }
        val tmdb = if (year != null) TmdbNumber(year, parsed.episode) else order.at(season, parsed.episode)
        val lastTmdb = parsed.lastEpisode?.let { last -> if (year != null) TmdbNumber(year, last) else order.at(season, last) }
        val tmdbLast = lastTmdb?.takeIf { it.season == tmdb?.season }?.episode
        return Placement(season, parsed.episode, parsed.lastEpisode, tmdb, tmdbLast, absolute = null)
    }
}

/** How [EpisodeOrderEntity.places] are written: TMDB's seasons' counts, or a group's seasons and their episodes. */
internal object OrderPlaces {
    private val aired = ListSerializer(AiredSeason.serializer())
    private val groups = ListSerializer(GroupSeason.serializer())

    fun ofAired(seasons: List<TmdbSeasonSummary>): String =
        lenientJson.encodeToString(aired, seasons.map { AiredSeason(it.seasonNumber, it.episodeCount, it.airDate) })

    /** TMDB's seasons as kept; null when never fetched or unreadable. */
    fun aired(places: String?): List<AiredSeason>? = decoded(places) { lenientJson.decodeFromString(aired, it) }

    /** [group]'s seasons, in their order, each episode TMDB's season then number. */
    fun ofGroup(group: TmdbEpisodeGroup): String = lenientJson.encodeToString(
        groups,
        group.groups.sortedBy { it.order }.map { season ->
            val episodes = season.episodes.sortedBy { it.order }.flatMap { listOf(it.seasonNumber, it.episodeNumber) }
            GroupSeason(season.order, season.name, episodes)
        },
    )

    /** A group's seasons as kept; null when never fetched, refused ("[]") or unreadable. */
    fun group(places: String?): List<OrderSeason>? = decoded(places) { lenientJson.decodeFromString(groups, it) }
        ?.takeIf { it.isNotEmpty() }
        ?.map { s -> OrderSeason(s.n, s.name, s.e.chunked(2).filter { it.size == 2 }.map { (n, e) -> TmdbNumber(n, e) }) }

    private inline fun <T> decoded(places: String?, read: (String) -> T): T? = places?.let {
        try {
            read(it)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
