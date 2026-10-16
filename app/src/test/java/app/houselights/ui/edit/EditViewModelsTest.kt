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
import kotlinx.coroutines.async
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
        val vm = IdentifyViewModel("m1") { provider }
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.query)
        assertEquals("1995", vm.state.value.year)
        assertEquals(listOf(heat), vm.state.value.results)
    }

    @Test
    fun withoutAKeyThereIsNothingToSearch() = runTest {
        provider.identity = provider.identity!!.copy(canSearch = false)
        val vm = IdentifyViewModel("m1") { provider }
        advanceUntilIdle()
        assertEquals(UiError.NO_TMDB_KEY, vm.state.value.error)
        assertFalse(provider.calls.any { it.startsWith("search") })
    }

    @Test
    fun oneIdentificationAtATime() = runTest {
        val vm = IdentifyViewModel("m1") { provider }
        advanceUntilIdle()
        val gate = provider.gate("identify m1 949")
        val first = async { vm.identify(heat) }
        advanceUntilIdle()
        assertTrue(vm.state.value.identifying)
        // A second OK while matching changes nothing.
        assertFalse(vm.identify(heat))
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, provider.calls.count { it.startsWith("identify") })
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
}
