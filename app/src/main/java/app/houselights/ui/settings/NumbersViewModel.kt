package app.houselights.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.LibraryNumbers
import app.houselights.core.MediaProvider
import app.houselights.core.ProviderCapabilities
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.loadState
import app.houselights.ui.util.readingAgain
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The library's [numbers], its source's [capabilities], and the first films and shows libraries rows open. */
internal data class CountedLibrary(
    val numbers: LibraryNumbers,
    val capabilities: ProviderCapabilities,
    val walls: Map<LibraryKind, Library>,
)

/** The library in numbers, counted off the main thread when its pane first shows and kept while Settings is open. */
internal class NumbersViewModel(private val provider: () -> MediaProvider) : ViewModel() {
    var state by mutableStateOf<LoadState<CountedLibrary>>(LoadState.Loading)
        private set

    private val counting = LatestJob(viewModelScope)
    private var asked = false

    /** Counts the library, once, when the pane first shows. */
    fun count() {
        if (asked) return
        asked = true
        start()
    }

    /** Counts again after a failure, which stays shown, its button busy, meanwhile. */
    fun retry() {
        val failed = state as? LoadState.Failed ?: return
        if (failed.retrying) return
        state = failed.readingAgain()
        start()
    }

    private fun start() {
        counting.launch {
            state = loadState(TAG, "Counting the library") {
                val source = provider()
                val catalog = checkNotNull(source.catalog) { "The source counts nothing" }
                coroutineScope {
                    val numbers = async { catalog.numbers() }
                    val libraries = source.libraries()
                    CountedLibrary(numbers.await(), source.capabilities, walls(libraries))
                }
            }
        }
    }

    private companion object {
        const val TAG = "Numbers"

        /** The first library of films and of shows: a row opens one of them, filtered on its value. */
        fun walls(libraries: List<Library>): Map<LibraryKind, Library> =
            listOf(LibraryKind.MOVIES, LibraryKind.SHOWS).mapNotNull { kind -> libraries.firstOrNull { it.kind == kind } }
                .associateBy { it.kind }
    }
}

/** The library in numbers for Settings; counting starts the first time its pane shows. */
@Composable
internal fun numbersViewModel(): NumbersViewModel {
    val app = appContainer()
    return viewModel { NumbersViewModel(app::provider) }.also { vm -> LaunchedEffect(vm) { vm.count() } }
}
