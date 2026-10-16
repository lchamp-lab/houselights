package app.houselights.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The welcome wall's posters, asked of TMDB once ([trending]) and kept while the welcome screen is
 * in the back stack: back from a choice (the Jellyfin sign-in, the first folder), the wall is there.
 * None while TMDB answers, or when it cannot.
 */
class WelcomeViewModel(private val trending: suspend () -> List<String>) : ViewModel() {
    private val _posters = MutableStateFlow<List<String>>(emptyList())
    val posters: StateFlow<List<String>> = _posters

    init {
        viewModelScope.launch { _posters.value = trending() }
    }

    companion object {
        /** Posters [widthPx] wide, the size the wall shows them at. */
        fun factory(app: AppContainer, widthPx: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer { WelcomeViewModel { app.trendingPosters(widthPx) } }
        }
    }
}
