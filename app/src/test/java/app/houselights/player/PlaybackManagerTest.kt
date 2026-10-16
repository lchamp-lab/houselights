package app.houselights.player

import android.app.Application
import android.content.ComponentName
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
    }) { null }

    private fun plan(uri: String = "nfs://nas/films/Heat.mkv", backdrop: String? = null) = PlaybackPlan.Launch(
        uri = uri, container = "mkv", title = "Heat", backdropUrl = backdrop, logoUrl = null, startMs = 0,
        reporter = reporter, resumeKey = "m1",
    )

    private fun playing(positionMs: Long, durationMs: Long = HEAT) = PlayerAnswer.Playing(ZidooPlayState(true, false, positionMs, durationMs))

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

    /** The film played to its end, then the player went on to the next file of the folder: what it says is not this title's. */
    @Test
    fun anotherFileIsNotTakenForTheTitleLaunched() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        statuses += listOf(playing(HEAT - 9_000), playing(HEAT - 5_000), playing(2_000, durationMs = 2_400_000))
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 5L)
        val pollsThen = polls
        advanceTimeBy(20_000)
        assertEquals("Tracking stopped at the other file", pollsThen, polls)

        // The position the player returns is the other file's.
        manager.onPlayerResult(result(300_000))
        advanceUntilIdle()
        assertEquals("stopped ${HEAT - 5_000} / $HEAT", reports.last())
    }

    /**
     * A disc image opens on a 30 s intro, then its film: the film is the title launched, not another
     * file, and what the player says of it (then returns) is kept.
     */
    @Test
    fun aThirtySecondIntroThenATwoHourFilmIsTheSameTitle() = runTest {
        val manager = manager()
        manager.intentFor(plan())
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

    /** The player's API out of reach a while (a network hiccup): the film still plays, the NAS is still its own. */
    @Test
    fun aPlayerOutOfReachIsStillPlaying() = runTest {
        val manager = manager()
        manager.intentFor(plan())
        statuses += listOf(playing(10_000)) + List(40) { null }
        manager.onPlayerLaunched()
        advanceTimeBy(4_000 + 2_000 * 40L)
        assertTrue(manager.playing.value)
        // Tracking itself stops after some thirty seconds without an answer.
        val pollsThen = polls
        advanceTimeBy(60_000)
        assertEquals(pollsThen, polls)

        manager.onPlayerResult(result(20_000))
        advanceUntilIdle()
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
        val first = manager()
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
        const val HEAT = 10_200_000L
        const val FILM = 7_200_000L
    }
}
