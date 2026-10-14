package app.houselights.ui.home

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaItem
import app.houselights.core.SourceConfig
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlTab
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.sectionTitle
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
        delay(HlMotion.SETTLE)
        shown = focused
    }
    val ambient = rememberAmbientColor(shown?.backdrop)

    CompositionLocalProvider(LocalAmbient provides ambient) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(shown?.backdrop, BackdropStyle.Hero)
            Column(Modifier.fillMaxSize().houseLit(LocalHouseLights.current)) {
                TopBar(libraries = state.libraries, onLibrary = onOpenLibrary, onSettings = onOpenSettings)
                HeroInfo(shown, Modifier.padding(start = HlSpace.safeH, top = HlSpace.xs).height(200.dp))
                Spacer(Modifier.height(HlSpace.s))
                when {
                    state.loading && state.sections.isEmpty() -> SkeletonRows()
                    state.error != null && state.sections.isEmpty() -> ErrorMessage(state.error!!, vm::load)
                    state.sections.isEmpty() && scan.running ->
                        Message(stringResource(R.string.home_scanning, scan.processed, scan.total))
                    state.sections.isEmpty() && settings.source == SourceConfig.Local ->
                        Message(stringResource(R.string.home_empty_local)) {
                            HlButton(stringResource(R.string.nav_settings), onOpenSettings, icon = Icons.Filled.Settings)
                        }
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
}

@Composable
private fun TopBar(libraries: List<Library>, onLibrary: (Library) -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = HlSpace.safeH - HlSpace.m, end = HlSpace.safeH - HlSpace.m, top = HlSpace.safeV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs),
    ) {
        HlTab(stringResource(R.string.nav_home), selected = true, onClick = {})
        libraries.forEach { library ->
            HlTab(library.title ?: libraryName(library.kind), selected = false, onClick = { onLibrary(library) })
        }
        Spacer(Modifier.weight(1f))
        HlTab("", selected = false, onClick = onSettings, icon = Icons.Filled.Settings)
    }
}

@Composable
private fun Sections(
    sections: List<HomeSection>,
    initialFocus: Pair<Int, Int>,
    onOpen: (MediaItem) -> Unit,
    onFocus: (section: Int, index: Int, item: MediaItem) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    // If the remembered card no longer exists (data reloaded), start from the first one.
    val (focusSection, focusIndex) = initialFocus
        .takeIf { (s, i) -> i < (sections.getOrNull(s)?.items?.size ?: 0) } ?: (0 to 0)
    LaunchedEffect(sections.isNotEmpty()) {
        if (sections.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = HlSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(HlSpace.xs),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            item(key = "${section.type}-${section.libraryName}-${section.libraryKind}") {
                MediaRow(header = sectionTitle(section), spacing = if (section.landscape) HlSpace.l else HlSpace.m) {
                    itemsIndexed(section.items, key = { _, it -> it.id }) { index, item ->
                        val mod = if (sectionIndex == focusSection && index == focusIndex) Modifier.focusRequester(firstFocus) else Modifier
                        val focus = { onFocus(sectionIndex, index, item) }
                        if (section.landscape) {
                            LandscapeCard(item, onClick = { onOpen(item) }, modifier = mod, onFocused = focus)
                        } else {
                            PosterCard(item, onClick = { onOpen(item) }, modifier = mod, onFocused = focus)
                        }
                    }
                }
            }
        }
    }
}

/** Same footprint as the real rows, so nothing jumps when data arrives. */
@Composable
private fun SkeletonRows() {
    Column(Modifier.padding(start = HlSpace.safeH, top = HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                repeat(8) { CardSkeleton(HlSize.poster, 2f / 3f) }
            }
        }
    }
}

@Composable
private fun Message(text: String, action: (@Composable () -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(action != null) { if (action != null) runCatching { focus.requestFocus() } }
    Column(Modifier.padding(start = HlSpace.safeH, top = HlSpace.l)) {
        Text(text, style = Hl.type.body, color = Hl.colors.contentSecondary, modifier = Modifier.widthIn(max = 560.dp))
        if (action != null) {
            Spacer(Modifier.height(HlSpace.l))
            Box(Modifier.focusRequester(focus)) { action() }
        }
    }
}

@Composable
fun ErrorMessage(error: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.padding(start = HlSpace.safeH, top = HlSpace.l)) {
        Text(stringResource(R.string.error_unreachable), style = Hl.type.title, color = Hl.colors.content)
        Spacer(Modifier.height(HlSpace.xs))
        Text(error, style = Hl.type.bodySmall, color = Hl.colors.contentSecondary, modifier = Modifier.widthIn(max = 560.dp))
        Spacer(Modifier.height(HlSpace.l))
        HlButton(stringResource(R.string.retry), onRetry, Modifier.focusRequester(focus), icon = Icons.Filled.Refresh, emphasis = Emphasis.Primary)
    }
}
