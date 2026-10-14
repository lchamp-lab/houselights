package app.houselights.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.HlLayout
import app.houselights.R
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.MediaItem
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.components.ArtworkChoiceCard
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.home.ErrorIntertitle
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.languageName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Choosing one piece of artwork. The page behind previews the focused choice where it will
 * be used: the backdrop behind the title, the logo above it. A poster is its own preview: no room
 * is kept for one, and the choices come larger, right under the header.
 */
@Composable
fun ArtworkScreen(itemId: String, kind: ArtworkKind, onDone: () -> Unit) {
    val app = appContainer()
    val vm = viewModel(key = "artwork-$itemId-$kind") { ArtworkViewModel(app, itemId, kind) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val item = state.item
    val choices = state.choices

    var focused by remember { mutableStateOf<ArtworkOption?>(null) }
    // The preview follows focus once it settles: no image load on every step.
    var shown by remember { mutableStateOf<ArtworkOption?>(null) }
    LaunchedEffect(focused) {
        delay(HlMotion.SETTLE)
        shown = focused
    }
    val preview = shown ?: choices?.selected
    val backdrop = if (kind == ArtworkKind.BACKDROP) preview?.image else item?.backdrop

    val ambient = rememberAmbientColor(backdrop)
    CompositionLocalProvider(LocalAmbient provides { ambient.value }) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(backdrop, if (kind == ArtworkKind.POSTER) BackdropStyle.Quiet else BackdropStyle.Full)
            Column(Modifier.fillMaxSize().padding(top = HlSpace.safeV, bottom = HlSpace.m)) {
                Header(kind, item, choices)
                if (kind == ArtworkKind.POSTER) {
                    Spacer(Modifier.height(HlSpace.m))
                } else {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = HlSpace.safeH, vertical = HlSpace.m),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (item != null) Preview(kind, item, preview)
                    }
                }
                when {
                    state.error != null -> ErrorIntertitle(state.error!!, vm::load, ChoicesPadding)
                    choices == null -> Skeletons(kind)
                    choices.options.isEmpty() -> HlEmptyState(stringResource(R.string.artwork_empty), ChoicesPadding, backHint = true)
                    else -> Choices(
                        kind = kind,
                        item = item!!,
                        choices = choices,
                        onFocus = { focused = it },
                        onChoose = { option -> scope.launch { if (vm.choose(option)) onDone() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(kind: ArtworkKind, item: MediaItem?, choices: ArtworkChoices?) {
    Column(Modifier.padding(horizontal = HlSpace.safeH)) {
        ScreenTitle(stringResource(kind.title))
        Spacer(Modifier.height(HlSpace.xxs))
        FactRow(listOf(item?.title, choices?.let { pluralStringResource(R.plurals.image_count, it.options.size, it.options.size) }))
        Spacer(Modifier.height(HlSpace.xs))
        val reading = Modifier.widthIn(max = HlLayout.readable)
        if (choices != null && !choices.onlineAvailable) {
            AttentionNote(stringResource(R.string.artwork_offline), reading, style = Hl.type.caption)
        } else {
            Text(stringResource(R.string.artwork_hint), style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = reading)
        }
    }
}

/** The title as the detail page will show it with the focused choice; a poster shows in its row. */
@Composable
private fun Preview(kind: ArtworkKind, item: MediaItem, preview: ArtworkOption?) {
    when (kind) {
        ArtworkKind.BACKDROP -> Column {
            // As the detail page shows it.
            TitleArt(item.title, item.logo, maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight)
            Spacer(Modifier.height(HlSpace.m))
            MetaLine(item)
        }
        ArtworkKind.LOGO -> TitleArt(
            title = item.title,
            logo = preview?.image,
            maxWidth = HlLayout.titleArtWidth,
            maxHeight = HlLayout.titleArtHeight,
        )
        ArtworkKind.POSTER -> Unit
    }
}

@Composable
private fun Choices(
    kind: ArtworkKind,
    item: MediaItem,
    choices: ArtworkChoices,
    onFocus: (ArtworkOption) -> Unit,
    onChoose: (ArtworkOption) -> Unit,
) {
    val selectedIndex = choices.options.indexOf(choices.selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Start on the image in use, once the row has laid it out.
        withFrameNanos { }
        runCatching { selectedFocus.requestFocus() }
    }
    MediaRow(header = null, state = listState) {
        itemsIndexed(choices.options, key = { _, o -> o.value }) { i, option ->
            val automatic = option == choices.automatic
            ArtworkChoiceCard(
                image = option.image,
                selected = option == choices.selected,
                title = if (automatic) stringResource(R.string.artwork_automatic) else describe(kind, option),
                caption = if (automatic) listOfNotNull(describe(kind, option), size(option)).joinToString(FACT_GAP) else size(option),
                onClick = { onChoose(option) },
                width = kind.cardWidth,
                aspectRatio = kind.aspectRatio,
                fit = kind == ArtworkKind.LOGO,
                placeholder = item.title.takeIf { option.origin == ArtworkOrigin.NONE },
                onFocused = { onFocus(option) },
                modifier = if (i == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
    }
}

/** What an option is: its language, or where it comes from. */
@Composable
private fun describe(kind: ArtworkKind, option: ArtworkOption): String = when (option.origin) {
    ArtworkOrigin.NONE -> stringResource(R.string.artwork_title_in_type)
    ArtworkOrigin.LOCAL_FILE -> stringResource(R.string.artwork_local_file)
    ArtworkOrigin.NFO -> stringResource(R.string.artwork_from_nfo)
    ArtworkOrigin.ONLINE -> languageName(option.language)
        ?: stringResource(if (kind == ArtworkKind.LOGO) R.string.artwork_any_language else R.string.artwork_no_text)
}

private fun size(option: ArtworkOption): String? =
    if (option.width > 0 && option.height > 0) formatDimensions(option.width, option.height) else null

/** Where the choices go, and what takes their place while they load or when there are none. */
private val ChoicesPadding = Modifier.padding(horizontal = HlSpace.safeH, vertical = HlSpace.m)

@Composable
private fun Skeletons(kind: ArtworkKind) {
    Row(
        ChoicesPadding,
        horizontalArrangement = Arrangement.spacedBy(HlSpace.m),
    ) {
        repeat(8) { CardSkeleton(kind.cardWidth, kind.aspectRatio) }
    }
}

private val ArtworkKind.title
    get() = when (this) {
        ArtworkKind.POSTER -> R.string.artwork_poster
        ArtworkKind.BACKDROP -> R.string.artwork_backdrop
        ArtworkKind.LOGO -> R.string.artwork_logo
    }

/** Choices are shown at the shape they are used in. */
private val ArtworkKind.cardWidth: Dp
    get() = when (this) {
        ArtworkKind.POSTER -> HlLayout.posterChoice
        ArtworkKind.BACKDROP, ArtworkKind.LOGO -> HlLayout.landscapeChoice
    }

private val ArtworkKind.aspectRatio: Float
    get() = if (this == ArtworkKind.POSTER) 2f / 3f else 16f / 9f

