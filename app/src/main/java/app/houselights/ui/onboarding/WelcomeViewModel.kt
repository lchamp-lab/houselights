package app.houselights.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.ui.util.LatestJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** TMDB trending posters for the welcome wall, kept while on the back stack; empty until loaded ([show] retries). */
class WelcomeViewModel(private val trending: suspend () -> List<String>) : ViewModel() {
    private val _posters = MutableStateFlow<List<String>>(emptyList())
    val posters: StateFlow<List<String>> = _posters
    private val asking = LatestJob(viewModelScope)

    init {
        show()
    }

    /** Loads the posters unless they are already loaded or loading. */
    fun show() {
        if (_posters.value.isNotEmpty() || asking.isActive) return
        asking.launch { _posters.value = trending() }
    }

    companion object {
        /** Requests posters [widthPx] wide, the size the wall shows them at. */
        fun factory(app: AppContainer, widthPx: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer { WelcomeViewModel { app.trendingPosters(widthPx) } }
        }
    }
}
