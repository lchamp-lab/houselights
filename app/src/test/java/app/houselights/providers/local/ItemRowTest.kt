package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Secrets
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import javax.crypto.KeyGenerator

/**
 * The hand-written [ITEM_ROW] and [CARD_ROW] match Room's [ItemEntity] table (a missing column reads null
 * silently), and no list reads the NFO text or the media JSON, too heavy per card.
 */
@RunWith(RobolectricTestRunner::class)
class ItemRowTest {
    private lateinit var db: LibraryDatabase
    private lateinit var provider: LocalProvider
    private val queries: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun open() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .setQueryCallback({ sql, _ -> queries += sql }, Runnable::run)
            .allowMainThreadQueries()
            .build()
        val dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, LocalProviderTest.NoEditor) { 1 }
        val source = dao.insertSource(
            SourceEntity(
                name = "Médias", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas", share = "media", path = "",
                username = "", password = Secret(""),
            )
        )
        val saga = dao.insertCollection(CollectionEntity(matchKey = "name:alien", name = "Alien", sortTitle = "alien"))
        fun item(kind: ItemKind, title: String, parentId: Long? = null, seriesId: Long? = null, positionMs: Long = 0) = ItemEntity(
            kind = kind, sourceId = source, title = title, sortTitle = title.lowercase(), parentId = parentId, seriesId = seriesId,
            filePath = "$title.mkv", nfoPath = "$title.nfo", nfo = "<movie><plot>$title</plot></movie>", genres = listOf("Drame"),
            collectionId = saga.takeIf { kind == ItemKind.MOVIE }, positionMs = positionMs, seasonNumber = 1, episodeNumber = 1,
            addedAt = 0,
        )
        dao.insert(item(ItemKind.MOVIE, "Alien"))
        dao.insert(item(ItemKind.MOVIE, "Aliens", positionMs = 60_000))
        val series = dao.insert(item(ItemKind.SERIES, "Severance"))
        val season = dao.insert(item(ItemKind.SEASON, "Saison 1", parentId = series, seriesId = series))
        dao.insert(item(ItemKind.EPISODE, "Good News About Hell", parentId = season, seriesId = series, positionMs = 60_000))
        queries.clear()
    }

    @After
    fun close() = db.close()

    @Test
    fun itemRowNamesEveryColumnOfItems() {
        val columns = db.openHelper.readableDatabase.query("PRAGMA table_info(items)").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(rows.getColumnIndexOrThrow("name"))) }
        }
        val row = ITEM_ROW.split(',').map { it.trim() }
        assertEquals("Named twice in ITEM_ROW", emptyList<String>(), row.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
        assertTrue("ITEM_ROW reads the NFO text", "NULL AS nfo" in row)
        assertEquals(columns.sorted(), row.map { if (it == "NULL AS nfo") "nfo" else it }.sorted())

        val card = CARD_ROW.split(',').map { it.trim() }
        assertEquals("CARD_ROW is ITEM_ROW but what the file holds", row.map { if (it == "mediaInfo") "NULL AS mediaInfo" else it }, card)
    }

    /** Every list a screen shows, each of its sorts and filters, as they query the library. */
    private suspend fun browseEveryList(): List<String> {
        val films = Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null)
        val shows = Library(LocalProvider.SHOWS, LibraryKind.SHOWS, null)
        val sagas = Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)
        suspend fun page(library: Library, sort: SortOption = SortOption.TITLE, filter: LibraryFilter = LibraryFilter()) =
            provider.libraryPage(library, sort, filter, 0, 50)
        provider.home()
        listOf(films, shows, sagas).forEach { library -> SortOption.entries.forEach { page(library, sort = it) } }
        listOf(films, shows).forEach { library -> WatchStatus.entries.forEach { page(library, filter = LibraryFilter(status = it)) } }
        val severance = page(shows).items.single()
        val season = provider.seasons(severance).single()
        assertTrue(provider.episodes(severance, season).single().hasNfo)
        assertEquals(2, provider.collectionItems(page(sagas).items.single()).count { it.hasNfo })
        return queries.toList()
    }

    @Test
    fun listsNeverReadTheNfoText() = runTest {
        val read = browseEveryList()
        assertTrue("No list went through CARD_ROW: ${read.joinToString("\n")}", read.any { "NULL AS nfo" in it })
        assertEquals("Lists reading the NFO text", emptyList<String>(), read.filter(::readsNfo).distinct())
    }

    @Test
    fun listsNeverReadWhatFilesHold() = runTest {
        val read = browseEveryList()
        assertEquals("Lists reading what files hold", emptyList<String>(), read.filter { reads(it, "mediaInfo") }.distinct())
    }

    @Test
    fun aTitleReadsItsNfoTextOnlyToShowIt() = runTest {
        val films = Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null)
        val aliens = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 50).items.single { it.title == "Aliens" }
        queries.clear()
        val page = provider.item(aliens.id)
        provider.similar(page)
        assertEquals("Alien", provider.collectionOf(page)?.title)
        provider.setFavorite(page, true)
        provider.setPlayed(page, false)
        checkNotNull(provider.reporter(aliens.id)).progress(120_000, 7_000_000, paused = false)
        // Meanwhile, Room's invalidation tracker checks each write on its own thread.
        val read = synchronized(queries) { queries.toList() }
        assertEquals("Read the NFO text", emptyList<String>(), read.filter(::readsNfo).distinct())
        assertTrue(page.hasNfo)

        val nfo = checkNotNull(provider.nfo(page))
        assertEquals("nfs://nas/media/Aliens.nfo" to "<movie><plot>Aliens</plot></movie>", nfo.path to nfo.text)
    }

    @Test
    fun theRuleCatchesWhatItShould() {
        listOf(
            "SELECT * FROM items WHERE kind = 'MOVIE'",
            "SELECT i.* FROM items i JOIN collections c ON c.id = i.collectionId",
            "SELECT id, title, nfo FROM items WHERE parentId = ?",
        ).forEach { assertTrue(it, readsNfo(it)) }
        listOf(
            "SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' LIMIT 50 OFFSET 0",
            "SELECT COUNT(*) FROM items WHERE kind = 'MOVIE'",
            "SELECT c.* FROM collections c JOIN (SELECT collectionId, COUNT(*) AS n FROM items GROUP BY collectionId) s " +
                "ON s.collectionId = c.id",
            "SELECT * FROM sources ORDER BY kind, name",
        ).forEach { assertFalse(it, readsNfo(it)) }
    }

    private fun readsNfo(sql: String): Boolean = reads(sql, "nfo")

    /** Whether [sql] reads [column] of items: all their columns (`*`, `items.*`, an alias's), or that one by its name. */
    private fun reads(sql: String, column: String): Boolean {
        val text = sql.replace(Regex("""\bNULL\s+AS\s+$column\b""", RegexOption.IGNORE_CASE), "")
        if (!Regex("""\bitems\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        val aliases = Regex("""\bitems\s+(?:AS\s+)?(\w+)""", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.groupValues[1].lowercase() }.filter { it !in SQL_WORDS }.toSet() + "items"
        val everyColumn = Regex("""\bSELECT\s+\*\s+FROM\s+items\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""\b(\w+)\.\*""").findAll(text).any { it.groupValues[1].lowercase() in aliases }
        return everyColumn || Regex("""\b$column\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private companion object {
        val SQL_WORDS = setOf("where", "join", "left", "inner", "cross", "on", "order", "group", "limit", "union", "set", "values")
    }
}
