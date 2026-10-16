package app.houselights.ui.detail

import app.houselights.core.NfoDocument
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.film
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NfoViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val document = NfoDocument("/films/Heat.nfo", "<movie><title>Heat</title></movie>")
    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat")
        nfo = document
    }

    @Test
    fun readsTheNfoOfTheTitle() = runTest {
        val vm = NfoViewModel("m1") { provider }
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.title)
        assertEquals(LoadState.Ready(document), vm.state.value.document)
    }

    @Test
    fun aFailureCanBeTriedAgain() = runTest {
        provider.fail = { IOException("NAS away") }
        val vm = NfoViewModel("m1") { provider }
        advanceUntilIdle()
        assertEquals(LoadState.Failed(UiError.OFFLINE), vm.state.value.document)

        provider.fail = { null }
        vm.load()
        assertEquals(LoadState.Loading, vm.state.value.document)
        advanceUntilIdle()
        assertEquals(LoadState.Ready(document), vm.state.value.document)
    }
}
