package app.houselights.ui.library

import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.core.WatchStatus
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
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

/** A wall's poster options (#47): a mark or a favourite shown at once, held over the reads under way, undone alone. */
@RunWith(RobolectricTestRunner::class)
class LibraryPosterChangesTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val provider = FakeProvider().apply { wall = (1..300).map { film("m$it") } }
    private val library = Library("films", LibraryKind.MOVIES, "Films")
    private val sagas = Library("sagas", LibraryKind.COLLECTIONS, null)

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
     * A next page begun after a favourite and before a mark taking the title off Non vus counts it no more, as the wall
     * no longer holds it: the favourite still saving, made before the page began, says nothing of its count.
     */
    @Test
    fun aNextPageBegunBetweenAFavouriteAndAMarkCountsTheTitleNoMore() = runTest {
        val vm = LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.UNWATCHED), work = this)
        advanceUntilIdle()
        val favouring = provider.gate("favorite m2 true")
        vm.favorite(vm.state.value.items[1])
        runCurrent()
        val next = provider.gate("page 120 120")
        vm.loadMore()
        runCurrent()
        vm.mark(vm.state.value.items[1])
        advanceUntilIdle()
        assertEquals(299, vm.state.value.total)

        next.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.items.any { it.id == "m2" })
        assertEquals(299, vm.state.value.total)
        favouring.complete(Unit)
        advanceUntilIdle()
        assertEquals(299, vm.state.value.total)
    }

    /**
     * A saga unmarked from its poster, read again by a scan's progress while it is saved, a film added to it meanwhile,
     * shows none of its films watched, where it kept the count of films left it was unmarked with: 1 of 3 watched.
     */
    @Test
    fun aSagaUnmarkedLaidOverAReadCountsTheFilmsTheReadHolds() = runTest {
        val dune = MediaItem("saga:dune", MediaKind.COLLECTION, "Dune", childCount = 2, user = UserState(played = true, unplayedCount = 0))
        provider.wall = listOf(film("m1"), dune, film("m3"))
        val vm = LibraryViewModel({ provider }, library, work = this)
        advanceUntilIdle()
        val saving = provider.gate("played saga:dune false")
        vm.mark(vm.shown("saga:dune"))
        runCurrent()
        assertEquals(UserState(played = false, unplayedCount = 2), vm.shown("saga:dune").user)

        provider.wall = listOf(film("m1"), dune.copy(childCount = 3, user = UserState(played = false, unplayedCount = 1)), film("m3"))
        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals(3, vm.shown("saga:dune").childCount)
        assertEquals(UserState(played = false, unplayedCount = 3), vm.shown("saga:dune").user)
        saving.complete(Unit)
        advanceUntilIdle()
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

    /**
     * Marked on Non vus, the wall then switched to Vus before the save fails (#118): the title, still unwatched, is not
     * put back on Vus, which never held it, nor counted there.
     */
    @Test
    fun aMarkNotSavedAfterTheWallChangedFilterStaysOffTheNewWall() = runTest {
        provider.wall = listOf(film("m1").copy(user = UserState(played = true)), film("m2"), film("m3"))
        provider.fail = { if (it.startsWith("played")) IOException("NAS away") else null }
        val vm = LibraryViewModel({ provider }, library, opensOn = LibraryFilter(status = WatchStatus.UNWATCHED), work = this)
        advanceUntilIdle()
        val saving = provider.gate("played m2 true")
        vm.mark(vm.shown("m2"))
        runCurrent()
        vm.setFilter(LibraryFilter(status = WatchStatus.WATCHED))
        advanceUntilIdle()
        assertEquals(listOf("m1"), vm.state.value.items.map { it.id })

        saving.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("m1"), vm.state.value.items.map { it.id })
        assertEquals(1, vm.state.value.total)
        assertEquals(NotSaved("m2", UiError.OFFLINE), vm.notSaved.value)
    }

    private fun LibraryViewModel.shown(id: String) = state.value.items.single { it.id == id }
}
