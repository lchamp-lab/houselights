package app.houselights.ui.edit

import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Artwork and identification on a fake source. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val heat = MatchCandidate("949", "Heat", null, 1995, null, null)
    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat")
        identity = Identity("Heat.mkv", "Heat", 1995, current = null, manual = false, canSearch = true)
        results = listOf(heat)
        val poster = ArtworkOption("p1", null, ArtworkOrigin.ONLINE)
        choices = ArtworkChoices(listOf(poster), poster, poster, onlineAvailable = true)
    }

    @Test
    fun identifySearchesWhatAutomaticMatchingWould() = runTest {
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.query)
        assertEquals("1995", vm.state.value.year)
        assertEquals(listOf(heat), vm.state.value.results)
    }

    @Test
    fun withoutAKeyThereIsNothingToSearch() = runTest {
        provider.identity = provider.identity!!.copy(canSearch = false)
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        assertEquals(UiError.NO_TMDB_KEY, vm.state.value.error)
        assertFalse(provider.calls.any { it.startsWith("search") })
    }

    @Test
    fun oneIdentificationAtATime() = runTest {
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        val gate = provider.gate("identify m1 949")
        vm.identify(heat)
        advanceUntilIdle()
        assertTrue(vm.state.value.identifying)
        // A second OK while matching changes nothing.
        vm.identify(heat)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertEquals(1, provider.calls.count { it.startsWith("identify") })
    }

    /** Back while a title is matched: the screen goes, the match is still written whole. */
    @Test
    fun leavingWhileMatchingNeverCutsTheMatchOff() = runTest {
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        val gate = provider.gate("identify m1 949")
        vm.identify(heat)
        advanceUntilIdle()
        vm.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(heat, provider.identified)
    }

    @Test
    fun aFailedMatchSaysWhyAndCanBeChosenAgain() = runTest {
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("identify")) IOException("TMDB away") else null }
        vm.identify(heat)
        advanceUntilIdle()
        assertFalse(vm.state.value.identifying)
        assertFalse(vm.state.value.done)
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        vm.identify(heat)
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
    }

    @Test
    fun aFailedChoiceOfArtworkSaysWhy() = runTest {
        val vm = ArtworkViewModel("m1", ArtworkKind.POSTER) { provider }
        advanceUntilIdle()
        val option = vm.state.value.choices!!.options.first()
        provider.fail = { if (it.startsWith("setArtwork")) IOException("NAS away") else null }
        assertFalse(vm.choose(option))
        assertEquals(UiError.OFFLINE, vm.state.value.error)
    }

    @Test
    fun retryLoadsTheTitleAgainWhenItCouldNotBe() = runTest {
        provider.fail = { if (it == "item m1") IOException("NAS away") else null }
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        vm.retry()
        assertEquals(null, vm.state.value.error)
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.item?.title)
        assertEquals(listOf(heat), vm.state.value.results)
    }

    @Test
    fun retrySearchesAgainOnceTheTitleIsThere() = runTest {
        provider.fail = { if (it.startsWith("search")) IOException("TMDB away") else null }
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, provider.calls.count { it.startsWith("search") })
        assertEquals(1, provider.calls.count { it == "item m1" })
        assertEquals(listOf(heat), vm.state.value.results)
    }
}
