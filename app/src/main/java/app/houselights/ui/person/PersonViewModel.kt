package app.houselights.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.PersonPage
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A person's page as it loads: their [page] once read, else why not ([error]). */
data class PersonState(val page: PersonPage? = null, val loading: Boolean = true, val error: UiError? = null)

/** The page of the person [personId], from the [provider] of the source in use. */
class PersonViewModel(private val personId: String, private val provider: () -> MediaProvider) : ViewModel() {
    private val _state = MutableStateFlow(PersonState())
    val state: StateFlow<PersonState> = _state

    /** One load at a time, the latest asked for. */
    private val loads = LatestJob(viewModelScope)

    init {
        load()
    }

    /** Reads the page again: at first, after a failure, or once the library changed. */
    fun load() {
        _state.update { it.copy(loading = true) }
        loads.launch {
            suspendRunCatching { provider().catalog?.person(personId) }
                .onSuccess { page ->
                    // None of their titles left: Back is the way out.
                    _state.update { PersonState(page = page, loading = false, error = if (page == null) UiError.PERSON_GONE else null) }
                }
                .onFailure { e ->
                    val error = e.reported(TAG, "Person $personId")
                    // A page shown stays shown: only one with nothing to show says why.
                    _state.update { it.copy(loading = false, error = if (it.page == null) error else it.error) }
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
