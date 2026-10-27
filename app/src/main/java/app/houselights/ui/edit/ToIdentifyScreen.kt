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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.StatusState
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "ToIdentify"

/**
 * « À identifier » (Réglages › Bibliothèque): the films and shows Houselights did not recognise with certainty, the
 * latest added first, each with why, the title it may be and where it lies. OK opens Identifier on one, the titles
 * after it following ([onOpen]); back from them, focus is on the title the queue reached ([queueAt], then [onQueueAtFocus]).
 * Menu opens a title's suggestions, the best first and focused, so Menu then OK accepts it.
 */
@Composable
fun ToIdentifyScreen(
    onOpen: (String) -> Unit,
    queueAt: String? = null,
    onQueueAtFocus: () -> Unit = {},
    vm: ToIdentifyViewModel = viewModel(factory = ToIdentifyViewModel.factory(appContainer())),
) {
    ReloadOnLibraryChange { vm.load() }
    ToIdentifyPage(vm, onOpen, queueAt, onQueueAtFocus)
}

/**
 * The titles to identify as [vm] has them. Focus opens on the first, or on the title the queue reached ([queueAt]); it
 * stays on the title it is on as the list reloads, and goes, once that title leaves (identified), to the one now in its
 * place, else to the last. Back, from past the first title, first returns to it.
 */
@Composable
internal fun ToIdentifyPage(vm: ToIdentifyViewModel, onOpen: (String) -> Unit, queueAt: String? = null, onQueueAtFocus: () -> Unit = {}) {
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
    val currentOnQueueAtFocus by rememberUpdatedState(onQueueAtFocus)
    LaunchedEffect(titles, queueAt) {
        val shown = titles ?: return@LaunchedEffect
        // Back from the queue: the title it reached, once.
        val wanted = queueAt ?: at
        // None left: nothing to return to, so Back leaves.
        val (id, index) = focusTitle(shown, wanted, place, memory, list) ?: (null to 0)
        at = id
        place = index
        settled = shown
        if (queueAt != null) currentOnQueueAtFocus()
    }
    var options by remember { mutableStateOf<ToIdentify?>(null) }
    BackToFirst(titles?.firstOrNull(), away = place > 0, list, memory)

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
                // A suggestion chosen from Menu that could not be saved.
                state.chooseError?.let { error -> item(key = CHOOSE_ERROR) { NotSaved(error) } }
                itemsIndexed(titles, key = { _, t -> t.item.id }) { i, t ->
                    Title(
                        t,
                        onOpen = { onOpen(t.item.id) },
                        // A title not found on TMDB has nothing to suggest: OK alone leads on, to Identifier's search.
                        onOptions = { options = t }.takeIf { t.suggestions.isNotEmpty() },
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
    options?.let { t -> Suggestions(t, onChoose = { vm.choose(t, it) }, onDismiss = { options = null }) }
}

/** Back, from past the [first] title ([away]), first returns to it; with none left, it leaves. */
@Composable
private fun BackToFirst(first: ToIdentify?, away: Boolean, list: LazyListState, memory: FocusMemory) {
    BackToStart(away = away && first != null) {
        list.scrollToItem(0)
        withFrameNanos { }
        first?.let { memory.focus(titleKey(it.item.id)) }
    }
}

/** A title to identify: why, as its facts; what it may be, as its note; where it lies, as its overview. */
@Composable
private fun Title(title: ToIdentify, onOpen: () -> Unit, onOptions: (() -> Unit)?, modifier: Modifier = Modifier) {
    HlResultItem(
        poster = title.item.poster,
        title = title.item.title,
        facts = listOf(title.item.year?.toString(), stringResource(doubtText(title.doubt))),
        note = title.best?.let { bestText(it) },
        overview = title.place,
        onClick = onOpen,
        onLongClick = onOptions,
        onLongClickLabel = stringResource(R.string.more_options),
        modifier = modifier,
    )
}

/** Why a suggestion chosen from Menu was not saved, over the titles. */
@Composable
private fun NotSaved(error: UiError) {
    HlStatus(StatusState.Attention, stringResource(R.string.action_not_saved), detail = stringResource(error.message))
}

/**
 * A title's suggestions in a side sheet over the list, the best first and focused: OK identifies the title as that
 * one, the sheet closes, and the title leaves the list. No poster: Identifier has them, with OK on the title.
 */
@Composable
private fun Suggestions(title: ToIdentify, onChoose: (MatchCandidate) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    HlSideSheet(
        title = stringResource(R.string.identify),
        onDismiss = onDismiss,
        facts = listOf(title.item.title, title.item.year?.toString()),
        stateText = stringResource(doubtText(title.doubt)),
        state = StatusState.Off,
        initialFocus = first,
    ) { sheet ->
        HlSettingGroup(stringResource(R.string.identify_suggestions)) {
            title.suggestions.forEachIndexed { i, candidate ->
                HlSettingRow(
                    candidate.title,
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                    value = candidate.year?.toString(),
                    description = candidate.overview ?: candidate.originalTitle,
                    onClick = { sheet.dismiss { onChoose(candidate) } },
                )
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
private const val CHOOSE_ERROR = "choose-error"

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
    /** Why the last suggestion chosen from the list was not saved, said while its title ([chooseErrorFor]) is listed. */
    val chooseError: UiError? = null,
    val chooseErrorFor: String? = null,
)

/**
 * The titles to identify, read again as the library changes; a suggestion chosen from the list is saved in [work],
 * which outlives the screen, so a show is never left half matched.
 */
class ToIdentifyViewModel(private val provider: () -> MediaProvider, private val work: CoroutineScope) : ViewModel() {
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
                .onSuccess { titles -> _state.update { it.listing(titles) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.reported(TAG, "Titles to identify")) } }
        }
    }

    /** Identifies [title] as [candidate], one of its suggestions; the list then reads again without it. */
    fun choose(title: ToIdentify, candidate: MatchCandidate) {
        _state.update { it.copy(chooseError = null, chooseErrorFor = null) }
        work.launch {
            suspendRunCatching { provider().editor?.identify(title.item, candidate) }
                .onSuccess { load() }
                .onFailure { e ->
                    val error = e.reported(TAG, "Identification of ${title.item.id} as ${candidate.id}")
                    _state.update { it.copy(chooseError = error, chooseErrorFor = title.item.id) }
                }
        }
    }

    /** [titles] read; a suggestion not saved is no longer said once its title leaves them, identified otherwise. */
    private fun ToIdentifyState.listing(titles: List<ToIdentify>): ToIdentifyState {
        val failed = chooseErrorFor?.takeIf { id -> titles.any { it.item.id == id } }
        val notSaved = chooseError.takeIf { failed != null }
        return copy(titles = titles, loading = false, error = null, chooseError = notSaved, chooseErrorFor = failed)
    }

    companion object {
        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ToIdentifyViewModel(app::provider, app.scope) }
        }
    }
}
