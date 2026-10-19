package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Shows keyed by an earlier rule than their paths give are rekeyed in place, keeping the user's changes. */
@RunWith(RobolectricTestRunner::class)
class ScanShowKeysTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var shows: SourceEntity
    private val nas get() = bench.showsNas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(SLOW_HORSES, "Slow Horses", 2022)
        tmdb.show(REMASTERED, "Slow Horses Remastered", 2022)
        for (id in listOf(SLOW_HORSES, REMASTERED)) (1..2).forEach { tmdb.season(id, it, episodes = 6) }
    }

    @After
    fun close() = bench.close()

    private suspend fun showOf(path: String): ItemEntity = bench.showOf(shows, path)

    /** Keys [show] as an earlier rule did, with what the user did with it. */
    private suspend fun keyedBefore(show: ItemEntity, key: String, title: String): ItemEntity {
        dao.saveScanned(show.copy(matchKey = key, title = title, sortTitle = MetadataMerge.sortKey(title)))
        dao.setFavorite(show.id, true)
        dao.setUserPoster(show.id, POSTER)
        return checkNotNull(dao.item(show.id))
    }

    /** Moves [episodes] and their season to the show [to], as an earlier rule had them. */
    private suspend fun moveSeason(from: ItemEntity, number: Int, to: Long, episodes: List<String>) {
        val season = checkNotNull(dao.season(from.id, number))
        dao.saveScanned(season.copy(parentId = to, seriesId = to, matchKey = "$to:$number"))
        episodes.forEach { dao.saveScanned(bench.item(shows, it).copy(seriesId = to)) }
    }

    /** A season pack at the root, refreshed: its show keeps its row and takes its path's key and name. */
    @Test
    fun aShowKeyedByAnEarlierRuleIsKeyedAgainInPlace() = runTest {
        nas.put(S01E01)
        nas.put(S01E02)
        bench.scan()
        val show = keyedBefore(showOf(S01E01), "slow horses s01", "Slow Horses S01")
        dao.setUserTmdbId(show.id, SLOW_HORSES)

        bench.scan(refreshMetadata = true)
        val kept = showOf(S01E01)
        assertEquals(show.id, kept.id)
        assertEquals(1, dao.count(ItemKind.SERIES))
        assertEquals(listOf(show.id), listOf(S01E01, S01E02).map { bench.item(shows, it).seriesId }.distinct())
        assertEquals("slow horses", kept.matchKey)
        assertEquals("Slow Horses", kept.title)
        assertTrue(kept.favorite)
        assertEquals(POSTER, kept.userPosterPath)
        assertEquals(SLOW_HORSES, kept.userTmdbId)
    }

    @Test
    fun aNewEpisodeJoinsItsShowKeyedByAnEarlierRule() = runTest {
        bench.withTmdb = false
        nas.put(ARCANE_1)
        bench.scan()
        val show = keyedBefore(showOf(ARCANE_1), "animation", "Animation")

        nas.put(ARCANE_2)
        bench.scan()
        val kept = showOf(ARCANE_2)
        assertEquals(show.id, kept.id)
        assertEquals(show.id, showOf(ARCANE_1).id)
        assertEquals(1, dao.count(ItemKind.SERIES))
        assertEquals("arcane", kept.matchKey)
        assertEquals("Arcane", kept.title)
        assertTrue(kept.favorite)
        assertEquals(POSTER, kept.userPosterPath)
    }

    /** Two season packs, two shows under the earlier rule, merge: the emptied one hands its user data over. */
    @Test
    fun twoShowsOfAnEarlierRuleAreOneWithWhatTheUserDidWithEither() = runTest {
        listOf(S01E01, S01E02, S02E01, S02E02).forEach { nas.put(it) }
        bench.scan()
        val first = showOf(S01E01)
        dao.saveScanned(first.copy(matchKey = "slow horses s01", title = "Slow Horses S01"))
        val second = dao.insert(first.copy(id = 0, matchKey = "slow horses s02", title = "Slow Horses S02", sortTitle = "slow horses s02"))
        moveSeason(first, 2, second, listOf(S02E01, S02E02))
        keyedBefore(checkNotNull(dao.item(second)), "slow horses s02", "Slow Horses S02")
        dao.setUserTmdbId(second, REMASTERED)

        bench.scan(refreshMetadata = true)
        val kept = showOf(S01E01)
        assertEquals(1, dao.count(ItemKind.SERIES))
        assertEquals(listOf(kept.id), listOf(S01E01, S01E02, S02E01, S02E02).map { bench.item(shows, it).seriesId }.distinct())
        assertTrue(kept.favorite)
        assertEquals(POSTER, kept.userPosterPath)
        assertEquals(REMASTERED, kept.userTmdbId)
        assertEquals("the user's match applied", REMASTERED, kept.tmdbId)
        assertEquals(listOf(1, 2), dao.children(kept.id).map { it.seasonNumber })
    }

    /** Episodes naming two shows: the row stays the first one's, and is not rekeyed for the other later. */
    @Test
    fun aShowOfAnEarlierRuleNowTwoKeepsItsRowForOne() = runTest {
        bench.withTmdb = false
        nas.put(ARCANE_1)
        nas.put(SAMURAI)
        bench.scan()
        val arcane = showOf(ARCANE_1)
        val samurai = showOf(SAMURAI)
        moveSeason(samurai, 1, arcane.id, listOf(SAMURAI))
        dao.deleteItems(listOf(samurai.id))
        val show = keyedBefore(arcane, "animation", "Animation")

        bench.scan(refreshMetadata = true)
        val ids = listOf(showOf(ARCANE_1).id, showOf(SAMURAI).id)
        bench.scan(refreshMetadata = true)
        assertEquals(ids, listOf(showOf(ARCANE_1).id, showOf(SAMURAI).id))
        assertEquals(show.id, ids.first())
        assertEquals(listOf("Arcane", "Blue Eye Samurai"), listOf(showOf(ARCANE_1).title, showOf(SAMURAI).title))
        assertTrue(showOf(ARCANE_1).favorite)
        assertEquals(2, dao.count(ItemKind.SERIES))
    }

    /** One show under the earlier rule, matched with the 2005 series: split by year, the row stays with it. */
    @Test
    fun aShowOfTwoYearsOnceOneKeepsItsRowForTheYearItIs() = runTest {
        tmdb.show(CLASSIC_WHO, "Doctor Who", 1963)
        tmdb.show(MODERN_WHO, "Doctor Who", 2005)
        for (id in listOf(CLASSIC_WHO, MODERN_WHO)) tmdb.season(id, 1, episodes = 6)
        nas.put(WHO_1963)
        nas.put(WHO_2005)
        bench.scan()
        val classic = showOf(WHO_1963)
        val modern = showOf(WHO_2005)
        val season = checkNotNull(dao.season(classic.id, 1))
        dao.saveScanned(bench.item(shows, WHO_2005).copy(seriesId = classic.id, parentId = season.id))
        dao.deleteItems(listOf(checkNotNull(dao.season(modern.id, 1)).id, modern.id))
        // One show by name alone, as the user matched it: the 2005 series.
        dao.saveScanned(classic.copy(tmdbId = MODERN_WHO, year = 2005))
        val show = keyedBefore(checkNotNull(dao.item(classic.id)), "doctor who", "Doctor Who")
        dao.setUserTmdbId(show.id, MODERN_WHO)

        bench.scan(refreshMetadata = true)
        val kept = showOf(WHO_2005)
        assertEquals(show.id, kept.id)
        assertEquals("doctor who|2005", kept.matchKey)
        assertEquals(MODERN_WHO, kept.userTmdbId)
        assertEquals(MODERN_WHO, kept.tmdbId)
        assertTrue(kept.favorite)
        assertEquals(POSTER, kept.userPosterPath)
        val earlier = showOf(WHO_1963)
        assertEquals("doctor who|1963", earlier.matchKey)
        assertEquals(CLASSIC_WHO, earlier.tmdbId)
        assertNull(earlier.userTmdbId)
        assertFalse(earlier.favorite)
        assertEquals(2, dao.count(ItemKind.SERIES))
    }

    /** A show keyed before keys included the year keeps its row as episodes arrive; a yearless folder joins it. */
    @Test
    fun aShowKeyedWithoutItsYearKeepsItsRow() = runTest {
        bench.withTmdb = false
        val old = dao.insert(
            ItemEntity(
                kind = ItemKind.SERIES, sourceId = shows.id, matchKey = "severance", title = "Severance", sortTitle = "severance",
                addedAt = 0,
            )
        )
        dao.setFavorite(old, true)
        nas.put("Severance (2022)/Season 2/Severance - S02E01.mkv")
        bench.scan()
        val uhd = FakeRemoteBrowser().apply { put("Severance/Season 2/Severance - S02E02 2160p.mkv") }
        val second = bench.folder("Séries 4K", SourceKind.SHOWS, on = uhd)
        bench.scan()

        val row = checkNotNull(dao.item(old))
        assertEquals("severance|2022", row.matchKey)
        assertTrue(row.favorite)
        assertEquals(listOf(old, old), (dao.filesOf(shows.id) + dao.filesOf(second.id)).map { it.seriesId })
    }

    private companion object {
        const val SLOW_HORSES = 95480
        const val REMASTERED = 95481
        const val CLASSIC_WHO = 121
        const val MODERN_WHO = 57243
        const val WHO_1963 = "Doctor Who/Doctor Who (1963)/Season 01/Doctor Who - S01E01 - An Unearthly Child.mkv"
        const val WHO_2005 = "Doctor Who/Doctor Who (2005)/Season 01/Doctor Who - S01E01 - Rose.mkv"
        const val POSTER = "https://image.tmdb.org/t/p/original/chosen.jpg"
        const val S01 = "Slow.Horses.S01.1080p.ATVP.WEB-DL"
        const val S02 = "Slow.Horses.S02.1080p.ATVP.WEB-DL"
        const val S01E01 = "$S01/Slow.Horses.S01E01.1080p.ATVP.WEB-DL.mkv"
        const val S01E02 = "$S01/Slow.Horses.S01E02.1080p.ATVP.WEB-DL.mkv"
        const val S02E01 = "$S02/Slow.Horses.S02E01.1080p.ATVP.WEB-DL.mkv"
        const val S02E02 = "$S02/Slow.Horses.S02E02.1080p.ATVP.WEB-DL.mkv"
        const val ARCANE_1 = "Animation/Arcane/Saison 1/Arcane.S01E01.MULTi.1080p.WEB.mkv"
        const val ARCANE_2 = "Animation/Arcane/Saison 2/Arcane.S02E01.MULTi.1080p.WEB.mkv"
        const val SAMURAI = "Animation/Blue Eye Samurai/Saison 1/Blue.Eye.Samurai.S01E01.MULTi.1080p.WEB.mkv"
    }
}
