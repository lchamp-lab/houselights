package app.houselights.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SourceConfig
import app.houselights.ui.components.ActionButton
import app.houselights.ui.components.Chip
import app.houselights.ui.components.HeroInfo
import app.houselights.ui.components.ImmersiveBackdrop
import app.houselights.ui.components.LandscapeCard
import app.houselights.ui.components.PosterCard
import app.houselights.ui.components.SafeH
import app.houselights.ui.components.SafeV
import app.houselights.ui.components.SectionTitle
import app.houselights.ui.components.appContainer
import app.houselights.ui.components.formatTimecode
import app.houselights.ui.components.libraryName
import app.houselights.ui.components.screenWidthPx
import app.houselights.ui.components.sectionTitle
import app.houselights.ui.components.toPx
import app.houselights.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpenItem: (MediaItem) -> Unit,
    onOpenLibrary: (Library) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = appContainer()
    val vm = viewModel { HomeViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scan by app.scanner.state.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()

    var focused by remember { mutableStateOf<MediaItem?>(null) }
    // The backdrop only changes once focus settles: no image load on every scroll step.
    var shown by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(focused) {
        delay(220)
        shown = focused
    }

    val screenPx = screenWidthPx()

    Box(Modifier.fillMaxSize()) {
        ImmersiveBackdrop(shown?.backdrop?.url(screenPx), bottomWeight = 0.42f)

        Column(Modifier.fillMaxSize()) {
            TopBar(libraries = state.libraries, onLibrary = onOpenLibrary, onSettings = onOpenSettings)
            HeroInfo(
                item = shown,
                modifier = Modifier.padding(start = SafeH, top = 12.dp).height(230.dp),
            )
            Spacer(Modifier.height(16.dp))
            when {
                state.loading && state.sections.isEmpty() -> Message(stringResource(R.string.loading))
                state.error != null && state.sections.isEmpty() -> ErrorMessage(state.error!!, vm::load)
                state.sections.isEmpty() && scan.running ->
                    Message(stringResource(R.string.home_scanning, scan.processed, scan.total))
                state.sections.isEmpty() && settings.source == SourceConfig.Local ->
                    Message(stringResource(R.string.home_empty_local))
                state.sections.isEmpty() -> Message(stringResource(R.string.empty))
                else -> Sections(
                    sections = state.sections,
                    initialFocus = vm.lastFocus,
                    onOpen = onOpenItem,
                    onFocus = { section, index, item ->
                        vm.lastFocus = section to index
                        focused = item
                    },
                )
            }
        }
    }
}

@Composable
private fun TopBar(libraries: List<Library>, onLibrary: (Library) -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = SafeH, end = SafeH, top = SafeV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "HOUSELIGHTS",
            style = MaterialTheme.typography.titleSmall,
            color = Palette.Accent,
            modifier = Modifier.padding(end = 18.dp),
        )
        libraries.forEach { library ->
            Chip(library.title ?: libraryName(library.kind), selected = false, onClick = { onLibrary(library) })
        }
        Spacer(Modifier.weight(1f))
        Chip(stringResource(R.string.nav_settings), selected = false, onClick = onSettings)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Sections(
    sections: List<HomeSection>,
    initialFocus: Pair<Int, Int>,
    onOpen: (MediaItem) -> Unit,
    onFocus: (section: Int, index: Int, item: MediaItem) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val posterPx = 124.dp.toPx()
    val thumbPx = 236.dp.toPx()

    // If the remembered card no longer exists (data reloaded), start from the first one.
    val (focusSection, focusIndex) = initialFocus
        .takeIf { (s, i) -> i < (sections.getOrNull(s)?.items?.size ?: 0) } ?: (0 to 0)
    LaunchedEffect(sections.isNotEmpty()) {
        if (sections.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            item(key = "${section.type}-${section.libraryName}-${section.libraryKind}") {
                Column {
                    SectionTitle(sectionTitle(section), Modifier.padding(start = SafeH, bottom = 4.dp))
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(horizontal = SafeH, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (section.landscape) 20.dp else 16.dp),
                    ) {
                        itemsIndexed(section.items, key = { _, it -> it.id }) { index, item ->
                            val mod = if (sectionIndex == focusSection && index == focusIndex) {
                                Modifier.focusRequester(firstFocus)
                            } else Modifier
                            if (section.landscape) {
                                val episode = item.kind == MediaKind.EPISODE
                                LandscapeCard(
                                    item = item,
                                    imageUrl = item.landscape?.url(thumbPx),
                                    title = if (episode) item.seriesTitle ?: item.title else item.title,
                                    subtitle = when {
                                        episode -> listOfNotNull(item.episodeLabel, item.title).joinToString(" · ")
                                        item.user.positionMs > 0 ->
                                            stringResource(R.string.resume_at, formatTimecode(item.user.positionMs))
                                        else -> item.year?.toString()
                                    },
                                    onClick = { onOpen(item) },
                                    onFocused = { onFocus(sectionIndex, index, item) },
                                    modifier = mod,
                                )
                            } else {
                                PosterCard(
                                    item = item,
                                    imageUrl = item.poster?.url(posterPx),
                                    onClick = { onOpen(item) },
                                    onFocused = { onFocus(sectionIndex, index, item) },
                                    modifier = mod,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = Palette.TextDim, modifier = Modifier.padding(start = SafeH).width(640.dp))
}

@Composable
fun ErrorMessage(error: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.padding(start = SafeH).fillMaxHeight()) {
        Text(stringResource(R.string.error_unreachable), style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        Text(error, color = Palette.TextDim, modifier = Modifier.width(560.dp))
        Spacer(Modifier.height(16.dp))
        ActionButton(stringResource(R.string.retry), onRetry, Modifier.focusRequester(focus), icon = Icons.Filled.Refresh)
    }
}
