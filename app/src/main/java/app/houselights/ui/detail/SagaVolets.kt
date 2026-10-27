package app.houselights.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.SagaPart
import app.houselights.core.SagaTally
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlActionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.SagaVoletWords
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.SynopsisSheet
import app.houselights.ui.design.components.VoletMark
import app.houselights.ui.design.components.VoletPart
import app.houselights.ui.design.components.VoletState
import app.houselights.ui.design.components.VoletWords
import app.houselights.ui.design.components.Volets
import app.houselights.ui.design.components.filmCount
import app.houselights.ui.design.components.wearsNewDot
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.UiError
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.heldSince
import app.houselights.ui.util.okClears
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.run
import app.houselights.ui.util.timeLeft
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

// A saga's page as a polyptych (« Les volets »): the saga's panel, then a panel a film, over the predella's actions.

/**
 * What the saga's page does: play a film ([onPlay], from where it was left or from its start), mark it, open a film,
 * read the saga again once that failed ([onRetry]).
 */
@Immutable
internal class SagaLinks(
    val onPlay: (MediaItem, resume: Boolean) -> Unit,
    val onTogglePlayed: () -> Unit,
    val onOpenItem: (String) -> Unit,
    val onRetry: () -> Unit,
    val onClearProblem: () -> Unit,
    val onClearActionError: () -> Unit,
)

/**
 * A saga's page, which never scrolls: the saga's panel and a panel a film in release order (the films the library
 * lacks in grey), then under them the predella, Lire or Reprendre naming the film it plays. The room is the open
 * panel's diffuse light once focus settled, black on a film the library lacks. The open panel is its own state: the
 * saga's on arrival, a film's once it took focus, kept as focus goes down to the predella (« greffe 1 ») and laid out
 * open from the first frame back from its page. Up from the predella enters on the panel left, the first time on the
 * film Lire plays; Down from a panel goes to Lire; Back opens the saga's panel again, focus on Lire, then leaves. OK
 * opens a film held, the synopsis of one the library lacks, and the saga's own once cut.
 */
@Composable
internal fun SagaVolets(
    item: MediaItem,
    state: DetailState,
    memory: FocusMemory,
    actionRow: FocusRequester,
    problem: UiError?,
    links: SagaLinks,
    modifier: Modifier = Modifier,
) {
    val parts = remember(state.saga, state.collectionFilms) { partsOf(state) }
    val tally = remember(parts, state.nextUp) { SagaTally(parts, state.nextUp) }
    val volets = voletsOf(item, parts, tally)
    // The film open, by its panel's key; none: the saga's. Kept back from a film's page and the player.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<Int?>(null) }
    val open = volets.indexOfFirst { it.key == opened }.coerceAtLeast(0)
    val shown by rememberSettled { open }
    var cut by remember { mutableStateOf(false) }
    var sagaShown by remember { mutableStateOf(false) }
    var partShown by remember { mutableStateOf<SagaPart?>(null) }
    // Back from a panel, or from the predella under a film open: the saga's panel open again, focus on Lire.
    BackToStart(away = focused != null || open != 0) {
        opened = null
        if (!memory.focus(PLAY)) actionRow.tryRequestFocus()
    }
    // Réessayer gone with the failure it said while it had focus: focus goes to Lire.
    FocusOnPlayAfter(swap = state.error != null, held = RETRY, memory, actionRow)
    val onOpen: (
        Int,
    ) -> Unit = { at -> openPanel(parts.getOrNull(at - 1), links, onSaga = { sagaShown = cut }, onPart = { partShown = it }) }
    val films = stringResource(R.string.library_movies)
    val facts = sagaFacts(tally)
    val progress = sagaProgress(tally)
    AmbientBackdrop(roomOf(item, parts, shown ?: open), BackdropStyle.Diffuse, modifier.okClears(links.onClearProblem)) {
        Column(
            Modifier
                .fillMaxSize()
                .houseLit(LocalHouseLights.current)
                .padding(top = HlLayout.voletTop, bottom = HlSpace.safeV),
        ) {
            Volets(
                volets,
                open = open,
                shown = shown,
                onFocus = { at ->
                    focused = at
                    if (at != null) opened = volets[at].key.takeIf { at > 0 }
                },
                onOpen = onOpen,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = HlLayout.voletSide)
                    .focusGroupKey(FocusKey.row(FILMS), memory)
                    .semantics {
                        contentDescription = films
                        isTraversalGroup = true
                    },
                enter = (parts.indexOf(tally.nextPart) + 1).takeIf { it > 0 },
                downTo = if (state.nextUp != null) memory.requester(PLAY) else actionRow,
                keyed = { at -> parts.getOrNull(at - 1)?.item?.let { Modifier.focusKey(voletKey(it), memory) } ?: Modifier },
            ) { at ->
                val part = parts.getOrNull(at - 1)
                if (part == null) {
                    SagaVoletWords(item.title, item.logo, facts, progress, item.overview, onCut = { cut = it })
                } else {
                    val year = part.year?.toString()?.takeIf { part.released }
                    VoletWords(part.title, part.item?.logo, part.item, year, partState(part, tally), part.overview)
                }
            }
            Spacer(Modifier.height(HlSpace.l))
            Predella(item, state, memory, problem, links, Modifier.padding(horizontal = HlSpace.safeH).focusRequester(actionRow))
        }
        // Over the page, in its light.
        if (sagaShown) SynopsisSheet(item, onDismiss = { sagaShown = false })
        PartSynopsis(partShown, onDismiss = { partShown = null })
    }
}

/** OK on a panel: a film held opens its page, one the library lacks its synopsis when it has one; the saga's, [onSaga]. */
private fun openPanel(part: SagaPart?, links: SagaLinks, onSaga: () -> Unit, onPart: (SagaPart) -> Unit) {
    val film = part?.item
    when {
        part == null -> onSaga()
        film != null -> links.onOpenItem(film.id)
        !part.overview.isNullOrBlank() -> onPart(part)
    }
}

/** The saga's films in release order, those the library lacks in their place; without them, the films held. */
private fun partsOf(state: DetailState): List<SagaPart> =
    state.saga?.parts ?: state.collectionFilms.map { SagaPart(it, it.title, it.year, overview = it.overview, backdrop = it.backdrop) }

/** The saga's own picture: its backdrop, else its first film's; never its poster, which prints its name. */
private fun sagaPicture(item: MediaItem, parts: List<SagaPart>): ImageRef? = item.backdrop ?: parts.firstNotNullOfOrNull { it.backdrop }

/**
 * The room on panel [at]: the saga's picture, a film's held (its backdrop, else its poster), none for a film the
 * library lacks or not out yet: the house lights go down.
 */
private fun roomOf(item: MediaItem, parts: List<SagaPart>, at: Int): ImageRef? =
    if (at == 0) sagaPicture(item, parts) else parts.getOrNull(at - 1)?.takeIf { it.owned }?.let { it.backdrop ?: it.poster }

/** The saga's panel, its box (its backdrop, its name on its spine), then a film's each, numbered in release order. */
@Composable
private fun voletsOf(item: MediaItem, parts: List<SagaPart>, tally: SagaTally): List<VoletPart> {
    val saga = VoletPart(
        key = SAGA_VOLET,
        number = null,
        poster = null,
        backdrop = sagaPicture(item, parts),
        readAs = stringResource(R.string.saga_volet_saga, item.title),
        spine = item.title,
    )
    return listOf(saga) + parts.mapIndexed { i, part -> filmVolet(part, i + 1, tally) }
}

/** Film [number]'s panel: read by its title, number and year, then its state; a film the library lacks in grey. */
@Composable
private fun filmVolet(part: SagaPart, number: Int, tally: SagaTally): VoletPart {
    val film = part.item
    val year = part.year?.toString()
    return VoletPart(
        key = film?.let { FocusKey.item(it.id) } ?: "part:$number",
        number = number,
        poster = part.poster,
        backdrop = part.backdrop,
        readAs = listOfNotNull(stringResource(R.string.saga_card_name, part.title, number), year).joinToString(", "),
        grey = film == null,
        mark = markOf(part),
        year = year,
        progress = film?.takeUnless { it.user.played }?.progress ?: 0f,
        state = readState(part, tally),
    )
}

/** The sign at a closed film's foot: held first, then watched, new; the ring of a film missing, the quiet dot of one to come. */
private fun markOf(part: SagaPart): VoletMark {
    val film = part.item
    return when {
        film == null -> if (part.released) VoletMark.Absent else VoletMark.Upcoming
        film.held -> VoletMark.Held
        film.user.played -> VoletMark.Watched
        film.wearsNewDot() -> VoletMark.New
        else -> VoletMark.None
    }
}

/** A film's state as read after its name: « vu », « en cours, 55 % », « à suivre », « absent… », « sortie le… ». */
@Composable
private fun readState(part: SagaPart, tally: SagaTally): String? {
    val film = part.item
    return when {
        !part.released -> part.releaseDate?.let { stringResource(R.string.saga_card_release, longDate(it)) }
            ?: stringResource(R.string.saga_card_announced)
        film == null -> stringResource(R.string.saga_card_absent)
        film.held -> stringResource(R.string.card_held)
        film.user.played -> stringResource(R.string.card_watched)
        film.progress > 0f -> stringResource(R.string.card_in_progress, (film.progress * PERCENT).roundToInt())
        film.id == tally.nextUp?.id -> stringResource(R.string.saga_card_next)
        film.wearsNewDot() -> stringResource(R.string.card_new)
        else -> null
    }
}

/** An open film's state, last on its facts' line: « Vu », « Il reste 1 h 14 », « À suivre », « Absent… », « Sortie le… ». */
@Composable
private fun partState(part: SagaPart, tally: SagaTally): VoletState? {
    val film = part.item
    return when {
        !part.released -> VoletState(releaseWords(part), StatusState.Pending)
        film == null -> VoletState(stringResource(R.string.saga_absent), StatusState.Off)
        film.held -> film.missingSince?.let { VoletState(heldSince(it), StatusState.Off) }
        film.user.played -> VoletState(stringResource(R.string.watched), StatusState.Done)
        else -> timeLeft(film)?.let { VoletState(it) }
            ?: VoletState(stringResource(R.string.section_next_up), lit = true).takeIf { film.id == tally.nextUp?.id }
    }
}

/**
 * The predella, under the panels: Lire or Reprendre the film the saga plays next, named with its time left, whatever
 * panel is open; from its start once started; the saga marked watched or not; once the saga could not be read again,
 * what fixes it (Réessayer), and what failed at its end. Nothing past its last action.
 */
@Composable
private fun Predella(
    item: MediaItem,
    state: DetailState,
    memory: FocusMemory,
    problem: UiError?,
    links: SagaLinks,
    modifier: Modifier = Modifier,
) {
    val target = state.nextUp
    val resume = target != null && target.user.positionMs > 0
    val marks = state.capabilities?.markPlayed == true
    val fixedBy = state.error?.action
    // Right from the last action would reach a panel above it.
    val last = Modifier.focusProperties { right = FocusRequester.Cancel }
    OnArtwork {
        HlActionRow(modifier.okClears(links.onClearActionError)) {
            if (target != null) {
                HlButton(
                    stringResource(if (resume) R.string.resume else R.string.play),
                    onClick = { links.onPlay(target, resume) },
                    modifier = Modifier.focusKey(PLAY, memory).then(if (resume || marks || fixedBy != null) Modifier else last),
                    icon = HlIcons.Play,
                    detail = listOfNotNull(target.title, timeLeft(target).takeIf { resume }).joinToString(FACT_GAP),
                    emphasis = Emphasis.Primary,
                )
                if (resume) {
                    HlIconButton(
                        HlIcons.Restart,
                        stringResource(R.string.from_start),
                        onClick = { links.onPlay(target, false) },
                        modifier = Modifier.focusKey(RESTART, memory).then(if (marks || fixedBy != null) Modifier else last),
                    )
                }
            }
            if (marks) {
                SagaWatchedTile(
                    item,
                    links.onTogglePlayed,
                    Modifier.focusKey(WATCHED, memory).then(if (fixedBy != null) Modifier else last),
                )
            }
            if (fixedBy != null) {
                // Focus on Lire first: Réessayer goes once the saga is read, and focus lost would enter the panels.
                val retry = {
                    memory.focus(PLAY)
                    links.onRetry()
                }
                Retry(fixedBy, state.retrying, retry, Modifier.focusKey(RETRY, memory).then(last))
            }
            // What failed to play or to save, else why the saga could not be read again.
            ActionFailure(problem, state.actionError, nextUpError = state.error)
        }
    }
}

/** « Marquer la saga comme vue », or « non vue » once it is: it marks the films held. */
@Composable
private fun SagaWatchedTile(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val watched = item.user.played
    HlIconButton(
        if (watched) HlIcons.CheckFilled else HlIcons.Check,
        stringResource(if (watched) R.string.mark_saga_unwatched else R.string.mark_saga_watched),
        onClick = onClick,
        modifier = modifier,
    )
}

/** What fixes a saga that could not be read again, as `PartFailed`'s button: Réessayer ([onRetry]), busy while [retrying]. */
@Composable
private fun Retry(fixedBy: ErrorAction, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val fix = LocalErrorFix.current
    HlButton(
        stringResource(fixedBy.label),
        onClick = { fixedBy.run(onRetry, fix) },
        modifier = modifier,
        icon = HlIcons.Refresh.takeIf { fixedBy == ErrorAction.RETRY },
        busy = retrying && fixedBy == ErrorAction.RETRY,
        busyLabel = stringResource(R.string.retrying),
    )
}

/** The saga's facts: the years of its films out, those held (of those out), their length, those to come. */
@Composable
private fun sagaFacts(tally: SagaTally): List<String> {
    val held = tally.owned.size
    return listOfNotNull(
        tally.years?.let { if (it.first == it.last) "${it.first}" else "${it.first} – ${it.last}" },
        filmCount(held, tally.releasedCount.takeIf { it != held }),
        tally.lengthMs?.let(::formatDuration),
        tally.upcoming.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.saga_upcoming_count, it, it) },
    )
}

/** The saga's progress in words: watched of held and what is left, else what is left of the one started; none before. */
@Composable
private fun sagaProgress(tally: SagaTally): List<String> {
    if (!tally.begun) return emptyList()
    val held = tally.owned.size
    val left = tally.leftMs.takeIf { it > 0 }?.let { stringResource(R.string.time_left, formatDuration(maxOf(it, MINUTE_MS))) }
    return when {
        tally.watched == held -> listOf(pluralStringResource(R.plurals.saga_all_watched, held, held))
        tally.watched > 0 -> listOfNotNull(pluralStringResource(R.plurals.saga_watched_of, tally.watched, tally.watched, held), left)
        else -> listOfNotNull(left)
    }
}

/** The synopsis of [part], a film of a saga the library lacks or not out yet: its title, what it is, its whole overview. */
@Composable
internal fun PartSynopsis(part: SagaPart?, onDismiss: () -> Unit) {
    if (part == null) return
    SynopsisSheet(listOf(part.title) + absentFacts(part), tagline = null, overview = part.overview.orEmpty(), onDismiss = onDismiss)
}

/** What a film of the saga the library lacks is said to be after its title: « 2022   Absent… », « Sortie le… ». */
@Composable
private fun absentFacts(part: SagaPart): List<String> =
    if (part.released) listOfNotNull(part.year?.toString(), stringResource(R.string.saga_absent)) else listOf(releaseWords(part))

/** « Sortie le 15 décembre 2026 », else « Annoncé ». */
@Composable
private fun releaseWords(part: SagaPart): String =
    part.releaseDate?.let { stringResource(R.string.saga_release_on, longDate(it)) } ?: stringResource(R.string.saga_announced)

/** [date] in full in the viewer's language. */
@Composable
@ReadOnlyComposable
private fun longDate(date: LocalDate): String = longDate(date, LocalConfiguration.current.locales[0])

/** [date] in full in [locale]'s words: in French, « 15 décembre 2026 », « 1er mai 2027 », unbroken. */
internal fun longDate(date: LocalDate, locale: Locale): String {
    if (locale.language != Locale.FRENCH.language) {
        return DateTimeFormatter.ofLocalizedDate(
            FormatStyle.LONG,
        ).withLocale(locale).format(date)
    }
    val day = if (date.dayOfMonth == 1) FIRST_FR else "${date.dayOfMonth}"
    return listOf(day, DateTimeFormatter.ofPattern(MONTH, locale).format(date), "${date.year}").joinToString("$NBSP")
}

/** A film's panel keyed as the films' row's cards were: back from its page, focus is on it, open. */
private fun voletKey(film: MediaItem) = FocusKey.within(FocusKey.row(FILMS), FocusKey.item(film.id))

/** Réessayer, once the saga could not be read again. */
private val RETRY = FocusKey.action("retry-saga")
private const val FILMS = "films"
private const val SAGA_VOLET = "saga"
private const val MINUTE_MS = 60_000L
private const val PERCENT = 100
private const val MONTH = "MMMM"
private const val FIRST_FR = "1er"
