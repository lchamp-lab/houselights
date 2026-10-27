package app.houselights.providers.trakt

import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktPendingEntity
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.FakeTrakt.Companion.activities
import app.houselights.providers.trakt.FakeTrakt.Companion.movie
import app.houselights.providers.trakt.FakeTrakt.Companion.pausedMovie
import app.houselights.providers.trakt.FakeTrakt.Companion.show
import app.houselights.testing.Answer
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

/** Answers Trakt keeps in a shape this version cannot read: left out, logged and said, never the whole sync lost. */
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

    /** What a sync left out is kept by what Trakt still names of it, until a sync that reads Trakt reads everything. */
    @Test
    fun whatASyncLeftOutIsKeptByNameUntilOneReadsEverything() = runTest {
        trakt.movies = """[{"plays":"twice","movie":{"title":"Ocean's Eleven","year":2001,"ids":{"tmdb":161}}},${movie(61, T2)}]"""
        trakt.shows = """[{"show":{"title":" Breaking Bad ","year":2008,"ids":{"tmdb":1396}},"seasons":"all"}]"""
        // Nothing named, an episode named by its show, a title that is no text.
        trakt.playback = """[{"id":"x"},{"id":"y","type":"episode","show":{"title":"Severance","year":2022}},""" +
            """{"id":"z","type":"movie","movie":{"title":42,"year":1999}},${pausedMovie(83, 62, 60f, T2)}]"""

        val sync = connected()

        val left = listOf(
            SkippedEntry(SkippedKind.MOVIE_HISTORY, "Ocean’s Eleven", 2001),
            SkippedEntry(SkippedKind.EPISODE_HISTORY, "Breaking Bad", 2008),
            SkippedEntry(SkippedKind.PAUSED),
            SkippedEntry(SkippedKind.PAUSED, "Severance", 2022),
            SkippedEntry(SkippedKind.PAUSED),
        )
        assertEquals(left, sync.state.value.skipped)
        assertEquals(setOf(SkippedKind.MOVIE_HISTORY, SkippedKind.EPISODE_HISTORY), sync.state.value.skipped.readInPart)
        assertNull("not an error: the sync went through", sync.state.value.error)

        // Trakt unchanged, a check reads none of its lists: what the last read left out stands.
        sync.scanned(changedLibrary = false)
        waitFor("the check done") { trakt.count("GET /sync/last_activities") == 2 && !sync.state.value.running }
        assertEquals(1, trakt.count("GET /sync/watched/movies"))
        assertEquals(left, sync.state.value.skipped)

        trakt.movies = "[${movie(61, T2)}]"
        trakt.shows = "[]"
        trakt.playback = "[${pausedMovie(83, 62, 60f, T2)}]"
        sync.syncNow()
        waitFor("a sync reading everything") { trakt.count("GET /sync/watched/movies") == 2 && !sync.state.value.running }
        assertEquals(emptyList<SkippedEntry>(), sync.state.value.skipped)
    }

    /** Nothing of what a sync left out is stored: a new process reads Trakt at launch, though unchanged and synced lately. */
    @Test
    fun aNewProcessSaysAgainWhatTheLastSyncLeftOut() = runTest {
        trakt.playback = """[{"id":84,"progress":"half","type":"movie","movie":{"title":"Heat","year":1995}}]"""
        connected()
        val heat = listOf(SkippedEntry(SkippedKind.PAUSED, "Heat", 1995))

        val relaunched = sync()
        relaunched.follow(connected = true, folders = true)
        waitFor("Trakt read again") { relaunched.state.value.skipped == heat && !relaunched.state.value.running }
        assertEquals(2, trakt.count("GET /sync/playback"))

        // Read once a process: a check after that reads its lists only if Trakt changed.
        relaunched.scanned(changedLibrary = false)
        waitFor("the check done") { trakt.count("GET /sync/last_activities") == 3 && !relaunched.state.value.running }
        assertEquals(2, trakt.count("GET /sync/playback"))
        assertEquals(heat, relaunched.state.value.skipped)
    }

    /** A pause left out hides nothing of the histories, read whole. */
    @Test
    fun aPauseLeftOutLeavesTheHistoriesWhole() = runTest {
        trakt.playback = """[{"id":84,"progress":"half","type":"movie","movie":{"title":"Heat","year":1995}}]"""

        val sync = connected()

        assertEquals(listOf(SkippedEntry(SkippedKind.PAUSED, "Heat", 1995)), sync.state.value.skipped)
        assertEquals(emptySet<SkippedKind>(), sync.state.value.skipped.readInPart)
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

    /** Over a history read in part, a late stop never sent goes at its date; one sent without an answer may be there. */
    @Test
    fun aLateStopWatchedGoesOverAHistoryReadInPartUnlessItMayHaveReachedTrakt() = runTest {
        val sync = connected()
        val hourAgo = NOW - 60 * MINUTE
        trakt.movies = """[{"plays":"twice","movie":{"ids":{"tmdb":69}}}]"""
        trakt.activities = activities(iso(NOW - 30 * MINUTE))
        queue.queue(
            listOf(
                TraktPendingEntity("movie:51", TraktAction.PROGRESS, hourAgo, progress = 90f),
                TraktPendingEntity("movie:52", TraktAction.PROGRESS, hourAgo, progress = 90f),
            ),
        )
        queue.sending(queue.pending().filter { it.title == "movie:52" }, at = hourAgo)

        sync.scanned(changedLibrary = false)
        waitFor("the stops sent") { queue.pending().isEmpty() && !sync.state.value.running }

        val sent = trakt.sent("POST /sync/history").single()
        assertEquals(listOf(51), sent.tmdbs)
        assertEquals(iso(hourAgo), sent.movieDate())
        assertEquals(0, trakt.count("POST /scrobble/stop"))
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
            ),
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
