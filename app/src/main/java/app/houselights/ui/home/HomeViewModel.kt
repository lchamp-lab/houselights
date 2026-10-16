package app.houselights.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaProvider
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val error: UiError? = null,
    val libraries: List<Library> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
)

/** Home's rows, from the [provider] of the source in use. */
class HomeViewModel(private val provider: () -> MediaProvider) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        // With nothing shown yet, trying again shows: the error gives way to the load. Rows shown stay
        // as they are while they load again.
        if (_state.value.sections.isEmpty()) _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            suspendRunCatching {
                val source = provider()
                source.libraries() to source.home()
            }.onSuccess { (libraries, sections) ->
                _state.value = HomeState(loading = false, libraries = libraries, sections = sections)
            }.onFailure { e ->
                Log.w(TAG, "Home failed", e)
                _state.update { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    companion object {
        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(app::provider) }
        }
    }
}

private const val TAG = "Home"
