package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** What the player's reports leave of a film's resume point, as the Zidoo player sends them. */
@RunWith(RobolectricTestRunner::class)
class LocalReporterTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private var source = 0L

    @Before
    fun open() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        source = dao.insertSource(
            SourceEntity(
                name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas", share = "media",
                path = "films", username = "", password = Secret(""),
            )
        )
    }

    @After
    fun close() = db.close()

    private suspend fun film(positionMs: Long = 0, played: Boolean = false): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = "Heat", sortTitle = "heat", runtimeMin = 100,
            filePath = "Heat.mkv", addedAt = 0, positionMs = positionMs, played = played,
        )
    )

    @Test
    fun startingOverAndLeavingWithinAMinuteKeepsTheResumePoint() = runTest {
        val id = film(positionMs = 4_200_000)
        val reporter = LocalReporter(dao, id)

        reporter.started(0)
        dao.item(id)!!.let {
            assertEquals(4_200_000L, it.positionMs)
            assertNotNull(it.lastPlayedAt)
        }
        reporter.progress(14_000, DURATION, paused = false)
        assertEquals(4_200_000L, dao.item(id)!!.positionMs)
        reporter.stopped(30_000, DURATION)
        assertEquals(4_200_000L, dao.item(id)!!.positionMs)
    }

    @Test
    fun aFilmStartedByMistakeHasNothingToResume() = runTest {
        val id = film()
        val reporter = LocalReporter(dao, id)
        reporter.started(0)
        reporter.progress(4_000, DURATION, paused = false)
        reporter.stopped(5_000, DURATION)
        assertEquals(0L, dao.item(id)!!.positionMs)
        assertTrue(dao.inProgress(10).isEmpty())
    }

    @Test
    fun pastAMinuteEveryReportMovesTheResumePoint() = runTest {
        val id = film(positionMs = 4_200_000)
        val reporter = LocalReporter(dao, id)
        reporter.started(0)
        reporter.progress(64_000, DURATION, paused = false)
        assertEquals(64_000L, dao.item(id)!!.positionMs)
        reporter.stopped(90_000, DURATION)
        dao.item(id)!!.let {
            assertEquals(90_000L, it.positionMs)
            assertFalse(it.played)
        }
    }

    @Test
    fun aWatchedFilmStartedAgainHasNoResumePoint() = runTest {
        val id = film(played = true)
        val reporter = LocalReporter(dao, id)
        reporter.started(0)
        reporter.stopped(30_000, DURATION)
        dao.item(id)!!.let {
            assertEquals(0L, it.positionMs)
            assertTrue(it.played)
        }
    }

    private companion object {
        const val DURATION = 6_000_000L
    }
}
