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
import org.junit.Assert.assertNotEquals
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
 * A saga's poster and backdrop as the user chose them (#48): every screen that shows the saga shows them, its walls,
 * home's Sagas, search, a film's saga and its page; and they outlive scans, and the saga leaving with its last film and
 * coming back, kept by its key, as a title's choice outlives its file leaving and coming back.
 */
@RunWith(RobolectricTestRunner::class)
class SagaChoiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity

    @Before
    fun open() = runBlocking {
        bench = ScanBench(temp.newFolder("sagas"))
        films = bench.folder("Films")
        val saga = TmdbCollectionRef(DUNE_SAGA, "Dune - Saga")
        bench.tmdb.movie(DUNE, "Dune", 2021, collection = saga)
        bench.tmdb.movie(PART_TWO, "Dune Part Two", 2024, collection = saga)
        bench.tmdb.movie(HEAT, "Heat", 1995)
        bench.tmdb.collections[DUNE_SAGA] =
            TmdbCollection(DUNE_SAGA, "Dune - Saga", posterPath = "/saga.jpg", backdropPath = "/saga-wide.jpg")
        bench.tmdb.imageSets[DUNE_SAGA] = TmdbImageSet(
            posters = listOf(TmdbImage("/fr-1.jpg", "fr"), TmdbImage("/en-1.jpg", "en")),
            backdrops = listOf(TmdbImage("/wide-1.jpg", null), TmdbImage("/wide-2.jpg", null)),
        )
        listOf(FIRST, SECOND, "Heat (1995).mkv").forEach { bench.nas.put(it) }
        bench.scan()
    }

    @After
    fun close() = bench.close()

    private val editor by lazy { LocalEditor(bench.dao, bench.scanner) { bench.tmdb.client() } }
    private val pages by lazy { LocalProvider(ApplicationProvider.getApplicationContext(), bench.dao, editor) { 2 } }

    /** The saga as the library holds it now, by its first film's row. */
    private suspend fun saga(): MediaItem {
        val key = checkNotNull(bench.item(films, FIRST).collectionId)
        return MediaItem(LocalId.ofCollection(key), MediaKind.COLLECTION, "Dune")
    }

    /** Chooses the saga's poster [poster] and backdrop [backdrop] as its contact sheets do: OK on the choice. */
    private suspend fun choose(poster: String, backdrop: String) {
        val saga = saga()
        for ((kind, value) in listOf(ArtworkKind.POSTER to poster, ArtworkKind.BACKDROP to backdrop)) {
            editor.setArtwork(saga, kind, editor.artwork(saga, kind).options.single { it.value == value })
        }
    }

    private fun poster(path: String): ImageRef = LocalProvider.image(path, ImageType.POSTER)

    private fun backdrop(path: String): ImageRef = LocalProvider.image(path, ImageType.BACKDROP)

    /** [library]'s first page by title, its sagas among its films when [grouped]. */
    private suspend fun page(library: Library, grouped: Boolean = false) =
        pages.libraryPage(library, SortOption.TITLE, LibraryFilter(), start = 0, limit = 50, grouped = grouped).items

    /** The poster and the backdrop this saga shows. */
    private fun MediaItem.pictures() = poster to backdrop

    @Test
    fun theChosenPicturesShowWhereverTheSagaShows() = runTest {
        assertEquals("before a choice, TMDB's", poster("/saga.jpg") to backdrop("/saga-wide.jpg"), pages.item(saga().id).pictures())
        choose(poster = "/fr-1.jpg", backdrop = "/wide-2.jpg")
        val chosen = poster("/fr-1.jpg") to backdrop("/wide-2.jpg")

        // Its page, whose panel and room take its backdrop, and a film's saga row.
        assertEquals(chosen, pages.item(saga().id).pictures())
        assertEquals(chosen, checkNotNull(pages.collectionOf(pages.item(bench.item(films, FIRST).id.toString()))).pictures())
        // The films wall and the sagas alone, which home's Sagas door shows.
        val wall = page(Library(LocalProvider.MOVIES, LibraryKind.MOVIES, null), grouped = true)
        assertEquals(chosen, wall.single { it.kind == MediaKind.COLLECTION }.pictures())
        assertEquals(chosen, page(Library(LocalProvider.COLLECTIONS, LibraryKind.COLLECTIONS, null)).single().pictures())
        // Search's row of sagas.
        assertEquals(chosen, pages.catalog.search("dune").sagas.single().pictures())
    }

    @Test
    fun theChoiceOutlivesScansAndTheSagaLeavingWithItsLastFilmAndComingBack() = runTest {
        choose(poster = "/poster-$PART_TWO.jpg", backdrop = "/wide-1.jpg")
        val chosen = poster("/poster-$PART_TWO.jpg") to backdrop("/wide-1.jpg")
        bench.scan(refreshMetadata = true)
        assertEquals("a scan reading TMDB again", chosen, pages.item(saga().id).pictures())

        val before = saga().id
        bench.nas.remove(FIRST)
        bench.scan()
        bench.nas.remove(SECOND)
        bench.scan()
        assertNull("gone with its last film", bench.dao.collectionByKey(KEY))
        assertNotNull("its choice kept by its key", bench.dao.sagaChoice(KEY))

        listOf(FIRST, SECOND).forEach { bench.nas.put(it) }
        bench.scan()
        val back = saga()
        assertNotEquals("another row", before, back.id)
        assertEquals(chosen, pages.item(back.id).pictures())
        assertEquals("/poster-$PART_TWO.jpg", editor.artwork(back, ArtworkKind.POSTER).selected?.value)
    }

    /**
     * A saga's poster chosen from « Ses films », a film's own picture cached from the NAS (a poster beside its file), is
     * kept as the saga's once that film's poster has gone from the NAS and the library no longer holds it for the film:
     * the scan's sweep of cached pictures spares it, as it spares a title's whose files left.
     */
    @Test
    fun aFilmsCachedPictureChosenForTheSagaOutlivesTheFilmLettingItGo() = runTest {
        bench.nas.putImage(SECOND_POSTER)
        bench.scan(refreshMetadata = true)
        val cached = checkNotNull(bench.item(films, SECOND).posterPath)
        assertTrue("cached from the NAS: $cached", cached.isCachedArtwork())
        choose(poster = cached, backdrop = "/wide-1.jpg")

        bench.nas.remove(SECOND_POSTER)
        bench.scan()
        assertNotEquals("the film no longer shows it", cached, bench.item(films, SECOND).posterPath)
        assertEquals(poster(cached), pages.item(saga().id).poster)
        assertTrue("still on the device", File(cached.removePrefix("file://")).isFile)
    }

    private companion object {
        const val DUNE = 438631
        const val PART_TWO = 693134
        const val HEAT = 949
        const val DUNE_SAGA = 726871
        const val KEY = "tmdb:$DUNE_SAGA"
        const val FIRST = "Dune (2021).mkv"
        const val SECOND = "Dune Part Two (2024).mkv"
        const val SECOND_POSTER = "Dune Part Two (2024)-poster.jpg"
    }
}
