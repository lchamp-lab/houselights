package app.houselights.providers.local

import app.houselights.core.Definition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What each folder holds, as the settings' cards say it: every folder counted in one query. */
@RunWith(RobolectricTestRunner::class)
class FolderCountsTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun open() = runBlocking {
        db = testDatabase()
        dao = db.dao()
    }

    @After
    fun close() = db.close()

    private suspend fun folder(name: String, kind: SourceKind) = dao.insertSource(nfsFolder(name, kind))

    private suspend fun row(
        source: Long,
        kind: ItemKind,
        title: String,
        file: Boolean = kind == ItemKind.MOVIE || kind == ItemKind.EPISODE,
        badges: List<String> = emptyList(),
        parentId: Long? = null,
    ) = dao.insert(
        ItemEntity(
            kind = kind, sourceId = source, title = title, sortTitle = title, parentId = parentId, seriesId = parentId,
            filePath = "$title.mkv".takeIf { file }, badges = badges, addedAt = 0,
        )
    )

    @Test
    fun eachFolderIsCountedAsItsOwnQueriesCountIt() = runTest {
        val films = folder("films", SourceKind.MOVIES)
        val shows = folder("series", SourceKind.SHOWS)
        val empty = folder("vide", SourceKind.MOVIES)
        val uhd = Definition.ULTRA_HD.token
        row(films, ItemKind.MOVIE, "Heat", badges = listOf(uhd, "HDR10"))
        row(films, ItemKind.MOVIE, "Ronin", badges = listOf("1080p"))
        // A badge that only starts like the token is another one.
        row(films, ItemKind.MOVIE, "Collateral", badges = listOf("${uhd}x"))
        val series = row(shows, ItemKind.SERIES, "Severance", file = false)
        val season = row(shows, ItemKind.SEASON, "Saison 1", file = false, parentId = series)
        row(shows, ItemKind.EPISODE, "S01E01", badges = listOf(uhd), parentId = season)
        row(shows, ItemKind.EPISODE, "S01E02", badges = listOf(uhd), parentId = season)

        val counts = dao.observeFolderCounts(uhd).first().associateBy { it.sourceId }
        assertEquals(FolderCounts(films, titles = 3, ultraHd = 1), counts[films])
        assertEquals(FolderCounts(shows, titles = 1, ultraHd = 2), counts[shows])
        assertNull("A folder with nothing in it has no row: its card says 0", counts[empty])
        for (source in listOf(films, shows, empty)) {
            assertEquals(dao.observeTitleCount(source).first(), counts[source]?.titles ?: 0)
            assertEquals(dao.observeDefinitionCount(source, uhd).first(), counts[source]?.ultraHd ?: 0)
        }
    }
}
