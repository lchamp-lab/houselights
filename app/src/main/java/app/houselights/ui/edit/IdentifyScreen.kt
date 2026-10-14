package app.houselights.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.ui.design.HlLayout
import app.houselights.R
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlResultItem
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.toPx
import coil.compose.AsyncImage
import app.houselights.ui.home.ErrorIntertitle
import kotlinx.coroutines.launch

/** Matching a title with the right TMDB entry, when automatic matching got it wrong. */
@Composable
fun IdentifyScreen(itemId: String, onDone: () -> Unit) {
    val app = appContainer()
    val vm = viewModel(key = "identify-$itemId") { IdentifyViewModel(app, itemId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val identity = state.identity
    val isShow = state.item?.kind == MediaKind.SERIES

    fun identify(candidate: MatchCandidate?) {
        scope.launch { if (vm.identify(candidate)) onDone() }
    }

    // The first results of the automatic search take focus: the right title is often first.
    val firstResult = remember { FocusRequester() }
    var focusedResults by remember { mutableStateOf(false) }
    LaunchedEffect(state.results) {
        if (!focusedResults && !state.results.isNullOrEmpty()) {
            focusedResults = true
            withFrameNanos { }
            runCatching { firstResult.requestFocus() }
        }
    }

    HlFormScaffold(
        title = stringResource(R.string.identify),
        description = stringResource(R.string.identify_desc),
        aside = {
            // The file or folder, then what it is matched with now, on one sheet.
            StatusPanel(
                state = if (identity?.current != null) StatusState.On else StatusState.Off,
                stateText = stringResource(if (identity?.current != null) R.string.identify_state_matched else R.string.identify_none),
                label = stringResource(if (isShow) R.string.identify_folder else R.string.identify_file),
                value = identity?.fileName ?: state.item?.title ?: stringResource(R.string.placeholder_pending),
                valueStyle = Hl.type.entry,
                content = identity?.current?.let { current ->
                    {
                        Text(stringResource(R.string.identify_current), style = Hl.type.caption, color = Hl.colors.contentSecondary)
                        Spacer(Modifier.height(HlSpace.s))
                        CurrentMatch(current, identity.manual)
                    }
                },
            )
            if (identity != null) {
                if (identity.manual) {
                    Spacer(Modifier.height(HlSpace.m))
                    HlButton(stringResource(R.string.identify_reset), onClick = { identify(null) })
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Bottom) {
            HlTextField(
                label = stringResource(R.string.label_title),
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.search_placeholder),
                imeAction = ImeAction.Search,
                onImeAction = vm::search,
            )
            HlTextField(
                label = stringResource(R.string.label_year),
                value = state.year,
                onValueChange = vm::setYear,
                modifier = Modifier.width(HlLayout.fieldShort),
                placeholder = stringResource(R.string.placeholder_year),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Search,
                onImeAction = vm::search,
            )
        }
        HlButton(stringResource(R.string.search), onClick = vm::search, icon = HlIcons.Search, emphasis = Emphasis.Primary)

        // What is going on shows above the results, which stay in place, and so does focus. With no
        // result to keep, a failure takes their place, with what fixes it.
        val error = state.error
        val results = state.results
        when {
            state.identifying -> HlStatus(StatusState.Pending, stringResource(R.string.identifying))
            state.searching -> HlStatus(StatusState.Pending, stringResource(R.string.searching))
            error != null && results.isNullOrEmpty() -> ErrorIntertitle(error, vm::search)
            error != null -> HlStatus(StatusState.Attention, stringResource(error.title), detail = stringResource(error.message))
        }
        when {
            results == null -> Unit
            results.isEmpty() -> if (!state.searching && error == null) {
                HlEmptyState(stringResource(R.string.search_empty), message = stringResource(R.string.search_empty_hint))
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                SectionHeader(pluralStringResource(R.plurals.result_count, results.size, results.size))
                results.forEachIndexed { i, candidate ->
                    HlResultItem(
                        poster = candidate.poster,
                        title = candidate.title,
                        facts = listOf(candidate.year?.toString(), candidate.originalTitle),
                        overview = candidate.overview,
                        onClick = { identify(candidate) },
                        mark = stringResource(R.string.result_current).takeIf { candidate.id == identity?.current?.id },
                        busy = stringResource(R.string.identifying).takeIf { state.identifying && candidate.id == state.chosenId },
                        modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                    )
                }
            }
        }
    }
}

/** The title this one is matched with now, and who chose it. */
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
            Badge(stringResource(if (manual) R.string.identify_manual else R.string.identify_automatic), lit = manual)
        }
    }
}
