package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ArtworkKind
import app.houselights.core.ImageRef
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SortOption
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A saga's choice made while only its films' NFO sets named it (`name:`), kept by its key, follows it once TMDB names it
 * (`tmdb:`), a key added or its films' NFOs naming TMDB's saga ([SagaChoiceCarry]): TMDB's saga's own choice wins, two
 * sagas of names joining one take the one of the most films', and the name's row goes with its last film.
 */
@RunWith(RobolectricTestRunner::class)
class SagaChoiceKeyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity

    @Before
    fun open() = runBlocking {
        bench = ScanBench(temp.newFolder("sagas"), tmdbKey = false)
        films = bench.folder("Films")
        val saga = TmdbCollectionRef(DUNE_SAGA, "Dune - Saga")
        bench.tmdb.movie(DUNE, "Dune", 2021, collection = saga)
        bench.tmdb.movie(PART_TWO, "Dune Part Two", 2024, collection = saga)
        bench.tmdb.movie(JODOROWSKY, "Jodorowsky's Dune", 2013)
        bench.tmdb.collections[DUNE_SAGA] =
            TmdbCollection(DUNE_SAGA, "Dune - Saga", posterPath = "/saga.jpg", backdropPath = "/saga-wide.jpg")
    }

    @After
    fun close() = bench.close()

    private val editor by lazy { LocalEditor(bench.dao, bench.scanner) { bench.tmdb.client().takeIf { bench.withTmdb } } }
    private val pages by lazy { LocalProvider(ApplicationProvider.getApplicationContext(), bench.dao, editor) { 2 } }

    /** Films [sets] names, each with an NFO naming its set, read by a scan without TMDB: sagas known by their names. */
    private suspend fun scanNamed(sets: Map<String, String>) {
        for ((video, set) in sets) {
            bench.nas.put(video)
            bench.nas.putText(nfoOf(video), "<movie><set><name>$set</name></set></movie>", lastModified = T1)
        }
        bench.scan()
    }

    private fun nfoOf(video: String) = video.replace(".mkv", ".nfo")

    /** The key of the saga [video]'s film is in. */
    private suspend fun keyOf(video: String): String? =
        bench.item(films, video).collectionId?.let { bench.dao.collection(it)?.matchKey }

    /** The saga as the library holds it now, by [video]'s film's row. */
    private suspend fun saga(video: String = FIRST): MediaItem {
        val key = checkNotNull(bench.item(films, video).collectionId)
        return MediaItem(LocalId.ofCollection(key), MediaKind.COLLECTION, "Dune")
    }

    private fun poster(path: String): ImageRef = LocalProvider.image(path, ImageType.POSTER)

    private fun backdrop(path: String): ImageRef = LocalProvider.image(path, ImageType.BACKDROP)

    /** [library]'s first page by title, its sagas among its films when [grouped]. */
    private suspend fun page(library: Library, grouped: Boolean = false) =
        pages.libraryPage(library, SortOption.TITLE, LibraryFilter(), start = 0, limit = 50, grouped = grouped).items

    private fun MediaItem.pictures() = poster to backdrop

    /**
     * A poster chosen from « Ses films » for a saga its NFOs name, TMDB not asked, shows on the saga once a TMDB key
     * names it: on its page, a film's saga row, its walls; the choice under TMDB's key alone, its cached picture kept.
     */
    @Test
    fun aChoiceMadeOnASagaKnownByItsNameFollowsItOnceTmdbNamesIt() = runTest {
        listOf(FIRST_POSTER, SECOND_POSTER).forEach { bench.nas.putImage(it) }
        scanNamed(mapOf(FIRST to "Dune", SECOND to "Dune"))
        assertEquals(NAME_KEY, keyOf(FIRST))
        val cached = checkNotNull(bench.item(films, SECOND).posterPath)
        assertTrue("cached from the NAS: $cached", cached.isCachedArtwork())
        val named = saga()
        editor.setArtwork(named, ArtworkKind.POSTER, editor.artwork(named, ArtworkKind.POSTER).options.single { it.value == cached })
        assertEquals(cached, bench.dao.sagaChoice(NAME_KEY)?.userPosterPath)

        bench.withTmdb = true
        bench.scan()
        assertEquals(KEY, keyOf(FIRST))
        assertEquals(KEY, keyOf(SECOND))
        val chosen = poster(cached) to backdrop("/saga-wide.jpg")
        assertEquals("its page", chosen, pages.item(saga().id).pictures())
        assertEquals(chosen, checkNotNull(pages.collectionOf(pages.item(bench.item(films, FIRST).id.toString()))).pictures())
        val wall = page(Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null), grouped = true)
        assertEquals("the films wall", chosen, wall.single { it.kind == MediaKind.COLLECTION }.pictures())
        assertEquals("the sagas wall", chosen, page(Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)).single().pictures())
        assertEquals(cached, editor.artwork(saga(), ArtworkKind.POSTER).selected?.value)

        assertNull("the name's row gone", bench.dao.sagaChoice(NAME_KEY))
        assertEquals(SagaChoiceEntity(KEY, userPosterPath = cached), bench.dao.sagaChoice(KEY))
        assertTrue("still on the device", File(cached.removePrefix("file://")).isFile)
        bench.scan(refreshMetadata = true)
        assertEquals("a scan later", chosen, pages.item(saga().id).pictures())
    }

    /** TMDB's saga chosen before (its films left and came back since) keeps its own choice, not filled by the name's. */
    @Test
    fun aChoiceTmdbsSagaHasOfItsOwnIsKeptOverTheNames() = runTest {
        scanNamed(mapOf(FIRST to "Dune", SECOND to "Dune"))
        bench.dao.saveSagaChoice(SagaChoiceEntity(NAME_KEY, userPosterPath = "/name.jpg", userBackdropPath = "/name-wide.jpg"))
        val own = SagaChoiceEntity(KEY, userPosterPath = "/own.jpg")
        bench.dao.saveSagaChoice(own)

        bench.withTmdb = true
        bench.scan()
        assertEquals(KEY, keyOf(FIRST))
        assertEquals(own, bench.dao.sagaChoice(KEY))
        assertEquals(poster("/own.jpg") to backdrop("/saga-wide.jpg"), pages.item(saga().id).pictures())
        assertNull("the name's row gone", bench.dao.sagaChoice(NAME_KEY))
    }

    /** Films of two sets, named TMDB's saga by their NFOs since, are one saga: the choice of the set of most films. */
    @Test
    fun twoSagasOfNamesJoiningOneTakeTheChoiceOfTheOneOfMostFilms() = runTest {
        val sets = mapOf(LYNCH to "Arrakis", FIRST to "Dune", SECOND to "Dune")
        scanNamed(sets)
        assertEquals(ARRAKIS_KEY, keyOf(LYNCH))
        assertEquals(NAME_KEY, keyOf(FIRST))
        bench.dao.saveSagaChoice(ARRAKIS)
        bench.dao.saveSagaChoice(DUNE_SET)

        for ((video, set) in sets) {
            bench.nas.putText(nfoOf(video), "<movie><set tmdbcolid=\"$DUNE_SAGA\"><name>$set</name></set></movie>", lastModified = T2)
        }
        bench.scan()
        assertEquals(listOf(KEY, KEY, KEY), sets.keys.map { keyOf(it) })
        assertEquals(DUNE_SET.copy(matchKey = KEY), bench.dao.sagaChoice(KEY))
        assertEquals(poster("/dune.jpg") to backdrop("/dune-wide.jpg"), pages.item(saga(LYNCH).id).pictures())
        assertNull(bench.dao.sagaChoice(ARRAKIS_KEY))
        assertNull(bench.dao.sagaChoice(NAME_KEY))
    }

    /** Whichever set's films join TMDB's saga first, the set of most films gives it its choice, as no scan's order decides. */
    @Test
    fun theSetOfMostFilmsWinsWhicheverJoinsFirst() = runTest {
        scanNamed(mapOf(LYNCH to "Arrakis", FIRST to "Dune", SECOND to "Dune"))
        val joined = bench.dao.insertCollection(CollectionEntity(matchKey = KEY, tmdbId = DUNE_SAGA, name = "Dune", sortTitle = "dune"))
        val was = listOf(LYNCH, FIRST, SECOND).associateWith { checkNotNull(bench.item(films, it).collectionId) }
        for (order in listOf(listOf(LYNCH, FIRST, SECOND), listOf(FIRST, SECOND, LYNCH), listOf(FIRST, LYNCH, SECOND))) {
            bench.dao.saveSagaChoice(ARRAKIS)
            bench.dao.saveSagaChoice(DUNE_SET)
            bench.dao.deleteSagaChoice(KEY)
            val carry = SagaChoiceCarry(bench.dao)
            for (video in order) {
                bench.dao.saveScanned(bench.item(films, video).copy(collectionId = joined))
                carry.moved(left = was[video], joined = joined)
            }
            assertEquals("$order", DUNE_SET.copy(matchKey = KEY), bench.dao.sagaChoice(KEY))
            assertNull("$order", bench.dao.sagaChoice(ARRAKIS_KEY))
            assertNull("$order", bench.dao.sagaChoice(NAME_KEY))
            order.forEach { bench.dao.saveScanned(bench.item(films, it).copy(collectionId = was[it])) }
        }
    }

    /**
     * A set some of whose films TMDB places in no saga keeps its choice while they remain in it, and lends it to TMDB's
     * saga the others join.
     */
    @Test
    fun aSetPartOfWhoseFilmsStayKeepsItsChoiceAndLendsIt() = runTest {
        scanNamed(mapOf(FIRST to "Dune", SECOND to "Dune", DOCUMENTARY to "Dune"))
        val choice = SagaChoiceEntity(NAME_KEY, userPosterPath = "/name.jpg")
        bench.dao.saveSagaChoice(choice)

        bench.withTmdb = true
        bench.scan()
        assertEquals(KEY, keyOf(FIRST))
        assertEquals(NAME_KEY, keyOf(DOCUMENTARY))
        assertEquals(choice, bench.dao.sagaChoice(NAME_KEY))
        assertEquals(choice.copy(matchKey = KEY), bench.dao.sagaChoice(KEY))
        assertNotNull(bench.dao.collectionByKey(NAME_KEY))
    }

    private companion object {
        const val DUNE = 438631
        const val PART_TWO = 693134
        const val JODOROWSKY = 209403
        const val DUNE_SAGA = 726871
        const val KEY = "tmdb:$DUNE_SAGA"
        const val NAME_KEY = "name:dune"
        const val ARRAKIS_KEY = "name:arrakis"
        const val FIRST = "Dune (2021).mkv"
        const val SECOND = "Dune Part Two (2024).mkv"
        const val LYNCH = "Dune (1984).mkv"
        const val DOCUMENTARY = "Jodorowsky's Dune (2013).mkv"
        const val FIRST_POSTER = "Dune (2021)-poster.jpg"
        const val SECOND_POSTER = "Dune Part Two (2024)-poster.jpg"
        const val T1 = 1_700_000_000_000L
        const val T2 = 1_700_000_100_000L
        val ARRAKIS = SagaChoiceEntity(ARRAKIS_KEY, userPosterPath = "/arrakis.jpg", userBackdropPath = "/arrakis-wide.jpg")
        val DUNE_SET = SagaChoiceEntity(NAME_KEY, userPosterPath = "/dune.jpg", userBackdropPath = "/dune-wide.jpg")
    }
}
