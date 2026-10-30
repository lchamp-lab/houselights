package app.houselights.ui.detail

import app.houselights.core.NfoDocument
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** A title's NFO: read from its source, parsed on the dispatcher the ViewModel is given, the latest load winning. */
@RunWith(RobolectricTestRunner::class)
class NfoViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val document = NfoDocument("/films/Heat.nfo", "\n<movie>\n  <title>Heat</title>\n</movie>\n")
    private val content = NfoContent("/films/Heat.nfo", document.info, listOf("<movie>", "  <title>Heat</title>", "</movie>"))
    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat")
        nfo = document
    }

    /** Parsed on the test's own dispatcher: `advanceUntilIdle` waits for it. */
    private fun viewModel() = NfoViewModel("m1", { provider }, parsing = main.dispatcher)

    @Test
    fun readsTheNfoOfTheTitle() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.title)
        assertEquals(LoadState.Ready(content), vm.state.value.document)
        assertEquals("parsed", "Heat", (vm.state.value.document as LoadState.Ready).value?.info?.title)
    }

    /** Up to 1 MB of XML, parsed into lines off the main thread, on the dispatcher the ViewModel was given. */
    @Test
    fun theNfoIsParsedAwayFromTheMainThread() = runTest {
        val parsing = TestCoroutineScheduler()
        val vm = NfoViewModel("m1", { provider }, parsing = StandardTestDispatcher(parsing))
        advanceUntilIdle()
        assertEquals("Parsed on the main thread", LoadState.Loading, vm.state.value.document)
        parsing.advanceUntilIdle()
        advanceUntilIdle()
        assertEquals(LoadState.Ready(content), vm.state.value.document)
    }

    /** An NFO with CR alone ending its lines reads in lines, as one with LF does. */
    @Test
    fun crAloneEndsALine() = runTest {
        provider.nfo = NfoDocument(document.path, "\r<movie>\r  <title>Heat</title>\r</movie>\r")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(content.lines, (vm.state.value.document as LoadState.Ready).value?.lines)
    }

    /**
     * An NFO written on one line is shown in lines of [NFO_LINE] characters at most, each cut after a tag, which the
     * sheet lays out as they come in sight, never all of it at once.
     */
    @Test
    fun aLongLineIsCutAfterItsTags() = runTest {
        val text = "<movie>" + "<actor><name>Al Pacino</name></actor>".repeat(ACTORS) + "</movie>"
        provider.nfo = NfoDocument(document.path, text)
        val vm = viewModel()
        advanceUntilIdle()
        val lines = checkNotNull((vm.state.value.document as LoadState.Ready).value).lines
        assertEquals("the whole text", text, lines.joinToString(""))
        assertTrue("in several lines: ${lines.size}", lines.size > 1)
        assertTrue("none longer than $NFO_LINE", lines.all { it.length <= NFO_LINE })
        assertTrue("each cut after a tag", lines.all { it.endsWith('>') })
    }

    @Test
    fun aTitleWithoutAnNfoIsReadyWithNone() = runTest {
        provider.nfo = null
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(LoadState.Ready(null), vm.state.value.document)
    }

    @Test
    fun aFailureCanBeTriedAgain() = runTest {
        provider.fail = { IOException("NAS away") }
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(LoadState.Failed(UiError.OFFLINE), vm.state.value.document)

        provider.fail = { null }
        vm.load()
        assertEquals(LoadState.Loading, vm.state.value.document)
        advanceUntilIdle()
        assertEquals(LoadState.Ready(content), vm.state.value.document)
    }

    @Test
    fun theLatestLoadWins() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val older = provider.gate("nfo m1")
        vm.load()
        advanceUntilIdle()
        val newer = provider.gate("nfo m1")
        vm.load()
        advanceUntilIdle()
        newer.complete(Unit)
        advanceUntilIdle()
        provider.nfo = null
        older.complete(Unit)
        advanceUntilIdle()
        assertEquals(LoadState.Ready(content), vm.state.value.document)
    }

    private companion object {
        /** Actors enough to write the NFO's one line many times over [NFO_LINE]. */
        const val ACTORS = 1_000
    }
}
