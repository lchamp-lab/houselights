package app.houselights.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.NfoDocument
import app.houselights.core.NfoInfo
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.loadState
import app.houselights.ui.util.reloading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** An NFO ready to display: [path], parsed [info] (null when it does not parse), and its XML as [lines]. */
data class NfoContent(val path: String, val info: NfoInfo?, val lines: List<String>)

/** A title's NFO; a title without one is [LoadState.Ready] with null. */
data class NfoState(val document: LoadState<NfoContent?> = LoadState.Loading)

/** The NFO of [item], the title its page shows, read from its source as it is: the title is not read again for it. */
class NfoViewModel(
    private val item: MediaItem,
    private val provider: () -> MediaProvider,
    /** Where the NFO (up to 1 MB of XML) is parsed and split, off the main thread. */
    private val parsing: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(NfoState())
    val state: StateFlow<NfoState> = _state
    private val loads = LatestJob(viewModelScope)

    init {
        load()
    }

    /** Loads the title's NFO; on retry, only the latest load is shown. */
    fun load() {
        _state.update { it.copy(document = it.document.reloading()) }
        loads.launch {
            val loaded = loadState("Nfo", "NFO of ${item.id}") {
                provider().nfo(item)?.let { nfo -> withContext(parsing) { nfo.content() } }
            }
            _state.value = NfoState(loaded)
        }
    }

    companion object {
        fun factory(app: AppContainer, item: MediaItem): ViewModelProvider.Factory = viewModelFactory {
            initializer { NfoViewModel(item, app::provider) }
        }
    }
}

/** The NFO's parsed details and its trimmed text split into lines. */
private fun NfoDocument.content() = NfoContent(path, info, text.trim().split('\n'))
