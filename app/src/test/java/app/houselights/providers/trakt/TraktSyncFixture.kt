package app.houselights.providers.trakt

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.TraktSession
import app.houselights.providers.local.ItemEntity
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.NoEditor
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TraktDao
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import java.time.Instant

/** A test Trakt, an in-memory library and the sync between them, with titles to fill it. */
open class TraktSyncFixture {
    protected lateinit var db: LibraryDatabase
    protected lateinit var dao: LibraryDao
    protected lateinit var queue: TraktDao
    protected var films = 0L
    protected var shows = 0L

    protected val trakt = FakeTrakt()
    protected var session: TraktSession? = TraktSession("a1", "r1", expiresAt = NOW_S + DAY_S, username = "lea")
    protected var changes = 0

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

    protected fun TestScope.sync(following: (suspend () -> Boolean)? = null) = TraktSync(
        TraktAccount(TraktKeys("id"), session = { session }, save = { session = it }, http = trakt.server.http, now = { NOW_S }),
        queue,
        backgroundScope,
        changed = { changes++ },
        following = following,
        now = { NOW },
        clock = { testScheduler.currentTime },
    )

    /** Connects [sync] with the folders and waits for its first sync. */
    protected suspend fun TestScope.connected(sync: TraktSync = sync()): TraktSync = sync.apply {
        follow(connected = true, folders = true)
        waitFor("the first sync") { state.value.syncedAt != null && !state.value.running }
    }

    protected fun provider(sync: TraktSync) = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor, sync)

    protected suspend fun film(
        tmdb: Int?,
        played: Boolean = false,
        positionMs: Long = 0,
        lastPlayedAt: Long? = null,
        runtimeMin: Int? = 100,
        /** The main row of the film this file is another copy of. */
        versionOf: Long? = null,
    ) = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = films, title = "F$tmdb", sortTitle = "f$tmdb", tmdbId = tmdb, runtimeMin = runtimeMin,
            filePath = if (versionOf == null) "F$tmdb.mkv" else "F$tmdb 4K.mkv", addedAt = 0, played = played,
            positionMs = positionMs, lastPlayedAt = lastPlayedAt, versionOf = versionOf,
        ),
    )

    /** A show and its first season, as (show, season) rows. */
    protected suspend fun series(tmdb: Int?): Pair<Long, Long> {
        val show = dao.insert(
            ItemEntity(kind = ItemKind.SERIES, sourceId = shows, title = "S$tmdb", sortTitle = "s$tmdb", tmdbId = tmdb, addedAt = 0),
        )
        val season = dao.insert(
            ItemEntity(
                kind = ItemKind.SEASON,
                sourceId = shows,
                parentId = show,
                seriesId = show,
                title = "Saison 1",
                sortTitle = "1",
                seasonNumber = 1,
                addedAt = 0,
            ),
        )
        return show to season
    }

    protected suspend fun episode(
        show: Pair<Long, Long>,
        number: Int,
        last: Int? = null,
        played: Boolean = false,
        positionMs: Long = 0,
        lastPlayedAt: Long? = null,
    ) = dao.insert(
        ItemEntity(
            kind = ItemKind.EPISODE, sourceId = shows, parentId = show.second, seriesId = show.first, title = "E$number",
            sortTitle = "e$number", seasonNumber = 1, episodeNumber = number, lastEpisodeNumber = last, filePath = "S01E$number.mkv",
            runtimeMin = 40, addedAt = 0, played = played, positionMs = positionMs, lastPlayedAt = lastPlayedAt,
        ),
    )

    protected suspend fun LocalProvider.mark(id: Long, played: Boolean) = setPlayed(item(id.toString()), played)

    protected val JsonObject.tmdbs
        get() = this["movies"]!!.jsonArray.map { it.jsonObject["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content.toInt() }

    protected fun JsonObject.movieDate() = this["movies"]!!.jsonArray.single().jsonObject["watched_at"]?.jsonPrimitive?.content

    /** The episode numbers of the first season sent. */
    protected val JsonObject.episodes
        get() = this["shows"]!!.jsonArray.single().jsonObject["seasons"]!!.jsonArray.single().jsonObject["episodes"]!!.jsonArray
            .map { it.jsonObject["number"]!!.jsonPrimitive.content.toInt() }

    protected val JsonObject.movieTmdb get() = this["movie"]!!.jsonObject["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content.toInt()

    protected companion object {
        val NOW: Long = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
        val NOW_S = NOW / 1_000
        const val DAY_S = 24 * 3_600L
        const val MINUTE = 60_000L

        const val ADDED = """{"added":{"movies":1,"episodes":0},"not_found":{}}"""

        /** Before and after one another. */
        const val T1 = "2026-10-10T20:00:00.000Z"
        const val T2 = "2026-10-15T21:30:00.000Z"

        fun ms(date: String) = Instant.parse(date).toEpochMilli()

        fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    }
}
