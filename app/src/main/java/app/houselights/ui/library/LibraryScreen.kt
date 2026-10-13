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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.Library
import app.houselights.core.MediaItem
import app.houselights.core.SortOption
import app.houselights.ui.components.Chip
import app.houselights.ui.components.HeroInfo
import app.houselights.ui.components.ImmersiveBackdrop
import app.houselights.ui.components.PosterCard
import app.houselights.ui.components.SafeH
import app.houselights.ui.components.SafeV
import app.houselights.ui.components.appContainer
import app.houselights.ui.components.screenWidthPx
import app.houselights.ui.components.toPx
import app.houselights.ui.theme.Palette
import kotlinx.coroutines.delay

private const val COLUMNS = 7

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
        delay(220)
        shown = focused
    }

    val screenPx = screenWidthPx()
    val posterPx = 128.dp.toPx()
    val gridFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.isNotEmpty()) {
        if (state.items.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            runCatching { gridFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ImmersiveBackdrop(shown?.backdrop?.url(screenPx), bottomWeight = 0.35f)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = SafeH, end = SafeH, top = SafeV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
                Spacer(Modifier.width(10.dp))
                if (state.total > 0) {
                    Text("${state.total}", style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
                }
                Spacer(Modifier.weight(1f))
                SortOption.entries.forEach { opt ->
                    Chip(sortLabel(opt), selected = state.sort == opt, onClick = { vm.setSort(opt) })
                }
                Spacer(Modifier.width(12.dp))
                Chip(stringResource(R.string.filter_unwatched), selected = state.unplayedOnly, onClick = vm::toggleUnplayed)
            }
            HeroInfo(
                item = shown,
                overviewLines = 2,
                modifier = Modifier.padding(start = SafeH, top = 8.dp).height(176.dp),
            )
            when {
                state.loading && state.items.isEmpty() ->
                    Text(stringResource(R.string.loading), color = Palette.TextDim, modifier = Modifier.padding(SafeH))
                state.items.isEmpty() ->
                    Text(state.error ?: stringResource(R.string.empty), color = Palette.TextDim, modifier = Modifier.padding(SafeH))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    contentPadding = PaddingValues(start = SafeH, end = SafeH, top = 16.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                        PosterCard(
                            item = item,
                            imageUrl = item.poster?.url(posterPx),
                            onClick = { onOpenItem(item) },
                            onFocused = {
                                focused = item
                                if (index > state.items.size - COLUMNS * 3) vm.loadMore()
                            },
                            width = null,
                            modifier = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier,
                        )
                    }
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
