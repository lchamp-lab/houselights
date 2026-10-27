package app.houselights.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.ui.design.components.PicturesAhead
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import app.houselights.ui.testing.pictureServer
import coil.ImageLoader
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** A wall's first page, asked while home's pill slides to it, opens that wall without asking again. */
@RunWith(RobolectricTestRunner::class)
class LibraryAheadTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider().apply { wall = (1..300).map { film("m$it") } }
    private val films = Library("films", LibraryKind.MOVIES, "Films")
    private val series = Library("series", LibraryKind.SHOWS, "Séries")
    private var clock = 0L
    private val ahead = LibraryAhead({ provider }, now = { clock })

    /** Posters come back at once, from nowhere. */
    private val loader = ImageLoader.Builder(context).components { add(pictureServer()) }.build()

    private val sagas = Library("sagas", LibraryKind.COLLECTIONS, "Sagas")

    private fun TestScope.ask(library: Library, sagas: Library? = null) {
        ahead.ask(this, library, sagas, PicturesAhead(context, SCREEN_PX, textScale = 1f), loader)
    }

    @Test
    fun theWallOpensOnThePageAskedAhead() = runTest {
        ask(films)
        advanceUntilIdle()
        provider.calls.clear()
        val vm = LibraryViewModel({ provider }, films, ahead = ahead)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        assertEquals("nothing asked again but the filters", listOf("facets"), provider.calls)
    }

    @Test
    fun itIsTakenOnce() = runTest {
        ask(films)
        advanceUntilIdle()
        assertNotNull(ahead.take(films, SortOption.TITLE, LibraryFilter(), grouped = false))
        assertNull(ahead.take(films, SortOption.TITLE, LibraryFilter(), grouped = false))
    }

    @Test
    fun anotherWallAsksForItsOwn() = runTest {
        ask(films)
        advanceUntilIdle()
        assertNull("another library", ahead.take(series, SortOption.TITLE, LibraryFilter(), grouped = false))
        ask(films)
        advanceUntilIdle()
        assertNull("sorted otherwise", ahead.take(films, SortOption.YEAR, LibraryFilter(), grouped = false))
        ask(films)
        advanceUntilIdle()
        assertNull("filtered", ahead.take(films, SortOption.TITLE, LibraryFilter(status = WatchStatus.UNWATCHED), grouped = false))
    }

    /** From its door, the films wall is asked as it opens: its films and their sagas. */
    @Test
    fun aFilmsWallWithSagasOpensOnItsFilmsAndSagas() = runTest {
        ask(films, sagas)
        advanceUntilIdle()
        assertEquals(listOf(true), provider.pages.map { it.grouped })
        provider.calls.clear()
        val vm = LibraryViewModel({ provider }, films, ahead = ahead, sagas = sagas)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        assertEquals("nothing asked again but the filters", listOf("facets"), provider.calls)

        // Shown otherwise, the films one by one or the sagas alone, the wall asks for its own.
        ask(films, sagas)
        advanceUntilIdle()
        assertNull(ahead.take(films, SortOption.TITLE, LibraryFilter(), grouped = false))
        ask(films, sagas)
        advanceUntilIdle()
        assertNull(ahead.take(sagas, SortOption.TITLE, LibraryFilter(), grouped = false))
    }

    @Test
    fun aPageAskedLongAgoIsNotTaken() = runTest {
        ask(films)
        advanceUntilIdle()
        clock += 10_000
        assertNull(ahead.take(films, SortOption.TITLE, LibraryFilter(), grouped = false))
    }

    @Test
    fun aFailedPageLeavesTheWallToAskItself() = runTest {
        provider.fail = { if (it.startsWith("page")) IOException("NAS away") else null }
        ask(films)
        advanceUntilIdle()
        assertNull(ahead.take(films, SortOption.TITLE, LibraryFilter(), grouped = false))
    }

    private companion object {
        const val SCREEN_PX = 1920
    }
}
