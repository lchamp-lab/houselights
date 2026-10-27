package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
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

/** What the player's reports, as the Zidoo player sends them, leave of a film's resume point. */
@RunWith(RobolectricTestRunner::class)
class LocalReporterTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private var source = 0L

    @Before
    fun open() = runBlocking {
        db = testDatabase { allowMainThreadQueries() }
        dao = db.dao()
        source = dao.insertSource(nfsFolder())
    }

    @After
    fun close() = db.close()

    private suspend fun film(positionMs: Long = 0, played: Boolean = false, runtimeMin: Int = 100, fileDurationMs: Long? = null) =
        dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = source, title = "Heat", sortTitle = "heat", runtimeMin = runtimeMin,
                filePath = "Heat.mkv", fileDurationMs = fileDurationMs, addedAt = 0, positionMs = positionMs, played = played,
            ),
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

    @Test
    fun startingWritesWhenItWasPlayedAlone() = runTest {
        val id = film(positionMs = 5_400_000, played = true)
        // Reads come before the previous session's last report has landed.
        val late = object : PlaybackStateDao by dao {
            override suspend fun playState(id: Long): PlayState? = dao.playState(id)?.copy(positionMs = 4_200_000, played = false)
        }
        LocalReporter(late, id).started(0)
        dao.item(id)!!.let {
            assertEquals(5_400_000L, it.positionMs)
            assertTrue(it.played)
            assertNotNull(it.lastPlayedAt)
        }
    }

    /** Without the player's length, a long version is watched by its file's length, not TMDB's theatrical one. */
    @Test
    fun withoutThePlayersLengthAFilmIsWatchedByItsFilesAsItsCardSaysIt() = runTest {
        val id = film(runtimeMin = 160, fileDurationMs = THREE_HOURS)
        val card = LocalMapper(dao, ApplicationProvider.getApplicationContext()).map(checkNotNull(dao.item(id)))
        assertEquals(THREE_HOURS, card.runtimeMs)

        LocalReporter(dao, id).stopped(positionMs = 140 * 60_000L, durationMs = null)
        dao.item(id)!!.let {
            assertFalse(it.played)
            assertEquals(140 * 60_000L, it.positionMs)
        }
        LocalReporter(dao, id).stopped(positionMs = 165 * 60_000L, durationMs = null)
        assertTrue(dao.item(id)!!.played)
    }

    /** Watched from 80 %, as Trakt counts it; the history hears each stop, unless it kept the resume point. */
    @Test
    fun aFilmIsWatchedFromEightyPercentAndTheHistoryToldWhereItStopped() = runTest {
        val told = mutableListOf<Float>()
        val history = object : WatchHistory {
            override suspend fun stopped(id: Long, percent: Float) {
                told += percent
            }
        }
        val id = film()
        LocalReporter(dao, id, history = history).stopped(30_000, DURATION)
        LocalReporter(dao, id, history = history).stopped(4_740_000, DURATION)
        assertFalse(dao.item(id)!!.played)
        LocalReporter(dao, id, history = history).stopped(4_800_000, DURATION)
        assertTrue(dao.item(id)!!.played)
        assertEquals(listOf(79f, 80f), told)
    }

    private companion object {
        const val DURATION = 6_000_000L
        const val THREE_HOURS = 3 * 3_600_000L
    }
}
