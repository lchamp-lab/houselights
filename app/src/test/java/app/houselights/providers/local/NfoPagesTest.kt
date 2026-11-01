package app.houselights.providers.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.houselights.testing.testSecrets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Saving a resume point or marking a season watched writes as many pages whatever NFO texts the titles have: the texts
 * are apart from their rows ([NfoEntity]), which SQLite rewrites whole, overflow pages included, at any change. Pages
 * are counted in the write-ahead log of a library on disk.
 */
@RunWith(RobolectricTestRunner::class)
class NfoPagesTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun aResumePointAndAWatchedSeasonWriteAsManyPagesWithNfoTextsAsWithout() = runTest {
        val bare = pagesWritten(withNfo = false)
        val described = pagesWritten(withNfo = true)
        assertTrue("$bare", bare.values.all { it > 0 })
        assertEquals(bare, described)
    }

    /** The pages each write wrote, on a library of [FILMS] films and a season of [EPISODES] episodes, NFOs [withNfo]. */
    private suspend fun pagesWritten(withNfo: Boolean): Map<String, Long> {
        val file = File(dir.root, "library-$withNfo.db")
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java, file.path)
            .addTypeConverter(SecretConverter(testSecrets()))
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.dao()
            val (films, season) = db.withTransaction { fill(dao, withNfo) }
            val sql = db.openHelper.writableDatabase
            val mode = sql.query("PRAGMA journal_mode").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("wal", mode)
            val wal = File(file.path + "-wal")
            suspend fun pages(write: suspend () -> Unit): Long {
                sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
                    c.moveToFirst()
                    check(c.getInt(0) == 0) { "checkpoint busy" }
                }
                write()
                return wal.length() / (PAGE + WAL_FRAME_HEADER)
            }
            return mapOf(
                "resume points" to pages {
                    films.take(RESUMED).forEach { dao.savePlayback(it, positionMs = 60_000, durationMs = 7_000_000, played = false, 9) }
                },
                "season watched" to pages { dao.setSeasonPlayed(season, played = true, now = 9) },
            )
        } finally {
            db.close()
        }
    }

    /** The films' ids and the season's. */
    private suspend fun fill(dao: LibraryDao, withNfo: Boolean): Pair<List<Long>, Long> {
        val source = dao.insertSource(nfsFolder())
        val shows = dao.insertSource(nfsFolder("Séries", SourceKind.SHOWS))
        val filmNfo = "<movie><plot>${"x".repeat(FILM_NFO)}</plot></movie>".takeIf { withNfo }
        val episodeNfo = "<episodedetails><plot>${"y".repeat(EPISODE_NFO)}</plot></episodedetails>".takeIf { withNfo }
        val films = (0 until FILMS).map { i ->
            val film = ItemEntity(
                kind = ItemKind.MOVIE,
                sourceId = source,
                title = "Film $i",
                sortTitle = "film $i",
                filePath = "Film $i.mkv",
                nfoPath = "Film $i.nfo",
                addedAt = i.toLong(),
            )
            dao.insertWithNfo(film, filmNfo)
        }
        val series = dao.insert(ItemEntity(kind = ItemKind.SERIES, sourceId = shows, title = "Show", sortTitle = "show", addedAt = 0))
        val season = dao.insert(
            ItemEntity(
                kind = ItemKind.SEASON,
                sourceId = shows,
                title = "",
                sortTitle = "001",
                parentId = series,
                seriesId = series,
                seasonNumber = 1,
                addedAt = 0,
            ),
        )
        repeat(EPISODES) { e ->
            val episode = ItemEntity(
                kind = ItemKind.EPISODE, sourceId = shows, title = "E$e", sortTitle = "e$e", parentId = season, seriesId = series,
                seasonNumber = 1, episodeNumber = e + 1, filePath = "Show/S01E$e.mkv", nfoPath = "Show/S01E$e.nfo", addedAt = 0,
            )
            dao.insertWithNfo(episode, episodeNfo)
        }
        return films to season
    }

    private companion object {
        const val FILMS = 100
        const val RESUMED = 20
        const val EPISODES = 100
        const val FILM_NFO = 50 * 1024
        const val EPISODE_NFO = 8 * 1024
        const val PAGE = 4096
        const val WAL_FRAME_HEADER = 24
    }
}
