package app.houselights.ui.detail

import app.houselights.ui.util.timeLeft
import app.houselights.ui.design.HlMotion
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.ProviderCapabilities
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.textRoom
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.scrollsAsBlock
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.fadeOutsideSafeArea
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.FormatLines
import app.houselights.ui.design.components.BillingBlock
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.EpisodeHeading
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlActionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HeroSkeleton
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.TechSpecs
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.edit.EditMenu
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.screenHeight
import app.houselights.ui.util.skeletonDelayPassed
import app.houselights.ui.util.UiError
import app.houselights.ui.design.components.SegmentDetail
import app.houselights.ui.util.NBSP
import app.houselights.core.Credits
import app.houselights.core.Job
import app.houselights.core.Person
import androidx.compose.foundation.lazy.itemsIndexed
import app.houselights.ui.util.jobName
import app.houselights.ui.design.components.EndCredits
import app.houselights.ui.design.components.HeadOverview
import app.houselights.ui.design.components.SynopsisSheet
import app.houselights.ui.design.components.Craft
import app.houselights.ui.design.components.CRAFT_NAMES
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.MutableState
import app.houselights.core.SagaPart
import app.houselights.ui.design.components.AbsentCard
import app.houselights.core.Version
import app.houselights.ui.design.components.VersionsSheet
import app.houselights.ui.util.versionName
import app.houselights.core.hasPage

@Composable
fun DetailScreen(
    itemId: String,
    onPlay: (MediaItem, Boolean) -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenNfo: (String) -> Unit,
    onEditArtwork: (String, ArtworkKind) -> Unit,
    onIdentify: (String) -> Unit,
    onOpenPerson: (String) -> Unit = {},
) {
    val app = appContainer()
    val vm: DetailViewModel = viewModel(key = "detail-$itemId", factory = DetailViewModel.factory(app, itemId))
    ReloadOnLibraryChange { vm.onLibraryChanged() }
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.item
    // A failure to play stays until the next action (OK, wherever it is pressed) or the next screen.
    DisposableEffect(Unit) { onDispose { app.playback.clearProblem() } }

    // Each arrival puts focus back on what opened another screen (Modifier, the NFO, a film of a
    // row); a first visit starts on the first action, the play button.
    val memory = rememberFocusMemory()
    val actionRow = remember { FocusRequester() }
    // Once its rows are in: coming back, focus may return to one of their cards.
    RestoreFocus(memory, actionRow, ready = item != null && !state.partial)
    // A film started over is resumed next: back from the player, focus is on the play button.
    LaunchedEffect(Unit) {
        app.playback.playbackEnded.collect { if (memory.last == RESTART) memory.focus(PLAY) }
    }
    val page = rememberLazyListState()
    // Directors and a show's creators, named in the cast row by their job.
    val directing = jobName(Job.DIRECTOR)
    val creating = jobName(Job.CREATOR)
    val people = state.item?.let { peopleOf(it, state.credits, directing, creating) }.orEmpty()

    if (item == null) {
        NoTitleYet(state.error, onRetry = vm::load)
        return
    }

    // Down the page, Back returns to the actions; from the top, it leaves.
    val scrolled by remember {
        derivedStateOf { backReturnsToActions(page.firstVisibleItemIndex, page.firstVisibleItemScrollOffset) }
    }
    BackToStart(away = scrolled) {
        page.animateScrollToItem(0)
        actionRow.tryRequestFocus()
    }

    val screenHeight = screenHeight()
    val focusedEpisode = remember { mutableStateOf<MediaItem?>(null) }
    var showTech by remember { mutableStateOf(false) }
    val media = item.media?.takeIf { it.video != null || it.audio.isNotEmpty() }
    // Beside the page: what the file holds, read row by row.
    if (showTech && media != null) TechSpecs(item, onDismiss = { showTech = false })
    // Beside the page: the film's copies, the one Lire plays chosen there.
    var showVersions by remember { mutableStateOf(false) }
    if (showVersions) VersionsSheet(item, state.versions, onChoose = vm::chooseVersion, onDismiss = { showVersions = false })
    // Beside the page: the overview the hero cuts, whole.
    var showSynopsis by remember { mutableStateOf(false) }
    if (showSynopsis) SynopsisSheet(item, onDismiss = { showSynopsis = false })
    var showEdit by remember { mutableStateOf(false) }
    if (showEdit) {
        EditMenu(
            item = item,
            onDismiss = { showEdit = false },
            onArtwork = { kind -> showEdit = false; onEditArtwork(item.id, kind) },
            onIdentify = { showEdit = false; onIdentify(item.id) },
        )
    }
    val actions = DetailActions(
        onPlay = onPlay,
        onTogglePlayed = vm::togglePlayed,
        onToggleFavorite = vm::toggleFavorite,
        onOpenSeries = { item.seriesId?.let(onOpenItem) },
        onOpenNfo = { onOpenNfo(item.id) },
        // Left out where there is nothing to open: no technical sheet, a film of one copy.
        onShowTech = { showTech = true }.takeIf { media != null },
        onShowVersions = { showVersions = true }.takeIf { state.versions.size > 1 },
        onShowSynopsis = { showSynopsis = true },
        copy = state.playedCopy,
        copyIndex = state.playedCopyIndex,
        onEdit = { showEdit = true }.takeIf { state.canEdit },
    )

    val recede = rememberRecede { scrolled }

    AmbientBackdrop(
        item.backdrop,
        BackdropStyle.Full,
        Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key in OK_KEYS) {
                app.playback.clearProblem()
                vm.clearActionError()
            }
            false
        },
        recede = { recede.value },
    ) {
        // The page turns by whole blocks: a row coming into view rises to the safe area, and
        // the gap between blocks, wider than that keyline, takes the one above off the screen.
        ListScroll(top = HlFocus.edge, toTop = true) { LazyColumn(
                Modifier.fillMaxSize().houseLit(LocalHouseLights.current),
                state = page,
                contentPadding = PaddingValues(bottom = HlFocus.edge),
                verticalArrangement = Arrangement.spacedBy(HlLayout.detailGap),
            ) {
                item(key = "hero") {
                    // The screen less the first row's header and a strip of its images; taller
                    // rather than squeezed when the text is long.
                    Box(Modifier.fillMaxWidth().heightIn(min = HlLayout.detailHeroMin(screenHeight)).scrollsAsBlock()) {
                        Hero(
                            item = item,
                            nextUp = state.nextUp,
                            capabilities = state.capabilities,
                            actionError = state.actionError,
                            actions = actions,
                            memory = memory,
                            actionRow = actionRow,
                            modifier = Modifier
                                .padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)
                                .align(Alignment.BottomStart),
                        )
                    }
                }

                seasonRows(state, memory, focusedEpisode, vm, onPlay)
                titleRows(item, state, memory, people, RowLinks(onOpenItem, onOpenPerson))
                endRows(item, state.credits)
            } }
    }
}

/** A show's seasons and the episodes of the one chosen, under the hero; a single season names its episodes' row. */
private fun LazyListScope.seasonRows(
    state: DetailState,
    memory: FocusMemory,
    /** The episode with focus, read where it is shown. */
    focusedEpisode: MutableState<MediaItem?>,
    vm: DetailViewModel,
    onPlay: (MediaItem, Boolean) -> Unit,
) {
    val item = state.item ?: return
    if (item.kind == MediaKind.SERIES && state.seasons.isNotEmpty()) {
        // A track with one option is no choice: a single season names the episode row instead.
        val single = state.seasons.singleOrNull()
        if (single == null) {
            item(key = "seasons") {
                Box(Modifier.scrollsAsBlock()) { Seasons(state.seasons, state.selectedSeasonId, vm::selectSeason) }
            }
        }
        item(key = "episodes-${state.selectedSeasonId}") {
            val failed = state.episodesError
            // Réessayer had focus: once the episodes are there, focus goes to them, not nowhere.
            LaunchedEffect(failed == null && state.episodes.isNotEmpty()) {
                if (failed == null && state.episodes.isNotEmpty() && memory.last == RETRY_EPISODES) {
                    withFrameNanos { }
                    memory.focus(FocusKey.row(EPISODES))
                }
            }
            when {
                failed != null -> EpisodesFailed(
                    single?.title,
                    failed,
                    retrying = state.episodesLoading,
                    onRetry = vm::retrySeason,
                    action = Modifier.focusKey(RETRY_EPISODES, memory),
                )
                state.episodesLoading && state.episodes.isEmpty() -> EpisodesSkeleton(single?.title)
                else -> Episodes(
                    header = single?.title,
                    episodes = state.episodes,
                    memory = memory,
                    focused = focusedEpisode.value,
                    onFocus = { focusedEpisode.value = it },
                    onPlay = { ep -> onPlay(ep, ep.user.positionMs > 0) },
                )
            }
        }
    }
}

/** A collection's films, its saga whole; a film's saga, the film in its place. */
private fun LazyListScope.sagaRows(item: MediaItem, state: DetailState, memory: FocusMemory, onOpenItem: (String) -> Unit) {
    // A collection's saga whole, in the order its films came out: those the library lacks keep their
    // place, as empty frames. Without the saga, the films it holds.
    val parts = state.saga?.parts ?: state.collectionFilms.map { SagaPart(it, it.title, it.year) }
    if (item.kind == MediaKind.COLLECTION && parts.isNotEmpty()) {
        item(key = "films") {
            MediaRow(stringResource(R.string.library_movies), memory.rowGroup(FILMS)) {
                itemsIndexed(parts, key = { i, part -> part.item?.id ?: "absent-$i" }) { _, part ->
                    val film = part.item
                    if (film != null) {
                        PosterCard(film, onClick = { onOpenItem(film.id) }, modifier = memory.card(FILMS, film))
                    } else {
                        AbsentCard(part.title, part.year)
                    }
                }
            }
        }
    }

    // A film's saga: its films in order, the film itself in its place, under the saga's name; a saga
    // of this film alone, no row.
    state.collection?.takeIf { state.collectionFilms.any { film -> film.id != item.id } }?.let { collection ->
        item(key = "collection") {
            MediaRow(collection.title, memory.rowGroup(SAGA)) {
                items(state.collectionFilms, key = { it.id }) { f ->
                    PosterCard(f, onClick = { if (f.id != item.id) onOpenItem(f.id) }, modifier = memory.card(SAGA, f))
                }
            }
        }
    }
}

/**
 * The rows under a title's head: a collection's films, a film's saga, its people ([people]), the
 * titles like it.
 */
private fun LazyListScope.titleRows(
    item: MediaItem,
    state: DetailState,
    memory: FocusMemory,
    people: List<Person>,
    links: RowLinks,
) {
    val onOpenItem = links.onOpenItem
    val onOpenPerson = links.onOpenPerson
    sagaRows(item, state, memory, onOpenItem)

    if (people.isNotEmpty()) {
        item(key = "cast") {
            MediaRow(stringResource(R.string.cast), memory.rowGroup(CAST), spacing = HlSpace.s) {
                itemsIndexed(people) { i, person ->
                    // Back from a person's page, focus returns to them.
                    val key = FocusKey.within(FocusKey.row(CAST), FocusKey.item("$i:${person.id ?: person.name}"))
                    PersonCard(
                        person,
                        Modifier.focusKey(key, memory),
                        onOpen = person.id?.takeIf { person.hasPage }?.let { id -> { onOpenPerson(id) } },
                    )
                }
            }
        }
    }

    if (state.similar.isNotEmpty()) {
        item(key = "similar") {
            MediaRow(stringResource(R.string.similar), memory.rowGroup(SIMILAR)) {
                items(state.similar, key = { it.id }) { s ->
                    PosterCard(s, onClick = { onOpenItem(s.id) }, modifier = memory.card(SIMILAR, s))
                }
            }
        }
    }
}

/** Where a title's page ends, as a film does: the crafts its head does not name. */
private fun LazyListScope.endRows(item: MediaItem, credits: Credits?) {
    val crafts = endCrafts(item, credits)
    if (crafts.isNotEmpty()) {
        item(key = "credits") {
            EndCredits(
                crafts.map { (job, names) -> Craft(jobName(job), names) },
                Modifier.padding(start = HlLayout.insetRows, end = HlSpace.safeH),
            )
        }
    }
}

/**
 * A page whose title is not read yet: the picture handed on, then where its title and facts will
 * be once loading takes long enough to be seen, or why it could not be read ([error]).
 */
@Composable
private fun NoTitleYet(error: UiError?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(null, BackdropStyle.Full)
        Box(Modifier.padding(top = HlLayout.errorTop)) {
            // Trying again keeps the error on screen, its button at work, until the answer.
            val loading = error == null
            val shown = retainedError(error, retrying = loading)
            when {
                shown != null -> HlErrorState(shown, onRetry, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l), loading)
                skeletonDelayPassed() -> HeroSkeleton(Modifier.padding(start = HlSpace.safeH, top = HlSpace.l))
            }
        }
    }
}

/**
 * How far the film steps back as the page goes down ([away]): the rows read on the room, not on a
 * face. A page that comes back already scrolled is dark at once; then the veil follows the page
 * over SLOW.
 */
@Composable
private fun rememberRecede(away: () -> Boolean): Animatable<Float, *> {
    val recede = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow(away).collect { down ->
            val target = if (down) 1f else 0f
            if (first) recede.snapTo(target) else recede.animateTo(target, tween(HlMotion.SLOW, easing = HlMotion.standard))
            first = false
        }
    }
    return recede
}

/** Where a page's rows lead: another title, a person's page. */
@Immutable
private class RowLinks(val onOpenItem: (String) -> Unit, val onOpenPerson: (String) -> Unit)

/**
 * What the actions of a detail page do, built once by [DetailScreen] and handed down as it is: each
 * by its name, so two of the same type cannot change places on the way. [onShowTech] and [onEdit]
 * are null for a title with no technical sheet, or that cannot be edited: their buttons are left out.
 */
@Immutable
private data class DetailActions(
    val onPlay: (MediaItem, resume: Boolean) -> Unit,
    val onTogglePlayed: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onOpenSeries: () -> Unit,
    val onOpenNfo: () -> Unit,
    val onShowTech: (() -> Unit)?,
    /** The film's copies, null for a film of one copy. */
    val onShowVersions: (() -> Unit)?,
    /** The overview whole, when the hero cuts it. */
    val onShowSynopsis: () -> Unit,
    /** The copy Lire plays, among several, and its place among them (it names it); null for one copy. */
    val copy: Version? = null,
    val copyIndex: Int = 0,
    val onEdit: (() -> Unit)?,
)

@Composable
private fun Hero(
    item: MediaItem,
    nextUp: MediaItem?,
    capabilities: ProviderCapabilities?,
    actionError: UiError?,
    actions: DetailActions,
    memory: FocusMemory,
    actionRow: FocusRequester,
    modifier: Modifier,
) {
    val isEpisode = item.kind == MediaKind.EPISODE

    Column(modifier) {
        // The text keeps a reading measure; the actions below may run wider, within the safe area.
        // At the end of a page the last rows cannot rise to the top: what is left of the hero above
        // them fades out instead of showing a sliver.
        Column(Modifier.widthIn(max = HlLayout.readableNarrow).fadeOutsideSafeArea()) {
            TitleArt(
                title = if (isEpisode) item.seriesTitle ?: item.title else item.title,
                logo = item.logo,
                maxWidth = HlLayout.titleArtWidth,
                maxHeight = HlLayout.titleArtHeight,
                // Without its logo, the title is set to its measure, as on a poster.
                measured = true,
            )
            Spacer(Modifier.height(HlSpace.l))
            if (isEpisode) {
                EpisodeHeading(item.episodeLabel, item.title)
                Spacer(Modifier.height(HlSpace.xs))
            }
            MetaLine(item)
            // The picture, then the sound, as the back of a case lists them.
            if (item.badges.isNotEmpty() || item.media?.video != null || item.media?.audio?.isNotEmpty() == true) {
                Spacer(Modifier.height(HlSpace.s))
                FormatLines(item)
            }
            // The tagline introduces the overview, quieter than the title above it.
            item.tagline?.let {
                Spacer(Modifier.height(HlSpace.l))
                Text(it, style = Hl.type.lead, color = Hl.colors.contentSecondary)
            }
            if (!item.overview.isNullOrBlank()) {
                Spacer(Modifier.height(if (item.tagline != null) HlSpace.xs else HlSpace.l))
                // Cut, it opens whole beside the page; down from it is the first action, as up from that is it.
                HeadOverview(item.overview, onOpen = actions.onShowSynopsis, Modifier.focusProperties { down = actionRow })
            }
            if (item.directors.isNotEmpty() || item.cast.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.m))
                BillingBlock(item.directors, item.cast.map { it.name })
            }
        }
        Spacer(Modifier.height(HlSpace.xl))

        Actions(item, nextUp, capabilities, actionError, actions, memory, actionRow)
    }
}

/**
 * What can be done with the title ([actions]), in one row. Room under it for the label of a round
 * button: nothing moves when it shows. [actionRow] focuses the first of them.
 */
@Composable
private fun Actions(
    item: MediaItem,
    nextUp: MediaItem?,
    capabilities: ProviderCapabilities?,
    actionError: UiError?,
    actions: DetailActions,
    memory: FocusMemory,
    actionRow: FocusRequester,
) {
    val problem by appContainer().playback.playbackProblem.collectAsStateWithLifecycle()
    HlActionRow(Modifier.focusRequester(actionRow).fadeOutsideSafeArea()) {
        PlayButtons(item, nextUp, memory, actions)
        Parts(item, memory, actions.onPlay)
        // A film in several copies: which one Lire plays is chosen beside the page.
        if (actions.onShowVersions != null) {
            HlIconButton(
                HlIcons.Versions,
                stringResource(R.string.versions),
                onClick = actions.onShowVersions,
                modifier = Modifier.focusKey(VERSIONS, memory),
            )
        }
        if (item.kind == MediaKind.EPISODE && item.seriesId != null) {
            HlButton(stringResource(R.string.view_series), onClick = actions.onOpenSeries, modifier = Modifier.focusKey(SERIES, memory))
        }
        // A toggle says what OK does; its icon says where the title stands. Watched is done, not a
        // choice: the check fills, in projector white.
        if (capabilities?.markPlayed == true) {
            HlIconButton(
                if (item.user.played) HlIcons.CheckFilled else HlIcons.Check,
                stringResource(if (item.user.played) R.string.mark_unwatched else R.string.mark_watched),
                onClick = actions.onTogglePlayed,
                modifier = Modifier.focusKey(WATCHED, memory),
            )
        }
        // A button bears the name of what it opens.
        if (actions.onShowTech != null) {
            HlIconButton(
                HlIcons.Info,
                stringResource(R.string.tech_details),
                onClick = actions.onShowTech,
                modifier = Modifier.focusKey(TECH, memory),
            )
        }
        if (item.hasNfo) {
            HlIconButton(
                HlIcons.Document,
                stringResource(R.string.nfo_title),
                onClick = actions.onOpenNfo,
                modifier = Modifier.focusKey(NFO, memory),
            )
        }
        if (capabilities?.favorites == true && item.kind != MediaKind.COLLECTION) {
            HlIconButton(
                if (item.user.favorite) HlIcons.HeartFilled else HlIcons.Heart,
                stringResource(if (item.user.favorite) R.string.remove_favorite else R.string.add_favorite),
                onClick = actions.onToggleFavorite,
                modifier = Modifier.focusKey(FAVORITE, memory),
                lit = item.user.favorite,
            )
        }
        if (actions.onEdit != null) {
            HlIconButton(HlIcons.Edit, stringResource(R.string.edit), onClick = actions.onEdit, modifier = Modifier.focusKey(EDIT, memory))
        }
        ActionFailure(problem, actionError)
    }
}

/**
 * Why the last title asked for did not play ([problem]), or a change did not take ([actionError]),
 * beside the actions that asked for it.
 */
@Composable
private fun RowScope.ActionFailure(problem: UiError?, actionError: UiError?) {
    val (title, error) = problem?.let { R.string.play_failed to it } ?: actionError?.let { R.string.action_not_saved to it } ?: return
    HlStatus(
        StatusState.Attention,
        stringResource(title),
        Modifier.align(Alignment.CenterVertically).padding(start = HlSpace.m).weight(1f, fill = false),
        detail = stringResource(error.message),
    )
}

/**
 * One button that says Lire or Reprendre, so that a film ended half seen keeps focus on it, and
 * beside it, once started, the way to start over. A series plays through its next episode, a
 * collection through its next film.
 */
@Composable
private fun PlayButtons(item: MediaItem, nextUp: MediaItem?, memory: FocusMemory, actions: DetailActions) {
    val onPlay = actions.onPlay
    val series = item.kind == MediaKind.SERIES || item.kind == MediaKind.COLLECTION
    // A film in several copies plays the one chosen, from where that copy was left.
    val copy = actions.copy
    val target = (if (series) nextUp else copy?.item ?: item.takeIf { it.isPlayable }) ?: return
    val resume = target.user.positionMs > 0
    val episode = whatLirePlays(item, target, copy, actions.copyIndex)
    HlButton(
        stringResource(if (resume) R.string.resume else R.string.play),
        onClick = { onPlay(target, resume) },
        modifier = Modifier.focusKey(PLAY, memory),
        icon = Icons.Filled.PlayArrow,
        detail = playDetail(episode, target, resume, copy != null),
        emphasis = Emphasis.Primary,
    )
    // A film in several files starts over with its first part, beside this button (see Parts).
    if (resume && item.parts.isEmpty()) {
        HlIconButton(
            HlIcons.Restart,
            stringResource(R.string.from_start),
            onClick = { onPlay(target, false) },
            modifier = Modifier.focusKey(RESTART, memory),
        )
    }
}

/** What Lire names of what it starts: the episode, the film of a saga, the edition of a film in copies. */
@Composable
private fun whatLirePlays(item: MediaItem, target: MediaItem, copy: Version?, copyIndex: Int): String? = when {
    item.kind == MediaKind.SERIES -> target.episodeLabel
    item.kind == MediaKind.COLLECTION -> target.title
    copy != null -> versionName(copy.edition, copy.label, copyIndex)
    else -> null
}

/**
 * What Lire says beside its name: what it starts ([what]) and the time left when it [resume]s; the
 * length of a copy ("Lire  Version longue  2 h 28"), of a film; else what it starts alone.
 */
@Composable
private fun playDetail(what: String?, target: MediaItem, resume: Boolean, copy: Boolean): String? = when {
    resume -> listOfNotNull(what, timeLeft(target)).joinToString(FACT_GAP)
    copy -> listOfNotNull(what, target.runtimeMs?.let(::formatDuration)).joinToString(FACT_GAP)
    else -> what ?: target.runtimeMs?.let(::formatDuration)
}

/**
 * A film in several files, which the player plays one file at a time: beside Lire, which resumes its
 * first part, each part from its start, "Partie 1/2", "Partie 2/2". None for a film in one file.
 * From three parts on, their buttons would push the last round buttons out of the row: one button,
 * "Parties", opens them in a menu instead.
 */
@Composable
private fun Parts(item: MediaItem, memory: FocusMemory, onPlay: (MediaItem, Boolean) -> Unit) {
    val count = item.parts.size
    if (partsInMenu(count)) {
        val labels = List(count) { stringResource(R.string.part_of, it + 1, count) }
        HlMenuButton(
            text = stringResource(R.string.parts),
            icon = HlIcons.Playback,
            sections = listOf(MenuSection(null, partOptions(item.parts, labels) { onPlay(it, false) })),
            modifier = Modifier.focusKey(PARTS, memory),
            detail = count.toString(),
        )
        return
    }
    item.parts.forEachIndexed { i, part ->
        HlButton(
            stringResource(R.string.part_of, i + 1, item.parts.size),
            onClick = { onPlay(part, false) },
            modifier = Modifier.focusKey(FocusKey.action("part-${i + 1}"), memory),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Seasons(seasons: List<MediaItem>, selectedId: String?, onSelect: (String) -> Unit) {
    HlSegmented(
        options = seasons.map { it.title },
        selected = seasons.indexOfFirst { it.id == selectedId },
        onSelect = { onSelect(seasons[it].id) },
        modifier = Modifier.padding(horizontal = HlSpace.safeH),
        selectOnFocus = true,
        scrollable = true,
        details = seasons.map(::seasonDetail),
    )
}

/** How far a season is watched, after its name: a check when all of it is, "6 / 10" when part is, nothing before. */
internal fun seasonDetail(season: MediaItem): SegmentDetail? {
    val episodes = season.childCount?.takeIf { it > 0 } ?: return SegmentDetail.Done.takeIf { season.user.played }
    val watched = episodes - (season.user.unplayedCount ?: if (season.user.played) 0 else episodes)
    return when {
        season.user.played || watched >= episodes -> SegmentDetail.Done
        watched > 0 -> SegmentDetail.Count("$watched$NBSP/$NBSP$episodes")
        else -> null
    }
}

/** Where an episode stands, in words: watched, or the time left; nothing when not started. */
@Composable
private fun episodeState(ep: MediaItem): String? =
    if (ep.user.played) stringResource(R.string.watched) else timeLeft(ep)

@Composable
private fun Episodes(
    header: String?,
    episodes: List<MediaItem>,
    memory: FocusMemory,
    focused: MediaItem?,
    onFocus: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    // The row and the overview of its focused episode come into view together.
    Column(Modifier.scrollsAsBlock()) {
        MediaRow(header = header, modifier = memory.rowGroup(EPISODES), spacing = HlSpace.l) {
            items(episodes, key = { it.id }) { ep ->
                LandscapeCard(
                    item = ep,
                    modifier = memory.card(EPISODES, ep),
                    onClick = { onPlay(ep) },
                    width = HlSize.episode,
                    title = ep.title,
                    number = ep.episodeNumbers,
                    facts = listOf(ep.runtimeMs?.let(::formatDuration), episodeState(ep)),
                    onFocused = { onFocus(ep) },
                )
            }
        }
        val shown = focused?.takeIf { f -> episodes.any { it.id == f.id } }
        Text(
            shown?.overview ?: "",
            style = Hl.type.caption,
            color = Hl.colors.contentSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            // Three lines at the viewer's text size, so the page stays still as focus moves; more if a script needs it.
            modifier = Modifier
                .padding(horizontal = HlSpace.safeH)
                .widthIn(max = HlLayout.readable)
                .heightIn(min = textRoom(HlLayout.episodeOverview))
                .fadeOutsideSafeArea(),
        )
    }
}

/**
 * Empty frames where a season's episodes will be, once loading takes long enough to be seen. Laid
 * out from the start all the same, unseen: the rows below stay where they are when they show.
 */
@Composable
private fun EpisodesSkeleton(header: String?) {
    val seen = skeletonDelayPassed()
    Column(Modifier.scrollsAsBlock()) {
        header?.let { SectionHeader(it, Modifier.padding(start = HlSpace.safeH, bottom = HlSpace.xxs)) }
        Row(
            Modifier.padding(start = HlSpace.safeH).alpha(if (seen) 1f else 0f),
            horizontalArrangement = Arrangement.spacedBy(HlSpace.l),
        ) {
            repeat(EPISODE_SKELETONS) { CardSkeleton(HlSize.episode, 16f / 9f) }
        }
    }
}

/**
 * A season's episodes could not load: why, where the row would be, and what fixes it; trying again
 * ([retrying]), the row stays, its button at work and focused.
 */
@Composable
private fun EpisodesFailed(header: String?, error: UiError, retrying: Boolean, onRetry: () -> Unit, action: Modifier) {
    Column(Modifier.scrollsAsBlock().padding(horizontal = HlSpace.safeH)) {
        header?.let { SectionHeader(it, Modifier.padding(bottom = HlSpace.xxs)) }
        PartFailed(error, retrying, onRetry, action = action)
    }
}

/** A film's parts go in a menu from three on: two buttons beside Lire still leave the row its round buttons. */
internal fun partsInMenu(count: Int): Boolean = count > INLINE_PARTS

/**
 * A film's [parts] as the options of its menu, in order, each named by [labels]. None is chosen,
 * so the menu opens on the first part; choosing one plays it from its start ([onPlay]).
 */
internal fun partOptions(parts: List<MediaItem>, labels: List<String>, onPlay: (MediaItem) -> Unit): List<MenuOption> =
    parts.mapIndexed { i, part -> MenuOption(labels[i], selected = false) { onPlay(part) } }

/** Back returns to the actions once the page has scrolled down; at its top, it leaves. */
internal fun backReturnsToActions(firstVisibleItem: Int, offset: Int): Boolean = firstVisibleItem > 0 || offset > 0

/** A row of the page as a group: a card gone since focus left it, focus comes back to its row. */
private fun FocusMemory.rowGroup(row: String) = Modifier.focusGroupKey(FocusKey.row(row), this)

/** A card of a row of the page: the same title may sit in two rows. */
private fun FocusMemory.card(row: String, item: MediaItem) =
    Modifier.focusKey(FocusKey.within(FocusKey.row(row), FocusKey.item(item.id)), this)

private val PLAY = FocusKey.action("play")
private val VERSIONS = FocusKey.action("versions")
private val RESTART = FocusKey.action("restart")
private val SERIES = FocusKey.action("series")
private val WATCHED = FocusKey.action("watched")
private val TECH = FocusKey.action("tech")
private val NFO = FocusKey.action("nfo")
private val FAVORITE = FocusKey.action("favorite")
private val EDIT = FocusKey.action("edit")
private val PARTS = FocusKey.action("parts")

/** The parts a film's actions row holds as buttons, beside Lire and the round buttons. */
private const val INLINE_PARTS = 2
private const val EPISODES = "episodes"

/** Réessayer of a season that failed, in its row: coming back, focus finds the row. */
private val RETRY_EPISODES = FocusKey.within(FocusKey.row(EPISODES), FocusKey.action("retry"))
private const val FILMS = "films"
private const val SAGA = "collection"
private const val SIMILAR = "similar"
private const val CAST = "cast"

/**
 * The people of [item]'s row, direction first: its directors ([directing]), a show's creators
 * ([creating]), named by their job, then its cast in billing order. Without credits from the
 * source, its cast alone.
 */
internal fun peopleOf(item: MediaItem, credits: Credits?, directing: String, creating: String): List<Person> {
    if (credits == null) return item.cast
    val show = item.kind == MediaKind.SERIES
    val role = if (show) creating else directing
    val direction = credits.crew[if (show) Job.CREATOR else Job.DIRECTOR].orEmpty().map { it.copy(role = role) }
    return (direction + credits.cast).ifEmpty { item.cast }
}

/**
 * The crafts of [item]'s end credits, those its head does not name (its direction, a show's
 * creators, are in its billing block and its people): screenplay, music, cinematography, editing;
 * up to [CRAFT_NAMES] names each. A show's crafts change from one episode to the next: its music
 * alone. Without credits from the source, none.
 */
internal fun endCrafts(item: MediaItem, credits: Credits?): List<Pair<Job, List<String>>> {
    val crew = credits?.crew ?: return emptyList()
    val jobs = if (item.kind == MediaKind.SERIES) SHOW_CRAFTS else FILM_CRAFTS
    return jobs.mapNotNull { job ->
        crew[job].orEmpty().map { it.name }.distinct().take(CRAFT_NAMES).takeIf { it.isNotEmpty() }?.let { job to it }
    }
}

private val FILM_CRAFTS = listOf(Job.WRITER, Job.COMPOSER, Job.CINEMATOGRAPHY, Job.EDITING)
private val SHOW_CRAFTS = listOf(Job.COMPOSER)


/** Frames shown while a season loads: as many episodes as the screen shows whole. */
private const val EPISODE_SKELETONS = 3

/** The keys that act on what has focus. */
private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
