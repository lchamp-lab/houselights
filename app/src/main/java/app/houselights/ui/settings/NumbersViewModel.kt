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

/** Where the library in numbers stands: being counted, counted, or not. */
internal sealed interface NumbersState {
    data object Counting : NumbersState

    /**
     * The library counted ([numbers]), with what its source tells and filters by ([capabilities]), and the
     * walls its doors open: its first library of films and of shows ([walls]).
     */
    data class Counted(
        val numbers: LibraryNumbers,
        val capabilities: ProviderCapabilities,
        val walls: Map<LibraryKind, Library>,
    ) : NumbersState

    /** It could not be counted ([error]); [retrying] while it is counted again. */
    data class Failed(val error: UiError, val retrying: Boolean = false) : NumbersState
}

/**
 * The library in numbers ([app.houselights.core.Catalog.numbers]), counted off the main thread the first time
 * its pane shows ([count]), then kept while the settings are open: coming back from a wall its rows opened
 * finds them as they were, focus on the row left.
 */
internal class NumbersViewModel(private val provider: () -> MediaProvider) : ViewModel() {
    var state by mutableStateOf<NumbersState>(NumbersState.Counting)
        private set

    private val counting = LatestJob(viewModelScope)
    private var asked = false

    /** The pane shows: the library is counted, once. */
    fun count() {
        if (asked) return
        asked = true
        start()
    }

    /** After a failure: counts it again, the failure said meanwhile, its button at work. */
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

        /** The first library of films, and of shows: a door opens the films or the shows that hold its value. */
        fun walls(libraries: List<Library>): Map<LibraryKind, Library> =
            listOf(LibraryKind.MOVIES, LibraryKind.SHOWS).mapNotNull { kind -> libraries.firstOrNull { it.kind == kind } }
                .associateBy { it.kind }
    }
}

/** The settings' library in numbers, counted the first time its pane shows. */
@Composable
internal fun numbersViewModel(): NumbersViewModel {
    val app = appContainer()
    val vm = viewModel { NumbersViewModel(app::provider) }
    LaunchedEffect(vm) { vm.count() }
    return vm
}
