package app.houselights.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.ToIdentify
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

/** Artwork and identification on a fake source. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditViewModelsTest {
    @get:Rule
    val settle = SettleMainLooper()

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
    fun leavingWhileArtworkIsSavedNeverCutsItOff() = runTest {
        val vm = ArtworkViewModel("m1", ArtworkKind.POSTER, { provider }, work = this)
        advanceUntilIdle()
        val option = vm.state.value.choices!!.options.first()
        val gate = provider.gate("setArtwork m1 POSTER")
        vm.choose(option)
        advanceUntilIdle()
        assertEquals(option, vm.state.value.choosing)
        // A second OK while it is saved changes nothing.
        vm.choose(option)
        vm.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertEquals(1, provider.calls.count { it.startsWith("setArtwork") })
    }

    @Test
    fun retryLoadsTheTitleAgainWhenItCouldNotBe() = runTest {
        provider.fail = { if (it == "item m1") IOException("NAS away") else null }
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        vm.retry()
        assertNull(vm.state.value.error)
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

    /** Android killed the app meanwhile: what was typed comes back, and is what is searched. */
    @Test
    fun whatWasTypedOutlivesAStoppedProcess() = runTest {
        val saved = SavedStateHandle()
        val before = IdentifyViewModel("m1", { provider }, work = this, saved)
        advanceUntilIdle()
        before.setQuery("Heat McCann")
        before.setYear("1995a")

        provider.calls.clear()
        val after = IdentifyViewModel("m1", { provider }, work = this, saved.afterProcessDeath())
        advanceUntilIdle()
        assertEquals("Heat McCann", after.state.value.query)
        assertEquals("1995", after.state.value.year)
        assertTrue("search Heat McCann" in provider.calls)
    }

    @Test
    fun theLatestChoicesWin() = runTest {
        val vm = ArtworkViewModel("m1", ArtworkKind.POSTER, { provider }, work = this)
        advanceUntilIdle()
        val before = provider.choices
        val older = provider.gate("artwork m1 POSTER")
        vm.load()
        advanceUntilIdle()
        val newer = provider.gate("artwork m1 POSTER")
        vm.load()
        advanceUntilIdle()
        val other = ArtworkOption("p2", null, ArtworkOrigin.ONLINE)
        val latest = ArtworkChoices(listOf(other), other, other, onlineAvailable = true)
        provider.choices = latest
        newer.complete(Unit)
        advanceUntilIdle()
        provider.choices = before
        older.complete(Unit)
        advanceUntilIdle()
        assertEquals(latest, vm.state.value.choices)
    }

    /** A failed save says why in place of the choices, nothing chosen; retrying shows them at once, keeping the title. */
    @Test
    fun aFailedChoiceIsTriedAgainOverTheChoicesShown() = runTest {
        val vm = ArtworkViewModel("m1", ArtworkKind.POSTER, { provider }, work = this)
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("setArtwork")) IOException("NAS away") else null }
        vm.choose(vm.state.value.choices!!.options.first())
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)
        assertFalse(vm.state.value.done)
        assertNull(vm.state.value.choosing)
        assertEquals("Heat", vm.state.value.item?.title)

        provider.fail = { null }
        vm.load()
        assertNull(vm.state.value.error)
        assertEquals(provider.choices, vm.state.value.choices)
        advanceUntilIdle()
        assertEquals(provider.choices, vm.state.value.choices)
    }

    /** What automatic matching hesitated between shows at once, asking TMDB nothing; a search then replaces it. */
    @Test
    fun theSuggestionsShowAtOnceUntilASearchReplacesThem() = runTest {
        val dune = MatchCandidate("438631", "Dune", null, 2021, null, null)
        provider.identity = provider.identity!!.copy(doubt = MatchDoubt.UNSURE, suggestions = listOf(dune))
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        assertEquals(listOf(dune), vm.state.value.results)
        assertTrue(vm.state.value.suggested)
        assertFalse(provider.calls.any { it.startsWith("search") })

        vm.search()
        advanceUntilIdle()
        assertEquals(listOf(heat), vm.state.value.results)
        assertFalse(vm.state.value.suggested)
    }

    /** A search typed before Android stopped the app is run again, in place of the suggestions. */
    @Test
    fun aSearchTypedBeforeAStoppedProcessReplacesTheSuggestions() = runTest {
        val dune = MatchCandidate("438631", "Dune", null, 2021, null, null)
        provider.identity = provider.identity!!.copy(doubt = MatchDoubt.UNSURE, suggestions = listOf(dune))
        val saved = SavedStateHandle()
        val before = IdentifyViewModel("m1", { provider }, work = this, saved)
        advanceUntilIdle()
        before.setQuery("Heat")

        provider.calls.clear()
        val after = IdentifyViewModel("m1", { provider }, work = this, saved.afterProcessDeath())
        advanceUntilIdle()
        assertTrue("search Heat" in provider.calls)
        assertEquals(listOf(heat), after.state.value.results)
        assertFalse(after.state.value.suggested)
    }

    /** In the queue, the title after this one is read while it is still to identify, then this one is matched. */
    @Test
    fun inTheQueueTheNextTitleFollows() = runTest {
        provider.toIdentify = listOf(listed("m1"), listed("m2"))
        val vm = IdentifyViewModel("m1", { provider }, work = this, queue = true)
        advanceUntilIdle()
        vm.identify(heat)
        advanceUntilIdle()
        assertEquals(listOf("next m1", "identify m1 949"), provider.calls.filter { it.startsWith("next") || it.startsWith("identify") })
        assertTrue(vm.state.value.done)
        assertEquals("m2", vm.state.value.nextId)
    }

    /** Passer opens the next title, this one as it is; after the last, there is none. */
    @Test
    fun passerLeavesTheTitleAsItIs() = runTest {
        provider.toIdentify = listOf(listed("m0"), listed("m1"))
        val vm = IdentifyViewModel("m1", { provider }, work = this, queue = true)
        advanceUntilIdle()
        vm.skip()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertNull("the last title", vm.state.value.nextId)
        assertFalse(provider.calls.any { it.startsWith("identify") || it.startsWith("keep") })
    }

    /** Left as it is in the queue, the next title follows too. */
    @Test
    fun aTitleLeftAsItIsInTheQueueOpensTheNext() = runTest {
        provider.toIdentify = listOf(listed("m1"), listed("m2"))
        val vm = IdentifyViewModel("m1", { provider }, work = this, queue = true)
        advanceUntilIdle()
        vm.keep()
        advanceUntilIdle()
        assertTrue("keep m1" in provider.calls)
        assertEquals("m2", vm.state.value.nextId)
    }

    /** From a title's page, no next title is asked. */
    @Test
    fun outsideTheQueueNoNextTitleIsAsked() = runTest {
        provider.toIdentify = listOf(listed("m1"), listed("m2"))
        val vm = IdentifyViewModel("m1", { provider }, work = this)
        advanceUntilIdle()
        vm.identify(heat)
        advanceUntilIdle()
        assertNull(vm.state.value.nextId)
        assertFalse(provider.calls.any { it.startsWith("next") })
    }

    private fun listed(id: String) = ToIdentify(film(id), null, MatchDoubt.NOT_FOUND)
}
