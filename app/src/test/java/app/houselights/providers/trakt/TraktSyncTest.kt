package app.houselights.providers.trakt

import app.houselights.core.TraktSession
import app.houselights.testing.Answer
import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktPendingEntity
import app.houselights.providers.local.TraktStateEntity
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.FakeTrakt.Companion.activities
import app.houselights.providers.trakt.FakeTrakt.Companion.movie
import app.houselights.providers.trakt.FakeTrakt.Companion.pausedEpisode
import app.houselights.providers.trakt.FakeTrakt.Companion.pausedMovie
import app.houselights.providers.trakt.FakeTrakt.Companion.show
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The sync against a test Trakt and an in-memory library: both sides joined, the latest pause, the queue, the limits. */
@RunWith(RobolectricTestRunner::class)
class TraktSyncTest : TraktSyncFixture() {
    @Test
    fun connectingJoinsBothSidesWatchedWhereverEitherSawIt() = runTest {
        film(1, played = true, lastPlayedAt = ms(T1))
        val seenThere = film(2)
        val unseen = film(3)
        // Without a TMDB id a title stays out of it, without a fuss.
        film(null, played = true, lastPlayedAt = ms(T1))
        val show = series(100)
        val first = episode(show, 1)
        val second = episode(show, 2)
        trakt.movies = "[${movie(2, T2)}]"
        trakt.shows = "[${show(100, T2, 1 to 1)}]"

        val sync = connected()

        dao.item(seenThere)!!.let {
            assertTrue(it.played)
            assertEquals(ms(T2), it.lastPlayedAt)
        }
        assertTrue(dao.item(first)!!.played)
        assertFalse(dao.item(second)!!.played)
        assertFalse(dao.item(unseen)!!.played)
        val sent = trakt.sent("POST /sync/history").single()
        assertEquals(listOf(1), sent.tmdbs)
        assertEquals("2026-10-10T20:00:00Z", sent.movieDate())
        assertEquals(TraktSyncState(syncedAt = NOW, watched = 3, inProgress = 0), sync.state.value)
        assertEquals(1, changes)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun aPlayWithoutADateGoesAtTheTitlesRelease() = runTest {
        film(30, played = true)
        connected()
        assertEquals("released", trakt.sent("POST /sync/history").single().movieDate())
    }

    @Test
    fun theMostRecentPauseWinsOnEitherSide() = runTest {
        // Paused on Trakt since the last play here: Trakt's progress places the resume point.
        val there = film(4, positionMs = 30 * MINUTE, lastPlayedAt = ms(T1))
        // Played here since Trakt's pause: the resume point goes to Trakt.
        val here = film(5, positionMs = 40 * MINUTE, lastPlayedAt = ms(T2))
        // No length: a percentage cannot be placed.
        val unknown = film(6, runtimeMin = null)
        trakt.playback = "[${pausedMovie(11, 4, 60f, T2)},${pausedMovie(12, 5, 10f, T1)},${pausedMovie(13, 6, 50f, T2)}]"

        val sync = connected()

        dao.item(there)!!.let {
            assertEquals(60 * MINUTE, it.positionMs)
            assertEquals(ms(T2), it.lastPlayedAt)
            assertFalse(it.played)
        }
        assertEquals(40 * MINUTE, dao.item(here)!!.positionMs)
        assertEquals(0L, dao.item(unknown)!!.positionMs)
        val stop = trakt.sent("POST /scrobble/stop").single()
        assertEquals(40f, stop["progress"]!!.jsonPrimitive.content.toFloat())
        assertEquals("5", stop["movie"]!!.jsonObject["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertEquals(2, sync.state.value.inProgress)
    }

    @Test
    fun episodesAreMatchedByTheirShowSeasonAndNumber() = runTest {
        val show = series(100)
        // A file holding E01 and E02 is watched once both are.
        val double = episode(show, 1, last = 2)
        val third = episode(show, 3)
        // A show without a TMDB id is left out.
        episode(series(null), 1, played = true)
        trakt.shows = "[${show(100, T2, 1 to 1, 1 to 3)}]"

        val sync = connected()

        assertFalse(dao.item(double)!!.played)
        assertTrue(dao.item(third)!!.played)
        assertEquals(0, trakt.count("POST /sync/history"))

        trakt.shows = "[${show(100, T2, 1 to 1, 1 to 2, 1 to 3)}]"
        trakt.activities = activities(T2)
        sync.scanned(changedLibrary = false)
        waitFor("both episodes of the file watched") { dao.item(double)!!.played }
    }

    @Test
    fun aMarkOfflineWaitsAndGoesAtTheNextOccasion() = runTest {
        val id = film(7)
        val sync = connected()
        val provider = provider(sync)

        trakt.offline = true
        provider.mark(id, played = true)
        waitFor("the failure said") { sync.state.value.error == TraktSyncError.OFFLINE }
        assertEquals(listOf("movie:7"), queue.pending().map { it.title })

        trakt.offline = false
        sync.scanned(changedLibrary = false)
        waitFor("the mark sent") { queue.pending().isEmpty() && sync.state.value.error == null && !sync.state.value.running }
        val sent = trakt.sent("POST /sync/history").single()
        assertEquals(listOf(7), sent.tmdbs)
        assertEquals(Instant.ofEpochMilli(NOW).toString(), sent.movieDate())
    }

    @Test
    fun oneIntentWaitsPerTitleTheLastWinningAndUnmarkingForgetsTheProgress() = runTest {
        val id = film(8, played = true, lastPlayedAt = ms(T1))
        trakt.movies = "[${movie(8, T1)}]"
        trakt.playback = "[${pausedMovie(21, 8, 30f, T1)}]"
        val sync = connected()
        val provider = provider(sync)

        trakt.offline = true
        provider.mark(id, played = false)
        provider.mark(id, played = true)
        provider.mark(id, played = false)
        waitFor("the marks waiting") { sync.state.value.error == TraktSyncError.OFFLINE && queue.pending().size == 1 }
        advanceUntilIdle()
        assertEquals(listOf(TraktAction.UNWATCHED), queue.pending().map { it.action })

        trakt.offline = false
        sync.scanned(changedLibrary = false)
        waitFor("the last mark sent") { queue.pending().isEmpty() && !sync.state.value.running }
        assertEquals(0, trakt.count("POST /sync/history"))
        val removed = trakt.sent("POST /sync/history/remove").single()
        assertEquals(listOf(8), removed.tmdbs)
        assertNull("Every play goes", removed.movieDate())
        assertEquals(1, trakt.count("DELETE /sync/playback/21"))
    }

    @Test
    fun markingAShowWatchedSendsOnlyTheEpisodesItChanged() = runTest {
        val show = series(100)
        episode(show, 1, played = true)
        episode(show, 2)
        episode(show, 3)
        trakt.shows = "[${show(100, T1, 1 to 1)}]"
        val sync = connected()

        provider(sync).mark(show.first, played = true)
        waitFor("the episodes sent") { trakt.count("POST /sync/history") == 1 }
        val season = trakt.sent("POST /sync/history").single()["shows"]!!.jsonArray.single().jsonObject["seasons"]!!.jsonArray.single()
        val numbers = season.jsonObject["episodes"]!!.jsonArray.map { it.jsonObject["number"]!!.jsonPrimitive.content.toInt() }
        assertEquals(listOf(2, 3), numbers)
    }

    @Test
    fun aRateLimitIsWaitedOutThenTheSyncGoesOn() = runTest {
        trakt.stage("GET /sync/last_activities", Answer(429, headers = mapOf("Retry-After" to "5")))
        val sync = connected()
        assertEquals(2, trakt.count("GET /sync/last_activities"))
        assertTrue("${testScheduler.currentTime}", testScheduler.currentTime >= 5_000)
        assertNull(sync.state.value.error)
    }

    @Test
    fun aLimitTooLongToWaitIsSaidAndTheSyncTriedLaterStillJoiningBothSides() = runTest {
        film(49, played = true, lastPlayedAt = ms(T1))
        trakt.stage("GET /sync/last_activities", Answer(429, headers = mapOf("Retry-After" to "600")))
        val sync = sync()
        sync.follow(connected = true, folders = true)
        waitFor("the limit said") { sync.state.value.error == TraktSyncError.BUSY && !sync.state.value.running }
        assertNull(sync.state.value.syncedAt)
        assertEquals(0, trakt.count("GET /sync/watched/movies"))

        // The end of a scan asks only to check, but no sync has completed: both sides are joined.
        sync.scanned(changedLibrary = false)
        waitFor("the sync done") { sync.state.value.syncedAt != null && !sync.state.value.running }
        assertNull(sync.state.value.error)
        assertEquals(listOf(49), trakt.sent("POST /sync/history").single().tmdbs)
    }

    @Test
    fun aLimitSaidOnlyByItsEndIsWaitedOutToIt() = runTest {
        val limit = """{"name":"AUTHED_API_GET_LIMIT","period":300,"limit":1000,"remaining":0,"until":"2026-10-21T12:00:07Z"}"""
        trakt.stage("GET /sync/last_activities", Answer(429, headers = mapOf("X-Ratelimit" to limit)))
        val sync = connected()
        assertEquals(2, trakt.count("GET /sync/last_activities"))
        assertTrue("${testScheduler.currentTime}", testScheduler.currentTime >= 7_000)
        assertNull(sync.state.value.error)
    }

    @Test
    fun aTokenRefusedDuringASyncIsRenewedAndTheSyncGoesOn() = runTest {
        trakt.stage("GET /sync/watched/movies", Answer(401))
        val sync = connected()
        assertEquals(1, trakt.count("POST /oauth/token"))
        assertEquals("a2", session?.accessToken)
        assertEquals(2, trakt.count("GET /sync/watched/movies"))
        assertNull(sync.state.value.error)
    }

    @Test
    fun theEndOfAScanReadsTraktOnlyWhenEitherSideChanged() = runTest {
        val sync = connected()
        assertEquals(1, trakt.count("GET /sync/watched/movies"))

        sync.scanned(changedLibrary = false)
        waitFor("Trakt's activities read") { trakt.count("GET /sync/last_activities") == 2 && !sync.state.value.running }
        assertEquals(1, trakt.count("GET /sync/watched/movies"))

        sync.scanned(changedLibrary = true)
        waitFor("Trakt read for new titles") { trakt.count("GET /sync/watched/movies") == 2 && !sync.state.value.running }

        trakt.activities = activities(T2)
        sync.scanned(changedLibrary = false)
        waitFor("Trakt read once it changed") { trakt.count("GET /sync/watched/movies") == 3 && !sync.state.value.running }
    }

    @Test
    fun atLaunchTraktIsReadOnlyAfterSixHours() = runTest {
        queue.saveState(TraktStateEntity(syncedAt = NOW - 60 * MINUTE, activities = "x"))
        val sync = sync()
        sync.follow(connected = true, folders = true)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), trakt.routes)

        queue.saveState(TraktStateEntity(syncedAt = NOW - 7 * 60 * MINUTE, activities = "x"))
        sync.follow(connected = true, folders = true)
        waitFor("Trakt read") { trakt.count("GET /sync/watched/movies") == 1 && !sync.state.value.running }
    }

    @Test
    fun disconnectingForgetsWhatWaitsAndKeepsTheLibrary() = runTest {
        val id = film(9)
        val sync = connected()
        val provider = provider(sync)
        trakt.offline = true
        provider.mark(id, played = true)
        waitFor("the mark waiting") { sync.state.value.error == TraktSyncError.OFFLINE }

        session = null
        sync.follow(connected = false, folders = true)
        waitFor("the queue forgotten") { queue.pending().isEmpty() && queue.state() == null }
        assertTrue(dao.item(id)!!.played)
        assertEquals(TraktSyncState(), sync.state.value)

        provider.mark(id, played = false)
        advanceUntilIdle()
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun withAnotherSourceNothingIsSynced() = runTest {
        film(1, played = true)
        val sync = sync()
        sync.follow(connected = true, folders = false)
        sync.syncNow()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), trakt.routes)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun aTitleWatchedOnTraktSinceItsPauseHereComesBackWatchedWithNoPauseOnEitherSide() = runTest {
        // Stopped at 40 % here, then finished on another device.
        val finished = film(31, positionMs = 40 * MINUTE, lastPlayedAt = ms(T1))
        // Trakt's pause of it is older than its play there.
        val stale = film(32)
        trakt.movies = "[${movie(31, T2)},${movie(32, T2)}]"
        trakt.playback = "[${pausedMovie(31, 32, 40f, T1)}]"

        val sync = connected()

        dao.item(finished)!!.let { assertEquals(Triple(true, 0L, ms(T2)), Triple(it.played, it.positionMs, it.lastPlayedAt)) }
        dao.item(stale)!!.let { assertEquals(Triple(true, 0L, ms(T2)), Triple(it.played, it.positionMs, it.lastPlayedAt)) }
        assertEquals(0, trakt.count("POST /scrobble/stop"))
        assertEquals(0, sync.state.value.inProgress)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun aMarkMadeWhileTraktIsReadStandsAndReachesTrakt() = runTest {
        val id = film(33, played = true, lastPlayedAt = ms(T1))
        trakt.movies = "[${movie(33, T1)}]"
        val sync = connected()
        val provider = provider(sync)
        trakt.during("GET /sync/watched/movies") { runBlocking { provider.mark(id, played = false) } }

        sync.syncNow()
        waitFor("the mark sent") { trakt.count("POST /sync/history/remove") == 1 && !sync.state.value.running }

        assertFalse(dao.item(id)!!.played)
        assertEquals(listOf(33), trakt.sent("POST /sync/history/remove").single().tmdbs)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun aFilmInSeveralCopiesResumesOnTheCopyThatPlays() = runTest {
        // Trakt's pause goes where Lire plays from.
        val main = film(34)
        val chosen = film(34, versionOf = main)
        dao.setVersionChoice(main, chosen)
        trakt.playback = "[${pausedMovie(41, 34, 60f, T2)}]"
        // Trakt hears where the copy that plays stopped, though another was played since.
        val other = film(35, lastPlayedAt = ms(T2))
        val played = film(35, positionMs = 50 * MINUTE, lastPlayedAt = ms(T1), versionOf = other)
        dao.setVersionChoice(other, played)

        connected()

        assertEquals(0L, dao.item(main)!!.positionMs)
        assertEquals(60 * MINUTE, dao.item(chosen)!!.positionMs)
        val stop = trakt.sent("POST /scrobble/stop").single()
        assertEquals(35, stop.movieTmdb)
        assertEquals(50f, stop["progress"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun unmarkingAFilmReachesTraktWhicheverCopyWasWatched() = runTest {
        val main = film(37)
        film(37, played = true, lastPlayedAt = ms(T1), versionOf = main)
        val sync = connected()
        provider(sync).mark(main, played = false)
        waitFor("the film unmarked on Trakt") { trakt.count("POST /sync/history/remove") == 1 }
        assertEquals(listOf(37), trakt.sent("POST /sync/history/remove").single().tmdbs)
    }

    @Test
    fun markingAShowWatchedDropsThePauseOfAnEpisodeWatchedAgain() = runTest {
        val show = series(100)
        episode(show, 1, played = true, positionMs = 10 * MINUTE, lastPlayedAt = ms(T2))
        episode(show, 2)
        trakt.shows = "[${show(100, T1, 1 to 1)}]"
        trakt.playback = "[${pausedEpisode(61, 100, 1 to 1, 25f, T2)}]"
        val sync = connected()

        provider(sync).mark(show.first, played = true)
        waitFor("the pause dropped") { trakt.count("DELETE /sync/playback/61") == 1 && queue.pending().isEmpty() }
        assertEquals(listOf(2), trakt.sent("POST /sync/history").single().episodes)
    }

    @Test
    fun unmarkingATitleOnlyInProgressDropsItsPauseAlone() = runTest {
        val id = film(38, positionMs = 30 * MINUTE, lastPlayedAt = ms(T1))
        trakt.playback = "[${pausedMovie(42, 38, 30f, T1)}]"
        val sync = connected()

        provider(sync).mark(id, played = false)
        waitFor("the pause dropped") { trakt.count("DELETE /sync/playback/42") == 1 && queue.pending().isEmpty() }
        assertEquals(0, trakt.count("POST /sync/history/remove"))
        assertEquals(0L, dao.item(id)!!.positionMs)
    }

    @Test
    fun aMarkWhoseAnswerWasLostGoesAgainOnlyIfTraktLacksIt() = runTest {
        val reached = film(39)
        val lost = film(40)
        val sync = connected()
        val provider = provider(sync)

        // Trakt takes the first mark, but its answer never comes back.
        trakt.during("POST /sync/history") {
            trakt.movies = "[${movie(39, iso(NOW))}]"
            trakt.activities = activities(iso(NOW))
        }
        trakt.lose("POST /sync/history")
        provider.mark(reached, played = true)
        waitFor("the answer lost") { sync.state.value.error != null }
        sync.scanned(changedLibrary = false)
        waitFor("the mark found on Trakt") { queue.pending().isEmpty() && !sync.state.value.running }
        assertEquals(1, trakt.count("POST /sync/history"))

        // The second is lost on its way, Trakt's history having changed since.
        trakt.lose("POST /sync/history")
        provider.mark(lost, played = true)
        waitFor("the answer lost") { trakt.count("POST /sync/history") == 2 && sync.state.value.error != null }
        sync.scanned(changedLibrary = false)
        waitFor("the mark sent again") { queue.pending().isEmpty() && !sync.state.value.running }
        assertEquals(listOf(listOf(39), listOf(40), listOf(40)), trakt.sent("POST /sync/history").map { it.tmdbs })
    }

    @Test
    fun aLateStopWatchedGoesToTheHistoryAtItsDateUnlessTraktSawItSince() = runTest {
        val sync = connected()
        val hourAgo = NOW - 60 * MINUTE
        trakt.movies = "[${movie(52, iso(NOW - 30 * MINUTE))}]"
        trakt.activities = activities(iso(NOW - 30 * MINUTE))
        queue.queue(
            listOf(
                TraktPendingEntity("movie:51", TraktAction.PROGRESS, hourAgo, progress = 90f),
                TraktPendingEntity("movie:52", TraktAction.PROGRESS, hourAgo, progress = 90f),
            )
        )

        sync.scanned(changedLibrary = false)
        waitFor("the stops sent") { queue.pending().isEmpty() && !sync.state.value.running }

        val sent = trakt.sent("POST /sync/history").single()
        assertEquals(listOf(51), sent.tmdbs)
        assertEquals(iso(hourAgo), sent.movieDate())
        assertEquals(0, trakt.count("POST /scrobble/stop"))
    }

    @Test
    fun aLatePauseGivesWayToOneMadeSinceOnTrakt() = runTest {
        val sync = connected()
        val earlier = NOW - 3 * 60 * MINUTE
        trakt.playback = "[${pausedMovie(71, 53, 70f, iso(NOW - 60 * MINUTE))}]"
        queue.queue(
            listOf(
                TraktPendingEntity("movie:53", TraktAction.PROGRESS, earlier, progress = 30f),
                TraktPendingEntity("movie:54", TraktAction.PROGRESS, earlier, progress = 30f),
            )
        )

        sync.scanned(changedLibrary = false)
        waitFor("the stops sent") { queue.pending().isEmpty() && !sync.state.value.running }

        assertEquals(listOf(54), trakt.sent("POST /scrobble/stop").map { it.movieTmdb })
    }

    @Test
    fun aWriteTraktKeepsFailingHoldsNoSyncAndIsDroppedAtItsFifth() = runTest {
        queue.queue(listOf(TraktPendingEntity("movie:42", TraktAction.PROGRESS, NOW, progress = 30f)))
        val there = film(43)
        trakt.movies = "[${movie(43, T2)}]"
        trakt.stage("POST /scrobble/stop", *Array(5) { Answer(500) })

        val sync = connected()
        // Trakt is read all the same.
        assertTrue(dao.item(there)!!.played)
        assertEquals(TraktSyncError.FAILED, sync.state.value.error)

        repeat(4) { tried ->
            sync.scanned(changedLibrary = false)
            waitFor("try ${tried + 2}") { trakt.count("POST /scrobble/stop") == tried + 2 && !sync.state.value.running }
        }
        assertTrue(queue.pending().isEmpty())
        sync.scanned(changedLibrary = false)
        waitFor("a sync without it") { trakt.count("GET /sync/last_activities") == 6 && !sync.state.value.running }
        assertEquals(5, trakt.count("POST /scrobble/stop"))
        assertNull(sync.state.value.error)
    }

    @Test
    fun aMarkTraktFailsWaitsAndOneItRefusesLeaves() = runTest {
        val id = film(44)
        val sync = connected()
        trakt.stage("POST /sync/history", Answer(503), Answer(400))

        provider(sync).mark(id, played = true)
        waitFor("the failure said") { sync.state.value.error == TraktSyncError.FAILED }
        assertEquals(listOf("movie:44"), queue.pending().map { it.title })

        sync.scanned(changedLibrary = false)
        waitFor("the refusal") { trakt.count("POST /sync/history") == 2 && !sync.state.value.running }
        assertTrue(queue.pending().isEmpty())
        assertNull(sync.state.value.error)
    }

    @Test
    fun aBatchTraktRefusesGoesTitleByTitle() = runTest {
        for (tmdb in 46..48) film(tmdb, played = true, lastPlayedAt = ms(T1))
        trakt.stage("POST /sync/history", Answer(400), Answer(201, ADDED), Answer(400), Answer(201, ADDED))

        connected()

        assertEquals(listOf(listOf(46, 47, 48), listOf(46), listOf(47), listOf(48)), trakt.sent("POST /sync/history").map { it.tmdbs })
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun aFileOfSeveralEpisodesStoppedIsWatchedWholeOrNotAtAll() = runTest {
        val double = episode(series(100), 1, last = 2)
        val sync = connected()

        sync.stopped(double, 50f)
        advanceUntilIdle()
        assertTrue(queue.pending().isEmpty())

        sync.stopped(double, 90f)
        waitFor("both episodes sent") { trakt.count("POST /sync/history") == 1 && queue.pending().isEmpty() }
        assertEquals(listOf(1, 2), trakt.sent("POST /sync/history").single().episodes)
    }

    @Test
    fun whatWaitsWhenTraktEndsTheConnectionGoesOnceItIsConnectedAgain() = runTest {
        val id = film(50, played = true, lastPlayedAt = ms(T1))
        trakt.movies = "[${movie(50, T1)}]"
        val sync = connected()
        trakt.offline = true
        provider(sync).mark(id, played = false)
        waitFor("the mark waiting") { sync.state.value.error == TraktSyncError.OFFLINE }

        session = session?.copy(accessToken = "", refreshToken = "", ended = true)
        sync.follow(connected = false, folders = true, signedOut = false)
        waitFor("the sync's state forgotten") { queue.state() == null }
        assertEquals(listOf(TraktAction.UNWATCHED), queue.pending().map { it.action })

        // Connected again: the removal reaches Trakt first, then both sides are joined.
        trakt.offline = false
        trakt.movies = "[]"
        session = TraktSession("a3", "r3", expiresAt = NOW_S + DAY_S, username = "lea")
        sync.follow(connected = true, folders = true)
        waitFor("joined again") { sync.state.value.syncedAt != null && !sync.state.value.running }
        assertEquals(listOf(50), trakt.sent("POST /sync/history/remove").single().tmdbs)
        assertFalse(dao.item(id)!!.played)
    }

    @Test
    fun aStopToldBeforeTheSettingsAreFollowedWaitsForThem() = runTest {
        val id = film(55)
        val sync = sync(following = { true })

        sync.stopped(id, 30f)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), trakt.routes)
        assertEquals(listOf(TraktAction.PROGRESS), queue.pending().map { it.action })

        sync.follow(connected = true, folders = true)
        waitFor("the stop sent") { trakt.count("POST /scrobble/stop") == 1 && !sync.state.value.running }
    }

    @Test
    fun underAnotherSourceTraktPausesConnectedAndBackOnTheFoldersSendsWhatWaited() = runTest {
        val id = film(56)
        val sync = connected()
        trakt.offline = true
        provider(sync).mark(id, played = true)
        waitFor("the mark waiting") { queue.pending().isNotEmpty() && sync.state.value.error != null }
        trakt.offline = false
        val before = trakt.routes.size

        sync.follow(connected = true, folders = false)
        sync.syncNow()
        advanceUntilIdle()
        assertEquals(before, trakt.routes.size)
        assertEquals(listOf(TraktAction.WATCHED), queue.pending().map { it.action })
        assertNotNull(session)

        // Synced a moment ago: the queue goes alone, Trakt's history is not read again.
        sync.follow(connected = true, folders = true)
        waitFor("what waited sent") { queue.pending().isEmpty() && !sync.state.value.running }
        assertEquals(listOf(56), trakt.sent("POST /sync/history").single().tmdbs)
        assertEquals(listOf("POST /sync/history"), trakt.routes.drop(before))
        assertEquals(0, trakt.count("POST /oauth/revoke"))
    }
}
