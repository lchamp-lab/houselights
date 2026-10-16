package app.houselights.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.NfoDocument
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A title and its NFO; a title without one is [LoadState.Ready] with null. */
data class NfoState(val title: String = "", val document: LoadState<NfoDocument?> = LoadState.Loading)

class NfoViewModel(private val itemId: String, private val provider: () -> MediaProvider) : ViewModel() {
    private val _state = MutableStateFlow(NfoState())
    val state: StateFlow<NfoState> = _state

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(document = LoadState.Loading)
        viewModelScope.launch {
            suspendRunCatching {
                val provider = provider()
                val item = provider.item(itemId)
                NfoState(item.title, LoadState.Ready(provider.nfo(item)))
            }.onSuccess { _state.value = it }
                .onFailure { e ->
                    Log.w("Nfo", "NFO of $itemId failed", e)
                    _state.value = _state.value.copy(document = LoadState.Failed(e.toUiError()))
                }
        }
    }

    companion object {
        fun factory(app: AppContainer, itemId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { NfoViewModel(itemId, app::provider) }
        }
    }
}
