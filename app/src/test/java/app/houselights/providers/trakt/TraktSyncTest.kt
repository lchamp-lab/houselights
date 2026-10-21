package app.houselights.providers.trakt

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.TraktSession
import app.houselights.providers.jellyfin.Answer
import app.houselights.providers.local.ItemEntity
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.NoEditor
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktDao
import app.houselights.providers.local.TraktStateEntity
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.providers.trakt.FakeTrakt.Companion.activities
import app.houselights.providers.trakt.FakeTrakt.Companion.movie
import app.houselights.providers.trakt.FakeTrakt.Companion.pausedMovie
import app.houselights.providers.trakt.FakeTrakt.Companion.show
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The sync against a test Trakt and an in-memory library: both sides joined, the latest pause, the queue, the limits. */
@RunWith(RobolectricTestRunner::class)
class TraktSyncTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var queue: TraktDao
    private var films = 0L
    private var shows = 0L

    private val trakt = FakeTrakt()
    private var session: TraktSession? = TraktSession("a1", "r1", expiresAt = NOW_S + WEEK_S, username = "lea")
    private var changes = 0

    @Before
    fun open() = runBlocking {
        db = testDatabase { onCallingThread() }
        dao = db.dao()
        queue = db.trakt()
        films = dao.insertSource(nfsFolder())
        shows = dao.insertSource(nfsFolder("Séries", SourceKind.SHOWS))
    }

    @After
    fun close() = db.close()

    private fun TestScope.sync() = TraktSync(
        TraktAccount(TraktKeys("id"), session = { session }, save = { session = it }, http = trakt.server.http, now = { NOW_S }),
        queue,
        backgroundScope,
        changed = { changes++ },
        now = { NOW },
        clock = { testScheduler.currentTime },
    )

    /** Connects [sync] with the folders and waits for its first sync. */
    private suspend fun TestScope.connected(sync: TraktSync = sync()): TraktSync = sync.apply {
        follow(connected = true, folders = true)
        waitFor("the first sync") { state.value.syncedAt != null && !state.value.running }
    }

    private fun provider(sync: TraktSync) = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor, sync)

    private suspend fun film(
        tmdb: Int?,
        played: Boolean = false,
        positionMs: Long = 0,
        lastPlayedAt: Long? = null,
        runtimeMin: Int? = 100,
    ) = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = films, title = "F$tmdb", sortTitle = "f$tmdb", tmdbId = tmdb, runtimeMin = runtimeMin,
            filePath = "F$tmdb.mkv", addedAt = 0, played = played, positionMs = positionMs, lastPlayedAt = lastPlayedAt,
        )
    )

    /** A show and its first season, as (show, season) rows. */
    private suspend fun series(tmdb: Int?): Pair<Long, Long> {
        val show = dao.insert(
            ItemEntity(kind = ItemKind.SERIES, sourceId = shows, title = "S$tmdb", sortTitle = "s$tmdb", tmdbId = tmdb, addedAt = 0)
        )
        val season = dao.insert(
            ItemEntity(
                kind = ItemKind.SEASON, sourceId = shows, parentId = show, seriesId = show, title = "Saison 1", sortTitle = "1",
                seasonNumber = 1, addedAt = 0,
            )
        )
        return show to season
    }

    private suspend fun episode(show: Pair<Long, Long>, number: Int, last: Int? = null, played: Boolean = false) = dao.insert(
        ItemEntity(
            kind = ItemKind.EPISODE, sourceId = shows, parentId = show.second, seriesId = show.first, title = "E$number",
            sortTitle = "e$number", seasonNumber = 1, episodeNumber = number, lastEpisodeNumber = last, filePath = "S01E$number.mkv",
            runtimeMin = 40, addedAt = 0, played = played,
        )
    )

    private suspend fun LocalProvider.mark(id: Long, played: Boolean) = setPlayed(item(id.toString()), played)

    private val JsonObject.tmdbs
        get() = this["movies"]!!.jsonArray.map { it.jsonObject["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content.toInt() }

    private fun JsonObject.movieDate() = this["movies"]!!.jsonArray.single().jsonObject["watched_at"]?.jsonPrimitive?.content

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
    fun aLimitTooLongToWaitIsSaidAndTheSyncTriedLater() = runTest {
        trakt.stage("GET /sync/last_activities", Answer(429, headers = mapOf("Retry-After" to "600")))
        val sync = sync()
        sync.follow(connected = true, folders = true)
        waitFor("the limit said") { sync.state.value.error == TraktSyncError.BUSY && !sync.state.value.running }
        assertNull(sync.state.value.syncedAt)
        assertEquals(0, trakt.count("GET /sync/watched/movies"))
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

    private companion object {
        val NOW: Long = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
        val NOW_S = NOW / 1_000
        const val WEEK_S = 7 * 24 * 3_600L
        const val MINUTE = 60_000L

        /** Before and after one another. */
        const val T1 = "2026-10-10T20:00:00.000Z"
        const val T2 = "2026-10-15T21:30:00.000Z"

        fun ms(date: String) = Instant.parse(date).toEpochMilli()
    }
}
