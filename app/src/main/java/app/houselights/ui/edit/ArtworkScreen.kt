package app.houselights.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.MediaItem
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.OwnScroll
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.ArtworkChoiceCard
import app.houselights.ui.design.components.ArtworkPrint
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.LocalGroupFocused
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.ResultsHeading
import app.houselights.ui.design.components.TaskTitle
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.design.fadeOutsideSafeArea
import app.houselights.ui.design.keepHorizontalFocus
import app.houselights.ui.design.textRoom
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.languageName
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.skeletonDelayPassed
import kotlinx.coroutines.launch

/**
 * Choosing one picture of a title: its contact sheet (« la planche-contact »), the choices in sections, Automatique and
 * the one in use first, then a section a language as TMDB's are ranked, then a saga's films' (« Ses films »); and the
 * choice with focus shown at its use, in the shape the picture has. A poster stands: large beside the sheet, under the
 * screen's title (« l'épreuve »), six a line. A backdrop lies: full screen behind, as the title's page shows it, the
 * sheet a band at the screen's foot, five a line; a logo shows at its place over the title's backdrop, four a line. The
 * line with focus always comes to the sheet's top, the name of its section held over it. Nothing is written under a
 * choice but « Automatique » and a saga's film's name and year: the section says the language.
 */
@Composable
fun ArtworkScreen(
    itemId: String,
    kind: ArtworkKind,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ArtworkViewModel = viewModel(key = "artwork-$itemId-$kind", factory = ArtworkViewModel.factory(appContainer(), itemId, kind)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Once saved, return to the title when the app is resumed; the choice is saved even after Back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(state.done) { if (state.done) lifecycle.withResumed { currentOnDone() } }
    ArtworkSheet(
        kind = kind,
        item = state.item,
        choices = state.choices,
        error = state.error,
        onlineError = state.onlineError,
        onChoose = vm::choose,
        onRetry = vm::load,
        modifier = modifier,
    )
}

/**
 * The contact sheet of [kind] for [item] and its [choices], or what replaces them ([error], none yet); OK on a choice
 * chooses it ([onChoose]). With [takesFocus], focus comes on the one in use as the sheet arrives.
 */
@Composable
internal fun ArtworkSheet(
    kind: ArtworkKind,
    item: MediaItem?,
    choices: ArtworkChoices?,
    error: UiError?,
    onlineError: UiError?,
    onChoose: (ArtworkOption) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    takesFocus: Boolean = true,
) {
    var focused by remember { mutableStateOf<ArtworkOption?>(null) }
    // Follows focus once it settles, so no image loads per step; the one in use until a choice is focused.
    val shown by rememberSettled { focused }
    val preview = shown ?: choices?.selected
    val backdrop = if (kind == ArtworkKind.BACKDROP) preview?.image else item?.backdrop
    val sections = remember(choices) { choices?.let(::choiceSections).orEmpty() }
    // The section of the line with focus, at once: the one in use's until a choice has focus.
    val held = remember(sections) {
        derivedStateOf { (focused ?: choices?.selected).let { option -> sections.indexOfFirst { option in it.options } } }
    }
    val sheet = remember(kind, item, choices, sections) { Sheet(kind, item, choices, sections, held) }
    val choose by rememberUpdatedState(onChoose)
    val retry by rememberUpdatedState(onRetry)
    val actions = remember { SheetActions(onFocus = { focused = it }, onChoose = { choose(it) }, onRetry = { retry() }) }
    // Why TMDB's pictures are missing, said in their place.
    val note = choices?.takeIf { !it.onlineAvailable }?.let { stringResource(onlineNote(onlineError)) }
    AmbientBackdrop(backdrop, if (kind == ArtworkKind.POSTER) BackdropStyle.Quiet else BackdropStyle.Full, modifier) {
        if (kind == ArtworkKind.POSTER) {
            PosterSheet(sheet, preview) { SheetBody(sheet, error, note, takesFocus, actions) }
        } else {
            // The band shows one line: the note is read over it, under the title.
            BandSheet(sheet, preview, note) { SheetBody(sheet, error, note = null, takesFocus, actions) }
        }
    }
}

/** What the sheet lists, for both its layouts: [held] is the section of the line with focus. */
private class Sheet(
    val kind: ArtworkKind,
    val item: MediaItem?,
    val choices: ArtworkChoices?,
    val sections: List<ChoiceSection>,
    val held: State<Int>,
)

/** What the sheet does: a choice focused, chosen, or the choices asked for again. */
private class SheetActions(val onFocus: (ArtworkOption) -> Unit, val onChoose: (ArtworkOption) -> Unit, val onRetry: () -> Unit)

/**
 * A poster's choice, one composition on the safe area: the screen's title and the title's name over the print, the
 * poster with focus at its largest ([HlLayout.choicePrint]), standing to the safe area's foot over its words; beside it
 * the sheet, six a line, its line with focus level with the print's top, the name of its section held over it on the
 * line of the title's name; the lines under it run to the screen's foot, which cuts them.
 */
@Composable
private fun PosterSheet(sheet: Sheet, preview: ArtworkOption?, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = HlSpace.safeH, top = HlSpace.safeV, end = HlSpace.safeH)) {
        // Over the sheet scrolling under it.
        Row(Modifier.zIndex(1f), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.width(HlLayout.choicePrint)) {
                TaskTitle(stringResource(ArtworkKind.POSTER.title))
                Spacer(Modifier.height(HlSpace.xxs))
                Text(
                    sheet.item?.title.orEmpty(),
                    style = Hl.type.meta,
                    color = Hl.colors.contentSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(HlLayout.choicePrintGap))
            HeldName(sheet, Modifier.weight(1f).offset(y = HlLayout.choiceNameDrop))
        }
        Row(Modifier.weight(1f)) {
            // The print keeps the wall's room the line with focus keeps over it, so their tops are level.
            Box(Modifier.width(HlLayout.choicePrint).fillMaxHeight().padding(top = HlFocus.wallRoom, bottom = HlSpace.safeV)) {
                Print(sheet, preview)
            }
            Spacer(Modifier.width(HlLayout.choicePrintGap))
            Box(Modifier.weight(1f).fillMaxHeight()) { body() }
        }
    }
}

/** The poster with focus as it will stand, its check when it is the one in use, and what it is when it has a name. */
@Composable
private fun Print(sheet: Sheet, preview: ArtworkOption?) {
    val choices = sheet.choices ?: return
    if (preview == null || sheet.item == null) return
    val automatic = preview == choices.automatic
    val film = preview.film?.takeIf { preview.origin == ArtworkOrigin.FILM }
    ArtworkPrint(
        image = preview.image,
        selected = preview == choices.selected,
        width = HlLayout.choicePrint,
        aspectRatio = HlSize.POSTER_RATIO,
        // As a result's under its row: Automatique and the film whose picture it is, a film and its year.
        name = if (automatic) stringResource(R.string.artwork_automatic) else film,
        detail = if (automatic) film else preview.filmYear?.takeIf { film != null }?.toString(),
        modifier = Modifier.fillMaxHeight(),
    )
}

/**
 * A backdrop's or a logo's choice: the screen's title alone, the logo or the name set over the picture naming the
 * title; then the title as its page shows it in the light of the choice with focus (its backdrop full screen behind,
 * else its logo at its place), why TMDB's pictures are missing ([note]) under it; then the sheet, a band at the
 * screen's foot ([HlLayout.choiceBand]): the name of the section of the line with focus, held, that line, which always
 * comes to the band's top, and the top of the next.
 */
@Composable
private fun BandSheet(sheet: Sheet, preview: ArtworkOption?, note: String?, body: @Composable () -> Unit) {
    val kind = sheet.kind
    val item = sheet.item
    Column(Modifier.fillMaxSize()) {
        TaskTitle(stringResource(kind.title), Modifier.padding(start = HlSpace.safeH, top = HlSpace.safeV, end = HlSpace.safeH))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = HlSpace.safeH, vertical = HlSpace.m),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (item != null) Preview(kind, item, preview)
            if (note != null) {
                Spacer(Modifier.height(HlSpace.l))
                Note(note)
            }
        }
        val films = sheet.choices?.options.orEmpty().any { it.origin == ArtworkOrigin.FILM }
        Column(Modifier.fillMaxWidth().height(HlLayout.choiceBand(kind.cellHeight, films)).padding(horizontal = HlSpace.safeH)) {
            HeldName(sheet, Modifier.zIndex(1f).offset(y = HlLayout.choiceNameDrop))
            Box(Modifier.weight(1f)) { body() }
        }
    }
}

/**
 * The name of the section of the line with focus and its count, held over that line as the sheet scrolls under it, so
 * every line says its language; the first section, Automatique and the one in use, has none. Its room is kept.
 */
@Composable
private fun HeldName(sheet: Sheet, modifier: Modifier = Modifier) {
    val section = sheet.sections.getOrNull(sheet.held.value)
    val name = section?.let { sectionName(sheet.kind, it.name) }
    Box(modifier.heightIn(min = textRoom(Hl.type.rowTitle)), contentAlignment = Alignment.BottomStart) {
        if (section != null && name != null) ResultsHeading(name, section.options.size)
    }
}

/**
 * The sheet, its loading lines, or what replaces it: a failure and what fixes it ([error]), or no picture at all; the
 * [note] of TMDB's missing pictures after its head.
 */
@Composable
private fun SheetBody(sheet: Sheet, error: UiError?, note: String?, takesFocus: Boolean, actions: SheetActions) {
    val choices = sheet.choices
    val media = sheet.item
    val place = Modifier.padding(top = HlFocus.wallRoom)
    when {
        error != null -> HlErrorState(error, actions.onRetry, place)
        // The title and its choices come with the same page.
        choices == null || media == null -> Skeletons(sheet.kind, place)
        choices.options.isEmpty() -> HlEmptyState(stringResource(R.string.artwork_empty), place, backHint = true)
        else -> Choices(sheet, choices, media, note, takesFocus, actions)
    }
}

/**
 * Why TMDB's images are missing: a key problem (fixed in Settings) or a refused certificate, else identify the title or
 * check the network.
 */
@StringRes
internal fun onlineNote(error: UiError?): Int = when (error) {
    UiError.NO_TMDB_KEY, UiError.TMDB_KEY_REFUSED, UiError.CERTIFICATE -> error.message
    else -> R.string.artwork_offline
}

/** Why TMDB's pictures are missing, in their place: a failure, and what fixes it. */
@Composable
private fun Note(note: String, modifier: Modifier = Modifier) {
    AttentionNote(note, modifier.widthIn(max = HlLayout.readable))
}

/** The title as the detail page will show it with the focused choice: over its backdrop, its logo or its name and facts. */
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

/** A section of the sheet and its choices: the head, untitled, then a language's, then a saga's films'. */
internal data class ChoiceSection(val name: SectionName, val options: List<ArtworkOption>)

/** What names a section: nothing for the head ([Head]); its [Language] (null: without any); a saga's [Films]. */
internal sealed interface SectionName {
    data object Head : SectionName

    data class Language(val language: String?) : SectionName

    data object Films : SectionName
}

/**
 * [choices] in the sheet's sections, in their order: Automatique and the one in use, with what the title's files give
 * (its NFO's picture, a file beside it, its name set in type for a logo); then TMDB's, a section a language in the
 * order they come ranked (posters and logos: the viewer's language, English, none; backdrops: none first); then a
 * saga's films', one a film. The one in use is in the head alone, never again in its language's.
 */
internal fun choiceSections(choices: ArtworkChoices): List<ChoiceSection> {
    val head = buildList<ArtworkOption> {
        choices.automatic?.let(::add)
        choices.selected?.let(::add)
        addAll(choices.options.filter { it.origin != ArtworkOrigin.ONLINE && it.origin != ArtworkOrigin.FILM })
    }.distinct()
    val rest = choices.options.filterNot { it in head }
    val languages = rest.filter { it.origin == ArtworkOrigin.ONLINE }.groupBy { it.language }
    val films = rest.filter { it.origin == ArtworkOrigin.FILM }
    return buildList<ChoiceSection> {
        if (head.isNotEmpty()) add(ChoiceSection(SectionName.Head, head))
        languages.forEach { (language, options) -> add(ChoiceSection(SectionName.Language(language), options)) }
        if (films.isNotEmpty()) add(ChoiceSection(SectionName.Films, films))
    }
}

/** A line of the sheet: the [index]th of the [section]th section, [options] of it, the [last] of it or not. */
internal data class SheetLine(val section: Int, val index: Int, val options: List<ArtworkOption>, val last: Boolean)

/** [sections] in lines of [columns], each section starting a line. */
internal fun sheetLines(sections: List<ChoiceSection>, columns: Int): List<SheetLine> = sections.flatMapIndexed { s, section ->
    val lines = section.options.chunked(columns)
    lines.mapIndexed { i, options -> SheetLine(s, i, options, last = i == lines.lastIndex) }
}

/**
 * What the sheet lists, item by item, each [gap] from the one before it; a list's place is lent only to an item of the
 * same [type], laid out as it is, so none keeps the gap of the one it took the place of.
 */
internal sealed interface SheetItem {
    val gap: SheetGap
    val key: String
    val type: String
        get() = when (this) {
            is Name -> "name-$gap"
            is Cards -> "cards-$gap"
            is Note -> "note-$gap"
        }

    /** The name of the [section]th section, over its first line. */
    data class Name(val section: Int, override val gap: SheetGap) : SheetItem {
        override val key: String get() = "name-$section"
    }

    /** A [line] of choices. */
    data class Cards(val line: SheetLine, override val gap: SheetGap) : SheetItem {
        override val key: String get() = "line-${line.section}-${line.index}"
    }

    /** Why TMDB's pictures are missing, in their place. */
    data class Note(override val gap: SheetGap) : SheetItem {
        override val key: String get() = "note"
    }
}

/** The gap over an item: none over the first; a section's name's over its first line, a line's, a block's over a section. */
internal enum class SheetGap { NONE, NAME, LINE, BLOCK }

/**
 * [sections] as the sheet lists them in lines of [columns]: a section's name, a block from what comes before it, over
 * its first line, the head having none; lines a wall's lines apart; with a [note], why TMDB's pictures are missing
 * after the head, or first without one.
 */
internal fun sheetItems(sections: List<ChoiceSection>, columns: Int, note: Boolean): List<SheetItem> {
    val items = mutableListOf<SheetItem>()
    val gap = { wanted: SheetGap -> if (items.isEmpty()) SheetGap.NONE else wanted }
    val head = sections.indexOfFirst { it.name == SectionName.Head }
    if (note && head < 0) items += SheetItem.Note(SheetGap.NONE)
    sheetLines(sections, columns).forEach { line ->
        val named = line.index == 0 && sections[line.section].name != SectionName.Head
        if (named) items += SheetItem.Name(line.section, gap(SheetGap.BLOCK))
        items += SheetItem.Cards(line, if (named) SheetGap.NAME else gap(SheetGap.LINE))
        if (note && line.section == head && line.last) items += SheetItem.Note(SheetGap.LINE)
    }
    return items
}

/** The room a gap keeps. */
private val SheetGap.room: Dp
    get() = when (this) {
        SheetGap.NONE -> HlSpace.none
        SheetGap.NAME -> HlLayout.choiceNameGap
        SheetGap.LINE -> HlSpace.l
        SheetGap.BLOCK -> HlLayout.choiceSectionGap
    }

/** A section's name: its language (« Français »), « Sans texte » (a logo's, « Toutes langues »), « Ses films ». */
@Composable
private fun sectionName(kind: ArtworkKind, name: SectionName): String? = when (name) {
    SectionName.Head -> null
    SectionName.Films -> stringResource(R.string.artwork_saga_films)
    is SectionName.Language -> languageName(name.language)
        ?: stringResource(if (kind == ArtworkKind.LOGO) R.string.artwork_any_language else R.string.artwork_no_text)
}

/**
 * The sheet's items, the line with focus always brought to its top under the wall's room for its glow, its section's
 * name held over it, whatever is over the line in the list: the sheet scrolls itself there ([OwnScroll]); the note of
 * TMDB's missing pictures after the head, in their place; and room under the last line, so it too comes to the top.
 * While a choice has focus, the others step back, as on a wall.
 */
@Composable
private fun Choices(
    sheet: Sheet,
    choices: ArtworkChoices,
    media: MediaItem,
    note: String?,
    takesFocus: Boolean,
    actions: SheetActions,
) {
    val kind = sheet.kind
    val sections = sheet.sections
    val start = choices.selected ?: choices.options.first()
    val startFocus = remember { FocusRequester() }
    if (takesFocus) {
        LaunchedEffect(Unit) {
            // Start on the image in use, once the sheet has laid it out.
            withFrameNanos { }
            startFocus.tryRequestFocus()
        }
    }
    val names = sections.map { sectionName(kind, it.name) }
    val entries = remember(sections, kind, note != null) { sheetItems(sections, kind.columns, note != null) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val room = with(LocalDensity.current) { HlFocus.wallRoom.toPx() }
    val list = remember { Placed() }
    var within by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalGroupFocused provides within) {
        OwnScroll {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(top = HlFocus.wallRoom),
                modifier = Modifier.fillMaxSize().onPlaced { list.coordinates = it }.onFocusChanged { within = it.hasFocus },
            ) {
                entries.forEach { entry ->
                    item(key = entry.key, contentType = entry.type) {
                        val gap = entry.gap.room
                        when (entry) {
                            is SheetItem.Name -> SectionHeading(
                                name = names[entry.section].orEmpty(),
                                count = sections[entry.section].options.size,
                                held = { sheet.held.value == entry.section },
                                modifier = Modifier.padding(top = gap),
                            )
                            // Its cards to the wall's room under the list's top, where the print stands, whatever is over them.
                            is SheetItem.Cards -> Line(
                                line = entry.line,
                                sheet = sheet,
                                choices = choices,
                                media = media,
                                start = start,
                                startFocus = startFocus,
                                actions = actions,
                                onFocus = { cards ->
                                    val top = list.coordinates?.localPositionOf(cards, Offset.Zero)?.y
                                    if (top != null) scope.launch { state.animateScrollBy(top - room) }
                                },
                                modifier = Modifier.padding(top = gap),
                            )
                            is SheetItem.Note -> Note(note.orEmpty(), Modifier.padding(top = gap))
                        }
                    }
                }
                item(key = RUNOUT, contentType = RUNOUT) { Spacer(Modifier.fillParentMaxHeight()) }
            }
        }
    }
}

/**
 * A section's [name] and its [count], over its first line: hidden while it is the section of the line with focus
 * ([held]), whose name the sheet holds over it, faded across the safe area's margin otherwise, never cut by the edge.
 */
@Composable
private fun SectionHeading(name: String, count: Int, held: () -> Boolean, modifier: Modifier = Modifier) {
    ResultsHeading(name, count, modifier.alpha(if (held()) 0f else 1f).fadeOutsideSafeArea())
}

/**
 * A line of the sheet, as many choices as [Sheet.kind] holds a line. A choice's words take its column and its gap, or
 * the next column too when the next choice has none or there is none, unless it is the last but one; in the last
 * column, its picture's width. [start] takes focus as the sheet arrives ([startFocus]); focus coming into the line
 * tells where its cards lie ([onFocus]), so the sheet brings them to its top.
 */
@Composable
private fun Line(
    line: SheetLine,
    sheet: Sheet,
    choices: ArtworkChoices,
    media: MediaItem,
    start: ArtworkOption,
    startFocus: FocusRequester,
    actions: SheetActions,
    onFocus: (LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = sheet.kind
    val columns = kind.columns
    // The cards, past the gap over them.
    val cards = remember { Placed() }
    Row(
        modifier
            .onPlaced { cards.coordinates = it }
            .keepHorizontalFocus()
            .onFocusChanged { focus -> if (focus.hasFocus) cards.coordinates?.let(onFocus) },
        horizontalArrangement = Arrangement.spacedBy(HlLayout.choiceGutter),
    ) {
        line.options.forEachIndexed { column, option ->
            val next = line.options.getOrNull(column + 1)
            val roomy = column < columns - 2 && (next == null || !choices.hasWords(next))
            val words = HlLayout.choiceWords(kind.cellWidth, if (roomy) 2 else 1, last = column == columns - 1)
            val focus = if (option == start) Modifier.focusRequester(startFocus) else Modifier
            Choice(option, words, sheet, choices, media, actions, focus)
        }
    }
}

/** Where a layout lies, as last placed: read as focus comes, never drawn from. */
private class Placed {
    var coordinates: LayoutCoordinates? = null
}

/** Whether [option] says what it is under it: Automatique, a saga's film. */
private fun ArtworkChoices.hasWords(option: ArtworkOption) = option == automatic || option.origin == ArtworkOrigin.FILM

@Composable
private fun Choice(
    option: ArtworkOption,
    words: Dp,
    sheet: Sheet,
    choices: ArtworkChoices,
    media: MediaItem,
    actions: SheetActions,
    modifier: Modifier = Modifier,
) {
    val kind = sheet.kind
    val automatic = option == choices.automatic
    val film = option.film?.takeIf { option.origin == ArtworkOrigin.FILM && !automatic }
    ArtworkChoiceCard(
        image = option.image,
        selected = option == choices.selected,
        title = if (automatic) stringResource(R.string.artwork_automatic) else describe(kind, option),
        onClick = { actions.onChoose(option) },
        width = kind.cellWidth,
        aspectRatio = kind.aspectRatio,
        modifier = modifier,
        // Only Automatique and a saga's film say what they are: the section says the language.
        name = if (automatic) stringResource(R.string.artwork_automatic) else film,
        detail = option.filmYear?.takeIf { film != null }?.toString(),
        captionWidth = words,
        fit = kind == ArtworkKind.LOGO,
        placeholder = media.title.takeIf { option.origin == ArtworkOrigin.NONE },
        onFocus = { actions.onFocus(option) },
    )
}

/** What an option is, as it is read: its language, or its origin; a saga's film's, that film's title. */
@Composable
private fun describe(kind: ArtworkKind, option: ArtworkOption): String = when (option.origin) {
    ArtworkOrigin.FILM -> option.film.orEmpty()
    ArtworkOrigin.NONE -> stringResource(R.string.artwork_title_in_type)
    ArtworkOrigin.LOCAL_FILE -> stringResource(R.string.artwork_local_file)
    ArtworkOrigin.NFO -> stringResource(R.string.artwork_from_nfo)
    ArtworkOrigin.ONLINE -> languageName(option.language)
        ?: stringResource(if (kind == ArtworkKind.LOGO) R.string.artwork_any_language else R.string.artwork_no_text)
}

/** Choice placeholders, shown once loading is slow but laid out from the start so the print and preview stay put. */
@Composable
private fun Skeletons(kind: ArtworkKind, modifier: Modifier = Modifier) {
    val seen = skeletonDelayPassed()
    Column(modifier.alpha(if (seen) 1f else 0f), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlLayout.choiceGutter)) {
                repeat(kind.columns) { CardSkeleton(kind.cellWidth, kind.aspectRatio) }
            }
        }
    }
}

private const val RUNOUT = "runout"

private val ArtworkKind.title
    get() = when (this) {
        ArtworkKind.POSTER -> R.string.artwork_poster
        ArtworkKind.BACKDROP -> R.string.artwork_backdrop
        ArtworkKind.LOGO -> R.string.artwork_logo
    }

/** The choices a line of the sheet holds, the wider the picture the fewer. */
private val ArtworkKind.columns: Int
    get() = when (this) {
        ArtworkKind.POSTER -> HlLayout.POSTER_CHOICES
        ArtworkKind.BACKDROP -> HlLayout.BACKDROP_CHOICES
        ArtworkKind.LOGO -> HlLayout.LOGO_CHOICES
    }

/** A choice's width on the sheet, by its shape. */
private val ArtworkKind.cellWidth: Dp
    get() = when (this) {
        ArtworkKind.POSTER -> HlLayout.posterChoiceCell
        ArtworkKind.BACKDROP -> HlLayout.backdropChoiceCell
        ArtworkKind.LOGO -> HlLayout.logoChoiceCell
    }

/** A choice's height on the sheet: its width at its shape. */
private val ArtworkKind.cellHeight: Dp
    get() = cellWidth / aspectRatio

/** Choices show in the shape they are used in: a poster's, a backdrop's at 16:9, a logo in the box a gallery gives it. */
private val ArtworkKind.aspectRatio: Float
    get() = when (this) {
        ArtworkKind.POSTER -> HlSize.POSTER_RATIO
        ArtworkKind.BACKDROP -> HlSize.PREVIEW_RATIO
        ArtworkKind.LOGO -> HlSize.LOGO_BOX_RATIO
    }
