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
