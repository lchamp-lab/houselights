package app.houselights.player

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Following the player through its local API, with a fake player in its place (launching it: [PlayerLaunchTest]). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** What the backend was told, in order. */
    private val reports = mutableListOf<String>()

    /** Held until the test lets it go, when set. */
    private var startGate: CompletableDeferred<Unit>? = null

    private val reporter = object : PlaybackReporter {
        override suspend fun started(positionMs: Long) {
            startGate?.await()
            reports += "started $positionMs"
        }

        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) {
            reports += "progress $positionMs"
        }

        override suspend fun stopped(positionMs: Long, durationMs: Long?) {
            reports += "stopped $positionMs / $durationMs"
        }
    }

    /** What the fake player says at each poll, in turn (null: its API does not answer); then no answer. */
    private val statuses = ArrayDeque<PlayerAnswer?>()
    private var polls = 0

    private fun TestScope.manager() = PlaybackManager(context, this, status = {
        polls++
        statuses.removeFirstOrNull() ?: PlayerAnswer.NoAnswer
    }, clock = { currentTime }) { null }

    private fun plan(uri: String = "nfs://nas/films/Heat.mkv", backdrop: String? = null, disc: Boolean = false) = PlaybackPlan.Launch(
        uri = uri, container = uri.substringAfterLast('.'), title = "Heat", backdropUrl = backdrop, logoUrl = null, startMs = 0,
        reporter = reporter, resumeKey = "m1", disc = disc,
    )

    private fun playing(positionMs: Long, durationMs: Long = HEAT) =
        PlayerAnswer.Playing(ZidooPlayState(playing = true, paused = false, positionMs = positionMs, durationMs = durationMs))

    private fun result(positionMs: Int) = Intent().putExtra("position", positionMs)

    @Test
    fun onlyWhatThePlayerSaysNowIsReported() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        statuses += listOf(playing(10_000)) + List(10) { null } + listOf(playing(30_000))
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 11L + 1)
        // A report at the first status; the fifth and tenth polls had no answer, so nothing was reported.
        assertEquals(listOf("started 0", "progress 10000"), reports)
        manager.onPlayerResult(result(31_000))
        advanceUntilIdle()
        assertEquals("stopped 31000 / $HEAT", reports.last())
    }

    /** Each write syncs a file to flash: done at the report pace, not every status, and once more at the end. */
    @Test
    fun thePositionIsWrittenDownAtTheReportsPace() = runTest {
        val manager = manager()
        val kept = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        manager.intentFor(plan())
        statuses += (1..8).map { playing(it * 2_000L) } + List(15) { PlayerAnswer.NothingPlays }
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 1)
        assertEquals("The first status is written down", 2_000L, kept.getLong(POSITION, -1))
        advanceTimeBy(2_000 * 4L)
        assertEquals("Written down at every status", 2_000L, kept.getLong(POSITION, -1))
        advanceTimeBy(2_000)
        assertEquals(12_000L, kept.getLong(POSITION, -1))
        // Two more statuses, then the player says nothing plays until tracking stops.
        advanceTimeBy(2_000 * 18L)
        assertFalse(manager.playing.value)
        assertEquals("The last status is written down as tracking stops", 16_000L, kept.getLong(POSITION, -1))

        manager.onPlayerResult(result(20_000))
        advanceUntilIdle()
        assertEquals("stopped 20000 / $HEAT", reports.last())
        assertFalse(kept.contains(POSITION))
    }

    /** "Next" pressed mid-film: the player moved to the folder's next file, whose status is not this title's. */
    @Test
    fun anotherFileIsNotTakenForTheTitleLaunched() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        statuses += listOf(playing(7_000_000), playing(7_150_000), playing(300_000, durationMs = 2_400_000))
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 5L)
        val pollsThen = polls
        advanceTimeBy(20_000)
        assertEquals("Tracking stopped at the other file", pollsThen, polls)

        // The position the player returns is the other file's.
        manager.onPlayerResult(result(300_000))
        advanceUntilIdle()
        assertEquals("stopped 7150000 / $HEAT", reports.last())
    }

    /** Part [n] of a film in [of] files, whose next part's file the player names [nextPath]. */
    private fun part(n: Int, of: Int, nextPath: String = "Heat/Heat CD${n + 1}.mkv"): PlaybackReporter = object : PlaybackReporter {
        override suspend fun started(positionMs: Long) {
            reports += "part $n started $positionMs"
        }

        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) {
            reports += "part $n progress $positionMs / $durationMs"
        }

        override suspend fun stopped(positionMs: Long, durationMs: Long?) {
            reports += "part $n stopped $positionMs / $durationMs"
        }

        override suspend fun next() = if (n < of) PlaybackReporter.Next(nextPath, part(n + 1, of), "m1p${n + 1}") else null
    }

    private fun parts(at: String = "nfs://nas/films/Heat/Heat%20CD1.mkv", reporter: PlaybackReporter = part(1, of = 2)) =
        plan(at).copy(reporter = reporter)

    private fun playingFile(positionMs: Long, durationMs: Long, path: String?) =
        PlayerAnswer.Playing(ZidooPlayState(playing = true, paused = false, positionMs = positionMs, durationMs = durationMs, path = path))

    /** The player moves on by itself to the film's second file: still the film, whose end is the last part's. */
    @Test
    fun aFilmsNextPartIsFollowedToItsEnd() = runTest {
        val manager = manager()
        val kept = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        manager.intentFor(parts())
        val first = (1..10).map { playingFile(CD1 - 22_000 + it * 2_000L, CD1, "/mnt/nfs/nas/films/Heat/Heat CD1.mkv") }
        val second = (1..10).map { playingFile(it * 2_000L, CD2, "/mnt/nfs/nas/films/Heat/Heat CD2.mkv") }
        statuses += first + second
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 19L + 1)
        assertTrue("Tracking went on with the second part", polls >= 20)
        assertTrue("The second part was reported: $reports", "part 2 progress 12000 / $CD2" in reports)
        assertEquals("A new process reports the second part", "m1p2", kept.getString("resume_key", null))

        manager.onPlayerResult(result(CD2.toInt() - 1_000))
        advanceUntilIdle()
        assertEquals("part 2 stopped ${CD2 - 1_000} / $CD2", reports.last())
        assertFalse("The first part is not told it stopped elsewhere", reports.any { it.startsWith("part 1 stopped") })
    }

    @Test
    fun theNextPartIsTheFileAfterTheEndOfAPartWhenThePlayerNamesNone() = runTest {
        val manager = manager()
        manager.intentFor(parts())
        statuses += listOf(playingFile(CD1 - 4_000, CD1, null), playingFile(CD1 - 2_000, CD1, null)) +
            (1..10).map { playingFile(it * 2_000L, CD2, null) }
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 11L + 1)
        assertTrue("The second part was reported: $reports", "part 2 progress 18000 / $CD2" in reports)

        manager.onPlayerResult(result(30_000))
        advanceUntilIdle()
        assertEquals("part 2 stopped 30000 / $CD2", reports.last())
    }

    @Test
    fun anotherFileAfterAPartIsNotItsNextPart() = runTest {
        // The player names another file at the end of the first part.
        val manager = manager()
        manager.intentFor(parts())
        statuses += listOf(playingFile(CD1 - 4_000, CD1, "/mnt/nfs/Heat CD1.mkv"), playingFile(CD1 - 2_000, CD1, "/mnt/nfs/Heat CD1.mkv")) +
            (1..10).map { playingFile(it * 2_000L, CD2, "/mnt/nfs/Heat - Trailer.mkv") }
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 11L + 1)
        assertEquals("Tracking stopped at the other file", 3, polls)
        manager.onPlayerResult(result(30_000))
        advanceUntilIdle()
        assertEquals("part 1 stopped ${CD1 - 2_000} / $CD1", reports.last())

        // "Next" pressed in the middle of the first part, the player naming nothing.
        reports.clear()
        statuses.clear()
        polls = 0
        val again = manager()
        again.intentFor(parts())
        statuses += listOf(playingFile(600_000, CD1, null), playingFile(30_000, CD2, null))
        again.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 10L)
        assertEquals("Tracking stopped at the other file", 2, polls)
        again.onPlayerResult(result(30_000))
        advanceUntilIdle()
        assertEquals("part 1 stopped 600000 / $CD1", reports.last())
    }

    /** A 3 min 30 episode played to its end, then the next one, about as long: that is another file. */
    @Test
    fun theNextShortEpisodeIsAnotherFile() = runTest {
        val manager = manager()
        manager.intentFor(plan("nfs://nas/series/Kaamelott/S01E01.mkv"))
        val first = (4..208 step 2).map { playing(it * 1_000L, durationMs = EPISODE) }
        val next = (1..30).map { playing(it * 2_000L, durationMs = EPISODE - 4_000) }
        statuses += first + next
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * (first.size + 10L))
        assertEquals("Tracking went on with the next episode", first.size + 1, polls)

        manager.onPlayerResult(result(60_000))
        advanceUntilIdle()
        assertEquals("stopped 208000 / $EPISODE", reports.last())
    }

    @Test
    fun aThirtySecondIntroThenATwoHourFilmIsTheSameTitle() = runTest {
        val manager = manager()
        manager.intentFor(plan("nfs://nas/films/Heat.iso", disc = true))
        val intro = (4..28 step 2).map { playing(it * 1_000L, durationMs = 30_000) }
        val film = (1..30).map { playing(it * 2_000L, durationMs = FILM) }
        statuses += intro + film
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 30L)
        // Polls 0 to 12 are the intro's, the film follows: the fifteenth poll's report is the film's.
        assertTrue("The film's progress was not reported: $reports", "progress 6000" in reports)

        manager.onPlayerResult(result(3_600_000))
        advanceUntilIdle()
        assertEquals("stopped 3600000 / $FILM", reports.last())
    }

    @Test
    fun aDiscsMenuAfterItsFilmIsNotTheFilm() = runTest {
        val manager = manager()
        manager.intentFor(plan("nfs://nas/films/Heat.iso", disc = true))
        statuses += listOf(playing(FILM - 7_000, FILM), playing(FILM - 4_000, FILM), playing(2_000, durationMs = 30_000))
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 10L)
        assertEquals("Tracking went on with the menu", 3, polls)

        manager.onPlayerResult(result(20_000))
        advanceUntilIdle()
        assertEquals("stopped ${FILM - 4_000} / $FILM", reports.last())
    }

    /** While the API is unreachable, the film keeps the NAS; polling slows, and stops only once nothing plays. */
    @Test
    fun aPlayerOutOfReachIsStillPlaying() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        statuses += listOf(playing(10_000)) + List(40) { null } + List(15) { PlayerAnswer.NothingPlays }
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 16L)
        assertTrue(manager.playing.value)
        // Past some thirty seconds without an answer, it is asked every ten seconds.
        val pollsThen = polls
        advanceTimeBy(60_000)
        assertTrue("Asked ${polls - pollsThen} times in a minute", polls - pollsThen <= 6)
        assertTrue(manager.playing.value)

        advanceTimeBy(600_000)
        assertFalse(manager.playing.value)
    }

    @Test
    fun aPlayerSilentLongerThanItsFilmHadLeftIsNoLongerPlaying() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        // 1 h 50 of a 2 h film.
        statuses += playing(6_600_000, durationMs = FILM)
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 14 * 60_000L)
        assertTrue(manager.playing.value)
        advanceTimeBy(2 * 60_000L)
        assertFalse(manager.playing.value)
        val pollsThen = polls
        advanceTimeBy(60_000)
        assertEquals("Still asked after it stopped", pollsThen, polls)

        // The session waits for the player's result all the same.
        manager.onPlayerResult(result(7_100_000))
        advanceUntilIdle()
        assertEquals("stopped 7100000 / $FILM", reports.last())
    }

    /** Past about four hours without an answer, the scan resumes. */
    @Test
    fun aPlayerThatNeverAnsweredIsNotPlayingForEver() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        manager.onPlayerLaunched()
        advanceTimeBy(3 * 60 * 60_000L + 59 * 60_000L)
        assertTrue(manager.playing.value)
        advanceTimeBy(2 * 60_000L)
        assertFalse(manager.playing.value)
    }

    /** A disc's 30 s intro, then a menu where the API stops answering: the film to come may play for hours. */
    @Test
    fun aDiscsIntroDoesNotBoundTheSilenceThatFollows() = runTest {
        val manager = manager()
        manager.intentFor(plan("nfs://nas/films/Heat.iso", disc = true))
        statuses += (4..28 step 2).map { playing(it * 1_000L, durationMs = 30_000) }
        manager.onPlayerLaunched()
        advanceTimeBy(2 * 60 * 60_000L)
        assertTrue(manager.playing.value)
        advanceTimeBy(2 * 60 * 60_000L + 60_000L)
        assertFalse(manager.playing.value)
    }

    @Test
    fun aPlayerThatSaysNothingPlaysIsNoLongerFollowed() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        statuses += listOf(playing(10_000)) + List(20) { PlayerAnswer.NothingPlays }
        manager.onPlayerLaunched()
        assertTrue(manager.playing.value)
        advanceTimeBy(4_000 + 2_000 * 17L)
        assertFalse(manager.playing.value)
        val pollsThen = polls
        advanceTimeBy(60_000)
        assertEquals(pollsThen, polls)

        // The result still ends the session.
        manager.onPlayerResult(result(20_000))
        advanceUntilIdle()
        assertEquals("stopped 20000 / $HEAT", reports.last())
    }

    /** A player back in a moment: Stopped never reaches the server before Playing. */
    @Test
    fun stoppedWaitsForStarted() = runTest {
        startGate = CompletableDeferred()
        val manager = manager()
        manager.intentFor(plan())
        manager.onPlayerLaunched()
        manager.onPlayerResult(null)
        advanceTimeBy(1_000)
        assertEquals(emptyList<String>(), reports)
        startGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("started 0", "stopped 0 / null"), reports)
    }

    /** On a 2 GB player, Android may stop the app while the film plays: the result reaches a new process. */
    @Test
    fun aSessionOutlivesTheProcess() = runTest {
        // The first process dies: its tracking, in the background scope, never completes.
        val first = PlaybackManager(context, backgroundScope, status = { statuses.removeFirstOrNull() ?: PlayerAnswer.NoAnswer }) { null }
        first.intentFor(plan())
        statuses += playing(600_000)
        first.onPlayerLaunched()
        advanceTimeBy(4_001)

        val reportersAsked = mutableListOf<String>()
        val second = PlaybackManager(context, this, status = { PlayerAnswer.NoAnswer }) { key ->
            reportersAsked += key
            reporter
        }
        second.onPlayerResult(null)
        advanceUntilIdle()
        assertEquals(listOf("m1"), reportersAsked)
        assertEquals("stopped 600000 / null", reports.last())
    }

    private companion object {
        /** Where the manager writes the position down. */
        const val POSITION = "position_ms"
        const val HEAT = 10_200_000L
        const val FILM = 7_200_000L
        const val EPISODE = 210_000L

        /** Durations of a film's two files. */
        const val CD1 = 5_400_000L
        const val CD2 = 4_800_000L
    }
}
