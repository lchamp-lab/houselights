package app.houselights.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.LibraryEvents
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.PersonPage
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.PosterChanges
import app.houselights.ui.library.favoredAs
import app.houselights.ui.library.markedAs
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import app.houselights.ui.util.after
import app.houselights.ui.util.loadState
import app.houselights.ui.util.map
import app.houselights.ui.util.readingAgain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Loads the page of person [personId] from the current source's [provider]. A poster's options mark a title or make it
 * a favourite (#47), shown at once, saved in [work] and told to the other screens ([events]).
 */
class PersonViewModel(
    private val personId: String,
    private val provider: () -> MediaProvider,
    events: LibraryEvents? = null,
    /** Where a poster's change is saved, outliving the page; its own scope by default. */
    work: CoroutineScope? = null,
) : ViewModel() {
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

    /** What a poster's options change, saved as the title's page saves it. */
    internal val changes = PosterChanges(provider, events, work ?: viewModelScope, TAG)

    /** The last change a poster's options asked that was not saved, said on its title's line while it has focus. */
    val notSaved: StateFlow<NotSaved?> get() = changes.notSaved

    /** Marks [item] watched, or not once it is, from its poster's options: its mark shows at once, the title staying. */
    fun mark(item: MediaItem) {
        val played = !item.user.played
        change(item, item.markedAs(played)) { undo -> changes.mark(item, played, undo) }
    }

    /** Makes [item] a favourite, or not once it is, from its poster's options, shown at once. */
    fun favorite(item: MediaItem) {
        val favorite = !item.user.favorite
        change(item, item.favoredAs(favorite)) { undo -> changes.favorite(item, favorite, undo) }
    }

    /** Shows [item] [changed] at once, in its place, as the page filters nothing; then [save]s it, [item] again if not. */
    private fun change(item: MediaItem, changed: MediaItem, save: (undo: () -> Unit) -> Unit) {
        _state.update { it.showing(changed) }
        save { _state.update { it.showing(item) } }
    }

    companion object {
        private const val TAG = "Person"

        fun factory(app: AppContainer, personId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { PersonViewModel(personId, app::provider, events = app, work = app.scope) }
        }
    }
}

/** The page showing [item] in its title's place, as it now stands. */
private fun LoadState<PersonPage>.showing(item: MediaItem): LoadState<PersonPage> = map { page ->
    page.copy(titles = page.titles.map { title -> if (title.item.id == item.id) title.copy(item = item) else title })
}
