package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.SectionType
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val provider = FakeProvider().apply {
        home = listOf(HomeSection(SectionType.CONTINUE, listOf(film("m1")), landscape = true))
    }

    @Test
    fun loadsTheLibrariesAndTheRows() = runTest {
        val vm = HomeViewModel { provider }
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(provider.libraries, vm.state.value.libraries)
        assertEquals(provider.home, vm.state.value.sections)
    }

    @Test
    fun aFailureSaysWhy() = runTest {
        provider.fail = { IOException("Server away") }
        val vm = HomeViewModel { provider }
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(UiError.OFFLINE, vm.state.value.error)
    }

    @Test
    fun aNewerLoadReplacesAnOlderOne() = runTest {
        val first = provider.gate("libraries")
        val vm = HomeViewModel { provider }
        advanceUntilIdle()
        provider.gates.clear()
        vm.load()
        advanceUntilIdle()
        // The first load was cancelled while it waited: only the second asked for the rows.
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("libraries", "libraries", "home"), provider.calls)
    }
}
