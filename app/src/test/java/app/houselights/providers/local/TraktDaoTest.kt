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
