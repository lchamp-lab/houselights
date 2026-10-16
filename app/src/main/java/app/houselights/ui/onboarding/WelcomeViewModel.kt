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

/**
 * The welcome wall's posters, asked of TMDB ([trending]) and kept while the welcome screen is in the
 * back stack: back from a choice (the Jellyfin sign-in, the first folder), the wall is there. None
 * while TMDB answers, or when it cannot: then the wall asks again each time it shows ([show]).
 */
class WelcomeViewModel(private val trending: suspend () -> List<String>) : ViewModel() {
    private val _posters = MutableStateFlow<List<String>>(emptyList())
    val posters: StateFlow<List<String>> = _posters
    private val asking = LatestJob(viewModelScope)

    init {
        show()
    }

    /** The wall shows: TMDB is asked for its posters, unless they are here or on their way. */
    fun show() {
        if (_posters.value.isNotEmpty() || asking.isActive) return
        asking.launch { _posters.value = trending() }
    }

    companion object {
        /** Posters [widthPx] wide, the size the wall shows them at. */
        fun factory(app: AppContainer, widthPx: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer { WelcomeViewModel { app.trendingPosters(widthPx) } }
        }
    }
}
