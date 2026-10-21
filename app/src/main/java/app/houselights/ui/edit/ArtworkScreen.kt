package app.houselights.ui.edit

import androidx.annotation.StringRes
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
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
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ArtworkChoiceCard
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.TaskTitle
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.HlSize
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.languageName
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.skeletonDelayPassed

/**
 * Choosing one piece of artwork, the page behind previewing the focused choice in place. A poster is its own
 * preview, so its choices are larger, right under the header.
 */
@Composable
fun ArtworkScreen(itemId: String, kind: ArtworkKind, onDone: () -> Unit) {
    val app = appContainer()
    val vm: ArtworkViewModel = viewModel(key = "artwork-$itemId-$kind", factory = ArtworkViewModel.factory(app, itemId, kind))
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.item
    val choices = state.choices
    // Once saved, return to the title when the app is resumed; the choice is saved even after Back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.done) { if (state.done) lifecycle.withResumed { onDone() } }

    var focused by remember { mutableStateOf<ArtworkOption?>(null) }
    // Follows focus once it settles, so no image loads per step; the one in use until a choice is focused.
    val shown by rememberSettled { focused }
    val preview = shown ?: choices?.selected
    val backdrop = if (kind == ArtworkKind.BACKDROP) preview?.image else item?.backdrop

    AmbientBackdrop(backdrop, if (kind == ArtworkKind.POSTER) BackdropStyle.Quiet else BackdropStyle.Full) {
        Column(Modifier.fillMaxSize().padding(top = HlSpace.safeV, bottom = HlSpace.m)) {
            Header(kind, item, choices, state.onlineError)
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
                state.error != null -> HlErrorState(state.error!!, vm::load, ChoicesPadding)
                choices == null -> Skeletons(kind)
                choices.options.isEmpty() -> HlEmptyState(stringResource(R.string.artwork_empty), ChoicesPadding, backHint = true)
                else -> Choices(
                    kind = kind,
                    item = item!!,
                    choices = choices,
                    onFocus = { focused = it },
                    onChoose = vm::choose,
                )
            }
        }
    }
}

@Composable
private fun Header(kind: ArtworkKind, item: MediaItem?, choices: ArtworkChoices?, onlineError: UiError?) {
    Column(Modifier.padding(horizontal = HlSpace.safeH)) {
        TaskTitle(stringResource(kind.title))
        Spacer(Modifier.height(HlSpace.xxs))
        FactRow(listOf(item?.title, choices?.let { pluralStringResource(R.plurals.image_count, it.options.size, it.options.size) }))
        Spacer(Modifier.height(HlSpace.xs))
        val reading = Modifier.widthIn(max = HlLayout.readable)
        if (choices != null && !choices.onlineAvailable) {
            AttentionNote(stringResource(onlineNote(onlineError)), reading)
        } else {
            Text(stringResource(R.string.artwork_hint), style = Hl.type.meta, color = Hl.colors.contentSecondary, modifier = reading)
        }
    }
}

/** Why TMDB's images are missing: a key problem (fixed in Settings), else identify the title or check the network. */
@StringRes
private fun onlineNote(error: UiError?): Int = when (error) {
    UiError.NO_TMDB_KEY, UiError.TMDB_KEY_REFUSED -> error.message
    else -> R.string.artwork_offline
}

/** The title as the detail page will show it with the focused choice; a poster previews in its own row. */
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
        selectedFocus.tryRequestFocus()
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

/** What an option is: its language, or its origin. */
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

/** Padding of the choices, and of what replaces them while they load or when there are none. */
private val ChoicesPadding = Modifier.padding(horizontal = HlSpace.safeH, vertical = HlSpace.m)

/** Choice placeholders, shown once loading is slow but laid out from the start so the preview above stays put. */
@Composable
private fun Skeletons(kind: ArtworkKind) {
    val seen = skeletonDelayPassed()
    Row(
        ChoicesPadding.alpha(if (seen) 1f else 0f),
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

/** Choices show in the shape they are used in. */
private val ArtworkKind.cardWidth: Dp
    get() = when (this) {
        ArtworkKind.POSTER -> HlLayout.posterChoice
        ArtworkKind.BACKDROP, ArtworkKind.LOGO -> HlLayout.landscapeChoice
    }

private val ArtworkKind.aspectRatio: Float
    get() = if (this == ArtworkKind.POSTER) HlSize.POSTER_RATIO else 16f / 9f

