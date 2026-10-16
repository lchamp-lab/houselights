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
import app.houselights.core.NfoInfo
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * An NFO as the screen shows it, ready: where it is, what it says (null when it does not read), and
 * its XML as written, line by line.
 */
data class NfoContent(val path: String, val info: NfoInfo?, val lines: List<String>)

/** A title and its NFO; a title without one is [LoadState.Ready] with null. */
data class NfoState(val title: String = "", val document: LoadState<NfoContent?> = LoadState.Loading)

class NfoViewModel(
    private val itemId: String,
    private val provider: () -> MediaProvider,
    /** Where the NFO is parsed and split: up to 1 MB of XML, never on the main thread. */
    private val parsing: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
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
                val document = provider.nfo(item)
                NfoState(item.title, LoadState.Ready(document?.let { nfo -> withContext(parsing) { nfo.content() } }))
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

/** What the screen shows of the NFO: its details parsed and set for display, and its text in lines, as one text would break it. */
private fun NfoDocument.content() = NfoContent(path, info, text.trim().split('\n'))
