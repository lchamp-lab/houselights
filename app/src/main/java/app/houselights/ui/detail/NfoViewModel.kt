package app.houselights.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.NfoDocument
import app.houselights.core.NfoInfo
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.loadState
import app.houselights.ui.util.map
import app.houselights.ui.util.reloading
import app.houselights.ui.util.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** An NFO ready to display: [path], parsed [info] (null when it does not parse), and its XML as [lines]. */
data class NfoContent(val path: String, val info: NfoInfo?, val lines: List<String>)

/** A title and its NFO; a title without one is [LoadState.Ready] with null. */
data class NfoState(val title: String = "", val document: LoadState<NfoContent?> = LoadState.Loading)

class NfoViewModel(
    private val itemId: String,
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

    /** Loads the title and its NFO; on retry, only the latest load is shown. */
    fun load() {
        _state.update { it.copy(document = it.document.reloading()) }
        loads.launch {
            val loaded = loadState("Nfo", "NFO of $itemId") {
                val provider = provider()
                val item = provider.item(itemId)
                item.title to provider.nfo(item)?.let { nfo -> withContext(parsing) { nfo.content() } }
            }
            _state.update { s -> NfoState(loaded.valueOrNull?.first ?: s.title, loaded.map { it.second }) }
        }
    }

    companion object {
        fun factory(app: AppContainer, itemId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { NfoViewModel(itemId, app::provider) }
        }
    }
}

/** The NFO's parsed details and its trimmed text split into lines. */
private fun NfoDocument.content() = NfoContent(path, info, text.trim().split('\n'))
