package app.houselights.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlResultItem
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.rememberFieldWidth
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.calmAfter
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.titleText

/**
 * Matching a title with the right TMDB entry, when automatic matching got it wrong or left it to identify, its
 * suggestions shown at once. In the [queue] (opened from the titles to identify), once done or passed the next title
 * opens ([onNext], null after the last); else [onDone].
 */
@Composable
fun IdentifyScreen(
    itemId: String,
    onDone: () -> Unit,
    queue: Boolean = false,
    onNext: (String?) -> Unit = { onDone() },
    vm: IdentifyViewModel = viewModel(key = "identify-$itemId", factory = IdentifyViewModel.factory(appContainer(), itemId, queue)),
) {
    IdentifyPage(vm, onDone, queue, onNext)
}

/**
 * Identifier as [vm] has it: the file or folder and how it stands beside the search, its sentence following why a title
 * is to identify, its suggestions at once, the best focused; a title to identify can be left as it is (« Laisser tel
 * quel »), one left so put back to automatic. In the [queue], « Passer » comes first, nearest the results.
 */
@Composable
internal fun IdentifyPage(vm: IdentifyViewModel, onDone: () -> Unit, queue: Boolean = false, onNext: (String?) -> Unit = { onDone() }) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Once matched, return to the title when resumed (after a screen saver or dialog); the match outlives Back. In the
    // queue, the next title opens.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnDone by rememberUpdatedState(onDone)
    val currentOnNext by rememberUpdatedState(onNext)
    LaunchedEffect(state.done) {
        if (state.done) lifecycle.withResumed { if (queue) currentOnNext(state.nextId) else currentOnDone() }
    }
    // In the queue, Left from the results reaches « Passer », the aside's action nearest them.
    val skip = remember { FocusRequester() }.takeIf { queue }

    // The first results of the automatic search take focus: the right title is often first.
    val firstResult = remember { FocusRequester() }
    var focusedResults by remember { mutableStateOf(false) }
    LaunchedEffect(state.results) {
        if (!focusedResults && !state.results.isNullOrEmpty()) {
            focusedResults = true
            withFrameNanos { }
            firstResult.tryRequestFocus()
        }
    }

    // The results come under focus: a second OK given too soon, the last screen's, chooses none of them unseen.
    Box(Modifier.fillMaxSize().calmAfter(state.results != null)) {
        HlFormScaffold(
            title = stringResource(R.string.identify),
            description = stringResource(descriptionOf(state.identity)),
            aside = { Aside(state, skip, onSkip = vm::skip, onKeep = vm::keep, onReset = { vm.identify(null) }) },
        ) {
            SearchFields(state, onQueryChange = vm::setQuery, onYearChange = vm::setYear, onSearch = vm::search)
            Results(state, firstResult, skip, onRetry = vm::retry, onIdentify = vm::identify)
        }
    }
}

/** What Identifier does, by why a title is to identify: TMDB hesitated, found nothing, or the user left it so. */
@StringRes
private fun descriptionOf(identity: Identity?): Int = when {
    identity?.doubt == MatchDoubt.UNSURE -> R.string.identify_desc_unsure
    identity?.doubt != null || identity?.kept == true -> R.string.identify_desc_none
    else -> R.string.identify_desc
}

/** How the title stands, by its light: a match chosen by hand is lit, as is a title left as it is; to identify, its ring. */
private fun stateOf(identity: Identity?): StatusState = when {
    identity?.kept == true -> StatusState.On
    identity?.current == null -> StatusState.Off
    identity.manual -> StatusState.On
    else -> StatusState.Done
}

/** How the title stands, in words: why it is to identify, left as it is, identified, or not yet. */
@StringRes
private fun stateTextOf(identity: Identity?): Int {
    val doubt = identity?.doubt
    return when {
        doubt != null -> doubtText(doubt)
        identity?.kept == true -> R.string.identify_kept
        identity?.current != null -> R.string.identify_state_matched
        else -> R.string.identify_none
    }
}

/**
 * The file or folder and its current match, in one panel; under it, in the queue, « Passer » ([skip], [onSkip]), then
 * what leaves the title as it is ([onKeep]) or puts it back ([onReset]).
 */
@Composable
private fun ColumnScope.Aside(
    state: IdentifyState,
    skip: FocusRequester?,
    onSkip: () -> Unit,
    onKeep: () -> Unit,
    onReset: () -> Unit,
) {
    val identity = state.identity
    StatusPanel(
        state = stateOf(identity),
        stateText = stringResource(stateTextOf(identity)),
        label = stringResource(if (state.item?.kind == MediaKind.SERIES) R.string.identify_folder else R.string.identify_file),
        value = identity?.fileName ?: state.item?.title ?: stringResource(R.string.placeholder_pending),
        valueStyle = Hl.type.entry,
        content = identity?.current?.let { current ->
            {
                Text(stringResource(R.string.identify_current), style = Hl.type.meta, color = Hl.colors.contentSecondary)
                Spacer(Modifier.height(HlSpace.s))
                CurrentMatch(current, identity.manual)
            }
        },
    )
    if (identity == null) return
    // Under the panel, then a step apart.
    var gap = HlSpace.m
    if (skip != null) {
        Spacer(Modifier.height(gap))
        HlButton(
            stringResource(R.string.identify_skip),
            onClick = onSkip,
            modifier = Modifier.focusRequester(skip),
            busy = state.skipping,
        )
        gap = HlSpace.s
    }
    // A home video TMDB will never know: no longer searched for, nor counted.
    if (identity.doubt != null) {
        Spacer(Modifier.height(gap))
        HlButton(stringResource(R.string.identify_keep), onClick = onKeep, busy = state.keeping)
        gap = HlSpace.s
    }
    if (identity.manual || identity.kept) {
        Spacer(Modifier.height(gap))
        HlButton(stringResource(R.string.identify_reset), onClick = onReset)
    }
}

/** Every year as wide as four of one digit, the widest of which sets the year field's measure. */
private val YEARS = ('0'..'9').map { it.toString().repeat(IdentifyViewModel.YEAR_DIGITS) }

/** The title and year to search for, and Rechercher. */
@Composable
private fun SearchFields(
    state: IdentifyState,
    onQueryChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Bottom) {
        HlTextField(
            label = stringResource(R.string.label_title),
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = stringResource(R.string.search_placeholder),
            imeAction = ImeAction.Search,
            onImeAction = onSearch,
        )
        // Empty, it searches every year, as « (facultatif) » says; as wide as its whole name, or as a year, « OK saisir »
        // and its cross, at the text size in use, the title taking the rest.
        val year = stringResource(R.string.label_year)
        HlTextField(
            label = year,
            value = state.year,
            onValueChange = onYearChange,
            modifier = Modifier.width(rememberFieldWidth(year, YEARS, least = HlLayout.fieldShort, optional = true)),
            placeholder = stringResource(R.string.placeholder_year),
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Search,
            onImeAction = onSearch,
            optional = true,
        )
    }
    HlButton(stringResource(R.string.search), onClick = onSearch, icon = HlIcons.Search)
}

/**
 * Where the search stands, then its results, or the suggestions automatic matching kept, the first one [first] (focus
 * goes to it); OK matches the title with one ([onIdentify]). Left from one goes to « Passer » ([skip]) in the queue.
 */
@Composable
private fun Results(
    state: IdentifyState,
    first: FocusRequester,
    skip: FocusRequester?,
    onRetry: () -> Unit,
    onIdentify: (MatchCandidate) -> Unit,
) {
    // Status shows above the results, which keep their place and focus; with none, a failure replaces them.
    val error = state.error
    val results = state.results
    // While retrying (the title or the search), the failure stays in place with its button busy.
    val retrying = state.searching || (state.item == null && error == null)
    val failure = retainedError(error?.takeIf { results.isNullOrEmpty() }, retrying)
    when {
        failure != null -> HlErrorState(failure, onRetry, retrying = retrying)
        state.identifying -> HlStatus(StatusState.Pending, stringResource(R.string.identifying))
        state.searching -> HlStatus(StatusState.Pending, stringResource(R.string.searching))
        error != null -> HlStatus(StatusState.Attention, error.titleText(), detail = stringResource(error.message))
    }
    when {
        results == null -> Unit
        results.isEmpty() -> if (!state.searching && error == null) {
            HlEmptyState(stringResource(R.string.search_empty), message = stringResource(R.string.search_empty_hint))
        }
        else -> ResultList(results, state, first, skip, onIdentify)
    }
}

/** The results, under « Suggestions » for automatic matching's, else under their count; the [first] takes focus. */
@Composable
private fun ResultList(
    results: List<MatchCandidate>,
    state: IdentifyState,
    first: FocusRequester,
    skip: FocusRequester?,
    onIdentify: (MatchCandidate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        SectionHeader(
            if (state.suggested) {
                stringResource(R.string.identify_suggestions)
            } else {
                pluralStringResource(R.plurals.result_count, results.size, results.size)
            },
        )
        results.forEachIndexed { i, candidate ->
            HlResultItem(
                poster = candidate.poster,
                title = candidate.title,
                facts = listOf(candidate.year?.toString(), candidate.originalTitle),
                overview = candidate.overview,
                onClick = { onIdentify(candidate) },
                mark = stringResource(R.string.result_current).takeIf { candidate.id == state.identity?.current?.id },
                busy = stringResource(R.string.identifying).takeIf { state.identifying && candidate.id == state.chosenId },
                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).then(
                    if (skip != null) Modifier.focusProperties { left = skip } else Modifier,
                ),
            )
        }
    }
}

/** The current match, and whether it was chosen by hand. */
@Composable
private fun CurrentMatch(current: MatchCandidate, manual: Boolean) {
    val c = Hl.colors
    Row {
        PosterThumb(current.poster, width = HlSize.posterThumbLarge)
        Spacer(Modifier.width(HlSpace.m))
        Column {
            Text(current.title, style = Hl.type.cardTitle, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(HlSpace.xxs))
            FactRow(listOf(current.year?.toString(), current.originalTitle))
            Spacer(Modifier.height(HlSpace.s))
            // A manual match is a state, with its status light; automatic is plain text.
            if (manual) {
                HlStatus(StatusState.On, stringResource(R.string.identify_manual))
            } else {
                Text(stringResource(R.string.identify_automatic), style = Hl.type.meta, color = c.contentSecondary)
            }
        }
    }
}
