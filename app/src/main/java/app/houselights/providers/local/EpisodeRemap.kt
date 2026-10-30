package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import app.houselights.core.TmdbNotFoundException
import app.houselights.core.yearOf
import java.util.Locale

/**
 * Places a show's episodes anew ([EpisodeNumbering]) when its order or TMDB's numbering of it changes: the TMDB seasons
 * they now show are fetched first, nothing written unless all answered; then each episode moves in place, its row,
 * what the user did to it and its show's choice of tracks kept ([EpisodePlace]), seasons made for those placed anew and
 * those left empty removed.
 */
internal class EpisodeRemap(private val dao: LibraryDao, private val seasons: ShowIndexer.SeasonCache, private val clock: () -> Long) {
    /**
     * The writes placing [series]'s [episodes] as [numbering] says, then noting the order its episodes follow, to run in
     * one transaction; how many moved once written. Null when TMDB did not answer for a season they show.
     * Episodes named by season come first, so a number from the show's start never takes their place; those not moved
     * keep theirs.
     */
    suspend fun plan(series: ItemEntity, numbering: ShowNumbering, episodes: List<ItemEntity>): (suspend () -> Int)? {
        val moving = episodes.mapTo(HashSet()) { it.id }
        val held = HashMap<TmdbNumber, String>()
        for (row in dao.episodesOfSeries(series.id).filter { it.id !in moving }) {
            val place = row.placeNumbers() ?: continue
            row.fileKey()?.let { held[place] = it }
        }
        val parsed = episodes.mapNotNull { row -> row.filePath?.let(FilenameParser::parseShowPath)?.let { row to it.episode } }
            .sortedBy { (_, episode) -> episode.numbering == Numbering.ABSOLUTE }
        val placements = parsed.map { (row, episode) ->
            val key = episode.key()
            val placement = EpisodeNumbering.place(episode, numbering) { place -> held[place].let { it != null && it != key } }
            held[TmdbNumber(placement.season, placement.episode)] = key
            row to placement
        }
        val sources = placements.map { it.second.season }.distinct().associateWith { numbering.order.seasonSource(it) }
        val details = details(series.tmdbId, placements.mapNotNull { it.second.tmdb?.season } + sources.values.mapNotNull { it.tmdbSeason })
            ?: return null
        // Read before the writes, a query a batch of episodes rather than one an episode in their transaction.
        val withNfo = placements.mapNotNull { (row, _) -> row.id.takeIf { row.nfoPath != null } }
        val nfos = dao.nfosOf(withNfo).mapValues { NfoParser.parse(it.value) }
        val now = clock()
        // A season made anew is in the folder of an episode it holds, as a scan makes it.
        val folders = placements.associate { (row, placement) -> placement.season to row.sourceId }
        return {
            val seasonIds = sources.mapValues { (number, source) ->
                shownSeason(series, number, folders.getValue(number), source, details, now)
            }
            val places = placements.mapNotNull { (row, placement) ->
                val place = placed(row, nfos[row.id], placement, numbering.order, details, seasonIds.getValue(placement.season), now)
                place.takeIf { it.copy(matchedAt = row.matchedAt) != row.place() }
            }
            if (places.isNotEmpty()) dao.placeEpisodes(places)
            dao.deleteEmptySeasons()
            val order = numbering.order.id.takeIf { it != EpisodeOrderEntity.AIRED }
            if (order != series.episodeOrder) dao.setEpisodeOrder(series.id, order)
            places.size
        }
    }

    /** TMDB's seasons [numbers] of show [tmdbId], each asked once a pass; null when one did not answer. */
    private suspend fun details(tmdbId: Int?, numbers: List<Int>): Map<Int, TmdbSeason?>? {
        tmdbId ?: return emptyMap()
        val details = HashMap<Int, TmdbSeason?>()
        for (number in numbers.distinct()) {
            val answer = seasons.get(tmdbId, number) as? TmdbAnswer.Given ?: return null
            details[number] = answer.value
        }
        return details
    }

    /** Season [number]'s row of [series], made in folder [sourceId] when missing, with what its [source] shows; its id. */
    @Suppress("LongParameterList") // A season, where it is made, and what it shows.
    private suspend fun shownSeason(
        series: ItemEntity,
        number: Int,
        sourceId: Long,
        source: SeasonSource,
        details: Map<Int, TmdbSeason?>,
        now: Long,
    ): Long {
        val season = dao.seasonRow(series, number, sourceId, now)
        val shown = season.withSeasonDetails(source.tmdbSeason?.let { details[it] }, now, source)
        if (shown != season) dao.saveScanned(shown)
        return season.id
    }

    /**
     * [row] at [placement] in [order], in season [seasonId]: showing another TMDB episode, it takes that one's details,
     * its [nfo]'s first, a thumb of its own kept; still the same, it keeps its own where TMDB gives none.
     */
    @Suppress("LongParameterList") // A row and its NFO, where it goes, in which order and season, with what TMDB says of it.
    private fun placed(
        row: ItemEntity,
        nfo: NfoInfo?,
        placement: Placement,
        order: EpisodeOrder,
        details: Map<Int, TmdbSeason?>,
        seasonId: Long,
        now: Long,
    ): EpisodePlace {
        val tmdb = placement.tmdb
        val meta = tmdb?.let { t -> details[t.season]?.episodes?.firstOrNull { it.episodeNumber == t.episode } }
        val shown = if (tmdb == row.tmdbNumber()) row else row.withoutDetails()
        val read = shown.withEpisodeDetails(nfo, meta, thumb = null, matchedAt = now)
        val kept = placement.keptTmdb(order)
        return EpisodePlace(
            id = row.id, parentId = seasonId, seasonNumber = placement.season, episodeNumber = placement.episode,
            lastEpisodeNumber = placement.last, sortTitle = placement.sortTitle, absoluteNumber = placement.absolute,
            tmdbSeason = tmdb?.season?.takeIf { kept }, tmdbEpisode = tmdb?.episode?.takeIf { kept },
            tmdbLastEpisode = placement.tmdbLast?.takeIf { kept }, title = read.title, overview = read.overview,
            stillPath = read.stillPath, runtimeMin = read.runtimeMin, rating = read.rating, year = read.year, matchedAt = read.matchedAt,
        )
    }
}

/**
 * Moves a show's episodes in place ([EpisodeRemap]): once it is read in another order ([ShowIndexer]), when the user
 * chooses one or it is identified anew ([reorder]), and when TMDB lists episodes that were waiting for it ([placeWaiting]).
 */
internal class EpisodePlacing(private val dao: LibraryDao, private val clock: () -> Long) {
    /**
     * Places [episodes] of [series] as [numbering] says ([EpisodeRemap]), TMDB's seasons fetched through [seasons], in one
     * transaction; when they changed order ([reordered]) and moved, noted for Trakt ([ItemEntity.reorderedAt]). Null when
     * TMDB did not answer; else whether anything changed.
     */
    suspend fun placeAnew(
        series: ItemEntity,
        numbering: ShowNumbering,
        episodes: List<ItemEntity>,
        seasons: ShowIndexer.SeasonCache,
        reordered: Boolean,
        also: (suspend () -> Unit)? = null,
    ): Boolean? {
        val writes = EpisodeRemap(dao, seasons, clock).plan(series, numbering, episodes) ?: return null
        var changed = false
        dao.inTransaction {
            also?.invoke()
            val moved = writes()
            if (reordered && moved > 0) dao.setReorderedAt(series.id, clock())
            changed = moved > 0 || numbering.order.id != (series.episodeOrder ?: EpisodeOrderEntity.AIRED)
        }
        return changed
    }

    /**
     * Places show [seriesId]'s episodes anew, asking TMDB through [tmdb]: in the order it follows once the user chose
     * [choice] ([ItemEntity.userEpisodeOrder], written with the places), else as it stands, as after a new
     * identification. Its writes are first fetched, then run in one transaction; true when anything changed. A TMDB
     * failure throws from a strict pass ([TmdbPass]) before anything is written, as does a chosen group TMDB would not
     * give ([TmdbNotFoundException]); null when unanswered. Seasons already asked this pass are read from [seasons].
     */
    suspend fun reorder(
        seriesId: Long,
        tmdb: TmdbPass,
        choice: Choice? = null,
        seasons: ShowIndexer.SeasonCache = ShowIndexer.SeasonCache(tmdb),
    ): Boolean? {
        val stored = dao.item(seriesId)?.takeIf { it.kind == ItemKind.SERIES } ?: return false
        val series = choice?.let { stored.copy(userEpisodeOrder = it.order) } ?: stored
        val episodes = dao.episodesOfSeries(seriesId)
        // TMDB's sequence of the show only when an episode may be numbered from its start.
        val absolute = episodes.any { row -> row.filePath?.let(FilenameParser::parseShowPath)?.episode.needsAbsolute() }
        val numbering = EpisodeOrders(dao, tmdb, clock).of(series, absolute).value ?: return null
        val wanted = choice?.order?.takeIf { it != EpisodeOrderEntity.AIRED }
        if (wanted != null && numbering.order.id != wanted) throw TmdbNotFoundException()
        val anew = numbering.order.id != (series.episodeOrder ?: EpisodeOrderEntity.AIRED)
        val chosen = if (choice != null) suspend { dao.setUserEpisodeOrder(seriesId, choice.order) } else null
        return placeAnew(series, numbering, episodes, seasons, reordered = anew, also = chosen)
    }

    /** The user's choice of an order ([ItemEntity.userEpisodeOrder]): [EpisodeOrderEntity.AIRED], a group's id, null for automatic. */
    class Choice(val order: String?)

    /**
     * Places show [seriesId]'s episodes waiting for TMDB to list them ([ItemEntity.absoluteNumber] without a TMDB episode),
     * once TMDB counted its seasons again ([EpisodeOrders.recount]), its absolute group fetched again when it stops short
     * of those its seasons now count ([EpisodeOrders.refetchShort]), a number they never reach holding back none of them;
     * every episode numbered from its start only when the seasons now count one placed before elsewhere, as a refresh
     * places them ([ShowIndexer]), not when the last season only grew. True when one moved.
     */
    suspend fun placeWaiting(seriesId: Long, orders: EpisodeOrders, seasons: ShowIndexer.SeasonCache): Boolean {
        val series = dao.item(seriesId)?.takeIf { it.kind == ItemKind.SERIES } ?: return false
        val tv = series.tmdbId?.let { orders.tv(it) } ?: return false
        val moved = orders.recount(tv.id, tv.seasons)
        val absolute = dao.episodesOfSeries(seriesId).filter { it.absoluteNumber != null }
        val waiting = absolute.filter { it.tmdbEpisode == null }
        val counted = tv.seasons.filter { it.seasonNumber > 0 }.sumOf { it.episodeCount }
        // A stray "Show - 1200" beside the 1101st, numbered as TMDB never will, leaves the group fetched again for the 1101st.
        waiting.mapNotNull { it.absoluteNumber }.filter { it <= counted }.maxOrNull()?.let { reach ->
            orders.refetchShort(tv.id, reach, counted)
        }
        orders.forget(seriesId)
        val numbering = orders.of(series, absolute = true).value ?: return false
        return placeAnew(series, numbering, if (moved) absolute else waiting, seasons, reordered = false) == true
    }
}

/** Whether this episode needs its show's numbering from its start ([EpisodeNumbering.needsAbsolute]). */
private fun ParsedEpisode?.needsAbsolute(): Boolean = this != null && EpisodeNumbering.needsAbsolute(this)

/** Where an episode row stands and what it shows, as [EpisodePlace] writes it. */
internal fun ItemEntity.place() = EpisodePlace(
    id, parentId, seasonNumber, episodeNumber, lastEpisodeNumber, sortTitle, absoluteNumber, tmdbSeason, tmdbEpisode,
    tmdbLastEpisode, title, overview, stillPath, runtimeMin, rating, year, matchedAt,
)

/** Where an episode row stands now and what it shows, as [EpisodeNumbering] places one; null without numbers. */
internal fun ItemEntity.placement(): Placement? =
    placeNumbers()?.let { Placement(it.season, it.episode, lastEpisodeNumber, tmdbNumber(), tmdbLastEpisode, absoluteNumber) }

/** An episode row's place, when it has one. */
internal fun ItemEntity.placeNumbers(): TmdbNumber? {
    val season = seasonNumber ?: return null
    return episodeNumber?.let { TmdbNumber(season, it) }
}

/** The TMDB episode an episode row shows: its own, else its place's; none while it waits for TMDB ([absoluteNumber]). */
internal fun ItemEntity.tmdbNumber(): TmdbNumber? {
    val season = tmdbSeason
    val episode = tmdbEpisode
    return when {
        season != null && episode != null -> TmdbNumber(season, episode)
        absoluteNumber != null -> null
        else -> placeNumbers()
    }
}

/** An episode file's numbering as its name gives it ([ParsedEpisode.key]); a file whose name places it nowhere holds none. */
internal fun ItemEntity.fileKey(): String? = fileKeyOf(filePath)

/** The numbering the episode file at [filePath] names ([ParsedEpisode.key]); null for none. */
internal fun fileKeyOf(filePath: String?): String? = filePath?.let(FilenameParser::parseShowPath)?.episode?.key()

/** Season [number]'s row of [series], made in folder [sourceId] when missing, as the scan and a re-placement make it. */
internal suspend fun LibraryDao.seasonRow(series: ItemEntity, number: Int, sourceId: Long, now: Long): ItemEntity =
    season(series.id, number) ?: ItemEntity(
        kind = ItemKind.SEASON,
        sourceId = sourceId,
        parentId = series.id,
        seriesId = series.id,
        matchKey = "${series.id}:$number",
        title = "",
        sortTitle = "%03d".format(Locale.ROOT, number),
        seasonNumber = number,
        addedAt = now,
    ).let { it.copy(id = insert(it)) }

/** Without what TMDB or the NFO said of it, a thumb of its own kept: to be read anew. */
internal fun ItemEntity.withoutDetails() =
    copy(title = "", overview = null, stillPath = stillPath?.takeIf { it.isCachedArtwork() }, runtimeMin = null, rating = null, year = null)

/** Whether a row placed so keeps its TMDB episode apart ([ItemEntity.tmdbEpisode]): always but at its place in TMDB's seasons. */
internal fun Placement.keptTmdb(order: EpisodeOrder): Boolean =
    order !is EpisodeOrder.Aired || absolute != null || tmdb != TmdbNumber(season, episode) || tmdbLast != last

/** What a season of an order shows: a TMDB season's details ([tmdbSeason]), its own [name], and whether it is a group's. */
internal class SeasonSource(val tmdbSeason: Int?, val name: String?, val group: Boolean)

/**
 * What season [number] of this order shows: TMDB's season of that number (its year's for a year's season); a group's,
 * the TMDB season all its episodes come from, if one, and its group's name for season 0 and story arcs.
 */
internal fun EpisodeOrder.seasonSource(number: Int): SeasonSource = when (this) {
    is EpisodeOrder.Aired -> SeasonSource(ofYear(number) ?: number, name = null, group = false)
    is EpisodeOrder.Group -> {
        val season = seasons.firstOrNull { it.number == number }
        val named = number == 0 || type == EpisodeOrderEntity.STORY_ARC_TYPE
        SeasonSource(season?.tmdbSeason, season?.name?.takeIf { named }, group = true)
    }
}

/** Details from the NFO, then TMDB, then the stored values. */
internal fun ItemEntity.withEpisodeDetails(nfo: NfoInfo?, meta: TmdbEpisode?, thumb: String?, matchedAt: Long?) = copy(
    title = nfo?.title ?: meta?.name ?: title,
    overview = nfo?.plot ?: meta?.overview?.ifBlank { null } ?: overview,
    stillPath = thumb ?: meta?.stillPath ?: stillPath,
    runtimeMin = nfo?.runtimeMin ?: meta?.runtime ?: runtimeMin,
    rating = nfo?.rating ?: meta?.voteAverage?.takeIf { it > 0 } ?: rating,
    year = yearOf(nfo?.premiered) ?: yearOf(meta?.airDate) ?: year,
    matchedAt = matchedAt,
)

/**
 * Applies TMDB season details, keeping a local poster; without details, stays unmatched. A group's season ([source])
 * takes no TMDB name, its own for season 0 and story arcs, and is matched whether TMDB has one season for it or not.
 */
internal fun ItemEntity.withSeasonDetails(meta: TmdbSeason?, now: Long, source: SeasonSource? = null) = copy(
    title = source?.name ?: meta?.name?.takeUnless { source?.group == true }.orEmpty(),
    overview = meta?.overview?.ifBlank { null },
    posterPath = posterPath?.takeIf { it.isCachedArtwork() } ?: meta?.posterPath,
    year = yearOf(meta?.airDate),
    matchedAt = if (meta != null || source?.group == true) now else null,
)
