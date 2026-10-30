package app.houselights.providers.local

import app.houselights.core.NfoInfo

/**
 * How TMDB numbers each show, for one pass or one action ([tmdb]): its seasons' counts and its episode groups as the
 * library keeps them ([EpisodeOrderEntity]), TMDB asked only for what is missing and what an episode needs: a show's
 * counts once (those matched before they were kept), its groups' list once, a group's places once, again once TMDB
 * lists it with another count or, absolute, its seasons count past it ([refetchShort]). The order a show
 * follows is the user's choice, else its tvshow.nfo's (a TMDB group's link as Kodi writes it, else Jellyfin's
 * `<displayorder>`), else TMDB's seasons; a choice TMDB no longer lists is forgotten.
 */
internal class EpisodeOrders(private val dao: LibraryDao, private val tmdb: TmdbPass, private val clock: () -> Long) {
    /** Numberings read this pass, by show and what they were read for ([Key]). */
    private val read = mutableMapOf<Key, ShowNumbering>()

    /** A show's numbering stays valid while its choice, its order and its NFO do; [absolute] when it holds the show's sequence. */
    private data class Key(val seriesId: Long, val choice: String?, val placed: String?, val nfo: Int, val absolute: Boolean)

    /**
     * [series]'s numbering, with its episodes from its start when [absolute]; unanswered when TMDB had to be asked and did
     * not answer, for a later pass. A show TMDB does not know has its files' numbers as they are ([ShowNumbering.UNKNOWN]).
     */
    suspend fun of(series: ItemEntity, absolute: Boolean): TmdbAnswer<ShowNumbering> {
        val id = series.tmdbId ?: return TmdbAnswer.Given(ShowNumbering.UNKNOWN)
        val key = Key(series.id, series.userEpisodeOrder, series.episodeOrder, series.nfo?.hashCode() ?: 0, absolute)
        // One read for its sequence serves one without.
        val known = read[key] ?: read[key.copy(absolute = true)]
        if (known != null) return TmdbAnswer.Given(known)
        val numbering = numbering(series, id, absolute) ?: return TmdbAnswer.Unanswered
        read[key] = numbering
        return TmdbAnswer.Given(numbering)
    }

    /** [of], read: null when TMDB had to be asked and did not answer. */
    private suspend fun numbering(series: ItemEntity, id: Int, absolute: Boolean): ShowNumbering? {
        val aired = aired(id) ?: return null
        val nfo = series.nfoInfo()
        val groups = (if (wantsGroups(series, nfo, absolute)) listed(id) else dao.episodeOrders(id).filter { it.isGroup }) ?: return null
        val chosen = chosen(series, id, aired, groups, nfo) ?: return null
        val sequence = if (absolute) sequenceOf(id, aired, groups) else emptyList()
        return sequence?.let { numbers ->
            ShowNumbering(chosen.first, automatic = chosen.second, absolute = numbers.takeIf { absolute }, aired = aired)
        }
    }

    /** Whether [series]'s groups are needed: for its sequence from its start, the user's choice or its NFO's. */
    private fun wantsGroups(series: ItemEntity, nfo: NfoInfo?, absolute: Boolean): Boolean =
        absolute || (series.userEpisodeOrder ?: EpisodeOrderEntity.AIRED) != EpisodeOrderEntity.AIRED ||
            nfo?.episodeGroup != null || NfoOrders.typeOf(nfo?.displayOrder) != null

    /** Forgets what was read of show [seriesId] this pass: its counts or its order just changed. */
    fun forget(seriesId: Long) {
        read.keys.removeAll { it.seriesId == seriesId }
    }

    /** Show [id]'s TMDB seasons, kept, else asked of TMDB and kept; null when TMDB did not answer. */
    private suspend fun aired(id: Int): EpisodeOrder.Aired? {
        val row = dao.aired(id)
        OrderPlaces.aired(row?.places)?.let { return EpisodeOrder.Aired(it) }
        val answer = tmdb.ask { it.tvById(id) } as? TmdbAnswer.Given ?: return null
        val seasons = answer.value?.seasons.orEmpty()
        saveAired(id, seasons, kept = row)
        return EpisodeOrder.Aired(OrderPlaces.aired(OrderPlaces.ofAired(seasons)).orEmpty())
    }

    /**
     * Show [id] as TMDB has it now, its seasons counted; null when TMDB did not answer, or has no such show any more, which
     * is then noted as asked about now, so it waits a day as the others do ([EpisodeOrderDao.waitingShows]).
     */
    suspend fun tv(id: Int): TmdbTv? {
        val answer = tmdb.ask { it.tvById(id) } as? TmdbAnswer.Given ?: return null
        if (answer.value == null) askedNow(id)
        return answer.value
    }

    /**
     * Keeps show [id]'s TMDB [seasons], counted now, over [kept], its row as read ([EpisodeOrderDao.aired]), whose groups'
     * listing it keeps; true when their counts differ from those kept.
     */
    suspend fun saveAired(id: Int, seasons: List<TmdbSeasonSummary>, kept: EpisodeOrderEntity?): Boolean =
        dao.saveAired(id, seasons, clock(), kept)

    /**
     * Keeps show [id]'s TMDB [seasons], counted now, its groups' listing kept; true when an episode they counted before
     * is now counted elsewhere from the show's start ([EpisodeOrder.Aired.absolute]), as when TMDB splits a season or
     * counts one but the last otherwise; never when only the last grew, as an ongoing anime's does each week, nor for
     * specials.
     */
    suspend fun recount(id: Int, seasons: List<TmdbSeasonSummary>): Boolean {
        val kept = dao.aired(id)
        saveAired(id, seasons, kept)
        val before = OrderPlaces.aired(kept?.places)?.let { EpisodeOrder.Aired(it).absolute() } ?: return false
        val now = EpisodeOrder.Aired(seasons.map { AiredSeason(it.seasonNumber, it.episodeCount) }).absolute()
        return now.take(before.size) != before
    }

    /** Notes show [id]'s counts asked about now, whatever TMDB answered. */
    private suspend fun askedNow(id: Int) {
        val row = dao.aired(id) ?: EpisodeOrderEntity(id, EpisodeOrderEntity.AIRED, type = 0, name = "", episodeCount = 0)
        dao.saveEpisodeOrders(listOf(row.copy(fetchedAt = clock())))
    }

    /**
     * Show [id]'s absolute groups whose episodes stop before its [reach]th, once its seasons count that far ([counted]),
     * fetched again when next needed: TMDB has since listed an episode the group held no place for when it was fetched.
     */
    suspend fun refetchShort(id: Int, reach: Int, counted: Int) {
        if (counted < reach) return
        val short = dao.episodeOrders(id).filter { row ->
            row.type == EpisodeOrderEntity.ABSOLUTE_TYPE && OrderPlaces.group(row.places)?.let { held(it) < reach } == true
        }
        if (short.isNotEmpty()) dao.saveEpisodeOrders(short.map { it.copy(places = null, fetchedAt = null) })
    }

    private fun held(seasons: List<OrderSeason>): Int = seasons.sumOf { it.episodes.size }

    /** Show [id]'s groups as kept, else listed by TMDB and kept; null when TMDB did not answer. */
    suspend fun listed(id: Int): List<EpisodeOrderEntity>? {
        val rows = dao.episodeOrders(id)
        if (rows.firstOrNull { it.orderId == EpisodeOrderEntity.AIRED }?.groupsAt != null) return rows.filter { it.isGroup }
        return list(id)
    }

    /**
     * Lists show [id]'s groups again and keeps them, in TMDB's order, with the places already fetched while TMDB counts
     * them as many episodes, those it refused or counts otherwise to be asked again; groups TMDB lists no more are
     * forgotten. Null when TMDB did not answer.
     */
    suspend fun list(id: Int): List<EpisodeOrderEntity>? {
        val answer = tmdb.ask { it.episodeGroups(id) } as? TmdbAnswer.Given ?: return null
        val rows = dao.episodeOrders(id)
        val known = rows.associateBy { it.orderId }
        val groups = answer.value.orEmpty().mapIndexed { i, ref ->
            val kept = known[ref.id]?.takeUnless { it.places == REFUSED || it.episodeCount != ref.episodeCount }
            EpisodeOrderEntity(
                id,
                ref.id,
                ref.type,
                ref.name.orEmpty(),
                ref.episodeCount,
                rank = i + 1,
                places = kept?.places,
                fetchedAt = kept?.fetchedAt,
            )
        }
        val aired = rows.firstOrNull { it.orderId == EpisodeOrderEntity.AIRED }?.copy(groupsAt = clock())
            ?: EpisodeOrderEntity(id, EpisodeOrderEntity.AIRED, type = 0, name = "", episodeCount = 0, groupsAt = clock())
        dao.inTransaction {
            dao.forgetEpisodeGroups(id, groups.map { it.orderId })
            dao.saveEpisodeOrders(groups + aired)
        }
        return groups
    }

    /**
     * The order [series] follows and whether it does automatically: the user's choice, else its NFO's, else TMDB's
     * seasons; null when a group's places had to be asked of TMDB and it did not answer.
     */
    private suspend fun chosen(
        series: ItemEntity,
        id: Int,
        aired: EpisodeOrder.Aired,
        groups: List<EpisodeOrderEntity>,
        nfo: NfoInfo?,
    ): Pair<EpisodeOrder, Boolean>? {
        val user = byUser(series, id, aired, groups)
        val chosen = if (user is TmdbAnswer.Given && user.value == null) byNfo(id, nfo, groups) else user
        return when (chosen) {
            is TmdbAnswer.Given -> chosen.value?.let { it to false } ?: (aired to true)
            TmdbAnswer.Unanswered -> null
        }
    }

    /**
     * The order the user chose for [series], given null without a choice or with one TMDB cannot give; a choice TMDB lists
     * no more, once it listed the show's groups, is forgotten.
     */
    private suspend fun byUser(
        series: ItemEntity,
        id: Int,
        aired: EpisodeOrder.Aired,
        groups: List<EpisodeOrderEntity>,
    ): TmdbAnswer<EpisodeOrder> {
        val choice = series.userEpisodeOrder ?: return TmdbAnswer.Given(null)
        if (choice == EpisodeOrderEntity.AIRED) return TmdbAnswer.Given(aired)
        val row = groups.firstOrNull { it.orderId == choice }
        if (row == null && (groups.isNotEmpty() || listedOnce(id))) forget(series, choice)
        return row?.let { group(id, it) } ?: TmdbAnswer.Given(null)
    }

    /** The group [nfo] names among [groups] ([NfoOrders.groupOf]), given null for none. */
    private suspend fun byNfo(id: Int, nfo: NfoInfo?, groups: List<EpisodeOrderEntity>): TmdbAnswer<EpisodeOrder> =
        NfoOrders.groupOf(nfo, groups)?.let { group(id, it) } ?: TmdbAnswer.Given(null)

    /** Forgets [series]'s choice [choice], once TMDB no longer lists it, unless it changed since it was read. */
    private suspend fun forget(series: ItemEntity, choice: String) {
        if (dao.item(series.id)?.userEpisodeOrder == choice) dao.setUserEpisodeOrder(series.id, null)
    }

    private suspend fun listedOnce(id: Int) = dao.aired(id)?.groupsAt != null

    /**
     * The group of [row], kept, else asked of TMDB and kept; given null when TMDB refuses it (no such group, or another
     * show's), kept refused until the show's groups are listed again.
     */
    suspend fun group(id: Int, row: EpisodeOrderEntity): TmdbAnswer<EpisodeOrder.Group> {
        if (row.places == REFUSED) return TmdbAnswer.Given(null)
        OrderPlaces.group(row.places)?.let { return TmdbAnswer.Given(EpisodeOrder.Group(row.orderId, row.type, it)) }
        val answer = tmdb.ask { it.episodeGroup(row.orderId) } as? TmdbAnswer.Given ?: return TmdbAnswer.Unanswered
        // Never another show's group, which a link in an NFO may name.
        val group = answer.value?.takeIf { g -> showOf(g).let { it == null || it == id } }
        val places = group?.let(OrderPlaces::ofGroup)?.takeIf { OrderPlaces.group(it) != null } ?: REFUSED
        dao.saveEpisodeOrders(listOf(row.copy(places = places, fetchedAt = clock())))
        return TmdbAnswer.Given(OrderPlaces.group(places)?.let { EpisodeOrder.Group(row.orderId, row.type, it) })
    }

    /**
     * Show [id]'s episodes from its first: its first absolute group's that TMDB gives, else its seasons' counted, specials
     * never; null when TMDB had to be asked and did not answer.
     */
    private suspend fun sequenceOf(id: Int, aired: EpisodeOrder.Aired, groups: List<EpisodeOrderEntity>): List<TmdbNumber>? {
        for (row in groups.filter { it.type == EpisodeOrderEntity.ABSOLUTE_TYPE }) {
            when (val group = group(id, row)) {
                is TmdbAnswer.Given -> group.value?.let { return it.sequence() }
                TmdbAnswer.Unanswered -> return null
            }
        }
        return aired.absolute()
    }

    private val EpisodeOrderEntity.isGroup: Boolean get() = orderId != EpisodeOrderEntity.AIRED

    /** The show [group]'s episodes say they are of; null when they do not say. */
    private fun showOf(group: TmdbEpisodeGroup): Int? = group.groups.flatMap { it.episodes }.firstNotNullOfOrNull { it.showId }

    companion object {
        /** The places of a group TMDB would not give for its show. */
        const val REFUSED = "[]"
    }
}

/**
 * Keeps show [id]'s TMDB [seasons], counted [at], over [before], its row as read ([EpisodeOrderDao.aired]), with
 * [groupsAt], its groups' listing, kept by default; true when their counts differ from those kept before.
 */
internal suspend fun LibraryDao.saveAired(
    id: Int,
    seasons: List<TmdbSeasonSummary>,
    at: Long,
    before: EpisodeOrderEntity?,
    groupsAt: Long? = before?.groupsAt,
): Boolean {
    val places = OrderPlaces.ofAired(seasons)
    val episodes = seasons.filter { it.seasonNumber > 0 }.sumOf { it.episodeCount }
    val row = EpisodeOrderEntity(
        id,
        EpisodeOrderEntity.AIRED,
        type = 0,
        name = "",
        episodeCount = episodes,
        places = places,
        fetchedAt = at,
        groupsAt = groupsAt,
    )
    saveEpisodeOrders(listOf(row))
    val counted = { text: String? -> OrderPlaces.aired(text)?.map { it.n to it.c } }
    return before?.places != null && counted(before.places) != counted(places)
}

/**
 * Keeps the seasons of show [tv] as its lookup gave them ([saveAired]): a [refresh] lists its groups again and fetches
 * them again when next needed. True when its seasons count otherwise than before.
 */
internal suspend fun LibraryDao.recount(tv: TmdbTv, refresh: Boolean, at: Long): Boolean {
    val kept = aired(tv.id)
    if (refresh) staleEpisodeGroups(tv.id)
    return saveAired(tv.id, tv.seasons, at, kept, kept?.groupsAt.takeUnless { refresh })
}

/** The order a tvshow.nfo names: a TMDB episode group by its link, as Kodi writes it, or by its type, as Jellyfin does. */
internal object NfoOrders {
    /** Jellyfin's and Emby's `<displayorder>`, by TMDB's group types; "aired" and "official" are TMDB's seasons. */
    private val TYPES = mapOf(
        "originalairdate" to 1,
        "absolute" to EpisodeOrderEntity.ABSOLUTE_TYPE,
        "dvd" to 3,
        "digital" to 4,
        "storyarc" to EpisodeOrderEntity.STORY_ARC_TYPE,
        "production" to 6,
        "tv" to 7,
    )

    /** The group type [displayOrder] names; null for TMDB's seasons and orders TMDB does not have (TVDB's "alternate"). */
    fun typeOf(displayOrder: String?): Int? = displayOrder?.let { TYPES[it.lowercase(java.util.Locale.ROOT)] }

    /** The group of [groups] that [nfo] names: its link's, when the show has it, else the first of its type. */
    fun groupOf(nfo: NfoInfo?, groups: List<EpisodeOrderEntity>): EpisodeOrderEntity? {
        nfo ?: return null
        nfo.episodeGroup?.let { link -> groups.firstOrNull { it.orderId == link } }?.let { return it }
        val type = typeOf(nfo.displayOrder) ?: return null
        return groups.filter { it.type == type }.minByOrNull { it.rank }
    }
}
