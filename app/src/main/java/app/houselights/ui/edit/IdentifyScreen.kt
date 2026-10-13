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
import app.houselights.R
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlResultItem
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.toPx
import coil.compose.AsyncImage
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
            StatusPanel(
                icon = HlIcons.Search,
                label = stringResource(if (isShow) R.string.identify_folder else R.string.identify_file),
                value = identity?.fileName ?: state.item?.title ?: "…",
                lit = identity?.current != null,
                result = null,
                resultIsError = false,
            )
            if (identity != null) {
                Spacer(Modifier.height(HlSpace.l))
                FieldLabel(stringResource(R.string.identify_current))
                CurrentMatch(identity.current, identity.manual)
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
                modifier = Modifier.width(112.dp),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Search,
                onImeAction = vm::search,
            )
        }
        HlButton(stringResource(R.string.search), onClick = vm::search, icon = HlIcons.Search, emphasis = Emphasis.Primary)

        val results = state.results
        when {
            state.identifying -> Status(stringResource(R.string.identifying))
            state.searching -> Status(stringResource(R.string.searching))
            state.error != null -> Status(stringResource(state.error!!.message), problem = true)
            results == null -> Unit
            results.isEmpty() -> Status(stringResource(R.string.search_empty))
            else -> Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                SectionHeader(pluralStringResource(R.plurals.result_count, results.size, results.size))
                results.forEachIndexed { i, candidate ->
                    HlResultItem(
                        poster = candidate.poster,
                        title = candidate.title,
                        meta = listOfNotNull(candidate.year?.toString(), candidate.originalTitle).joinToString("   ").ifEmpty { null },
                        overview = candidate.overview,
                        onClick = { identify(candidate) },
                        mark = stringResource(R.string.result_current).takeIf { candidate.id == identity?.current?.id },
                        modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                    )
                }
            }
        }
    }
}

/** The title this one is matched with now, and who chose it. */
@Composable
private fun CurrentMatch(current: MatchCandidate?, manual: Boolean) {
    val c = Hl.colors
    if (current == null) {
        Text(stringResource(R.string.identify_none), style = Hl.type.body, color = c.contentSecondary)
        return
    }
    val posterWidth = 72.dp
    Row {
        Box(Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(HlShapes.artwork).background(c.surface)) {
            current.poster?.url(posterWidth.toPx())?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(HlSpace.m))
        Column {
            Text(current.title, style = Hl.type.title, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(current.year?.toString(), current.originalTitle).joinToString("   ").ifEmpty { null }?.let {
                Spacer(Modifier.height(HlSpace.xxs))
                Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(HlSpace.s))
            Badge(stringResource(if (manual) R.string.identify_manual else R.string.identify_automatic), lit = manual)
        }
    }
}

@Composable
private fun Status(text: String, problem: Boolean = false) {
    Text(text, style = Hl.type.body, color = if (problem) Hl.colors.tungsten else Hl.colors.contentSecondary)
}
