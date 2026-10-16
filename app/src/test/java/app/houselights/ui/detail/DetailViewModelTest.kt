package app.houselights.ui.detail

import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.film
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.design.HlMotion
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

/** A title's page on a fake source: loading, replacing a load, failures, watched and favourites. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat")
        items["show"] = series("show")
        seasons = mapOf("show" to (1..3).map { season("s$it", it, "show") })
        episodes = (1..3).associate { n -> "s$n" to (1..2).map { episode("s${n}e$it", "s$n", it) } }
    }
    private val events = FakeEvents()

    private fun detail(id: String = "m1") = DetailViewModel(id, { provider }, events)

    @Test
    fun loadsTheTitle() = runTest {
        val vm = detail()
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.item?.title)
        assertTrue(vm.state.value.canEdit)
        assertNull(vm.state.value.error)
    }

    @Test
    fun aTitleGoneSaysSo() = runTest {
        val vm = detail("gone")
        advanceUntilIdle()
        assertNull(vm.state.value.item)
        assertEquals(UiError.GONE, vm.state.value.error)
    }

    @Test
    fun aNewerLoadReplacesAnOlderOne() = runTest {
        val first = provider.gate("item m1")
        val vm = detail()
        advanceUntilIdle()
        provider.gates.clear()
        provider.items["m1"] = film("m1", "Heat (1995)")
        vm.load()
        advanceUntilIdle()
        // The older load, let go after the newer one, changes nothing.
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals("Heat (1995)", vm.state.value.item?.title)
    }

    @Test
    fun watchedShowsAtOnceAndTellsTheOtherScreens() = runTest {
        val vm = detail()
        advanceUntilIdle()
        val reload = provider.gate("item m1")
        vm.togglePlayed()
        advanceUntilIdle()
        // Saved: the page says so before it has loaded again, and home and the walls will reload.
        assertEquals(true, vm.state.value.item?.user?.played)
        assertEquals(1L, events.libraryVersion.value)
        assertEquals(listOf("item m1", "played m1 true", "item m1"), provider.calls)
        reload.complete(Unit)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.item?.user?.played)

        vm.toggleFavorite()
        advanceUntilIdle()
        assertEquals(true, vm.state.value.item?.user?.favorite)
        assertEquals(2L, events.libraryVersion.value)
    }

    @Test
    fun aSecondPressWhileSavingDoesNothing() = runTest {
        val vm = detail()
        advanceUntilIdle()
        val saving = provider.gate("played m1 true")
        vm.togglePlayed()
        advanceUntilIdle()
        vm.togglePlayed()
        advanceUntilIdle()
        saving.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, provider.calls.count { it.startsWith("played") })
        assertEquals(true, vm.state.value.item?.user?.played)
    }

    @Test
    fun aFailedChangeIsSaidBesideTheActions() = runTest {
        val vm = detail()
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("favorite")) IOException("Server away") else null }
        vm.toggleFavorite()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.actionError)
        assertEquals(false, vm.state.value.item?.user?.favorite)
        assertEquals(0L, events.libraryVersion.value)

        // A reload keeps it; the next action clears it.
        vm.load()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.actionError)
        vm.clearActionError()
        assertNull(vm.state.value.actionError)
    }

    private val DetailViewModel.shownEpisodes get() = state.value.episodes.map { it.id }

    @Test
    fun seasonsAnsweringOutOfOrderNeverShowAnotherSeason() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals(listOf("s1e1", "s1e2"), vm.shownEpisodes)

        val slow = provider.gate("episodes s2")
        vm.selectSeason("s2")
        advanceTimeBy(HlMotion.SETTLE + 1)
        assertTrue("episodes s2" in provider.calls)
        vm.selectSeason("s3")
        // The tab changes at once and its row empties: the season before never shows under it.
        assertEquals("s3", vm.state.value.selectedSeasonId)
        assertEquals(emptyList<String>(), vm.shownEpisodes)
        assertTrue(vm.state.value.episodesLoading)
        advanceUntilIdle()
        assertEquals(listOf("s3e1", "s3e2"), vm.shownEpisodes)

        // The slower answer comes last: it changes nothing.
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals("s3", vm.state.value.selectedSeasonId)
        assertEquals(listOf("s3e1", "s3e2"), vm.shownEpisodes)
    }

    @Test
    fun crossingTabsLoadsOnlyTheOneFocusStopsOn() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        vm.selectSeason("s2")
        advanceTimeBy(HlMotion.SETTLE / 2)
        vm.selectSeason("s3")
        advanceUntilIdle()
        assertEquals(listOf("episodes s1", "episodes s3"), provider.calls.filter { it.startsWith("episodes") })
        assertEquals(listOf("s3e1", "s3e2"), vm.shownEpisodes)
    }

    @Test
    fun aReloadKeepsTheSeasonChosenMeanwhile() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        val reloading = provider.gate("episodes s1")
        vm.load()
        advanceUntilIdle()
        vm.selectSeason("s2")
        advanceUntilIdle()
        reloading.complete(Unit)
        advanceUntilIdle()
        assertEquals("s2", vm.state.value.selectedSeasonId)
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }

    @Test
    fun aReloadTakesOverASeasonStillToLoad() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        vm.selectSeason("s2")
        vm.load()
        advanceUntilIdle()
        assertEquals(1, provider.calls.count { it == "episodes s2" })
        assertEquals("s2", vm.state.value.selectedSeasonId)
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }

    @Test
    fun aSeasonThatFailedSaysSoAndCanBeTriedAgain() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        provider.fail = { if (it == "episodes s2") IOException("Server away") else null }
        vm.selectSeason("s2")
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.episodesError)
        assertEquals(emptyList<String>(), vm.shownEpisodes)

        provider.fail = { null }
        vm.retrySeason()
        assertNull(vm.state.value.episodesError)
        advanceUntilIdle()
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }

    @Test
    fun tryingAgainWithNoTitleShownSaysSo() = runTest {
        provider.fail = { IOException("Server away") }
        val vm = detail()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        vm.load()
        assertNull(vm.state.value.error)
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.item?.title)
    }

    @Test
    fun aReloadThatFailsLeavesNoSeasonLoading() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        vm.selectSeason("s2")
        provider.fail = { if (it == "item show") IOException("Server away") else null }
        vm.load()
        advanceUntilIdle()
        assertFalse(vm.state.value.episodesLoading)
        assertEquals(UiError.OFFLINE, vm.state.value.episodesError)
        provider.fail = { null }
        vm.retrySeason()
        advanceUntilIdle()
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }
}
