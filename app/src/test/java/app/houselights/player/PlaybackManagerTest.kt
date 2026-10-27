package app.houselights.player

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.shadows.ShadowLog
import java.net.ConnectException

/** Following the player through its local API, with a fake player in its place (launching it: [PlayerLaunchTest]). */
@RunWith(RobolectricTestRunner::class)
class PlaybackManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** What the backend was told, in order. */
    private val reports = mutableListOf<String>()

    /** Held until the test lets it go, when set. */
    private var startGate: CompletableDeferred<Unit>? = null

    /** Thrown by every progress report while set. */
    private var progressFailure: Exception? = null

    private val reporter = object : PlaybackReporter {
        override suspend fun started(positionMs: Long) {
            startGate?.await()
            reports += "started $positionMs"
        }

        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) {
            progressFailure?.let { throw it }
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

    /** A manager that launched the player for [plan], which then answers [answers] in turn. */
    private fun TestScope.started(plan: PlaybackPlan.Launch = plan(), answers: List<PlayerAnswer?> = emptyList()) = manager().apply {
        intentFor(plan)
        statuses += answers
        onPlayerLaunched()
    }

    /** What the backend was last told once the player returned at [positionMs] of the file it names [url] (or not). */
    private fun TestScope.closedAt(manager: PlaybackManager, positionMs: Int, url: String? = null): String {
        manager.onPlayerResult(result(positionMs, url))
        advanceUntilIdle()
        return reports.last()
    }

    private fun plan(uri: String = "nfs://nas/films/Heat.mkv", backdrop: String? = null, disc: Boolean = false) = PlaybackPlan.Launch(
        uri = uri, container = uri.substringAfterLast('.'), title = "Heat", backdropUrl = backdrop, logoUrl = null, startMs = 0,
        reporter = reporter, resumeKey = "m1", disc = disc,
    )

    /** The player at [positionMs] of a file [durationMs] long, which it names [path] (or not). */
    private fun playing(positionMs: Long, durationMs: Long = HEAT, path: String? = null) =
        PlayerAnswer.Playing(ZidooPlayState(playing = true, paused = false, positionMs = positionMs, durationMs = durationMs, path = path))

    private fun result(positionMs: Int, url: String? = null) = Intent().putExtra("position", positionMs).putExtra("url", url)

    @Test
    fun onlyWhatThePlayerSaysNowIsReported() = runTest {
        val manager = started(answers = listOf(playing(10_000)) + List(10) { null } + listOf(playing(30_000)))
        advanceTimeBy(4_000 + 2_000 * 11L + 1)
        // A report at the first status; the fifth and tenth polls had no answer, so nothing was reported.
        assertEquals(listOf("started 0", "progress 10000"), reports)
        assertEquals("stopped 31000 / $HEAT", closedAt(manager, 31_000))
    }

    /** A server away for the whole film: its first failed report is logged whole, the others counted in one line. */
    @Test
    fun reportsThatFailAllFilmLongAreLoggedWholeOnceThenCounted() = runTest {
        ShadowLog.clear()
        progressFailure = ConnectException("Connection refused")
        val manager = started(answers = (1..50).map { playing(it * 2_000L) })
        advanceTimeBy(4_000 + 2_000 * 50L)
        assertEquals("stopped 100000 / $HEAT", closedAt(manager, 100_000))
        val told = ShadowLog.getLogsForTag("Playback").filter { it.type == Log.WARN }
        assertEquals(told.map { it.msg }.toString(), 1, told.count { it.throwable != null })
        assertEquals(listOf("Reports of m1: 9 more failed"), told.filter { it.throwable == null }.map { it.msg })
    }

    /** Each write syncs a file to flash: done at the report pace, not every status, and once more at the end. */
    @Test
    fun thePositionIsWrittenDownAtTheReportsPace() = runTest {
        val kept = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val manager = started(answers = (1..8).map { playing(it * 2_000L) } + List(15) { PlayerAnswer.NothingPlays })
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

        assertEquals("stopped 20000 / $HEAT", closedAt(manager, 20_000))
        assertFalse(kept.contains(POSITION))
    }

    /** "Next" pressed mid-film: the player moved to the folder's next file, whose status is not this title's. */
    @Test
    fun anotherFileIsNotTakenForTheTitleLaunched() = runTest {
        val manager = started(answers = listOf(playing(7_000_000), playing(7_150_000), playing(300_000, durationMs = 2_400_000)))
        advanceTimeBy(4_000 + 2_000 * 5L)
        val pollsThen = polls
        advanceTimeBy(20_000)
        assertEquals("Tracking stopped at the other file", pollsThen, polls)

        // The position the player returns is the other file's.
        assertEquals("stopped 7150000 / $HEAT", closedAt(manager, 300_000))
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

    /** The player moves on by itself to the film's second file: still the film, whose end is the last part's. */
    @Test
    fun aFilmsNextPartIsFollowedToItsEnd() = runTest {
        val kept = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val first = (1..10).map { playing(CD1 - 22_000 + it * 2_000L, CD1, "/mnt/nfs/nas/films/Heat/Heat CD1.mkv") }
        val second = (1..10).map { playing(it * 2_000L, CD2, "/mnt/nfs/nas/films/Heat/Heat CD2.mkv") }
        val manager = started(parts(), first + second)
        advanceTimeBy(4_000 + 2_000 * 19L + 1)
        assertTrue("Tracking went on with the second part", polls >= 20)
        assertTrue("The second part was reported: $reports", "part 2 progress 12000 / $CD2" in reports)
        assertEquals("A new process reports the second part", "m1p2", kept.getString("resume_key", null))

        assertEquals("part 2 stopped ${CD2 - 1_000} / $CD2", closedAt(manager, CD2.toInt() - 1_000, "/mnt/nfs/nas/films/Heat/Heat CD2.mkv"))
        assertFalse("The first part is not told it stopped elsewhere", reports.any { it.startsWith("part 1 stopped") })
    }

    @Test
    fun theNextPartIsTheFileAfterTheEndOfAPartWhenThePlayerNamesNone() = runTest {
        val end = listOf(playing(CD1 - 4_000, CD1), playing(CD1 - 2_000, CD1))
        val manager = started(parts(), end + (1..10).map { playing(it * 2_000L, CD2) })
        advanceTimeBy(4_000 + 2_000 * 11L + 1)
        assertTrue("The second part was reported: $reports", "part 2 progress 18000 / $CD2" in reports)

        assertEquals("part 2 stopped 30000 / $CD2", closedAt(manager, 30_000, "nfs://nas/films/Heat/Heat%20CD2.mkv"))
    }

    /** The player names another file at the end of the first part. */
    @Test
    fun anotherFileAfterAPartIsNotItsNextPart() = runTest {
        val end = listOf(playing(CD1 - 4_000, CD1, "/mnt/nfs/Heat CD1.mkv"), playing(CD1 - 2_000, CD1, "/mnt/nfs/Heat CD1.mkv"))
        val manager = started(parts(), end + (1..10).map { playing(it * 2_000L, CD2, "/mnt/nfs/Heat - Trailer.mkv") })
        advanceTimeBy(4_000 + 2_000 * 11L + 1)
        assertEquals("Tracking stopped at the other file", 3, polls)
        assertEquals("part 1 stopped ${CD1 - 2_000} / $CD1", closedAt(manager, 30_000))
    }

    /** "Next" pressed in the middle of the first part, the player naming nothing. */
    @Test
    fun nextPressedMidPartWithoutAPathIsNotTheNextPart() = runTest {
        val manager = started(parts(), listOf(playing(600_000, CD1), playing(30_000, CD2)))
        advanceTimeBy(4_000 + 2_000 * 10L)
        assertEquals("Tracking stopped at the other file", 2, polls)
        assertEquals("part 1 stopped 600000 / $CD1", closedAt(manager, 30_000))
    }

    /** A 3 min 30 episode played to its end, then the next one, about as long: that is another file. */
    @Test
    fun theNextShortEpisodeIsAnotherFile() = runTest {
        val first = (4..208 step 2).map { playing(it * 1_000L, durationMs = EPISODE) }
        val next = (1..30).map { playing(it * 2_000L, durationMs = EPISODE - 4_000) }
        val manager = started(plan("nfs://nas/series/Kaamelott/S01E01.mkv"), first + next)
        advanceTimeBy(4_000 + 2_000 * (first.size + 10L))
        assertEquals("Tracking went on with the next episode", first.size + 1, polls)

        assertEquals("stopped 208000 / $EPISODE", closedAt(manager, 60_000))
    }

    /** An episode played to its end, then the next, 18 s longer, within the same-file tolerance: the player names it. */
    @Test
    fun theNextEpisodeAsLongIsAnotherFile() = runTest {
        val first = (1..5).map { playing(E01 - 12_000 + it * 2_000L, E01, "/mnt/nfs/nas/series/Show/S01E01.mkv") }
        val next = (1..10).map { playing(it * 2_000L, E02, "/mnt/nfs/nas/series/Show/S01E02.mkv") }
        val manager = started(plan("nfs://nas/series/Show/S01E01.mkv"), first + next)
        advanceTimeBy(4_000 + 2_000 * 14L + 1)
        assertEquals("Tracking stopped at the other file", first.size + 1, polls)
        assertEquals(listOf("started 0", "progress ${E01 - 10_000}"), reports)

        // Stopped at its end, the episode is watched; the position returned is the next one's.
        assertEquals("stopped ${E01 - 2_000} / $E01", closedAt(manager, 1_200_000))
    }

    /** "Next" pressed mid-episode, the next one 18 s longer: only the name the player gives tells it from a seek to the start. */
    @Test
    fun nextPressedMidEpisodeIsAnotherFileAsLong() = runTest {
        val first = (1..5).map { playing(590_000 + it * 2_000L, E01, "/mnt/nfs/nas/series/Show/S01E01.mkv") }
        val next = (1..10).map { playing(it * 2_000L, E02, "/mnt/nfs/nas/series/Show/S01E02.mkv") }
        val manager = started(plan("nfs://nas/series/Show/S01E01.mkv"), first + next)
        advanceTimeBy(4_000 + 2_000 * 14L + 1)
        assertEquals("Tracking stopped at the other file", first.size + 1, polls)

        assertEquals("stopped 600000 / $E01", closedAt(manager, 30_000))
    }

    /** Unnamed, the next episode as long is told by its start right after the end; a seek to the start is not. */
    @Test
    fun theNextEpisodeAsLongStartsOverRightAfterTheEnd() = runTest {
        val seek = listOf(playing(600_000, E01), playing(2_000, E01))
        val end = (1..3).map { playing(E01 - 8_000 + it * 2_000L, E01) }
        val manager = started(plan("nfs://nas/series/Show/S01E01.mkv"), seek + end + (1..10).map { playing(it * 2_000L, E02) })
        advanceTimeBy(4_000 + 2_000 * 14L + 1)
        assertEquals("Tracking stopped at the other file", seek.size + end.size + 1, polls)

        assertEquals("stopped ${E01 - 2_000} / $E01", closedAt(manager, 1_200_000))
    }

    /** The player names the file escaped or not: the same file all the same, and its result position is the title's. */
    @Test
    fun theSameFileNamedOtherwiseIsTheTitle() = runTest {
        val named = listOf("/mnt/smb/nas/series/Show/S01E01 Pilot.mkv", "smb://nas/series/Show/S01E01%20Pilot.mkv")
        val manager = started(plan("smb://nas/series/Show/S01E01%20Pilot.mkv"), (1..10).map { playing(it * 2_000L, E01, named[it % 2]) })
        advanceTimeBy(4_000 + 2_000 * 9L + 1)
        assertEquals("Tracking went on", 10, polls)

        assertEquals("stopped 30000 / $E01", closedAt(manager, 30_000, "smb://nas/series/Show/S01E01 Pilot.mkv"))
    }

    /** The player went on to the next episode after its last status: the position it returns is that file's. */
    @Test
    fun aPositionReturnedForAnotherFileIsNotTheTitles() = runTest {
        val last = playing(E01 - 4_000, E01, "/mnt/nfs/nas/series/Show/S01E01.mkv")
        val manager = started(plan("nfs://nas/series/Show/S01E01.mkv"), listOf(last))
        advanceTimeBy(4_000 + 1)
        assertEquals("stopped ${E01 - 4_000} / $E01", closedAt(manager, 1_200_000, "/mnt/nfs/nas/series/Show/S01E02.mkv"))
    }

    /** No status named the file: the result is read against the file launched. */
    @Test
    fun aResultIsReadAgainstTheFileLaunchedWhenNoStatusNamedIt() = runTest {
        val manager = started(plan("nfs://nas/series/Show/S01E01.mkv"), listOf(playing(E01 - 4_000, E01)))
        advanceTimeBy(4_000 + 1)
        assertEquals("stopped ${E01 - 4_000} / $E01", closedAt(manager, 1_200_000, "/mnt/nfs/nas/series/Show/S01E02.mkv"))
    }

    @Test
    fun aThirtySecondIntroThenATwoHourFilmIsTheSameTitle() = runTest {
        val intro = (4..28 step 2).map { playing(it * 1_000L, durationMs = 30_000) }
        val film = (1..30).map { playing(it * 2_000L, durationMs = FILM) }
        val manager = started(plan("nfs://nas/films/Heat.iso", disc = true), intro + film)
        advanceTimeBy(4_000 + 2_000 * 30L)
        // Polls 0 to 12 are the intro's, the film follows: the fifteenth poll's report is the film's.
        assertTrue("The film's progress was not reported: $reports", "progress 6000" in reports)

        assertEquals("stopped 3600000 / $FILM", closedAt(manager, 3_600_000))
    }

    @Test
    fun aDiscsMenuAfterItsFilmIsNotTheFilm() = runTest {
        val menu = listOf(playing(FILM - 7_000, FILM), playing(FILM - 4_000, FILM), playing(2_000, durationMs = 30_000))
        val manager = started(plan("nfs://nas/films/Heat.iso", disc = true), menu)
        advanceTimeBy(4_000 + 2_000 * 10L)
        assertEquals("Tracking went on with the menu", 3, polls)

        assertEquals("stopped ${FILM - 4_000} / $FILM", closedAt(manager, 20_000))
    }

    /** A disc's film may span several streams (seamless branching): named otherwise, it is still the disc. */
    @Test
    fun aDiscsFilmAcrossStreamsIsTheFilm() = runTest {
        val streams = listOf("/mnt/nfs/nas/films/Heat/BDMV/STREAM/00001.m2ts", "/mnt/nfs/nas/films/Heat/BDMV/STREAM/00002.m2ts")
        val manager = started(plan("nfs://nas/films/Heat/", disc = true), (1..10).map { playing(it * 2_000L, FILM, streams[it / 6]) })
        advanceTimeBy(4_000 + 2_000 * 9L + 1)
        assertEquals("Tracking went on", 10, polls)

        assertEquals("stopped 3600000 / $FILM", closedAt(manager, 3_600_000, "/mnt/nfs/nas/films/Heat/BDMV/STREAM/00003.m2ts"))
    }

    /** While the API is unreachable, the film keeps the NAS; polling slows, and stops only once nothing plays. */
    @Test
    fun aPlayerOutOfReachIsStillPlaying() = runTest {
        val manager = started(answers = listOf(playing(10_000)) + List(40) { null } + List(15) { PlayerAnswer.NothingPlays })
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
        // 1 h 50 of a 2 h film.
        val manager = started(answers = listOf(playing(6_600_000, durationMs = FILM)))
        advanceTimeBy(4_000 + 14 * 60_000L)
        assertTrue(manager.playing.value)
        advanceTimeBy(2 * 60_000L)
        assertFalse(manager.playing.value)
        val pollsThen = polls
        advanceTimeBy(60_000)
        assertEquals("Still asked after it stopped", pollsThen, polls)

        // The session waits for the player's result all the same.
        assertEquals("stopped 7100000 / $FILM", closedAt(manager, 7_100_000))
    }

    /** Past about four hours without an answer, the scan resumes. */
    @Test
    fun aPlayerThatNeverAnsweredIsNotPlayingForEver() = runTest {
        val manager = started()
        advanceTimeBy(3 * 60 * 60_000L + 59 * 60_000L)
        assertTrue(manager.playing.value)
        advanceTimeBy(2 * 60_000L)
        assertFalse(manager.playing.value)
    }

    /** A disc's 30 s intro, then a menu where the API stops answering: the film to come may play for hours. */
    @Test
    fun aDiscsIntroDoesNotBoundTheSilenceThatFollows() = runTest {
        val intro = (4..28 step 2).map { playing(it * 1_000L, durationMs = 30_000) }
        val manager = started(plan("nfs://nas/films/Heat.iso", disc = true), intro)
        advanceTimeBy(2 * 60 * 60_000L)
        assertTrue(manager.playing.value)
        advanceTimeBy(2 * 60 * 60_000L + 60_000L)
        assertFalse(manager.playing.value)
    }

    @Test
    fun aPlayerThatSaysNothingPlaysIsNoLongerFollowed() = runTest {
        val manager = started(answers = listOf(playing(10_000)) + List(20) { PlayerAnswer.NothingPlays })
        assertTrue(manager.playing.value)
        advanceTimeBy(4_000 + 2_000 * 17L)
        assertFalse(manager.playing.value)
        val pollsThen = polls
        advanceTimeBy(60_000)
        assertEquals(pollsThen, polls)

        // The result still ends the session.
        assertEquals("stopped 20000 / $HEAT", closedAt(manager, 20_000))
    }

    /** A player back in a moment: Stopped never reaches the server before Playing. */
    @Test
    fun stoppedWaitsForStarted() = runTest {
        startGate = CompletableDeferred()
        val manager = started()
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
        val first = PlaybackManager(
            context, backgroundScope, status = { statuses.removeFirstOrNull() ?: PlayerAnswer.NoAnswer }, clock = { currentTime },
        ) { null }
        first.intentFor(plan())
        statuses += playing(600_000)
        first.onPlayerLaunched()
        advanceTimeBy(4_001)

        val reportersAsked = mutableListOf<String>()
        val second = PlaybackManager(context, this, status = { PlayerAnswer.NoAnswer }, clock = { currentTime }) { key ->
            reportersAsked += key
            reporter
        }
        second.onPlayerResult(null)
        advanceUntilIdle()
        assertEquals(listOf("m1"), reportersAsked)
        assertEquals("stopped 600000 / null", reports.last())
    }

    /** Killed while the player went on to the next episode, the app reads the result against the file it launched. */
    @Test
    fun aNewProcessKnowsTheFileItLaunched() = runTest {
        val first = PlaybackManager(
            context, backgroundScope, status = { statuses.removeFirstOrNull() ?: PlayerAnswer.NoAnswer }, clock = { currentTime },
        ) { null }
        first.intentFor(plan("nfs://nas/series/Show/S01E01.mkv"))
        statuses += playing(E01 - 4_000, E01, "/mnt/nfs/nas/series/Show/S01E01.mkv")
        first.onPlayerLaunched()
        advanceTimeBy(4_001)
        // The file's name alone: an address may carry a password.
        assertEquals("S01E01.mkv", context.getSharedPreferences("playback", Context.MODE_PRIVATE).getString("file_name", null))

        val second = PlaybackManager(context, this, status = { PlayerAnswer.NoAnswer }, clock = { currentTime }) { reporter }
        second.onPlayerResult(result(1_200_000, "/mnt/nfs/nas/series/Show/S01E02.mkv"))
        advanceUntilIdle()
        assertEquals("stopped ${E01 - 4_000} / null", reports.last())
    }

    /**
     * Ended while the app was stopped, a file's tracks are the player's memory alone ([TrackChooser.plan]), even once
     * resumed and followed again, until it is played from the start.
     */
    @Test
    fun tracksPlayedWhileTheAppWasStoppedAreThePlayersUntilAStartOver() = runTest {
        closedAt(started(), 600_000)
        val first = PlaybackManager(context, backgroundScope, status = { PlayerAnswer.NoAnswer }, clock = { currentTime }) { null }
        assertFalse("followed to its end", first.playersOwnTracks("m1"))
        first.intentFor(plan())
        first.onPlayerLaunched()

        val second = PlaybackManager(context, this, status = { PlayerAnswer.NoAnswer }, clock = { currentTime }) { reporter }
        second.onPlayerResult(result(600_000))
        advanceUntilIdle()
        assertTrue(second.playersOwnTracks("m1"))
        closedAt(started(plan().copy(startMs = 600_000)), 700_000)
        assertTrue("resumed and followed", second.playersOwnTracks("m1"))
        val again = manager().apply { intentFor(plan()) }
        assertTrue("not started", second.playersOwnTracks("m1"))
        again.onPlayerLaunched()
        assertFalse("from the start", second.playersOwnTracks("m1"))
        closedAt(again, 60_000)
    }

    /** A stream the player names without its query, or with it otherwise, is the stream launched. */
    @Test
    fun aStreamIsTheStreamWhateverItsQuery() = runTest {
        val stream = "https://jf.example/Videos/abc/stream.mkv?static=true&mediaSourceId=abc&api_key=k"
        val manager = started(plan(stream), listOf(playing(600_000, FILM, "https://jf.example/Videos/abc/stream.mkv")))
        advanceTimeBy(4_000 + 1)
        assertEquals("stopped 650000 / $FILM", closedAt(manager, 650_000, "https://jf.example/Videos/abc/stream.mkv?static=true"))
    }

    private companion object {
        /** Where the manager writes the position down. */
        const val POSITION = "position_ms"
        const val HEAT = 10_200_000L
        const val FILM = 7_200_000L
        const val EPISODE = 210_000L

        /** Two episodes 18 s apart, within the same-file length tolerance. */
        const val E01 = 2_530_000L
        const val E02 = 2_548_000L

        /** Durations of a film's two files. */
        const val CD1 = 5_400_000L
        const val CD2 = 4_800_000L
    }
}
