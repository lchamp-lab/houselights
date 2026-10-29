package app.houselights.ui.library

import androidx.lifecycle.SavedStateHandle
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.UserState
import app.houselights.core.WatchStatus
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.FakeProvider.Asked
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** The wall: pages, reloads in place, failures, and the sort and filter it opens on or comes back to, on a fake source. */
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val provider = FakeProvider().apply { wall = (1..300).map { film("m$it") } }
    private val library = Library("films", LibraryKind.MOVIES, "Films")

    @Test
    fun theFirstPageLoadsThenTheNextAppendsWithoutDuplicates() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        assertEquals(300, vm.state.value.total)

        // A title added meanwhile shifts the next page by one: it is not shown twice.
        provider.wall = listOf(film("new")) + provider.wall
        vm.loadMore()
        advanceUntilIdle()
        val ids = vm.state.value.items.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(239, ids.size)
    }

    /** A wall restored before its source could be read (its connection unreadable): an error, never a crash. */
    @Test
    fun withoutASourceTheWallOpensOnAnError() = runTest {
        val vm = LibraryViewModel({ null }, library, SavedStateHandle(), opensOn = LibraryFilter(picture = "Dolby Vision"))
        assertFalse(vm.capabilities.formatFilters)
        advanceUntilIdle()
        assertEquals(UiError.FAILED, vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun aChangeReloadsWhatIsShownInPlace() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        provider.calls.clear()

        vm.onLibraryChanged()
        advanceUntilIdle()
        // In one go, as much as was shown: the grid keeps its place. The films wall first reads whether it has sagas.
        assertEquals(listOf("libraries", "facets", "page 0 240"), provider.calls)
        assertEquals(240, vm.state.value.items.size)
    }

    /** A scan's progress reloads the titles shown; what the filters offer and the sagas wait for its end. */
    @Test
    fun aScansProgressReloadsOnlyTheTitles() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.calls.clear()

        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals(listOf("page 0 120"), provider.calls)
    }

    @Test
    fun aFailedReloadKeepsWhatIsShown() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page")) IOException("NAS away") else null }

        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        assertNull(vm.state.value.error)
    }

    @Test
    fun retryStartsAgainFromTheFirstPage() = runTest {
        provider.fail = { IOException("NAS away") }
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        provider.calls.clear()
        vm.retry()
        assertNull(vm.state.value.error)
        advanceUntilIdle()
        assertEquals("page 0 120", provider.calls.first())
        assertEquals(120, vm.state.value.items.size)
    }

    @Test
    fun aFailedNextPageWaitsForRetryAtTheFootOfTheWall() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page 120")) IOException("Wi-Fi gone") else null }
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.pageError)
        // The wall stays, and says nothing of an error of its own.
        assertEquals(120, vm.state.value.items.size)
        assertNull(vm.state.value.error)

        // Focus along the last lines does not try again by itself.
        provider.calls.clear()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), provider.calls)

        provider.fail = { null }
        vm.retryPage()
        assertTrue(vm.state.value.pageRetrying)
        advanceUntilIdle()
        assertNull(vm.state.value.pageError)
        assertFalse(vm.state.value.pageRetrying)
        assertEquals(240, vm.state.value.items.size)
    }

    /** Réessayer at the foot, then a change whose reload fails: the foot can be pressed again. */
    @Test
    fun aFailedReloadFreesTheRetryAtTheFoot() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page 120")) IOException("Wi-Fi gone") else null }
        vm.loadMore()
        advanceUntilIdle()
        provider.gate("page 120 120")
        vm.retryPage()
        advanceUntilIdle()
        assertTrue(vm.state.value.pageRetrying)

        provider.fail = { if (it.startsWith("page")) IOException("Wi-Fi gone") else null }
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertFalse("Réessayer stayed at work", vm.state.value.pageRetrying)
        assertFalse(vm.state.value.loading)
        assertEquals(UiError.OFFLINE, vm.state.value.pageError)

        provider.gates.clear()
        provider.fail = { null }
        vm.retryPage()
        advanceUntilIdle()
        assertEquals(240, vm.state.value.items.size)
    }

    /** A change while the first page loads, whose reload fails: the wall says why instead of waiting forever. */
    @Test
    fun aFailedReloadOfTheFirstPageSaysWhy() = runTest {
        val first = provider.gate("page 0 120")
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        vm.onLibraryChanged()
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("page")) IOException("NAS away") else null }
        first.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(UiError.OFFLINE, vm.state.value.error)
    }

    /** Android killed the app while a film played: the wall comes back sorted and filtered as it was left, by every field. */
    @Test
    fun theSortAndFilterOutliveAStoppedProcess() = runTest {
        val saved = SavedStateHandle()
        val filter = LibraryFilter(
            status = WatchStatus.UNWATCHED,
            genre = "Drame",
            decade = 1990,
            quality = Quality.UHD_4K,
            picture = "Dolby Vision",
            sound = "TrueHD Atmos",
            fileType = "mkv",
            person = "p7",
        )
        val before = LibraryViewModel({ provider }, library, saved)
        before.setSort(SortOption.ADDED)
        before.setFilter(filter)
        advanceUntilIdle()

        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath())
        assertEquals(SortOption.ADDED, after.state.value.sort)
        assertEquals(filter, after.state.value.filter)
    }

    /** Opened from the library in numbers on Dolby Vision films: the wall and its first page start filtered. */
    @Test
    fun aWallOpenedOnPartOfTheLibraryStartsFilteredByIt() = runTest {
        provider.capabilities = provider.capabilities.copy(formatFilters = true, personFilter = true)
        val vm = LibraryViewModel({ provider }, library, SavedStateHandle(), opensOn = LibraryFilter(picture = "Dolby Vision"))
        assertEquals(LibraryFilter(picture = "Dolby Vision"), vm.state.value.filter)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
    }

    @Test
    fun aWallBroughtBackIsAsItWasLeftNotAsItWasOpened() = runTest {
        provider.capabilities = provider.capabilities.copy(formatFilters = true)
        val saved = SavedStateHandle()
        val opensOn = LibraryFilter(picture = "Dolby Vision")
        val changed = LibraryFilter(picture = "Dolby Vision", sound = "E-AC3")
        LibraryViewModel({ provider }, library, saved, opensOn = opensOn).setFilter(changed)
        advanceUntilIdle()

        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), opensOn = opensOn)
        assertEquals(changed, after.state.value.filter)
    }

    /** A source that cannot filter by format or person (Jellyfin's formats) never opens a wall on them. */
    @Test
    fun aWallNeverStartsOnWhatItsSourceCannotFilterBy() = runTest {
        provider.capabilities = provider.capabilities.copy(formatFilters = false, personFilter = true)
        val vm = LibraryViewModel({ provider }, library, SavedStateHandle(), opensOn = LibraryFilter(sound = "E-AC3", person = "p7"))
        assertEquals(LibraryFilter(person = "p7"), vm.state.value.filter)
    }

    private val sagas = Library("sagas", LibraryKind.COLLECTIONS, null)

    /** The films wall of a source with sagas: two films and a saga, and the saga alone. */
    private fun withSagas() {
        val alien = MediaItem("c1", MediaKind.COLLECTION, "Alien")
        provider.walls = mapOf(library.id to listOf(alien, film("m1"), film("m2")), sagas.id to listOf(alien))
    }

    @Test
    fun aFilmsWallWithSagasShowsThemAmongTheFilmsAndCountsThem() = runTest {
        withSagas()
        val vm = LibraryViewModel({ provider }, library, sagas = sagas)
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS, vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = true), provider.pages.single())
        assertEquals(3 to 1, vm.state.value.total to vm.state.value.sagas)
    }

    @Test
    fun theSagasAloneAreAskedOfTheSagasWithTheFilmsFilters() = runTest {
        withSagas()
        provider.capabilities = provider.capabilities.copy(sagaFilters = true)
        val vm = LibraryViewModel({ provider }, library, sagas = sagas)
        val unwatched = LibraryFilter(status = WatchStatus.UNWATCHED)
        vm.setFilter(unwatched)
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        assertEquals(WallView.SAGAS, vm.state.value.view)
        assertEquals(Asked("sagas", unwatched, grouped = false), provider.pages.last())
        assertEquals(listOf("c1"), vm.state.value.items.map { it.id })
        assertEquals("the films' choices, asked once", 1, provider.calls.count { it == "facets" })
    }

    /** Jellyfin cannot filter a collection by its films: the sagas show whole, the filters wait for the films. */
    @Test
    fun aSourceThatCannotFilterSagasShowsThemWholeAndKeepsTheFilters() = runTest {
        withSagas()
        val vm = LibraryViewModel({ provider }, library, sagas = sagas)
        val unwatched = LibraryFilter(status = WatchStatus.UNWATCHED)
        vm.setFilter(unwatched)
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        assertEquals(Asked("sagas", LibraryFilter(), grouped = false), provider.pages.last())
        assertEquals(unwatched, vm.state.value.filter)

        vm.setView(WallView.FILMS_AND_SAGAS)
        advanceUntilIdle()
        assertEquals(Asked("films", unwatched, grouped = true), provider.pages.last())
    }

    @Test
    fun theViewOutlivesAStoppedProcess() = runTest {
        withSagas()
        val saved = SavedStateHandle()
        LibraryViewModel({ provider }, library, saved, sagas = sagas).setView(WallView.SAGAS)
        advanceUntilIdle()
        provider.pages.clear()

        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), sagas = sagas)
        assertEquals(WallView.SAGAS, after.state.value.view)
        advanceUntilIdle()
        assertEquals("sagas", provider.pages.single().library)
    }

    /** Clearing the filters goes back to the films and their sagas, unfiltered, in one load, and stays so. */
    @Test
    fun clearingTheFiltersGoesBackToTheFilmsAndSagas() = runTest {
        withSagas()
        val saved = SavedStateHandle()
        val vm = LibraryViewModel({ provider }, library, saved, sagas = sagas)
        vm.setFilter(LibraryFilter(genre = "Drame"))
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        provider.pages.clear()

        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS to LibraryFilter(), vm.state.value.view to vm.state.value.filter)
        assertEquals(listOf(Asked("films", LibraryFilter(), grouped = true)), provider.pages)
        val after = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), sagas = sagas)
        assertEquals(WallView.FILMS_AND_SAGAS to LibraryFilter(), after.state.value.view to after.state.value.filter)
    }

    /** A person's films, or a format's, opened from elsewhere: every film on its own, no view to choose. */
    @Test
    fun aWallOpenedOnPartOfTheLibraryShowsEveryFilm() = runTest {
        withSagas()
        provider.capabilities = provider.capabilities.copy(personFilter = true)
        val vm = LibraryViewModel({ provider }, library, SavedStateHandle(), opensOn = LibraryFilter(person = "p7"), sagas = sagas)
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(person = "p7"), grouped = false), provider.pages.single())
        assertNull(vm.state.value.sagas)
        // Nothing to turn to, even once the source shows sagas.
        vm.setView(WallView.SAGAS)
        assertNull(vm.state.value.view)
        provider.libraries = listOf(library, sagas)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertFalse(provider.pages.last().grouped)
    }

    /** The Sagas setting changed while the wall is open: hidden, its films alone and nothing to choose; back, grouped. */
    @Test
    fun theWallFollowsTheSagasTheSourceShows() = runTest {
        withSagas()
        provider.libraries = listOf(library, sagas)
        val saved = SavedStateHandle()
        val vm = LibraryViewModel({ provider }, library, saved, sagas = sagas)
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        provider.pages.clear()

        provider.libraries = listOf(library)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertEquals(listOf(Asked("films", LibraryFilter(), grouped = false)), provider.pages)
        // The sagas alone forgotten: a wall brought back by its route, which names the sagas, opens on the films.
        val restored = LibraryViewModel({ provider }, library, saved.afterProcessDeath(), sagas = sagas)
        assertEquals(WallView.FILMS_AND_SAGAS, restored.state.value.view)

        // Back, on the films and their sagas: the sagas alone were left with them.
        provider.libraries = listOf(library, sagas)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS, vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = true), provider.pages.last())
        vm.setView(WallView.SAGAS)
        advanceUntilIdle()
        assertEquals("sagas", provider.pages.last().library)
    }

    /** A wall opened while the sagas were hidden groups them once the setting shows them. */
    @Test
    fun aWallOpenedWithoutSagasGroupsThemOnceShown() = runTest {
        withSagas()
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertNull(vm.state.value.view)

        provider.libraries = listOf(library, sagas)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(WallView.FILMS_AND_SAGAS, vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = true), provider.pages.last())
    }

    @Test
    fun aWallWithoutSagasGroupsNothing() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        assertNull(vm.state.value.view)
        assertEquals(Asked("films", LibraryFilter(), grouped = false), provider.pages.single())
    }

    @Test
    fun theLatestFacetsWin() = runTest {
        val vm = LibraryViewModel({ provider }, library)
        advanceUntilIdle()
        val older = provider.gate("facets")
        vm.onLibraryChanged()
        advanceUntilIdle()
        val newer = provider.gate("facets")
        vm.onLibraryChanged()
        advanceUntilIdle()
        provider.facets = LibraryFacets(genres = listOf("Drame"))
        newer.complete(Unit)
        advanceUntilIdle()
        provider.facets = LibraryFacets(genres = listOf("Western"))
        older.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Drame"), vm.state.value.facets.genres)
    }

    /** A title marked from its poster leaves a wall filtered on Non vus at once, the count with it; then it is saved and told. */
    @Test
    fun aTitleMarkedLeavesAnUnwatchedWallAtOnceThenIsSavedAndTold() = runTest {
        val events = FakeEvents()
        provider.wall = (1..3).map { film("m$it") }
        val vm =
            LibraryViewModel(
                { provider },
                library,
                opensOn = LibraryFilter(status = WatchStatus.UNWATCHED),
                events = events,
                work = this,
            )
        advanceUntilIdle()
        vm.mark(vm.state.value.items[1])
        assertEquals(listOf("m1", "m3"), vm.state.value.items.map { it.id })
        assertEquals(2, vm.state.value.total)
        advanceUntilIdle()
        assertTrue("played m2 true" in provider.calls)
        assertEquals("the other screens told", 1L, events.libraryVersion.value)
        assertNull(vm.notSaved.value)
    }

    /** A mark not saved comes undone: the title back in its place, counted again, and why it was not saved said for it. */
    @Test
    fun aMarkNotSavedIsUndoneInItsPlaceAndSaid() = runTest {
        val events = FakeEvents()
        provider.wall = (1..3).map { film("m$it") }
        provider.fail = { if (it.startsWith("played")) IOException("NAS away") else null }
        val vm =
            LibraryViewModel(
                { provider },
                library,
                opensOn = LibraryFilter(status = WatchStatus.UNWATCHED),
                events = events,
                work = this,
            )
        advanceUntilIdle()
        val marked = vm.state.value.items[1]
        vm.mark(marked)
        advanceUntilIdle()
        assertEquals(listOf("m1", "m2", "m3"), vm.state.value.items.map { it.id })
        assertEquals(marked, vm.state.value.items[1])
        assertEquals(3, vm.state.value.total)
        assertEquals(NotSaved("m2", UiError.OFFLINE), vm.notSaved.value)
        assertEquals("nothing told", 0L, events.libraryVersion.value)

        // Said while its title has focus, gone once another has it.
        vm.changes.focusedOn("m2")
        assertEquals("m2", vm.notSaved.value?.id)
        vm.changes.focusedOn("m3")
        assertNull(vm.notSaved.value)
    }

    /** Unfiltered, a title marked or made a favourite keeps its place, its mark changed; on Favoris, one taken out leaves. */
    @Test
    fun anUnfilteredWallKeepsItsTitleAFavoritesOneLetsGo() = runTest {
        provider.wall = (1..3).map { film("m$it").copy(user = UserState(favorite = true)) }
        val all = LibraryViewModel({ provider }, library, work = this)
        advanceUntilIdle()
        all.mark(all.state.value.items[0])
        all.favorite(all.state.value.items[1])
        assertEquals(listOf(true, false, false), all.state.value.items.map { it.user.played })
        assertEquals(listOf(true, false, true), all.state.value.items.map { it.user.favorite })
        assertEquals(3, all.state.value.total)

        val favorites =
            LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.FAVORITES), work = this)
        advanceUntilIdle()
        favorites.mark(favorites.state.value.items[0])
        assertEquals("a mark keeps a favourite", 3, favorites.state.value.items.size)
        favorites.favorite(favorites.state.value.items[0])
        assertEquals(listOf("m2", "m3"), favorites.state.value.items.map { it.id })
        advanceUntilIdle()
        assertTrue("favorite m1 false" in provider.calls)
    }

    /** On a wall filtered on Vus or En cours, a title marked leaves; a saga leaving takes one saga off the count. */
    @Test
    fun watchedAndInProgressWallsLetGoOfWhatTheyNoLongerHold() = runTest {
        val dune = MediaItem("saga:dune", MediaKind.COLLECTION, "Dune", childCount = 2, user = UserState(played = true, unplayedCount = 0))
        provider.wall = listOf(dune, film("m1").copy(user = UserState(played = true)))
        val watched = LibraryViewModel({ provider }, library, sagas = sagas)
        watched.setFilter(LibraryFilter(status = WatchStatus.WATCHED))
        advanceUntilIdle()
        assertEquals(1, watched.state.value.sagas)
        watched.mark(dune)
        assertEquals(listOf("m1"), watched.state.value.items.map { it.id })
        assertEquals(0, watched.state.value.sagas)

        provider.wall = listOf(film("m2").copy(runtimeMs = 6_000_000, user = UserState(positionMs = 60_000)))
        val started = LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.IN_PROGRESS))
        advanceUntilIdle()
        started.mark(started.state.value.items.single())
        assertEquals(emptyList<MediaItem>(), started.state.value.items)
        assertEquals(0, started.state.value.total)
    }

    /**
     * A reload begun before a change from a poster that lands after its save, with the source's list of before, never
     * brings the title back on a wall its filter no longer keeps it on, nor its count: Non vus, Vus, En cours, Favoris.
     */
    @Test
    fun aReloadBegunBeforeAChangeNeverBringsItsTitleBack() = runTest {
        mapOf(
            WatchStatus.UNWATCHED to UserState(),
            WatchStatus.WATCHED to UserState(played = true),
            WatchStatus.IN_PROGRESS to UserState(positionMs = 60_000),
            WatchStatus.FAVORITES to UserState(favorite = true),
        ).forEach { (status, user) ->
            val source = FakeProvider().apply { wall = (1..3).map { film("m$it").copy(user = user) } }
            val vm = LibraryViewModel({ source }, library, opensOn = LibraryFilter(status = status), events = FakeEvents(), work = this)
            advanceUntilIdle()
            val reload = source.gate("page 0 120")
            vm.onLibraryChanged(scanOnly = true)
            runCurrent()
            val m2 = vm.state.value.items[1]
            if (status == WatchStatus.FAVORITES) vm.favorite(m2) else vm.mark(m2)
            advanceUntilIdle()
            assertTrue("$status: saved", source.calls.any { it.startsWith("played m2") || it.startsWith("favorite m2") })

            reload.complete(Unit)
            advanceUntilIdle()
            assertEquals("$status", listOf("m1", "m3"), vm.state.value.items.map { it.id })
            assertEquals("$status", 2, vm.state.value.total)
        }
    }

    /** A next page begun before a mark that lands after its save counts the title no more, as the wall no longer holds it. */
    @Test
    fun aNextPageBegunBeforeAMarkCountsItNoMore() = runTest {
        val vm = LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.UNWATCHED), work = this)
        advanceUntilIdle()
        val next = provider.gate("page 120 120")
        vm.loadMore()
        runCurrent()
        vm.mark(vm.state.value.items[1])
        advanceUntilIdle()
        assertEquals(299, vm.state.value.total)

        next.complete(Unit)
        advanceUntilIdle()
        assertEquals(239, vm.state.value.items.size)
        assertFalse(vm.state.value.items.any { it.id == "m2" })
        assertEquals(299, vm.state.value.total)
    }

    /**
     * A mark is held over the reads begun while it is saved, the source still holding the title; a read begun after its
     * save lets go of it, and the wall shows what the source then says: the title marked again elsewhere, back.
     */
    @Test
    fun aChangeHeldLetsGoOnceAReadAfterItsSaveAgrees() = runTest {
        provider.wall = (1..3).map { film("m$it") }
        val vm = LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.UNWATCHED), work = this)
        advanceUntilIdle()
        val saving = provider.gate("played m2 true")
        vm.mark(vm.state.value.items[1])
        runCurrent()
        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals("held while it is saved", listOf("m1", "m3"), vm.state.value.items.map { it.id })
        assertEquals(2, vm.state.value.total)

        saving.complete(Unit)
        advanceUntilIdle()
        provider.wall = listOf(film("m1"), film("m3"))
        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals("as the source says it", listOf("m1", "m3"), vm.state.value.items.map { it.id })

        provider.wall = (1..3).map { film("m$it") }
        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals("let go", listOf("m1", "m2", "m3"), vm.state.value.items.map { it.id })
        assertEquals(3, vm.state.value.total)
    }

    /**
     * A title marked then made a favourite from its poster, neither saved, shows as the source has it, whichever fails
     * first: the first undone alone while the other is still saving, then both, the count never moved.
     */
    @Test
    fun aMarkThenAFavouriteNotSavedLeaveTheTitleAsTheSourceHasIt() = runTest {
        provider.fail = { if (it.startsWith("played") || it.startsWith("favorite")) IOException("NAS away") else null }
        listOf(true, false).forEach { markFailsFirst ->
            provider.wall = (1..3).map { film("m$it") }
            val vm = LibraryViewModel({ provider }, library, work = this)
            advanceUntilIdle()
            val m2 = vm.state.value.items[1]
            val marking = provider.gate("played m2 true")
            val favouring = provider.gate("favorite m2 true")
            vm.mark(m2)
            vm.favorite(vm.shown("m2"))
            runCurrent()
            assertEquals(m2.markedAs(true).favoredAs(true), vm.shown("m2"))

            (if (markFailsFirst) marking else favouring).complete(Unit)
            advanceUntilIdle()
            val other = if (markFailsFirst) m2.favoredAs(true) else m2.markedAs(true)
            assertEquals("$markFailsFirst: the other still saving shows", other, vm.shown("m2"))

            (if (markFailsFirst) favouring else marking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", m2, vm.shown("m2"))
            assertEquals(3, vm.state.value.total)
            assertEquals(NotSaved("m2", UiError.OFFLINE), vm.notSaved.value)
        }
    }

    /**
     * On a wall filtered on Favoris, a title marked then taken out of them, neither saved, comes back in its place as it
     * was, counted again, once neither keeps it away: its mark undone first, it stays away while it is taken out.
     */
    @Test
    fun onFavorisAMarkThenAFavouriteNotSavedBringTheTitleBackAsItWas() = runTest {
        provider.fail = { if (it.startsWith("played") || it.startsWith("favorite")) IOException("NAS away") else null }
        listOf(true, false).forEach { markFailsFirst ->
            provider.wall = (1..3).map { film("m$it").copy(user = UserState(favorite = true)) }
            val vm = LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.FAVORITES), work = this)
            advanceUntilIdle()
            val m2 = vm.state.value.items[1]
            val marking = provider.gate("played m2 true")
            val unfavouring = provider.gate("favorite m2 false")
            vm.mark(m2)
            vm.favorite(vm.shown("m2"))
            runCurrent()
            assertEquals(listOf("m1", "m3"), vm.state.value.items.map { it.id })
            assertEquals(2, vm.state.value.total)

            (if (markFailsFirst) marking else unfavouring).complete(Unit)
            advanceUntilIdle()
            if (markFailsFirst) {
                assertEquals("still taken out", listOf("m1", "m3"), vm.state.value.items.map { it.id })
                assertEquals(2, vm.state.value.total)
            } else {
                assertEquals("back, still marked", m2.markedAs(true), vm.shown("m2"))
                assertEquals(3, vm.state.value.total)
            }

            (if (markFailsFirst) unfavouring else marking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", listOf("m1", "m2", "m3"), vm.state.value.items.map { it.id })
            assertEquals("$markFailsFirst", m2, vm.shown("m2"))
            assertEquals("$markFailsFirst", 3, vm.state.value.total)
        }
    }

    /**
     * A title marked then unmarked from its poster, neither saved, is as it was, whichever fails first, its place in it
     * back: the unmark failing first leaves the mark still saving showing.
     */
    @Test
    fun aMarkThenItsOppositeNotSavedLeaveTheTitleAsItWas() = runTest {
        provider.fail = { if (it.startsWith("played")) IOException("NAS away") else null }
        listOf(true, false).forEach { markFailsFirst ->
            val started = film("m2").copy(runtimeMs = 6_000_000, user = UserState(positionMs = 60_000))
            provider.wall = listOf(film("m1"), started, film("m3"))
            val vm = LibraryViewModel({ provider }, library, work = this)
            advanceUntilIdle()
            val marking = provider.gate("played m2 true")
            val unmarking = provider.gate("played m2 false")
            vm.mark(vm.shown("m2"))
            vm.mark(vm.shown("m2"))
            runCurrent()
            val unmarked = started.markedAs(true).markedAs(false)
            assertEquals(unmarked, vm.shown("m2"))

            (if (markFailsFirst) marking else unmarking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", if (markFailsFirst) unmarked else started.markedAs(true), vm.shown("m2"))

            (if (markFailsFirst) unmarking else marking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", started, vm.shown("m2"))
            assertEquals(3, vm.state.value.total)
        }
    }

    /**
     * A mark not saved leaves a favourite asked after it showing, saved before or after the mark fails; on a wall filtered
     * on Favoris, the title taken out of them stays away, uncounted.
     */
    @Test
    fun aMarkNotSavedLeavesAFavouriteSavedShowing() = runTest {
        provider.fail = { if (it.startsWith("played")) IOException("NAS away") else null }
        listOf(true, false).forEach { markFailsFirst ->
            provider.wall = (1..3).map { film("m$it") }
            val all = LibraryViewModel({ provider }, library, work = this)
            advanceUntilIdle()
            val m2 = all.state.value.items[1]
            val marking = provider.gate("played m2 true")
            val favouring = provider.gate("favorite m2 true")
            all.mark(m2)
            all.favorite(all.shown("m2"))
            runCurrent()
            (if (markFailsFirst) marking else favouring).complete(Unit)
            advanceUntilIdle()
            (if (markFailsFirst) favouring else marking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", m2.favoredAs(true), all.shown("m2"))
            assertEquals(NotSaved("m2", UiError.OFFLINE), all.notSaved.value)

            provider.wall = (1..3).map { film("m$it").copy(user = UserState(favorite = true)) }
            val favorites = LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.FAVORITES), work = this)
            advanceUntilIdle()
            val marking2 = provider.gate("played m2 true")
            val unfavouring = provider.gate("favorite m2 false")
            favorites.mark(favorites.shown("m2"))
            favorites.favorite(favorites.shown("m2"))
            runCurrent()
            (if (markFailsFirst) marking2 else unfavouring).complete(Unit)
            advanceUntilIdle()
            (if (markFailsFirst) unfavouring else marking2).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", listOf("m1", "m3"), favorites.state.value.items.map { it.id })
            assertEquals("$markFailsFirst", 2, favorites.state.value.total)
        }
    }

    private fun LibraryViewModel.shown(id: String) = state.value.items.single { it.id == id }
}
