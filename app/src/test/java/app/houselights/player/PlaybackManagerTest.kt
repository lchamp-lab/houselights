package app.houselights.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Launching the player, then following it through its local API, with a fake player in its place. */
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
    fun aPlainFileGoesToAnyPlayerWhereThereIsNoZidoo() = runTest {
        val intent = manager().intentFor(plan())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertNull(intent.component)
    }

    /** Jellyfin's stream carries its token: another app gets it only when the user picks it. */
    @Test
    fun aTokenGoesElsewhereOnlyThroughAChooser() = runTest {
        val intent = manager().intentFor(plan("http://nas:8096/Videos/1/stream.mkv?static=true&api_key=secret"))
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        @Suppress("DEPRECATION")
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_VIEW, inner?.action)
    }

    @Test
    fun aPasswordNeverGoesToAnotherPlayer() = runTest {
        try {
            manager().intentFor(plan("smb://zidoo:secret@nas/films/Heat.mkv"))
            fail("The password went to another app")
        } catch (_: PlaybackManager.CredentialsNotShared) {
            // As it should.
        }
    }

    /** The Zidoo player is another app: a file of ours is out of its reach, a web address is not. */
    @Test
    fun theZidooPlayerGetsTheCredentialsAndOnlyWebArtwork() = runTest {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(
            PackageInfo().apply {
                packageName = PlaybackManager.ZIDOO_PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    packageName = PlaybackManager.ZIDOO_PACKAGE
                    flags = ApplicationInfo.FLAG_SYSTEM
                }
            }
        )
        pm.addActivityIfNotPresent(ComponentName(PlaybackManager.ZIDOO_PACKAGE, PlaybackManager.ZIDOO_ACTIVITY))
        val manager = manager()

        val ours = "file:///data/user/0/app.houselights/files/artwork/b.jpg"
        val local = manager.intentFor(plan("smb://zidoo:secret@nas/films/Heat.mkv", backdrop = ours))
        assertEquals(PlaybackManager.ZIDOO_ACTIVITY, local.component?.className)
        assertFalse(local.hasExtra("loading_image"))

        val remote = manager.intentFor(plan(backdrop = "https://image.tmdb.org/t/p/w1280/b.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", remote.getStringExtra("loading_image"))
    }

    @Test
    fun onlyWhatThePlayerSaysNowIsReported() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        statuses += listOf(playing(10_000)) + List(10) { null } + listOf(playing(30_000))
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 11L + 1)
        // A report at the first status; at the fifth and tenth polls, nothing was said: nothing was reported.
        assertEquals(listOf("started 0", "progress 10000"), reports)
        manager.onPlayerResult(result(31_000))
        advanceUntilIdle()
        assertEquals("stopped 31000 / $HEAT", reports.last())
    }

    /**
     * Each write of the position rewrites its file and syncs it to the flash: it is written at the
     * backend's pace, some ten seconds, not at every status, and once more as tracking stops.
     */
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

    /** "Next" pressed in the middle of the film: the player went on to the next file of the folder, what it says is not this title's. */
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

    /**
     * Lire on a film in two files: the player plays the first, then goes on by itself to the second, the
     * next file of its folder. That is the film still: its last part is reported, the end of the film.
     */
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

    /** A player that does not say what it plays: at the end of a part, it goes on to the next file of its folder, the next part. */
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

    /** A film in parts, then another file than its next part: that file is not the film's, tracking stops as for any other. */
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

    /** An episode of 3 min 30 played to its end, then the player went on to the next one, about as long: it is another file. */
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

    /**
     * A disc image opens on a 30 s intro, then its film: the film is the title launched, not another
     * file, and what the player says of it (then returns) is kept.
     */
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

    /** A disc's film played to its end, then its menu came back: the menu is not the film. */
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

    /**
     * The player's API out of reach a while: the film still plays, the NAS is still its own. The API
     * is asked less often, not given up while the film may still play: once it says nothing plays,
     * nothing does.
     */
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

    /**
     * The API gone with its player, or answering without a video for ever: once more time went by than
     * the film had left, and a margin, it can no longer be playing. The scan gets its NAS back.
     */
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

    /** A player that never said what it plays is not playing for ever: past some four hours, the scan goes on. */
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

    /**
     * A disc's 30 s intro, then its menu, where the player's API says no more: what was left of the
     * intro says nothing of the film to come, which may play for hours.
     */
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
        // The process stopped: what it was doing goes with it, never to an end of its own.
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

        /** A film's two files. */
        const val CD1 = 5_400_000L
        const val CD2 = 4_800_000L
    }
}
