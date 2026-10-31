package app.houselights.providers.trakt

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.player.PlaybackManager
import app.houselights.player.PlayerAnswer
import app.houselights.player.ZidooPlayState
import app.houselights.providers.local.ItemEntity
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.NoEditor
import app.houselights.providers.local.TraktAction
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The end of a playback, from the player's result to Trakt's scrobble: at the player's length, the file's, or none. */
@RunWith(RobolectricTestRunner::class)
class TraktScrobbleTest : TraktSyncFixture() {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val statuses = ArrayDeque<PlayerAnswer>()

    private suspend fun film(runtimeMin: Int?, fileDurationMs: Long? = null) = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = films, title = "Heat", sortTitle = "heat", tmdbId = 949, runtimeMin = runtimeMin,
            fileDurationMs = fileDurationMs, filePath = "Heat.mkv", addedAt = 0,
        ),
    )

    /** Plays film [id] with the player saying [said], then returns from it at [returnedMs]. */
    private suspend fun TestScope.played(sync: TraktSync, id: Long, returnedMs: Int, vararg said: PlayerAnswer) {
        val provider = LocalProvider(context, dao, NoEditor, sync)
        val options = PlaybackOptions(emptyList(), forceHttp = false)
        val plan = provider.playback(provider.item(id.toString()), resume = false, options) as PlaybackPlan.Launch
        val manager = PlaybackManager(context, backgroundScope, status = { statuses.removeFirstOrNull() ?: PlayerAnswer.NoAnswer }) {
            null
        }
        statuses += said
        manager.intentFor(plan)
        manager.onPlayerLaunched()
        advanceTimeBy(FIRST_POLL_MS)
        manager.onPlayerResult(Intent().putExtra("position", returnedMs))
    }

    private fun playing(positionMs: Long, durationMs: Long) =
        PlayerAnswer.Playing(ZidooPlayState(playing = true, paused = false, positionMs = positionMs, durationMs = durationMs, path = null))

    private fun scrobbled() = trakt.sent("POST /scrobble/stop").single()["progress"]!!.jsonPrimitive.content.toFloat()

    @Test
    fun aStopIsScrobbledAtThePlayersLength() = runTest {
        val id = film(runtimeMin = 100)
        val sync = connected()
        played(sync, id, returnedMs = 54 * MINUTE.toInt(), playing(50 * MINUTE, 120 * MINUTE))
        waitFor("the scrobble") { trakt.count("POST /scrobble/stop") == 1 }
        assertEquals(45f, scrobbled())
    }

    @Test
    fun withoutThePlayersLengthTheFilesIsUsed() = runTest {
        val id = film(runtimeMin = 100, fileDurationMs = 90 * MINUTE)
        val sync = connected()
        played(sync, id, returnedMs = 81 * MINUTE.toInt())
        waitFor("the scrobble") { trakt.count("POST /scrobble/stop") == 1 }
        assertEquals(90f, scrobbled())
        // Watched here as on Trakt, from 80 %.
        assertTrue(dao.item(id)!!.played)
    }

    @Test
    fun withNoLengthAtAllNothingIsSent() = runTest {
        val id = film(runtimeMin = null)
        val sync = connected()
        played(sync, id, returnedMs = 30 * MINUTE.toInt())
        advanceUntilIdle()
        waitFor("the position saved") { dao.item(id)!!.positionMs == 30 * MINUTE }
        assertEquals(0, trakt.count("POST /scrobble/stop"))
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun aStatedRuntimeAloneTellsTraktNothing() = runTest {
        // TMDB's runtime may be another cut's: watched here from it, but Trakt hears only a length the file gives.
        val id = film(runtimeMin = 100)
        val sync = connected()
        played(sync, id, returnedMs = 85 * MINUTE.toInt())
        waitFor("watched here") { dao.item(id)!!.played }
        advanceUntilIdle()
        assertEquals(0, trakt.count("POST /scrobble/stop"))
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun aStopOfflineWaitsAsAProgress() = runTest {
        val id = film(runtimeMin = 100, fileDurationMs = 100 * MINUTE)
        val sync = connected()
        trakt.offline = true
        played(sync, id, returnedMs = 30 * MINUTE.toInt())
        waitFor("the stop waiting") { queue.pending().isNotEmpty() && sync.state.value.error != null }
        queue.pending().single().let {
            assertEquals(TraktAction.PROGRESS, it.action)
            assertEquals(30f, it.progress)
        }
    }

    private companion object {
        /** When the manager first asks the player. */
        const val FIRST_POLL_MS = 4_001L
    }
}
