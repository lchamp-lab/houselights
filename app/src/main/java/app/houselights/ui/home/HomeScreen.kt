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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.rememberRowState
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
import app.houselights.ui.design.components.CollectionLight
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.util.ReloadOnLibraryChange
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
    ReloadOnLibraryChange { vm.load() }
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
            // Nothing selected (focus on the tabs): no film on the screen, the room takes the
            // colours of the row the viewer was browsing.
            val row = state.sections.getOrNull(vm.lastFocus.first) ?: state.sections.firstOrNull()
            val rowPosters = remember(row) { row?.items.orEmpty().take(ROW_TONES).mapNotNull { it.poster ?: it.backdrop } }
            CollectionLight(rowPosters, lit = shown == null)
            Column(Modifier.fillMaxSize().houseLit(LocalHouseLights.current)) {
                TopBar(libraries = state.libraries, onLibrary = onOpenLibrary, onSettings = onOpenSettings)
                HeroInfo(shown, Modifier.padding(start = HlSpace.safeH, top = HlSpace.xs).height(HlLayout.hero))
                Spacer(Modifier.height(HlSpace.s))
                when {
                    state.loading && state.sections.isEmpty() -> SkeletonRows()
                    state.error != null && state.sections.isEmpty() -> ErrorMessage(state.error!!, vm::load)
                    state.sections.isEmpty() && scan.running ->
                        Message(stringResource(R.string.home_scanning, scan.processed, scan.total))
                    state.sections.isEmpty() && settings.source == SourceConfig.Local ->
                        Message(stringResource(R.string.home_empty_local), stringResource(R.string.nav_settings), onOpenSettings)
                    state.sections.isEmpty() -> Message(stringResource(R.string.empty))
                    else -> Sections(
                        sections = state.sections,
                        initialFocus = vm.lastFocus,
                        onOpen = onOpenItem,
                        onFocus = { section, index, item ->
                            vm.lastFocus = section to index
                            focused = item
                        },
                        onLeave = { focused = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(libraries: List<Library>, onLibrary: (Library) -> Unit, onSettings: () -> Unit) {
    // Where you are among the libraries, in one track; settings apart, as an action.
    Row(
        Modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV),
        verticalAlignment = Alignment.Top,
    ) {
        // The white slides to the library chosen, then it opens; back home, "Accueil" is lit again.
        var current by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        HlSegmented(
            options = listOf(stringResource(R.string.nav_home)) + libraries.map { it.title ?: libraryName(it.kind) },
            selected = current,
            onSelect = { i ->
                if (i > 0 && current == 0) {
                    current = i
                    scope.launch {
                        delay(HlMotion.STANDARD.toLong())
                        onLibrary(libraries[i - 1])
                    }
                }
            },
        )
        Spacer(Modifier.weight(1f))
        HlIconButton(Icons.Filled.Settings, stringResource(R.string.nav_settings), onClick = onSettings)
    }
}

@Composable
private fun Sections(
    sections: List<HomeSection>,
    initialFocus: Pair<Int, Int>,
    onOpen: (MediaItem) -> Unit,
    onFocus: (section: Int, index: Int, item: MediaItem) -> Unit,
    /** Focus left the rows (for the tabs above): nothing is selected anymore. */
    onLeave: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    // If the remembered card no longer exists (data reloaded), start from the first one.
    val (focusSection, focusIndex) = initialFocus
        .takeIf { (s, i) -> i < (sections.getOrNull(s)?.items?.size ?: 0) } ?: (0 to 0)
    LaunchedEffect(sections.isNotEmpty()) {
        if (sections.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().onFocusChanged { if (!it.hasFocus) onLeave() },
        contentPadding = PaddingValues(bottom = HlSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(HlSpace.xs),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            item(key = "${section.type}-${section.libraryId}") {
                MediaRow(
                    header = sectionTitle(section),
                    spacing = if (section.landscape) HlSpace.l else HlSpace.m,
                    state = rememberRowState(section.items.map { it.id }),
                ) {
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
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    HlEmptyState(
        text,
        Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
        action = action,
        actionIcon = Icons.Filled.Settings,
        onAction = onAction,
    )
}

/** The library could not be read: what happened, and a way to try again. */
@Composable
fun ErrorMessage(error: String, onRetry: () -> Unit) {
    HlEmptyState(
        error,
        Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
        title = stringResource(R.string.error_unreachable),
        action = stringResource(R.string.retry),
        actionIcon = Icons.Filled.Refresh,
        onAction = onRetry,
    )
}

/** Posters of a row that lend their colours to the room, about what a row shows at once. */
private const val ROW_TONES = 7
