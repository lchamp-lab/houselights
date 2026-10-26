package app.houselights.providers.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What waits for Trakt: one intent per title, the latest; a sync's never over a mark's; only what was sent leaves. */
@RunWith(RobolectricTestRunner::class)
class TraktDaoTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: TraktDao

    @Before
    fun open() = runBlocking {
        db = testDatabase { allowMainThreadQueries() }
        dao = db.trakt()
    }

    @After
    fun close() = db.close()

    private fun intent(title: String, action: TraktAction, at: Long = 1) = TraktPendingEntity(title, action, at)

    private suspend fun film(tmdb: Int, lastPlayedAt: Long? = null, versionOf: Long? = null): Long {
        val source = db.dao().sources().firstOrNull()?.id ?: db.dao().insertSource(nfsFolder())
        return db.dao().insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = source, title = "F$tmdb", sortTitle = "f$tmdb", tmdbId = tmdb, addedAt = 0,
                filePath = "F$tmdb${versionOf?.let { " 4K" }.orEmpty()}.mkv", lastPlayedAt = lastPlayedAt, versionOf = versionOf,
            )
        )
    }

    @Test
    fun aTitleWaitsOnceWithItsLatestIntent() = runTest {
        dao.queue(listOf(intent("movie:1", TraktAction.WATCHED), intent("movie:2", TraktAction.WATCHED)))
        dao.queue(listOf(intent("movie:1", TraktAction.UNWATCHED, at = 2)))
        assertEquals(
            listOf("movie:2" to TraktAction.WATCHED, "movie:1" to TraktAction.UNWATCHED),
            dao.pending().map { it.title to it.action },
        )
    }

    @Test
    fun aSyncNeverTakesTheVoiceOfAMark() = runTest {
        dao.queue(listOf(intent("movie:1", TraktAction.UNWATCHED)))
        dao.queue(listOf(intent("movie:1", TraktAction.WATCHED), intent("movie:3", TraktAction.WATCHED)), replace = false)
        assertEquals(listOf(TraktAction.UNWATCHED, TraktAction.WATCHED), dao.pending().map { it.action })
    }

    @Test
    fun onlyWhatWasSentLeavesTheQueue() = runTest {
        dao.queue(listOf(intent("movie:1", TraktAction.WATCHED)))
        val sent = dao.pending()
        // Unmarked while the request was on its way.
        dao.queue(listOf(intent("movie:1", TraktAction.UNWATCHED, at = 2)))
        dao.sent(sent)
        assertEquals(listOf(TraktAction.UNWATCHED), dao.pending().map { it.action })
    }

    @Test
    fun whatTraktSaysLeavesTheTitlesThatWait() = runTest {
        val waiting = film(1)
        val free = film(2)
        dao.queue(listOf(intent("movie:1", TraktAction.UNWATCHED)))
        dao.apply(
            mapOf(5L to listOf(waiting, free)),
            listOf(Resume(waiting, 60_000, 6), Resume(free, 60_000, 6)),
            mapOf(waiting to listOf("movie:1"), free to listOf("movie:2")),
        )
        db.dao().item(waiting)!!.let { assertEquals(false to 0L, it.played to it.positionMs) }
        db.dao().item(free)!!.let { assertEquals(true to 60_000L, it.played to it.positionMs) }
    }

    @Test
    fun aPauseNeverOverwritesAStopSavedSince() = runTest {
        val id = film(1, lastPlayedAt = 10)
        dao.resumeAt(id, 60_000, at = 5)
        assertEquals(0L, db.dao().item(id)!!.positionMs)
        dao.resumeAt(id, 60_000, at = 20)
        assertEquals(60_000L to 20L, db.dao().item(id)!!.let { it.positionMs to it.lastPlayedAt })
    }

    @Test
    fun aTitleTraktFailedFiveTimesLeavesTheQueueAndANewIntentStartsAfresh() = runTest {
        dao.queue(listOf(intent("movie:1", TraktAction.WATCHED)))
        dao.sending(dao.pending(), at = 7)
        repeat(4) { assertEquals(0, dao.failed(dao.pending(), most = 5)) }
        assertEquals(4 to 7L, dao.pending().single().let { it.attempts to it.sentAt })

        dao.queue(listOf(intent("movie:1", TraktAction.UNWATCHED, at = 2)))
        assertEquals(0 to null, dao.pending().single().let { it.attempts to it.sentAt })
        repeat(4) { dao.failed(dao.pending(), most = 5) }
        assertEquals(1, dao.failed(dao.pending(), most = 5))
        assertTrue(dao.pending().isEmpty())
    }

    @Test
    fun aWriteThatNeverWentKeepsWhenItWentBefore() = runTest {
        dao.queue(listOf(intent("movie:1", TraktAction.WATCHED)))
        dao.sending(dao.pending(), at = 7)
        val tried = dao.pending()
        dao.sending(tried, at = 9)
        dao.unsent(tried)
        assertEquals(7L, dao.pending().single().sentAt)
    }

    @Test
    fun aFilmIsAllItsCopiesAndPlaysTheOneChosen() = runTest {
        val alone = film(2)
        val main = film(1)
        val copy = film(1, versionOf = main)
        assertEquals(setOf(main, copy), dao.rowsOf(main).map { it.id }.toSet())
        assertEquals(setOf(main, copy), dao.rowsOf(copy).map { it.id }.toSet())
        assertEquals(listOf(alone), dao.rowsOf(alone).map { it.id })
        assertNull(dao.playedCopy(alone))
        db.dao().setVersionChoice(main, copy)
        assertEquals(copy, dao.playedCopy(main))
    }

    /** Show [tmdb] in [order] (null: TMDB's seasons), and its episode at [place] showing TMDB's [shown], numbered from its start or not. */
    private suspend fun episode(
        tmdb: Int,
        place: Pair<Int, Int>,
        shown: Pair<Int, Int>?,
        absolute: Int? = null,
        order: String? = null,
    ): Long {
        val library = db.dao()
        val source = library.sources().firstOrNull()?.id ?: library.insertSource(nfsFolder())
        val show = library.seriesByKey("show $tmdb")?.id ?: library.insert(
            ItemEntity(
                kind = ItemKind.SERIES, sourceId = source, matchKey = "show $tmdb", title = "S", sortTitle = "s", tmdbId = tmdb,
                addedAt = 0,
            ),
        )
        if (order != null) library.setEpisodeOrder(show, order)
        return library.insert(
            ItemEntity(
                kind = ItemKind.EPISODE, sourceId = source, seriesId = show, title = "E", sortTitle = "e", addedAt = 0,
                filePath = "S/${place.first}-${place.second}.mkv", seasonNumber = place.first, episodeNumber = place.second,
                absoluteNumber = absolute, tmdbSeason = shown?.first, tmdbEpisode = shown?.second,
            ),
        )
    }

    /** Trakt names an episode by the TMDB episode it shows, whatever its place: One Piece's 1071st, Firefly's pilot on DVD. */
    @Test
    fun anEpisodePlacedFromItsAbsoluteNumberGoesToTraktByTmdbsNumbers() = runTest {
        val placed = episode(37854, place = 21 to 180, shown = 21 to 180, absolute = 1071)
        val dvd = episode(1437, place = 1 to 1, shown = 1 to 11, order = "dvd")
        val aired = episode(95396, place = 1 to 3, shown = null)
        val rows = dao.rows().associateBy { it.id }
        val numbers = listOf(placed, dvd, aired).map { id -> rows.getValue(id).let { it.seasonNumber to it.episodeNumber } }
        assertEquals(listOf(21 to 180, 1 to 11, 1 to 3), numbers)
    }

    /** One waiting for TMDB to list it, or left out of its show's order, is not sent: Trakt would mark another episode. */
    @Test
    fun anEpisodeWaitingForItsPlaceIsNotSentToTrakt() = runTest {
        val waiting = episode(37854, place = 1 to 1072, shown = null, absolute = 1072)
        val outOfOrder = episode(1437, place = 1 to 15, shown = null, order = "dvd")
        assertEquals(emptyList<Long>(), dao.rows().map { it.id }.filter { it == waiting || it == outOfOrder })
    }

    @Test
    fun forgettingTheAccountKeepsTheLibrary() = runTest {
        val source = db.dao().insertSource(nfsFolder())
        val id = db.dao().insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = source, title = "Heat", sortTitle = "heat", tmdbId = 949, addedAt = 0, played = true,
            )
        )
        dao.queue(listOf(intent("movie:949", TraktAction.UNWATCHED)))
        dao.saveState(TraktStateEntity(syncedAt = 5, watched = 1))
        dao.forget()
        assertTrue(dao.pending().isEmpty())
        assertNull(dao.state())
        assertTrue(db.dao().item(id)!!.played)
    }
}
