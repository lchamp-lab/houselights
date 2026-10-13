package app.houselights.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.Library
import app.houselights.core.MediaItem
import app.houselights.core.SortOption
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.HlTab
import app.houselights.ui.design.components.LocalGroupFocused
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.util.appContainer
import kotlinx.coroutines.delay

private const val COLUMNS = 7

/** A wall of posters, with the focused title named in the header. */
@Composable
fun LibraryScreen(
    library: Library,
    title: String,
    onOpenItem: (MediaItem) -> Unit,
) {
    val app = appContainer()
    val vm = viewModel(key = "library-${library.id}") { LibraryViewModel(app, library) }
    val state by vm.state.collectAsStateWithLifecycle()

    var focused by remember { mutableStateOf<MediaItem?>(null) }
    var shown by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(focused) {
        delay(HlMotion.SETTLE)
        shown = focused
    }
    val gridFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.isNotEmpty()) {
        if (state.items.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            runCatching { gridFocus.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalAmbient provides rememberAmbientColor(shown?.backdrop)) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(shown?.backdrop, BackdropStyle.Quiet)
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH - HlSpace.m, top = HlSpace.safeV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScreenTitle(title)
                    Spacer(Modifier.width(HlSpace.m))
                    if (state.total > 0) {
                        Text(
                            pluralStringResource(R.plurals.title_count, state.total, state.total),
                            style = Hl.type.meta,
                            color = Hl.colors.contentTertiary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SortOption.entries.forEach { opt ->
                        HlTab(sortLabel(opt), selected = state.sort == opt, onClick = { vm.setSort(opt) })
                    }
                    Spacer(Modifier.width(HlSpace.m))
                    HlTab(stringResource(R.string.filter_unwatched), selected = state.unplayedOnly, onClick = vm::toggleUnplayed)
                }
                // What is under the cursor, without taking space from the wall.
                Row(
                    Modifier.padding(start = HlSpace.safeH, top = HlSpace.xs).height(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shown?.let {
                        Text(it.title, style = Hl.type.label, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(HlSpace.m))
                        MetaLine(it, showGenres = false)
                    }
                }
                when {
                    state.loading && state.items.isEmpty() -> Unit
                    state.items.isEmpty() -> Text(
                        state.error ?: stringResource(R.string.empty),
                        style = Hl.type.body,
                        color = Hl.colors.contentSecondary,
                        modifier = Modifier.padding(HlSpace.safeH),
                    )
                    else -> CompositionLocalProvider(LocalGroupFocused provides gridHasFocus) { LazyVerticalGrid(
                        columns = GridCells.Fixed(COLUMNS),
                        contentPadding = PaddingValues(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.m, bottom = HlSpace.xxl),
                        horizontalArrangement = Arrangement.spacedBy(HlSpace.l),
                        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                        modifier = Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus },
                    ) {
                        itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                            PosterCard(
                                item = item,
                                onClick = { onOpenItem(item) },
                                width = null,
                                onFocused = {
                                    focused = item
                                    if (index > state.items.size - COLUMNS * 3) vm.loadMore()
                                },
                                modifier = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier,
                            )
                        }
                    } }
                }
            }
        }
    }
}

@Composable
private fun sortLabel(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.TITLE -> R.string.sort_title
        SortOption.ADDED -> R.string.sort_added
        SortOption.YEAR -> R.string.sort_year
        SortOption.RATING -> R.string.sort_rating
    }
)
