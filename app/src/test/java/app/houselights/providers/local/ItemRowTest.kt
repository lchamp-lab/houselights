package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaKind
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

/**
 * The hand-written [ITEM_ROW], [CARD_ROW] and [WALL_ROW] match Room's [ItemEntity] table (a missing column reads null
 * silently), no list reads the NFO texts' table or the media JSON, too heavy per card, and no wall the text only a page shows.
 */
@RunWith(RobolectricTestRunner::class)
class ItemRowTest {
    private lateinit var db: LibraryDatabase
    private lateinit var provider: LocalProvider
    private val queries: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun open() = runBlocking {
        db = testDatabase {
            setQueryCallback({ sql, _ -> queries += sql }, Runnable::run)
            allowMainThreadQueries()
        }
        val dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor) { 1 }
        val source = dao.insertSource(nfsFolder("Médias", path = ""))
        val saga = dao.insertCollection(CollectionEntity(matchKey = "name:alien", name = "Alien", sortTitle = "alien"))
        fun item(kind: ItemKind, title: String, parentId: Long? = null, seriesId: Long? = null, positionMs: Long = 0) = ItemEntity(
            kind = kind, sourceId = source, title = title, sortTitle = title.lowercase(), parentId = parentId, seriesId = seriesId,
            filePath = "$title.mkv", nfoPath = "$title.nfo", genres = listOf("Drame"),
            overview = "What $title tells", tagline = "In space no one can hear $title",
            collectionId = saga.takeIf { kind == ItemKind.MOVIE }, positionMs = positionMs, seasonNumber = 1, episodeNumber = 1,
            addedAt = 0,
        )
        suspend fun insert(item: ItemEntity) = dao.insertWithNfo(item, "<movie><plot>${item.title}</plot></movie>")
        insert(item(ItemKind.MOVIE, "Alien"))
        insert(item(ItemKind.MOVIE, "Aliens", positionMs = 60_000))
        // A film of no saga, among a grouped wall's sagas.
        insert(item(ItemKind.MOVIE, "Heat").copy(collectionId = null))
        val series = insert(item(ItemKind.SERIES, "Severance"))
        val season = insert(item(ItemKind.SEASON, "Saison 1", parentId = series, seriesId = series))
        insert(item(ItemKind.EPISODE, "Good News About Hell", parentId = season, seriesId = series, positionMs = 60_000))
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
        assertTrue("items holds the NFO text", "nfo" !in columns)
        assertEquals(columns.sorted(), row.sorted())

        val card = CARD_ROW.split(',').map { it.trim() }
        assertEquals("CARD_ROW is ITEM_ROW but what the file holds", row.map { if (it == "mediaInfo") "NULL AS mediaInfo" else it }, card)
        val wall = WALL_ROW.split(',').map { it.trim() }
        val text = setOf("overview", "tagline")
        assertEquals("WALL_ROW is CARD_ROW but the overview and tagline", card.map { if (it in text) "NULL AS $it" else it }, wall)
    }

    /** A wall's titles, the films of a wall of sagas and home's doors among them, leave out what only a page or a hero shows. */
    @Test
    fun wallsNeverReadTheTextOnlyAPageShows() = runTest {
        val films = Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null)
        val shows = Library(LocalProvider.SHOWS, LibraryKind.SHOWS, null)
        val walls = listOf(films, shows).flatMap { provider.libraryPage(it, SortOption.RATING, LibraryFilter(), 0, 50).items } +
            provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 50, grouped = true).items
        val read = queries.toList()
        val titles = walls.filter { it.kind != MediaKind.COLLECTION }.map { it.title }
        assertEquals(listOf("Alien", "Aliens", "Heat", "Severance", "Heat"), titles)
        val reading = read.filter { reads(it, "overview") || reads(it, "tagline") }.distinct()
        assertEquals("Walls reading the text", emptyList<String>(), reading)
        assertTrue("Titles with it", walls.all { it.overview == null && it.tagline == null })

        val heat = walls.first { it.title == "Heat" }
        assertEquals("the title page shows it", "What Heat tells", provider.item(heat.id).overview)
        assertTrue("home's heroes show it", provider.home().first().items.all { it.overview != null && it.tagline != null })
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
    fun listsNeverReadTheNfoTextNorWhatFilesHold() = runTest {
        val read = browseEveryList()
        assertTrue("No list went through CARD_ROW: ${read.joinToString("\n")}", read.any { "NULL AS mediaInfo" in it })
        assertEquals("Lists reading the NFO text", emptyList<String>(), read.filter(::readsNfo).distinct())
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
            "SELECT nfo FROM item_nfos WHERE itemId = ?",
            "SELECT i.sourceId, i.nfoPath, n.nfo FROM items i LEFT JOIN item_nfos n ON n.itemId = i.id WHERE i.id = ?",
            "DELETE FROM item_nfos WHERE itemId IN (SELECT id FROM items WHERE kind = 'SEASON')",
        ).forEach { assertTrue(it, readsNfo(it)) }
        listOf(
            "SELECT * FROM items WHERE kind = 'MOVIE'",
            "SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' LIMIT 50 OFFSET 0",
            "SELECT COUNT(*) FROM items WHERE kind = 'MOVIE'",
            "SELECT c.* FROM collections c JOIN (SELECT collectionId, COUNT(*) AS n FROM items GROUP BY collectionId) s " +
                "ON s.collectionId = c.id",
            "SELECT * FROM sources ORDER BY kind, name",
        ).forEach { assertFalse(it, readsNfo(it)) }
    }

    /** Whether [sql] touches the NFO texts' table ([NfoEntity]). */
    private fun readsNfo(sql: String): Boolean = Regex("""\bitem_nfos\b""", RegexOption.IGNORE_CASE).containsMatchIn(sql)

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
