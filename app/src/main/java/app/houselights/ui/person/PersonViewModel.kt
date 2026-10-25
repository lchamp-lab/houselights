package app.houselights.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.PersonPage
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import app.houselights.ui.util.after
import app.houselights.ui.util.loadState
import app.houselights.ui.util.readingAgain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Loads the page of person [personId] from the current source's [provider]. */
class PersonViewModel(private val personId: String, private val provider: () -> MediaProvider) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<PersonPage>>(LoadState.Loading)
    val state: StateFlow<LoadState<PersonPage>> = _state

    /** One load at a time: a new one cancels the previous. */
    private val loads = LatestJob(viewModelScope)

    init {
        load()
    }

    /** Loads the page: at start, after a failure, or after the library changes; a page shown stays if it fails. */
    fun load() {
        _state.update { it.readingAgain() }
        loads.launch {
            val read = loadState(TAG, "Person $personId") { provider().catalog?.person(personId) }
            _state.update { shown ->
                when (read) {
                    // Null when none of their titles remain in the library.
                    is LoadState.Ready -> read.value?.let { LoadState.Ready(it) } ?: LoadState.Failed(UiError.PERSON_GONE)
                    is LoadState.Failed -> shown.after(read)
                    LoadState.Loading -> shown
                }
            }
        }
    }

    companion object {
        private const val TAG = "Person"

        fun factory(app: AppContainer, personId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { PersonViewModel(personId, app::provider) }
        }
    }
}
