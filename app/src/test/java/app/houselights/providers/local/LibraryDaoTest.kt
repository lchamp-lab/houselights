package app.houselights.providers.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/** Bulk writes stay under Android 9's 999 values per statement, counted since Robolectric's SQLite takes more. */
@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var provider: LocalProvider
    private val widest = AtomicInteger()
    private var source = 0L

    @Before
    fun open() = runBlocking {
        db = testDatabase { setQueryCallback({ _, args -> widest.accumulateAndGet(args.size, ::maxOf) }, Runnable::run) }
        dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor)
        source = dao.insertSource(nfsFolder("Séries", SourceKind.SHOWS, path = "series"))
    }

    @After
    fun close() = db.close()

    private fun row(kind: ItemKind, title: String, parentId: Long? = null, seriesId: Long? = null, collectionId: Long? = null) =
        ItemEntity(
            kind = kind, sourceId = source, title = title, sortTitle = title, parentId = parentId, seriesId = seriesId,
            collectionId = collectionId, filePath = "$title.mkv".takeIf { kind == ItemKind.MOVIE || kind == ItemKind.EPISODE },
            addedAt = 0,
        )

    private suspend fun films(count: Int, collectionId: Long? = null): List<Long> = db.withTransaction {
        (0 until count).map { dao.insert(row(ItemKind.MOVIE, "Film $it", collectionId = collectionId)) }
    }

    private fun assertFitsAndroid9() = assertTrue("${widest.get()} values in one statement", widest.get() <= ANDROID_9_LIMIT)

    /** A renamed file keeps its probe; one of another size is probed again, its length with it. */
    @Test
    fun aFileReplacedByOneOfAnotherSizeIsReadAgain() = runTest {
        val info = MediaInfo(container = "MKV", durationMs = 7_094_000, probedWith = MediaProbe.VERSION)
        val read = row(ItemKind.MOVIE, "Dune").copy(fileSize = 42).withMedia(info)
        val renamed = dao.insert(read)
        val replaced = dao.insert(read.copy(filePath = "Arrival.mkv"))
        dao.relink(renamed, "Dune (2021).mkv", 42)
        dao.relink(replaced, "Arrival (2016).mkv", 43)

        val kept = checkNotNull(dao.item(renamed))
        assertEquals(Triple(read.mediaInfo, MediaProbe.VERSION, 7_094_000L), Triple(kept.mediaInfo, kept.probeVersion, kept.fileDurationMs))
        val again = checkNotNull(dao.item(replaced))
        assertEquals(Triple(null, 0, null), Triple(again.mediaInfo, again.probeVersion, again.fileDurationMs))
    }

    @Test
    fun removesMoreItemsThanAStatementTakes() = runTest {
        val ids = films(1_500)
        widest.set(0)
        dao.deleteItems(ids)
        assertEquals(0, dao.filesOf(source).size)
        assertFitsAndroid9()
    }

    @Test
    fun holdsMoreItemsThanAStatementTakes() = runTest {
        val ids = films(1_500)
        widest.set(0)
        dao.setMissingSince(ids, 42)
        assertEquals(setOf(42L), dao.filesOf(source).map { it.missingSince }.toSet())
        dao.setMissingSince(ids, null)
        assertEquals(setOf(null), dao.filesOf(source).map { it.missingSince }.toSet())
        assertFitsAndroid9()
    }

    @Test
    fun marksAVeryLongShowWatched() = runTest {
        // One Piece: more than a thousand episodes.
        val (series, seasons) = db.withTransaction {
            val series = dao.insert(row(ItemKind.SERIES, "One Piece"))
            val seasons = (1..12).map { n ->
                val season = dao.insert(row(ItemKind.SEASON, "Season $n", parentId = series, seriesId = series))
                repeat(100) { dao.insert(row(ItemKind.EPISODE, "S${n}E$it", parentId = season, seriesId = series)) }
                season
            }
            series to seasons
        }
        widest.set(0)
        provider.setPlayed(MediaItem(id = series.toString(), kind = MediaKind.SERIES, title = "One Piece"), played = true)
        assertEquals(1_200, dao.episodesOfSeries(series).count { it.played })
        assertFitsAndroid9()

        provider.setPlayed(MediaItem(id = seasons.first().toString(), kind = MediaKind.SEASON, title = ""), played = false)
        assertEquals(1_100, dao.episodesOfSeries(series).count { it.played })
        assertEquals(listOf(false), dao.childRows(seasons.first()).map { it.played }.distinct())
    }

    @Test
    fun marksAVeryLargeCollectionWatched() = runTest {
        val collection = dao.insertCollection(CollectionEntity(matchKey = "name:all", name = "All", sortTitle = "all"))
        films(1_200, collectionId = collection)
        val other = dao.insert(row(ItemKind.MOVIE, "Elsewhere"))
        widest.set(0)
        provider.setPlayed(MediaItem(id = LocalId.ofCollection(collection), kind = MediaKind.COLLECTION, title = "All"), played = true)
        assertEquals(1_200, dao.collectionFilms(collection).count { it.played })
        assertEquals(false, dao.item(other)?.played)
        assertFitsAndroid9()
    }

    @Test
    fun aRowInsertedAgainFailsRatherThanReplacingIt() = runTest {
        val id = films(1).single()
        dao.savePlayback(id, positionMs = 600_000, durationMs = null, played = false, lastPlayedAt = 1)
        val snapshot = checkNotNull(dao.item(id)).copy(positionMs = 0, lastPlayedAt = null)

        val failure = runCatching { dao.insert(snapshot) }.exceptionOrNull()

        assertTrue("$failure", failure is SQLiteConstraintException)
        assertEquals(600_000L, dao.item(id)?.positionMs)
    }

    private companion object {
        const val ANDROID_9_LIMIT = 999
    }
}
