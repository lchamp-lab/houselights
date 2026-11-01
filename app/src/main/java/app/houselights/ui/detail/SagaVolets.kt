package app.houselights.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.SagaPart
import app.houselights.core.SagaTally
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.Hl
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
import app.houselights.ui.design.components.LabelRoom
import app.houselights.ui.design.components.SagaVoletWords
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.SynopsisSheet
import app.houselights.ui.design.components.VoletMark
import app.houselights.ui.design.components.VoletPart
import app.houselights.ui.design.components.VoletSides
import app.houselights.ui.design.components.VoletState
import app.houselights.ui.design.components.VoletWords
import app.houselights.ui.design.components.Volets
import app.houselights.ui.design.components.filmCount
import app.houselights.ui.design.components.labelRoom
import app.houselights.ui.design.components.wearsNewDot
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.ErrorFixButton
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.UiError
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.heldSince
import app.houselights.ui.util.okClears
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.timeLeft
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

// A saga's page as a polyptych (« Les volets »): the saga's panel, then a panel a film, each open one with its actions.

/**
 * What the saga's page does: play a film ([onPlay], from where it was left or from its start), mark the saga or a film
 * of it watched or not as it stands ([onMark]), open a film's page, read the saga again once that failed ([onRetry]);
 * open Modifier on the saga's poster and backdrop ([onEdit], null where its source keeps no pictures).
 */
@Immutable
internal class SagaLinks(
    val onPlay: (MediaItem, resume: Boolean) -> Unit,
    val onMark: (MediaItem) -> Unit,
    val onOpenItem: (String) -> Unit,
    val onRetry: () -> Unit,
    val onClearProblem: () -> Unit,
    val onClearActionError: () -> Unit,
    val onEdit: (() -> Unit)? = null,
)

/**
 * A saga's page, which never scrolls: the saga's panel and a panel a film in release order (the films the library
 * lacks in grey), from the safe area's top to its foot. The room is the open panel's diffuse light once focus settled,
 * black on a film the library lacks. The open panel is its own state: the saga's on arrival, a film's once it took
 * focus, kept as focus goes down to its actions and laid out open from the first frame back from its page. Each open
 * panel has its actions under its words, which Down enters: the saga's Lire or Reprendre the film it plays next, named
 * on the line above, its check and Modifier ([SagaActions]); a film held, its own Lire, its check and its page
 * ([FilmActions]); a film the library lacks, none. What failed on the page, a play, a change not saved, is said over
 * the open panel's actions, whichever panel it is. Focus arrives on the saga's Lire ([actionRow]), on its panel when
 * it has no action; Up from a panel's actions goes back to it, Left from their first and Right from their last on to
 * the panel beside; Back opens the saga's panel again, focus on its Lire,
 * then leaves. OK opens a film held, the synopsis of one the library lacks, and the saga's own once cut, what it does
 * said in the panel's corner while it has focus. The Menu key does nothing here. [sheets] are its sheets, over the page
 * in its light.
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
    sheets: @Composable () -> Unit = {},
) {
    val parts = remember(state.saga, state.collectionFilms) { partsOf(state) }
    val tally = remember(parts, state.nextUp) { SagaTally(parts, state.nextUp) }
    // Each panel by its place, as a film the library lacks comes in it: its panel stays, open if it was.
    val keys = remember(parts.size) { listOf(SAGA_VOLET) + (1..parts.size).map(::partKey) }
    var cut by remember { mutableStateOf(false) }
    // The film open, by its panel's key; none: the saga's. Kept back from a film's page and the player.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val open = keys.indexOf(opened).coerceAtLeast(0)
    val acts = SagaActs(state, links)
    val volets = voletsOf(item, parts, tally, cut, open, acts.any)
    // Settled on it from the first frame: back from a film's page, its panel is whole at once.
    val shown by rememberSettled(initial = open) { open }
    var sagaShown by remember { mutableStateOf(false) }
    var partShown by remember { mutableStateOf<SagaPart?>(null) }
    // Focus on the saga's first action, Lire: Back from it leaves the page, as from anywhere on a saga with no action.
    var home by remember { mutableStateOf(false) }
    val homing = rememberBackToLire(away = open != 0 || acts.any && !home, home = { home }, memory, actionRow) { opened = null }
    // Réessayer gone with the failure it said while it had focus: focus goes to Lire.
    FocusOnPlayAfter(swap = state.error != null, held = RETRY, memory, actionRow)
    // The player changed (#58): what fixes it and the open panel's Lire swap places, focus following.
    val trustHold = remember { RemedyHold() }
    val trusting = trustHold.shows(trustsPlayer(problem))
    val play = parts.getOrNull(open - 1)?.item?.let { filmKey(it, PLAY_NAME) } ?: PLAY
    FocusOnPlayAfter(swap = trusting, held = play, memory, actionRow, play = play)
    val onOpen: (Int) -> Unit = { at ->
        openPanel(parts.getOrNull(at - 1), links, onSaga = { sagaShown = cut }, onPart = { partShown = it })
    }
    val films = stringResource(R.string.library_movies)
    val trust = trustHold.takeIf { trusting }
    AmbientBackdrop(roomOf(item, parts, shown ?: open), BackdropStyle.Diffuse, modifier.okClears(links.onClearProblem)) {
        Volets(
            volets,
            open = open,
            shown = shown,
            onFocus = { at -> if (at != null && !homing.value) opened = keys[at].takeIf { at > 0 } },
            onOpen = onOpen,
            modifier = Modifier
                .fillMaxSize()
                .houseLit(LocalHouseLights.current)
                .padding(horizontal = HlLayout.voletSide, vertical = HlLayout.voletTop)
                .focusGroupKey(FocusKey.row(FILMS), memory)
                .arrivingOn(actionRow, unless = acts.any)
                .semantics {
                    contentDescription = films
                    isTraversalGroup = true
                },
            keyed = { at -> parts.getOrNull(at - 1)?.item?.let { Modifier.focusKey(voletKey(it), memory) } ?: Modifier },
            row = { at, sides ->
                val film = parts.getOrNull(at - 1)?.item
                val needs = PanelPlay(memory, problem, links, trust, sides)
                if (at == 0) {
                    SagaActions(item, state, acts, needs, actionRow, onHome = { home = it })
                } else if (film != null) {
                    FilmActions(film, state, needs)
                }
            },
        ) { at -> PanelWords(item, parts.getOrNull(at - 1), tally, onCut = { cut = it }) }
        // Over the page, in its light.
        if (sagaShown) SynopsisSheet(item, onDismiss = { sagaShown = false })
        PartSynopsis(partShown, onDismiss = { partShown = null })
        sheets()
    }
}

/** The saga's actions, in their order. */
private enum class SagaAct { Play, Watched, Edit, Retry }

/** What the saga's actions hold: Lire's film ([target]), its check where the source keeps marks, Modifier, Réessayer. */
private class SagaActs(state: DetailState, links: SagaLinks) {
    val target = state.nextUp
    val marks = state.capabilities?.markPlayed == true
    val edit = links.onEdit
    val fixedBy = state.error?.action

    /** The actions there, in their order. */
    val shown = listOfNotNull(
        SagaAct.Play.takeIf { target != null },
        SagaAct.Watched.takeIf { marks },
        SagaAct.Edit.takeIf { edit != null },
        SagaAct.Retry.takeIf { fixedBy != null },
    )

    /** Whether there is any: the saga's panel has actions under its words. */
    val any = shown.isNotEmpty()

    /** [act] in its place: the first takes [lead], Left leading out of the row; the last [last], Right leading out. */
    fun placed(act: SagaAct, lead: Modifier, last: Modifier): Modifier =
        (if (act == shown.firstOrNull()) lead else Modifier).then(if (act == shown.lastOrNull()) last else Modifier)
}

/** Focus arriving on the page lands here, through [requester], [unless] the saga's actions take it. */
private fun Modifier.arrivingOn(requester: FocusRequester, unless: Boolean): Modifier = if (unless) this else focusRequester(requester)

/**
 * Back from anywhere but the saga's Lire ([away]): [toSaga] opens its panel again, then focus goes to Lire once its
 * actions are laid out, until [home] says it holds it. A film's actions leaving with focus hand it to their panel
 * meanwhile: while the returned state holds, a panel taking focus opens nothing.
 */
@Composable
private fun rememberBackToLire(
    away: Boolean,
    home: () -> Boolean,
    memory: FocusMemory,
    actionRow: FocusRequester,
    toSaga: () -> Unit,
): State<Boolean> {
    val homing = remember { mutableStateOf(false) }
    BackToStart(away) {
        homing.value = true
        toSaga()
        try {
            repeat(HOME_FRAMES) {
                withFrameNanos { }
                if (home()) return@BackToStart
                if (!memory.focus(PLAY)) actionRow.tryRequestFocus()
            }
        } finally {
            homing.value = false
        }
    }
    return homing
}

/**
 * What an open panel's actions need: the page's focus memory, the play that failed, the page's links, the player's
 * remedy, and the panels beside, which Left from the first and Right from the last lead to ([lead], [last]).
 */
@Immutable
private class PanelPlay(
    val memory: FocusMemory,
    val problem: UiError?,
    val links: SagaLinks,
    val trust: RemedyHold?,
    sides: VoletSides,
) {
    val lead = Modifier.focusProperties { left = sides.before }
    val last = Modifier.focusProperties { right = sides.after }
}

/**
 * What an open panel says: the saga's own ([part] null) its name, its facts on one line and its overview, [onCut] told
 * whether they cut it; a film's its title, its facts with its state last, its overview.
 */
@Composable
internal fun PanelWords(item: MediaItem, part: SagaPart?, tally: SagaTally, onCut: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    if (part == null) {
        SagaVoletWords(item.title, item.logo, sagaFacts(tally), item.overview, onCut = onCut, modifier = modifier)
    } else {
        val year = part.year?.toString()?.takeIf { part.released }
        VoletWords(part.title, part.item?.logo, part.item, year, partState(part, tally), part.overview, modifier)
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

/**
 * The saga's panel, its box (its backdrop, its name on its spine, under it where focus is among its films once one is
 * [open]), then a film's each, numbered in release order. The saga's is read « Dune, saga », then its facts and its
 * overview, which OK opens once [cut]; it has actions when it [acts].
 */
@Composable
private fun voletsOf(item: MediaItem, parts: List<SagaPart>, tally: SagaTally, cut: Boolean, open: Int, acts: Boolean): List<VoletPart> {
    val saga = VoletPart(
        key = SAGA_VOLET,
        number = null,
        poster = null,
        backdrop = sagaPicture(item, parts),
        readAs = stringResource(R.string.saga_volet_saga, item.title),
        year = open.takeIf { it > 0 }?.let { stringResource(R.string.saga_place, it, parts.size) },
        spine = item.title,
        say = (sagaFacts(tally) + listOfNotNull(item.overview?.takeIf { it.isNotBlank() })).joinToString(", ").ifEmpty { null },
        acts = cut,
        rowed = acts,
        hint = stringResource(R.string.saga_hint_overview).takeIf { cut },
    )
    return listOf(saga) + parts.mapIndexed { i, part -> filmVolet(part, i + 1, tally) }
}

/**
 * Film [number]'s panel: read by its title, number and year, then its state, its length, what is left of it and its
 * overview; a film held with its actions, OK opening its page; one the library lacks in grey, which OK opens only on
 * its synopsis.
 */
@Composable
private fun filmVolet(part: SagaPart, number: Int, tally: SagaTally): VoletPart {
    val film = part.item
    val year = part.year?.toString()
    val say = listOfNotNull(
        film?.runtimeMs?.let(::formatDuration),
        film?.takeUnless { it.user.played }?.let { timeLeft(it) },
        part.overview?.takeIf { it.isNotBlank() },
    )
    val synopsis = !part.overview.isNullOrBlank()
    return VoletPart(
        key = partKey(number),
        number = number,
        poster = part.poster,
        backdrop = part.backdrop,
        readAs = listOfNotNull(stringResource(R.string.saga_card_name, part.title, number), year).joinToString(", "),
        grey = film == null,
        mark = markOf(part),
        year = year,
        progress = film?.takeUnless { it.user.played }?.progress ?: 0f,
        state = readState(part, tally),
        say = say.joinToString(", ").ifEmpty { null },
        acts = film != null || synopsis,
        rowed = film != null,
        hint = when {
            film != null -> stringResource(R.string.saga_film_page)
            synopsis -> stringResource(R.string.saga_hint_synopsis)
            else -> null
        },
    )
}

/** Film [number]'s panel's key: its place in release order, whether the library holds it or not. */
private fun partKey(number: Int) = "part:$number"

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
 * The saga's actions, under its open panel's words: the line naming the film Lire plays (« À suivre »), else what failed
 * to play or to save, or why the saga could not be read again; then Lire or Reprendre that film ([acts]' target) with
 * its length or what is left of it, Depuis le début once started, « Marquer la saga comme vue », Modifier, and once the
 * saga could not be read again, what fixes it (Réessayer). Focus
 * arrives on the first ([actionRow]), [onHome] told while it holds it. The player changed, « Faire confiance au nouveau
 * lecteur » takes Lire's place, Depuis le début gone with it.
 */
@Composable
private fun SagaActions(
    item: MediaItem,
    state: DetailState,
    acts: SagaActs,
    play: PanelPlay,
    actionRow: FocusRequester,
    onHome: (Boolean) -> Unit,
) {
    val memory = play.memory
    val lead = play.lead.onFocusChanged { onHome(it.isFocused) }
    val last = play.last
    // The panel's actions' width, which their tiles' labels keep within.
    val labels = remember { LabelRoom() }
    Column(Modifier.okClears(play.links.onClearActionError).fillMaxWidth().labelRoom(labels)) {
        ActionsHead(play.problem, state.actionError, state.error, acts.target?.title)
        OnArtwork {
            HlActionRow(Modifier.focusRequester(actionRow)) {
                acts.target?.let { PlayActions(it, SAGA_KEYS, ends = acts.shown.last() == SagaAct.Play, play, labels, lead) }
                if (acts.marks) {
                    SagaWatchedTile(
                        item,
                        { play.links.onMark(item) },
                        labels,
                        acts.placed(SagaAct.Watched, lead, last).focusKey(WATCHED, memory),
                    )
                }
                acts.edit?.let { edit ->
                    HlIconButton(
                        HlIcons.Edit,
                        stringResource(R.string.edit),
                        onClick = edit,
                        modifier = acts.placed(SagaAct.Edit, lead, last).focusKey(EDIT, memory),
                        labelRoom = labels,
                    )
                }
                acts.fixedBy?.let { fixedBy ->
                    // Focus on Lire first: Réessayer goes once the saga is read, and focus lost would enter the panels.
                    val retry = {
                        memory.focus(PLAY)
                        play.links.onRetry()
                    }
                    ErrorFixButton(fixedBy, state.retrying, retry, acts.placed(SagaAct.Retry, lead, last).focusKey(RETRY, memory))
                }
            }
        }
    }
}

/**
 * A film held's actions, under its open panel's words: what failed to play or to save, over them; Lire or Reprendre it
 * with its length or what is left of it, Depuis le début once started, its check marking it alone, and its page, their
 * labels kept within the panel. The player changed, what fixes it in Lire's place.
 */
@Composable
private fun FilmActions(film: MediaItem, state: DetailState, play: PanelPlay) {
    val memory = play.memory
    val labels = remember { LabelRoom() }
    Column(Modifier.okClears(play.links.onClearActionError).fillMaxWidth().labelRoom(labels)) {
        ActionsHead(play.problem, state.actionError, nextUpError = null, next = null)
        OnArtwork {
            HlActionRow {
                val keys = RowKeys(filmKey(film, PLAY_NAME), filmKey(film, RESTART_NAME), filmKey(film, WATCHED_NAME))
                PlayActions(film, keys, ends = false, play, labels, play.lead)
                if (state.capabilities?.markPlayed == true) {
                    val watched = film.user.played
                    HlIconButton(
                        if (watched) HlIcons.CheckFilled else HlIcons.Check,
                        stringResource(if (watched) R.string.mark_unwatched else R.string.mark_watched),
                        onClick = { play.links.onMark(film) },
                        modifier = Modifier.focusKey(keys.watched, memory),
                        labelRoom = labels,
                    )
                }
                HlIconButton(
                    HlIcons.Info,
                    stringResource(R.string.saga_film_page),
                    onClick = { play.links.onOpenItem(film.id) },
                    modifier = Modifier.focusKey(filmKey(film, PAGE_NAME), memory).then(play.last),
                    labelRoom = labels,
                )
            }
        }
    }
}

/** Over an open panel's actions: what failed to play, to save or to read again, else « À suivre » and the [next] film. */
@Composable
private fun ActionsHead(problem: UiError?, actionError: UiError?, nextUpError: UiError?, next: String?) {
    if (failing(problem, actionError, nextUpError)) {
        FailureStatus(problem, actionError, nextUpError)
        Spacer(Modifier.height(HlSpace.s))
    } else if (next != null) {
        NextLine(next)
        Spacer(Modifier.height(HlSpace.s))
    }
}

/** « À suivre », then the film the saga's Lire plays, its name cut at the line's end. */
@Composable
private fun NextLine(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.section_next_up), style = Hl.type.group, color = Hl.colors.content, maxLines = 1)
        Spacer(Modifier.width(HlSpace.s))
        Text(title, style = Hl.type.meta, color = Hl.colors.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The focus keys of a row's play actions: Lire (or what takes its place), Depuis le début, its check. */
@Immutable
private class RowKeys(val play: String, val restart: String, val watched: String)

/** The saga's own: Lire's, as the page's, so focus comes back to it from the player. */
private val SAGA_KEYS = RowKeys(PLAY, RESTART, WATCHED)

/**
 * Lire, or Reprendre once [target] is started, with its length or what is left of it, then Depuis le début: the row's
 * last actions, Right from them leading out, when nothing [ends] after them. Lire is as wide as its words, the tiles
 * after it, its detail going whole before a tile gives way; [modifier] is the row's first action's, its
 * tiles' labels within [labels]. The player changed, what fixes it alone in their place.
 */
@Composable
private fun RowScope.PlayActions(
    target: MediaItem,
    keys: RowKeys,
    ends: Boolean,
    play: PanelPlay,
    labels: LabelRoom,
    modifier: Modifier = Modifier,
) {
    val last = play.last.takeIf { ends }
    val memory = play.memory
    val trust = play.trust
    if (trust != null) {
        // The player changed (#58): what fixes it in Lire's place, keyed as Lire; Depuis le début would start it too.
        TrustPlayerButton(trust, modifier.focusKey(keys.play, memory).then(last ?: Modifier))
        return
    }
    val resume = target.user.positionMs > 0
    HlButton(
        stringResource(if (resume) R.string.resume else R.string.play),
        onClick = { play.links.onPlay(target, resume) },
        modifier = modifier
            .focusKey(keys.play, memory)
            .weight(1f, fill = false)
            .then(last?.takeUnless { resume } ?: Modifier),
        icon = HlIcons.Play,
        detail = playLength(target, resume),
        emphasis = Emphasis.Primary,
        detailWhole = true,
    )
    if (resume) {
        HlIconButton(
            HlIcons.Restart,
            stringResource(R.string.from_start),
            onClick = { play.links.onPlay(target, false) },
            modifier = Modifier.focusKey(keys.restart, memory).then(last ?: Modifier),
            labelRoom = labels,
        )
    }
}

/** What Lire says of [target]: its length, or once started ([resume]) what is left of it, a minute at least. */
private fun playLength(target: MediaItem, resume: Boolean): String? {
    val length = target.runtimeMs ?: return null
    return formatDuration(if (resume) maxOf(length - target.user.positionMs, MINUTE_MS) else length)
}

/** « Marquer la saga comme vue », or « non vue » once it is: it marks the films held; its label within [labels]. */
@Composable
private fun SagaWatchedTile(item: MediaItem, onClick: () -> Unit, labels: LabelRoom, modifier: Modifier = Modifier) {
    val watched = item.user.played
    HlIconButton(
        if (watched) HlIcons.CheckFilled else HlIcons.Check,
        stringResource(if (watched) R.string.mark_saga_unwatched else R.string.mark_saga_watched),
        onClick = onClick,
        modifier = modifier,
        labelRoom = labels,
    )
}

/**
 * The saga's facts on one line: the years of its films out, those held (of those out), those watched once one is, their
 * length, or once one is begun what is left of them, then those to come; the last gives way first, then the length.
 */
@Composable
private fun sagaFacts(tally: SagaTally): List<String> {
    val held = tally.owned.size
    val left = tally.leftMs.takeIf { tally.begun && it > 0 }
    return listOfNotNull(
        tally.years?.let { if (it.first == it.last) "${it.first}" else "${it.first} – ${it.last}" },
        filmCount(held, tally.releasedCount.takeIf { it != held }),
        tally.watched.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.saga_watched, it, it) },
        if (left != null) {
            stringResource(
                R.string.time_left,
                formatDuration(maxOf(left, MINUTE_MS)),
            )
        } else {
            tally.lengthMs?.let(::formatDuration)
        },
        tally.upcoming.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.saga_upcoming_count, it, it) },
    )
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

/** Why a film of the saga is not in the library: « Absent de votre bibliothèque », « Sortie le… », « Annoncé ». */
@Composable
internal fun absentWords(part: SagaPart): String = if (part.released) stringResource(R.string.saga_absent) else releaseWords(part)

/** « Sortie le 15 décembre 2026 », else « Annoncé ». */
@Composable
private fun releaseWords(part: SagaPart): String =
    part.releaseDate?.let { stringResource(R.string.saga_release_on, longDate(it)) } ?: stringResource(R.string.saga_announced)

/** [date] in full in the viewer's language. */
@Composable
@ReadOnlyComposable
private fun longDate(date: LocalDate): String = longDate(date, LocalConfiguration.current.locales[0])

/**
 * [date] in full in [locale]'s words, unbroken where the day comes first and the first of the month is said as such:
 * French « 15 décembre 2026 », « 1er mai 2027 », Italian « 1º dicembre 2026 »; elsewhere the language's long date.
 */
internal fun longDate(date: LocalDate, locale: Locale): String {
    val first = FIRSTS[locale.language]
        ?: return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date)
    val day = if (date.dayOfMonth == 1) first else "${date.dayOfMonth}"
    return listOf(day, DateTimeFormatter.ofPattern(MONTH, locale).format(date), "${date.year}").joinToString("$NBSP")
}

/** A film's panel keyed as the films' row's cards were: back from its page, focus is on it, open. */
private fun voletKey(film: MediaItem) = FocusKey.within(FocusKey.row(FILMS), FocusKey.item(film.id))

/** An action of [film]'s panel, by its [name], kept within its panel: gone, focus falls back on the panel. */
private fun filmKey(film: MediaItem, name: String) = FocusKey.within(voletKey(film), FocusKey.action(name))

/** Réessayer, once the saga could not be read again; Modifier, on the saga's poster and backdrop. */
private val RETRY = FocusKey.action("retry-saga")
private val EDIT = FocusKey.action("edit")

private const val PLAY_NAME = "play"
private const val RESTART_NAME = "restart"
private const val WATCHED_NAME = "watched"
private const val PAGE_NAME = "page"

private const val FILMS = "films"
private const val SAGA_VOLET = "saga"
private const val MINUTE_MS = 60_000L

/** Frames Back waits at most for the saga's actions to be laid out and to keep focus. */
private const val HOME_FRAMES = 5
private const val PERCENT = 100
private const val MONTH = "MMMM"

/** The first of the month where a language writes it as an ordinal, before its month: French's « 1er », Italian's « 1º ». */
private val FIRSTS = mapOf(Locale.FRENCH.language to "1er", Locale.ITALIAN.language to "1º")
