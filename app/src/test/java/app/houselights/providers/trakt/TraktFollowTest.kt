package app.houselights.providers.trakt

import app.houselights.core.SourceConfig
import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktPendingEntity
import app.houselights.providers.local.TraktStateEntity
import app.houselights.settings.AppSettings
import app.houselights.settings.SecretSetting
import app.houselights.settings.TraktFollow
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Trakt following the settings: the account and the source, and a session the Keystore could not open at launch. */
@RunWith(RobolectricTestRunner::class)
class TraktFollowTest : TraktSyncFixture() {
    /** A removal that waited from an earlier launch. */
    private val waiting = TraktPendingEntity("movie:70", TraktAction.UNWATCHED, NOW - MINUTE)

    /** The sync following [settings], as the app's container runs it. */
    private fun TestScope.following(settings: MutableStateFlow<AppSettings>): TraktSync {
        val follow = TraktFollow(settings)
        return sync(following = follow::queues).also { backgroundScope.launch { follow.drive(it) } }
    }

    /**
     * Android 9's keystore daemon restarting at launch: the session reads as unset, yet nothing is signed out nor
     * forgotten, and once it opens again the sync goes on from where it was.
     */
    @Test
    fun aSessionTheKeystoreCouldNotOpenForgetsNothingAndQueuesMarks() = runTest {
        val id = film(71)
        queue.queue(listOf(waiting))
        val synced = TraktStateEntity(syncedAt = NOW - MINUTE, activities = "x")
        queue.saveState(synced)
        val saved = session
        session = null
        val settings = MutableStateFlow(AppSettings(loaded = true, source = SourceConfig.Local, lostSecrets = setOf(SecretSetting.TRAKT)))
        val sync = following(settings)
        provider(sync).mark(id, played = true)
        advanceUntilIdle()
        assertEquals(synced, queue.state())
        assertEquals(setOf(waiting.title, "movie:71"), queue.pending().map { it.title }.toSet())
        assertEquals(emptyList<String>(), trakt.routes)

        // Synced a minute before: what waited goes alone, Trakt's history is not read again from the start.
        session = saved
        settings.value = AppSettings(loaded = true, source = SourceConfig.Local, trakt = saved)
        waitFor("what waited sent") { queue.pending().isEmpty() && !sync.state.value.running }
        assertEquals(listOf(1, 1), listOf(trakt.count("POST /sync/history/remove"), trakt.count("POST /sync/history")))
        assertEquals(0, trakt.count("GET /sync/last_activities") + trakt.count("GET /sync/watched/movies"))
    }

    @Test
    fun signingOutForgetsWhatWaitsAndQueuesNothing() = runTest {
        val id = film(72)
        queue.queue(listOf(waiting))
        session = null
        val sync = following(MutableStateFlow(AppSettings(loaded = true, source = SourceConfig.Local)))
        waitFor("the queue forgotten") { queue.pending().isEmpty() }

        provider(sync).mark(id, played = true)
        advanceUntilIdle()
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun marksAreQueuedWithTheFoldersOnly() {
        val unread = AppSettings(loaded = true, source = SourceConfig.Local, lostSecrets = setOf(SecretSetting.TRAKT))
        assertTrue(TraktFollow.queues(unread))
        assertFalse(TraktFollow.queues(unread.copy(source = SourceConfig.Jellyfin("https://nas", null, "u1", null, "t"))))
        assertFalse(TraktFollow.queues(unread.copy(lostSecrets = emptySet())))
    }
}
