package app.houselights.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val libraries: List<Library> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
)

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state
    private var job: Job? = null

    /** Last focused card (section, position), restored when coming back from a detail screen. */
    var lastFocus: Pair<Int, Int> = 0 to 0

    init {
        load()
        viewModelScope.launch { app.libraryChanged.collect { load() } }
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            suspendRunCatching {
                val provider = app.provider
                provider.libraries() to provider.home()
            }.onSuccess { (libraries, sections) ->
                _state.value = HomeState(loading = false, libraries = libraries, sections = sections)
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
