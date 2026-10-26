package app.houselights.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import app.houselights.AppContainer
import app.houselights.R
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaProvider
import app.houselights.core.ToIdentify
import app.houselights.core.suspendRunCatching
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TwoPaneDefaults
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlResultItem
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.TaskTitle
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.reported
import app.houselights.ui.util.retainedError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "ToIdentify"

/**
 * « À identifier » (Réglages › Bibliothèque): the films and shows Houselights did not recognise with certainty, the
 * latest added first, each with why, the title it may be and where it lies. OK opens Identifier on one ([onOpen]).
 */
@Composable
fun ToIdentifyScreen(onOpen: (String) -> Unit) {
    val app = appContainer()
    val vm: ToIdentifyViewModel = viewModel(factory = ToIdentifyViewModel.factory(app))
    ReloadOnLibraryChange { vm.load() }
    ToIdentifyPage(vm, onOpen)
}

/**
 * The titles to identify as [vm] has them. Focus opens on the first; it stays on the title it is on as the list
 * reloads, and goes, once that title leaves (identified), to the one now in its place, else to the last. Back, from
 * past the first title, first returns to it.
 */
@Composable
internal fun ToIdentifyPage(vm: ToIdentifyViewModel, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val titles = state.titles
    val list = rememberLazyListState()
    val memory = rememberFocusMemory()
    // The title focus is on, or goes to, and its place in the list should it leave it.
    var at by rememberSaveable { mutableStateOf<String?>(null) }
    var place by rememberSaveable { mutableIntStateOf(0) }
    // The list focus was last placed in: while another shows, focus moving on its own is not the user's.
    var settled by remember { mutableStateOf<List<ToIdentify>?>(null) }
    val settling = titles !== settled
    LaunchedEffect(titles) {
        val shown = titles ?: return@LaunchedEffect
        focusTitle(shown, at, place, memory, list)?.let { (id, index) ->
            at = id
            place = index
        }
        settled = shown
    }
    BackToStart(away = place > 0) {
        list.scrollToItem(0)
        withFrameNanos { }
        titles?.firstOrNull()?.let { memory.focus(titleKey(it.item.id)) }
    }

    HlTwoPane(
        side = {
            TaskTitle(stringResource(R.string.to_identify))
            // From its baselines, as a form's description.
            Text(
                stringResource(R.string.to_identify_desc),
                style = Hl.type.body,
                color = Hl.colors.contentSecondary,
                modifier = Modifier.paddingFromBaseline(top = HlSpace.xl, bottom = HlSpace.xl),
            )
        },
        sideWidth = TwoPaneDefaults.sideWide,
        state = list,
    ) {
        val failure = retainedError(state.error.takeIf { titles == null }, retrying = state.loading)
        when {
            failure != null -> HlErrorState(failure, vm::load, retrying = state.loading)
            titles == null -> Unit
            // Only seen when the last titles leave while it is open: the end of the queue returns to Réglages.
            titles.isEmpty() -> HlEmptyState(
                stringResource(R.string.to_identify_none),
                message = stringResource(R.string.to_identify_none_hint),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().keepVerticalFocus(),
                state = list,
                contentPadding = PaddingValues(bottom = HlFocus.edge),
                verticalArrangement = Arrangement.spacedBy(HlSpace.s),
            ) {
                item(key = COUNT) { SectionHeader(pluralStringResource(R.plurals.title_count, titles.size, titles.size)) }
                itemsIndexed(titles, key = { _, t -> t.item.id }) { i, t ->
                    HlResultItem(
                        poster = t.item.poster,
                        title = t.item.title,
                        facts = listOf(t.item.year?.toString(), stringResource(doubtText(t.doubt))),
                        note = t.best?.let { bestText(it) },
                        overview = t.place,
                        onClick = { onOpen(t.item.id) },
                        modifier = Modifier
                            .focusKey(titleKey(t.item.id), memory)
                            .onFocusChanged {
                                if (it.isFocused && !settling) {
                                    at = t.item.id
                                    place = i
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * Focuses [at] among [titles] unless it holds focus already; gone, the title now at its [place], else the last; none
 * yet, the first. The title focused and its place; null for an empty list.
 */
private suspend fun focusTitle(
    titles: List<ToIdentify>,
    at: String?,
    place: Int,
    memory: FocusMemory,
    list: LazyListState,
): Pair<String, Int>? {
    if (titles.isEmpty()) return null
    val found = titles.indexOfFirst { it.item.id == at }
    val index = if (found >= 0) found else place.coerceAtMost(titles.lastIndex)
    val id = titles[index].item.id
    val key = titleKey(id)
    if (found >= 0 && memory.hasFocus(key)) return id to index
    repeat(LAID_OUT_FRAMES) {
        withFrameNanos { }
        if (memory.focus(key)) return id to index
    }
    // Not laid out: brought into view first, the title before it (or the count) above it.
    list.scrollToItem(index)
    withFrameNanos { }
    memory.focus(key)
    return id to index
}

/** Frames a list takes to lay out what it composes, before an element of it can take focus. */
private const val LAID_OUT_FRAMES = 3

private fun titleKey(id: String) = FocusKey.item(id)

private const val COUNT = "count"

/** Why a title is to identify, in a few words. */
@StringRes
internal fun doubtText(doubt: MatchDoubt): Int = when (doubt) {
    MatchDoubt.NOT_FOUND -> R.string.to_identify_not_found
    MatchDoubt.UNSURE -> R.string.to_identify_unsure
}

/** "Peut-être « Wonder Woman 1984 » (2020)": the title automatic matching found likeliest, its year when TMDB gives one. */
@Composable
private fun bestText(best: MatchCandidate): String = best.year
    ?.let { stringResource(R.string.to_identify_best, best.title, it) }
    ?: stringResource(R.string.to_identify_best_undated, best.title)

data class ToIdentifyState(
    /** Null until read. */
    val titles: List<ToIdentify>? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
)

/** The titles to identify, read again as the library changes. */
class ToIdentifyViewModel(private val provider: () -> MediaProvider) : ViewModel() {
    private val _state = MutableStateFlow(ToIdentifyState())
    val state: StateFlow<ToIdentifyState> = _state
    private val loads = LatestJob(viewModelScope)

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true) }
        loads.launch {
            suspendRunCatching { provider().editor?.toIdentify().orEmpty() }
                .onSuccess { titles -> _state.update { it.copy(titles = titles, loading = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.reported(TAG, "Titles to identify")) } }
        }
    }

    companion object {
        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ToIdentifyViewModel(app::provider) }
        }
    }
}
