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
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.reported
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** State of the library in numbers: counting, counted, or failed. */
internal sealed interface NumbersState {
    data object Counting : NumbersState

    /** The library's [numbers], its source's [capabilities], and the first films and shows libraries rows open. */
    data class Counted(
        val numbers: LibraryNumbers,
        val capabilities: ProviderCapabilities,
        val walls: Map<LibraryKind, Library>,
    ) : NumbersState

    /** Counting failed with [error]; [retrying] while it counts again. */
    data class Failed(val error: UiError, val retrying: Boolean = false) : NumbersState
}

/** The library in numbers, counted off the main thread when its pane first shows and kept while Settings is open. */
internal class NumbersViewModel(private val provider: () -> MediaProvider) : ViewModel() {
    var state by mutableStateOf<NumbersState>(NumbersState.Counting)
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
        val failed = state as? NumbersState.Failed ?: return
        if (failed.retrying) return
        state = failed.copy(retrying = true)
        start()
    }

    private fun start() {
        counting.launch {
            val source = provider()
            suspendRunCatching {
                val catalog = checkNotNull(source.catalog) { "The source counts nothing" }
                coroutineScope {
                    val numbers = async { catalog.numbers() }
                    val libraries = source.libraries()
                    NumbersState.Counted(numbers.await(), source.capabilities, walls(libraries))
                }
            }
                .onSuccess { state = it }
                .onFailure { state = NumbersState.Failed(it.reported(TAG, "Counting the library")) }
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
    val vm = viewModel { NumbersViewModel(app::provider) }
    LaunchedEffect(vm) { vm.count() }
    return vm
}
