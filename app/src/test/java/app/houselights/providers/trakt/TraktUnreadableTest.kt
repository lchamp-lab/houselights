package app.houselights.providers.trakt

import app.houselights.testing.Answer
import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktPendingEntity
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.FakeTrakt.Companion.activities
import app.houselights.providers.trakt.FakeTrakt.Companion.movie
import app.houselights.providers.trakt.FakeTrakt.Companion.pausedMovie
import app.houselights.providers.trakt.FakeTrakt.Companion.show
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** Answers Trakt keeps in a shape this version cannot read: left out and logged, never the whole sync lost. */
@RunWith(RobolectricTestRunner::class)
class TraktUnreadableTest : TraktSyncFixture() {
    @Test
    fun anEntryTraktKeepsInAShapeThisVersionCannotReadIsLeftOutTheOthersApplied() = runTest {
        val seen = film(61)
        val show = series(101)
        val first = episode(show, 1)
        val second = episode(show, 2)
        val paused = film(62)
        // Another app's entries: a count as text, seasons that are no list, a pause without a progress or a date.
        trakt.movies = """[{"plays":"twice","movie":{"ids":{"tmdb":63}}},${movie(61, T2)},{"plays":1}]"""
        trakt.shows = """[{"show":{"ids":{"tmdb":102}},"seasons":"all"},${show(101, T2, 1 to 1)},""" +
            """{"show":{"ids":{"tmdb":101}},"seasons":[{"episodes":[{"number":2}]},{"number":1,"episodes":[{"plays":1}]}]}]"""
        trakt.playback = """[{"id":"x"},{"id":81,"progress":null,"paused_at":"$T2","type":"movie","movie":{"ids":{"tmdb":62}}},""" +
            """{"id":82,"progress":20,"type":"movie","movie":{"ids":{"tmdb":62}}},${pausedMovie(83, 62, 60f, T2)}]"""

        val sync = connected()

        assertNull(sync.state.value.error)
        assertTrue(dao.item(seen)!!.played)
        assertTrue(dao.item(first)!!.played)
        assertFalse(dao.item(second)!!.played)
        assertEquals(60 * MINUTE, dao.item(paused)!!.positionMs)
    }

    /** A history read in part may hide what it seems to lack: a first sync sends none of that kind, the other kind still. */
    @Test
    fun aHistoryReadInPartSendsNothingOfItsKind() = runTest {
        film(67, played = true, lastPlayedAt = ms(T1))
        episode(series(103), 1, played = true, lastPlayedAt = ms(T1))
        trakt.shows = """[{"show":{"ids":{"tmdb":104}},"seasons":"all"}]"""

        val sync = connected()

        val sent = trakt.sent("POST /sync/history")
        assertEquals(listOf(listOf(67)), sent.map { it.tmdbs })
        assertTrue(sent.all { it["shows"]?.jsonArray.isNullOrEmpty() })
        assertNull(sync.state.value.error)
    }

    /** A mark whose answer was lost is not sent again while Trakt's history, read in part, may hold it. */
    @Test
    fun aMarkWhoseAnswerWasLostIsNotSentAgainOverAHistoryReadInPart() = runTest {
        val lost = film(68)
        val sync = connected()
        trakt.during("POST /sync/history") {
            trakt.movies = """[{"plays":"twice","movie":{"ids":{"tmdb":69}}}]"""
            trakt.activities = activities(iso(NOW))
        }
        trakt.lose("POST /sync/history")
        provider(sync).mark(lost, played = true)
        waitFor("the answer lost") { sync.state.value.error != null }
        sync.scanned(changedLibrary = false)
        waitFor("the mark taken as Trakt's") { queue.pending().isEmpty() && !sync.state.value.running }
        assertEquals(1, trakt.count("POST /sync/history"))
    }

    @Test
    fun aRemovalWaitingGoesThoughTraktKeepsAPauseItCannotRead() = runTest {
        film(64)
        trakt.playback = """[{"id":84,"progress":"half","type":"movie"},${pausedMovie(85, 64, 30f, T1)}]"""
        queue.queue(listOf(TraktPendingEntity("movie:64", TraktAction.UNWATCHED, NOW)))

        val sync = connected()

        assertEquals(listOf(64), trakt.sent("POST /sync/history/remove").single().tmdbs)
        assertEquals(1, trakt.count("DELETE /sync/playback/85"))
        assertTrue(queue.pending().isEmpty())
        assertNull(sync.state.value.error)
    }

    @Test
    fun aWriteTraktTookLeavesTheQueueWhateverItsAnswerSays() = runTest {
        queue.queue(
            listOf(
                TraktPendingEntity("movie:65", TraktAction.WATCHED, NOW),
                TraktPendingEntity("movie:66", TraktAction.PROGRESS, NOW, progress = 30f),
            )
        )
        trakt.stage("POST /sync/history", Answer(201))
        trakt.stage("POST /scrobble/stop", Answer(201, "{}"))

        val sync = connected()

        assertEquals(1, trakt.count("POST /sync/history"))
        assertEquals(1, trakt.count("POST /scrobble/stop"))
        assertTrue(queue.pending().isEmpty())
        assertNull(sync.state.value.error)
    }

    @Test
    fun aFailureIsLoggedWithTheRouteItCameFrom() = runTest {
        ShadowLog.clear()
        queue.queue(listOf(TraktPendingEntity("movie:67", TraktAction.WATCHED, NOW)))
        trakt.stage("POST /sync/history", Answer(503))
        trakt.shows = """{"error":"s3cret"}"""
        val sync = sync()

        sync.follow(connected = true, folders = true)
        waitFor("the failure said") { sync.state.value.error == TraktSyncError.UNREADABLE && !sync.state.value.running }

        val told = ShadowLog.getLogsForTag("Trakt").map { it.msg }
        assertTrue(told.toString(), "What waits could not be sent to Trakt: sync/history, HTTP 503" in told)
        assertTrue(told.toString(), "Trakt could not be synced: sync/watched/shows, unreadable answer at $" in told)
        assertTrue(told.none { "s3cret" in it })
    }
}
