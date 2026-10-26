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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.AudioTrack
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
import app.houselights.ui.design.OnArtwork
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
import app.houselights.ui.design.components.filmCount
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.TechSpecs
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.edit.EditMenu
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.run
import app.houselights.ui.util.okClears
import app.houselights.ui.util.PlayOrigin
import app.houselights.ui.util.PlaybackOutcome
import app.houselights.ui.util.PlaybackProblem
import app.houselights.ui.util.heldSince
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
import app.houselights.ui.design.components.HeadOverview
import app.houselights.ui.design.components.SynopsisSheet
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.MutableState
import app.houselights.core.SagaPart
import app.houselights.ui.design.components.AbsentCard
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan
import app.houselights.core.Version
import app.houselights.ui.design.components.TracksSheet
import app.houselights.ui.design.components.VersionsSheet
import app.houselights.ui.design.components.tracksHint
import app.houselights.ui.util.versionName
import app.houselights.core.hasPage

@Composable
fun DetailScreen(
    itemId: String,
    /** The page's back stack entry, which its plays are asked from. */
    screen: String,
    onPlay: (MediaItem, Boolean, PlayOrigin) -> Unit,
    onOpenItem: (String) -> Unit,
    onEditArtwork: (String, ArtworkKind) -> Unit,
    onIdentify: (String) -> Unit,
    onOpenPerson: (String) -> Unit = {},
    /**
     * Leaves the page, back where it was opened from, once its title held is removed; given an id, for that title's page
     * then: a copy of the film left under another row.
     */
    onLeave: (String?) -> Unit = {},
) {
    val app = appContainer()
    val vm: DetailViewModel = viewModel(key = "detail-$itemId", factory = DetailViewModel.factory(app, itemId))
    ReloadOnLibraryChange { scanOnly -> vm.onLibraryChanged(scanOnly) }
    DetailPage(vm, app.playback, screen, onPlay, onOpenItem, onEditArtwork, onIdentify, onOpenPerson, onLeave)
}

/**
 * A title's page as [vm] has it; [playback] says when a title ended and why one asked from this [screen] did not play:
 * beside the actions, or on the episode row for an episode.
 */
@Composable
internal fun DetailPage(
    vm: DetailViewModel,
    playback: PlaybackOutcome,
    screen: String,
    onPlay: (MediaItem, Boolean, PlayOrigin) -> Unit,
    onOpenItem: (String) -> Unit,
    onEditArtwork: (String, ArtworkKind) -> Unit,
    onIdentify: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
    onLeave: (String?) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.item
    LeaveOnceRemoved(state.removed, onLeave)
    // A failure to play stays until the next OK on the page, another play or another screen.
    val asked = playback.playbackProblem.collectAsStateWithLifecycle().value?.takeIf { it.origin.screen == screen }
    val problem = asked?.takeIf { it.origin.episode == null }?.error
    val episodeProblem = asked?.takeIf { it.origin.episode != null }

    // Focus returns to what opened another screen; a first visit starts on the play button.
    val memory = rememberFocusMemory()
    val actionRow = remember { FocusRequester() }
    // Wait for the rows: returning focus may target one of their cards.
    RestoreFocus(memory, actionRow, ready = item != null && !state.partial)
    // A film started over is resumed next: back from the player, focus is on the play button.
    LaunchedEffect(Unit) {
        playback.playbackEnded.collect { if (memory.last == RESTART) memory.focus(PLAY) }
    }
    val page = rememberLazyListState()
    val directing = jobName(Job.DIRECTOR)
    val creating = jobName(Job.CREATOR)
    val people = state.item?.let { peopleOf(it, state.credits, directing, creating) }.orEmpty()

    if (item == null) {
        NoTitleYet(state.error, onRetry = vm::load)
        return
    }

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
    var showVersions by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    val tracks = state.tracksToChoose
    var showSynopsis by remember { mutableStateOf(false) }
    var showNfo by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    // The episode whose options are open, and the watched tile's.
    var episodeOptions by remember { mutableStateOf<String?>(null) }
    var showMarks by remember { mutableStateOf(false) }
    val actions = DetailActions(
        onPlay = { title, resume -> onPlay(title, resume, PlayOrigin(screen)) },
        onTogglePlayed = vm::togglePlayed,
        onToggleSeasonPlayed = vm::toggleSeasonPlayed,
        onToggleFavorite = vm::toggleFavorite,
        onOpenSeries = { item.seriesId?.let(onOpenItem) },
        onOpenNfo = { showNfo = true },
        // Null hides the button: nothing for the technical sheet, or a single copy.
        onShowTech = { showTech = true }.takeIf { media != null },
        onShowVersions = { showVersions = true }.takeIf { state.versions.size > 1 },
        onShowTracks = { showTracks = true },
        tracks = tracks,
        onShowSynopsis = { showSynopsis = true },
        copy = state.playedCopy,
        copyIndex = state.playedCopyIndex,
        onEdit = { showEdit = true }.takeIf { state.canEdit },
        season = state.shownSeason,
        onShowMarks = { showMarks = true },
        nextUpError = state.lireError,
        retrying = state.retrying,
        onRetry = vm::retry,
        // A title held while its file is missing: out of the library alone, nothing deleted on the NAS.
        onRemove = vm::removeHeld.takeIf { item.held },
    )
    // Réessayer gone with the failure it said, or Lire and the Réessayer in its place swapped: focus goes to Lire.
    FocusOnPlayAfter(swap = state.error != null, held = RETRY_PAGE, memory, actionRow)
    FocusOnPlayAfter(swap = state.nextUpError != null, held = PLAY, memory, actionRow)

    val recede = rememberRecede { scrolled }

    AmbientBackdrop(
        item.backdrop,
        BackdropStyle.Full,
        Modifier.okClears(playback::clearProblem),
        recede = { recede.value },
    ) {
        // Scrolls by blocks: an incoming row rises to the safe area; the wider block gap hides the row above.
        ListScroll(top = HlFocus.edge, toTop = true) { LazyColumn(
                Modifier.fillMaxSize().houseLit(LocalHouseLights.current),
                state = page,
                contentPadding = PaddingValues(bottom = HlFocus.edge),
                verticalArrangement = Arrangement.spacedBy(HlLayout.detailGap),
            ) {
                item(key = "hero") {
                    // The screen minus a peek of the first row; grows rather than squeezes long text.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HlLayout.detailHeroMin(screenHeight))
                            .scrollsAsBlock()
                            .okClears(vm::clearActionError),
                    ) {
                        Hero(
                            item = item,
                            plays = state.playedAudio,
                            nextUp = state.nextUp,
                            capabilities = state.capabilities,
                            problem = problem,
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

                partFailed(state, memory, vm::retry)
                val links = EpisodeLinks(
                    onPlay = { ep, resume -> onPlay(ep, resume, PlayOrigin(screen, ep.id)) },
                    onOptions = { episodeOptions = it.id },
                    problem = episodeProblem,
                )
                seasonRows(state, memory, focusedEpisode, vm, links)
                titleRows(item, state, memory, people, RowLinks(onOpenItem, onOpenPerson))
            } }
        // Over the page, in its light.
        if (showTech && media != null) TechSpecs(item, onDismiss = { showTech = false })
        if (showVersions) {
            val choose = vm::chooseVersion.takeIf { state.canChooseVersion }
            VersionsSheet(item, state.versions, onChoose = choose, onDismiss = { showVersions = false })
        }
        TitleTracks(item, tracks, open = showTracks, onChoose = vm::chooseTracks, onDismiss = { showTracks = false })
        val episodeActions = EpisodeActions(
            onMark = vm::toggleEpisodePlayed.takeIf { state.capabilities?.markPlayed == true },
            onPlayFromStart = { onPlay(it, false, PlayOrigin(screen, it.id)) },
            onTracks = { showTracks = true },
            onRemove = vm::removeHeldEpisode,
        )
        EpisodeOptions(state, episodeOptions, episodeActions, onDismiss = { episodeOptions = null })
        ShowMarks(
            item,
            state.shownSeason,
            state.seasons.size,
            open = showMarks,
            onMarkSeason = vm::toggleSeasonPlayed,
            onMarkShow = vm::togglePlayed,
            onDismiss = { showMarks = false },
        )
        if (showSynopsis) SynopsisSheet(item, onDismiss = { showSynopsis = false })
        if (showNfo) NfoSheet(item, onDismiss = { showNfo = false })
        if (showEdit) {
            EditMenu(
                item = item,
                onDismiss = { showEdit = false },
                onArtwork = { kind -> showEdit = false; onEditArtwork(item.id, kind) },
                onIdentify = { showEdit = false; onIdentify(item.id) },
            )
        }
    }
}

/**
 * What failed once the title showed (its seasons, a saga's films), under the hero, with Réessayer ([onRetry]); with
 * Lire's failure, it is said in Lire's place instead.
 */
private fun LazyListScope.partFailed(state: DetailState, memory: FocusMemory, onRetry: () -> Unit) {
    val error = state.error?.takeUnless { state.failedWithLire } ?: return
    item(key = "failed") {
        Box(Modifier.scrollsAsBlock().padding(horizontal = HlSpace.safeH)) {
            PartFailed(error, state.retrying, onRetry, action = Modifier.focusKey(RETRY_PAGE, memory))
        }
    }
}

/** A show's season tabs and the chosen season's episodes, below the hero. */
private fun LazyListScope.seasonRows(
    state: DetailState,
    memory: FocusMemory,
    /** The focused episode, read only where its overview shows. */
    focusedEpisode: MutableState<MediaItem?>,
    vm: DetailViewModel,
    links: EpisodeLinks,
) {
    val item = state.item ?: return
    if (item.kind == MediaKind.SERIES && state.seasons.isNotEmpty()) {
        // One season is no choice: it titles the episode row instead of a tab.
        val single = state.seasons.singleOrNull()
        if (single == null) {
            item(key = "seasons") {
                Box(Modifier.scrollsAsBlock()) { Seasons(state.seasons, state.selectedSeasonId, vm::selectSeason) }
            }
        }
        item(key = "episodes-${state.selectedSeasonId}") {
            val failed = state.episodesError
            // If Réessayer had focus, move it to the episodes once they load rather than lose it.
            LaunchedEffect(failed == null && state.episodes.isNotEmpty()) {
                if (failed == null && state.episodes.isNotEmpty() && memory.last == RETRY_EPISODES) {
                    withFrameNanos { }
                    memory.focus(FocusKey.row(EPISODES))
                }
            }
            val canMark = state.capabilities?.markPlayed == true
            when {
                failed != null -> EpisodesFailed(
                    single?.title,
                    failed.takeUnless { state.failedWithLire },
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
                    onPlay = { ep -> links.onPlay(ep, ep.user.positionMs > 0) },
                    onOptions = { ep ->
                        { links.onOptions(ep) }.takeIf { episodeHasOptions(ep, canMark, state.tracksToChoose) }
                    },
                    markError = state.episodeMarkError,
                    onClearMarkError = vm::clearEpisodeMarkError,
                    playError = links.problem?.takeIf { p -> state.episodes.any { it.id == p.origin.episode } }?.error,
                )
            }
        }
    }
}

/** A collection's films (its whole saga), or a film's saga with the film in its place. */
private fun LazyListScope.sagaRows(item: MediaItem, state: DetailState, memory: FocusMemory, onOpenItem: (String) -> Unit) {
    // The whole saga in release order, missing films as empty frames; without it, the films held.
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

    // No row when the saga holds only this film.
    state.collection?.takeIf { state.collectionFilms.any { film -> film.id != item.id } }?.let { collection ->
        item(key = "collection") {
            MediaRow(collection.title, memory.rowGroup(SAGA), count = filmCount(state.collectionFilms.size, collection.totalCount)) {
                items(state.collectionFilms, key = { it.id }) { f ->
                    PosterCard(f, onClick = { if (f.id != item.id) onOpenItem(f.id) }, modifier = memory.card(SAGA, f))
                }
            }
        }
    }
}

/** The rows below a title's head: a collection's films, a film's saga, its [people], similar titles. */
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

/** A page whose title is not loaded yet: the backdrop handed on, then a skeleton once loading is slow, or [error]. */
@Composable
private fun NoTitleYet(error: UiError?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(null, BackdropStyle.Full)
        Box(Modifier.padding(top = HlLayout.errorTop)) {
            // While retrying, the error stays with its button busy.
            val loading = error == null
            val shown = retainedError(error, retrying = loading)
            when {
                shown != null -> HlErrorState(shown, onRetry, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l), loading)
                skeletonDelayPassed() -> HeroSkeleton(Modifier.padding(start = HlSpace.safeH, top = HlSpace.l))
            }
        }
    }
}

/** Focuses Lire, else the actions ([fallback]), once [swap] changes while the element keyed [held] had focus. */
@Composable
private fun FocusOnPlayAfter(swap: Boolean, held: String, memory: FocusMemory, fallback: FocusRequester) {
    // Read as the change composes: the element it removes still has focus, focus moved elsewhere is left there.
    val had = remember(swap) { memory.hasFocus(held) }
    LaunchedEffect(swap) {
        if (had) {
            withFrameNanos { }
            if (!memory.focus(PLAY)) fallback.tryRequestFocus()
        }
    }
}

/** How far the backdrop recedes once scrolled ([away]), so rows read on the room; snaps at first, then animates. */
@Composable
private fun rememberRecede(away: () -> Boolean): Animatable<Float, *> {
    val recede = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow(away).collect { down ->
            val target = if (down) 1f else 0f
            if (first) recede.snapTo(target) else recede.animateTo(target, tween(HlMotion.SLOW, easing = HlMotion.fallBack))
            first = false
        }
    }
    return recede
}

@Immutable
private class RowLinks(val onOpenItem: (String) -> Unit, val onOpenPerson: (String) -> Unit)

/**
 * What an episode's card does: OK plays it, from where it was left; Menu or a long OK opens its options. A play asked
 * from one that failed ([problem]) is said under the row while that episode is in it.
 */
@Immutable
private class EpisodeLinks(
    val onPlay: (MediaItem, Boolean) -> Unit,
    val onOptions: (MediaItem) -> Unit,
    val problem: PlaybackProblem? = null,
)

/** A tile says a second line: a show's watched tile the Menu key, the tracks tile what Lire plays. */
private fun hintsUnderTiles(item: MediaItem, capabilities: ProviderCapabilities?, tracks: TrackPlan?): Boolean =
    item.kind == MediaKind.SERIES && capabilities?.markPlayed == true || tracks != null

/** The title's audio and subtitles in their sheet, when [open]: for the film, or for the whole show from an episode. */
@Composable
private fun TitleTracks(item: MediaItem, plan: TrackPlan?, open: Boolean, onChoose: (TrackChoice) -> Unit, onDismiss: () -> Unit) {
    if (!open) return
    if (plan == null) {
        // The tracks went away (another copy, a file gone): the sheet closes for good.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val show = item.kind == MediaKind.SERIES || item.kind == MediaKind.EPISODE
    TracksSheet(item.seriesTitle ?: item.title, plan, show, onChoose = onChoose, onDismiss = onDismiss)
}

/** The page's actions, passed down whole; named fields keep same-typed lambdas apart, and null hides a button. */
@Immutable
private data class DetailActions(
    val onPlay: (MediaItem, resume: Boolean) -> Unit,
    val onTogglePlayed: () -> Unit,
    val onToggleSeasonPlayed: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onOpenSeries: () -> Unit,
    val onOpenNfo: () -> Unit,
    val onShowTech: (() -> Unit)?,
    val onShowVersions: (() -> Unit)?,
    /** Opens the title's audio and subtitles; null [tracks] hides the button. */
    val onShowTracks: () -> Unit,
    val tracks: TrackPlan? = null,
    val onShowSynopsis: () -> Unit,
    /** The copy Lire plays and its index (to name it); null for one copy. */
    val copy: Version? = null,
    val copyIndex: Int = 0,
    val onEdit: (() -> Unit)?,
    /** The season the watched tile marks; null where it marks the title. */
    val season: MediaItem? = null,
    /** Opens the watched tile's options, the season and the whole show, where it marks a [season]. */
    val onShowMarks: () -> Unit = {},
    /**
     * Why what a series' Lire starts could not be read, or what failed with it ([DetailState.lireError]): what fixes it
     * in Lire's place, Réessayer ([onRetry]) busy while [retrying].
     */
    val nextUpError: UiError? = null,
    val retrying: Boolean = false,
    val onRetry: () -> Unit = {},
    /** Removes the title held while its file is missing from the library alone; null hides the tile. */
    val onRemove: (() -> Unit)? = null,
)

@Composable
private fun Hero(
    item: MediaItem,
    plays: AudioTrack?,
    nextUp: MediaItem?,
    capabilities: ProviderCapabilities?,
    problem: UiError?,
    actionError: UiError?,
    actions: DetailActions,
    memory: FocusMemory,
    actionRow: FocusRequester,
    modifier: Modifier,
) {
    val isEpisode = item.kind == MediaKind.EPISODE

    Column(modifier) {
        // Reading measure; at the page's end the hero's remnant fades rather than showing a sliver.
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
            // Picture, then sound, as on the back of a case.
            if (item.badges.isNotEmpty() || item.media?.video != null || item.media?.audio?.isNotEmpty() == true) {
                Spacer(Modifier.height(HlSpace.s))
                FormatLines(item, plays = plays)
            }
            // The tagline introduces the overview, quieter than the title above it.
            item.tagline?.let {
                Spacer(Modifier.height(HlSpace.l))
                Text(it, style = Hl.type.lead, color = Hl.colors.contentSecondary)
            }
            if (!item.overview.isNullOrBlank()) {
                Spacer(Modifier.height(if (item.tagline != null) HlSpace.xs else HlSpace.l))
                // Opens in full beside the page when cut; Down goes to the first action, mirroring Up.
                HeadOverview(item.overview, onOpen = actions.onShowSynopsis, Modifier.focusProperties { down = actionRow })
            }
            if (item.directors.isNotEmpty() || item.cast.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.m))
                BillingBlock(item.directors, item.cast.map { it.name })
            }
        }
        Spacer(Modifier.height(HlSpace.xl))

        Actions(item, nextUp, capabilities, problem, actionError, actions, memory, actionRow)
    }
}

/** The title's [actions] in one row on plates over the picture, with room below for a tile's label so nothing moves. */
@Composable
private fun Actions(
    item: MediaItem,
    nextUp: MediaItem?,
    capabilities: ProviderCapabilities?,
    problem: UiError?,
    actionError: UiError?,
    actions: DetailActions,
    memory: FocusMemory,
    actionRow: FocusRequester,
) {
    // A tile may say a second line under its label: room for it from the start, so nothing moves.
    val hinted = hintsUnderTiles(item, capabilities, actions.tracks)
    OnArtwork { HlActionRow(Modifier.focusRequester(actionRow).fadeOutsideSafeArea(), hinted = hinted) {
        PlayButtons(item, nextUp, memory, actions)
        Parts(item, memory, actions.onPlay)
        // Several copies: the one Lire plays is chosen in a side sheet.
        if (actions.onShowVersions != null) {
            HlIconButton(
                HlIcons.Versions,
                stringResource(R.string.versions),
                onClick = actions.onShowVersions,
                modifier = Modifier.focusKey(VERSIONS, memory),
            )
        }
        if (actions.tracks != null) {
            HlIconButton(
                HlIcons.Captions,
                stringResource(R.string.tracks),
                onClick = actions.onShowTracks,
                modifier = Modifier.focusKey(TRACKS, memory),
                hint = tracksHint(actions.tracks),
            )
        }
        if (item.kind == MediaKind.EPISODE && item.seriesId != null) {
            HlButton(stringResource(R.string.view_series), onClick = actions.onOpenSeries, modifier = Modifier.focusKey(SERIES, memory))
        }
        if (capabilities?.markPlayed == true) WatchedTile(item, actions, Modifier.focusKey(WATCHED, memory))
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
        RemoveTile(actions.onRemove, memory)
        ActionFailure(problem, actionError, actions.nextUpError, heldSince = item.missingSince)
    } }
}

/**
 * The watched tile: the title, or the season shown ([DetailActions.season]), Menu or a long OK then opening its options,
 * which the Menu key under its label says. Label: what OK does; icon: the current state. Watched is done, not a choice:
 * the check fills white.
 */
@Composable
private fun WatchedTile(item: MediaItem, actions: DetailActions, modifier: Modifier) {
    val season = actions.season
    val show = item.user.played
    val watched = season?.let(::seasonWatched) ?: show
    val label = when {
        season == null -> if (watched) R.string.mark_unwatched else R.string.mark_watched
        watched -> R.string.mark_season_unwatched
        else -> R.string.mark_season_watched
    }
    HlIconButton(
        if (watched) HlIcons.CheckFilled else HlIcons.Check,
        stringResource(label),
        onClick = if (season != null) actions.onToggleSeasonPlayed else actions.onTogglePlayed,
        modifier = modifier,
        onLongClick = actions.onShowMarks.takeIf { season != null },
        onLongClickLabel = stringResource(R.string.more_options),
    )
}

/**
 * Beside the actions, the latest first: why the last play request failed ([problem]), a change did not save
 * ([actionError]), or what Lire starts could not be read ([nextUpError]); else, for a title held, since when its file is
 * missing ([heldSince]). A title held is said by the ring, never ember, as it may be no failure, Lire then saying it is
 * gone and what to do.
 */
@Composable
private fun RowScope.ActionFailure(problem: UiError?, actionError: UiError?, nextUpError: UiError?, heldSince: Long? = null) {
    val at = Modifier.align(Alignment.CenterVertically).padding(start = HlSpace.m).weight(1f, fill = false)
    if (problem == UiError.HELD) {
        HlStatus(StatusState.Off, stringResource(problem.title), at, detail = stringResource(problem.message))
        return
    }
    val failure = problem?.let { R.string.play_failed to it }
        ?: actionError?.let { R.string.action_not_saved to it }
        ?: nextUpError?.let { it.title to it }
    if (failure == null) {
        HeldState(heldSince, at)
    } else {
        HlStatus(StatusState.Attention, stringResource(failure.first), at, detail = stringResource(failure.second.message))
    }
}

/** « Retirer de la bibliothèque », a list crossed out, on a title held alone ([onRemove], null for a title there). */
@Composable
private fun RemoveTile(onRemove: (() -> Unit)?, memory: FocusMemory) {
    if (onRemove == null) return
    HlIconButton(
        HlIcons.Remove,
        stringResource(R.string.held_remove_title),
        onClick = onRemove,
        modifier = Modifier.focusKey(REMOVE, memory),
    )
}

/**
 * Its title held removed from the library ([removed]): the page closes on the screen it came from ([onLeave]), for the
 * film's page again when a copy of it is left ([TitleRemoved.stays]).
 */
@Composable
private fun LeaveOnceRemoved(removed: TitleRemoved?, onLeave: (String?) -> Unit) {
    LaunchedEffect(removed) { if (removed != null) onLeave(removed.stays) }
}

/** Since when the file of a title held ([since]) is missing, by the ring; nothing for a title there. */
@Composable
private fun HeldState(since: Long?, modifier: Modifier) {
    if (since != null) HlStatus(StatusState.Off, heldSince(since), modifier)
}

/**
 * Lire or Reprendre (one button, so focus stays put), then start-over; a series or saga plays its next title. A series
 * whose next episode could not be read has what fixes it in Lire's place, as `PartFailed` does (Réessayer, Se
 * reconnecter; nothing when nothing does), keyed as Lire, why said at the row's end.
 */
@Composable
private fun PlayButtons(item: MediaItem, nextUp: MediaItem?, memory: FocusMemory, actions: DetailActions) {
    val onPlay = actions.onPlay
    val series = item.kind == MediaKind.SERIES || item.kind == MediaKind.COLLECTION
    val failed = actions.nextUpError
    if (series && failed != null) {
        val fixedBy = failed.action ?: return
        val fix = LocalErrorFix.current
        HlButton(
            stringResource(fixedBy.label),
            onClick = { fixedBy.run(actions.onRetry, fix) },
            modifier = Modifier.focusKey(PLAY, memory),
            icon = HlIcons.Refresh.takeIf { fixedBy == ErrorAction.RETRY },
            busy = actions.retrying && fixedBy == ErrorAction.RETRY,
            busyLabel = stringResource(R.string.retrying),
        )
        return
    }
    // A film in several copies plays the one chosen, from where that copy was left.
    val copy = actions.copy
    val target = (if (series) nextUp else copy?.item ?: item.takeIf { it.isPlayable }) ?: return
    val resume = target.user.positionMs > 0
    val episode = whatLirePlays(item, target, copy, actions.copyIndex)
    HlButton(
        stringResource(if (resume) R.string.resume else R.string.play),
        onClick = { onPlay(target, resume) },
        modifier = Modifier.focusKey(PLAY, memory),
        icon = HlIcons.Play,
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

/** What Lire names: the episode, the saga's film, or the edition of a film in copies. */
@Composable
private fun whatLirePlays(item: MediaItem, target: MediaItem, copy: Version?, copyIndex: Int): String? = when {
    item.kind == MediaKind.SERIES -> target.episodeLabel
    item.kind == MediaKind.COLLECTION -> target.title
    copy != null -> versionName(copy.edition, copy.label, copyIndex)
    else -> null
}

/** Lire's detail: [what] plus the time left when resuming, or the length for a copy; else [what] or the length. */
@Composable
private fun playDetail(what: String?, target: MediaItem, resume: Boolean, copy: Boolean): String? = when {
    resume -> listOfNotNull(what, timeLeft(target)).joinToString(FACT_GAP)
    copy -> listOfNotNull(what, target.runtimeMs?.let(::formatDuration)).joinToString(FACT_GAP)
    else -> what ?: target.runtimeMs?.let(::formatDuration)
}

/** A multi-file film's parts ("Partie 1/2"), each from its start; from three on, a "Parties" menu instead. */
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

/** A season's progress after its name: a check once watched ([seasonWatched]), "6 / 10" when partly, nothing before. */
internal fun seasonDetail(season: MediaItem): SegmentDetail? {
    if (seasonWatched(season)) return SegmentDetail.Done
    val episodes = season.childCount?.takeIf { it > 0 } ?: return null
    val watched = episodes - (season.user.unplayedCount ?: episodes)
    return if (watched > 0) SegmentDetail.Count("$watched$NBSP/$NBSP$episodes") else null
}

/** An episode's state: watched, or the time left; null when not started. */
@Composable
private fun episodeState(ep: MediaItem): String? =
    if (ep.user.played) stringResource(R.string.watched) else timeLeft(ep)

/**
 * A season's episodes: OK plays one, Menu or a long OK opens its options ([onOptions], null for a card without); a play
 * that failed ([playError]), else a mark that did not save ([markError]), is said under them until an OK there.
 */
@Composable
private fun Episodes(
    header: String?,
    episodes: List<MediaItem>,
    memory: FocusMemory,
    focused: MediaItem?,
    onFocus: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOptions: (MediaItem) -> (() -> Unit)?,
    markError: UiError?,
    onClearMarkError: () -> Unit,
    playError: UiError? = null,
) {
    // The row and the overview of its focused episode come into view together.
    Column(Modifier.scrollsAsBlock().okClears(onClearMarkError)) {
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
                    onLongClick = onOptions(ep),
                    onLongClickLabel = stringResource(R.string.more_options),
                )
            }
        }
        val shown = focused?.takeIf { f -> episodes.any { it.id == f.id } }
        // At least three lines at the viewer's text size, so the page stays still as focus moves or an error comes.
        Box(
            Modifier
                .padding(horizontal = HlSpace.safeH)
                .widthIn(max = HlLayout.readable)
                .heightIn(min = textRoom(HlLayout.episodeOverview))
                .fadeOutsideSafeArea(),
        ) {
            if (playError == UiError.HELD) {
                HlStatus(StatusState.Off, stringResource(playError.title), detail = stringResource(playError.message))
            } else if (playError != null) {
                HlStatus(StatusState.Attention, stringResource(R.string.play_failed), detail = stringResource(playError.message))
            } else if (markError != null) {
                HlStatus(StatusState.Attention, stringResource(R.string.action_not_saved), detail = stringResource(markError.message))
            } else {
                Text(
                    shown?.overview ?: "",
                    style = Hl.type.body,
                    color = Hl.colors.contentSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Episode placeholders, shown once loading is slow but laid out from the start so the rows below never move. */
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
 * The episode row's error and its fix, its header alone when the error is said in Lire's place (null); while
 * [retrying], the row stays, its button busy and focused.
 */
@Composable
private fun EpisodesFailed(header: String?, error: UiError?, retrying: Boolean, onRetry: () -> Unit, action: Modifier) {
    Column(Modifier.scrollsAsBlock().padding(horizontal = HlSpace.safeH)) {
        header?.let { SectionHeader(it, Modifier.padding(bottom = HlSpace.xxs)) }
        error?.let { PartFailed(it, retrying, onRetry, action = action) }
    }
}

/** Parts go in a menu from three on: two buttons beside Lire still leave room for the tiles. */
internal fun partsInMenu(count: Int): Boolean = count > INLINE_PARTS

/** [parts] as menu options named by [labels], none selected so the menu opens on the first part. */
internal fun partOptions(parts: List<MediaItem>, labels: List<String>, onPlay: (MediaItem) -> Unit): List<MenuOption> =
    parts.mapIndexed { i, part -> MenuOption(labels[i], selected = false) { onPlay(part) } }

/** Back returns to the actions once the page has scrolled down; at its top, it leaves. */
internal fun backReturnsToActions(firstVisibleItem: Int, offset: Int): Boolean = firstVisibleItem > 0 || offset > 0

/** A page row as a focus group: if the focused card is gone, focus returns to its row. */
private fun FocusMemory.rowGroup(row: String) = Modifier.focusGroupKey(FocusKey.row(row), this)

/** A card keyed within its row: the same title may sit in two rows. */
private fun FocusMemory.card(row: String, item: MediaItem) =
    Modifier.focusKey(FocusKey.within(FocusKey.row(row), FocusKey.item(item.id)), this)

private val PLAY = FocusKey.action("play")
private val VERSIONS = FocusKey.action("versions")
private val TRACKS = FocusKey.action("tracks")
private val RESTART = FocusKey.action("restart")
private val SERIES = FocusKey.action("series")
private val WATCHED = FocusKey.action("watched")
private val TECH = FocusKey.action("tech")
private val NFO = FocusKey.action("nfo")
private val FAVORITE = FocusKey.action("favorite")
private val EDIT = FocusKey.action("edit")
private val REMOVE = FocusKey.action("remove")
private val PARTS = FocusKey.action("parts")

/** Parts shown as buttons in the action row, beside Lire and the tiles. */
private const val INLINE_PARTS = 2
private const val EPISODES = "episodes"

/** Réessayer of a page whose part failed once its title showed. */
private val RETRY_PAGE = FocusKey.action("retry-page")

/** Réessayer of a failed season, keyed within its row so returning focus finds the row. */
private val RETRY_EPISODES = FocusKey.within(FocusKey.row(EPISODES), FocusKey.action("retry"))
private const val FILMS = "films"
private const val SAGA = "collection"
private const val SIMILAR = "similar"
private const val CAST = "cast"

/** The cast row: directors, or a show's creators, labelled by job, then the cast; the cast alone without credits. */
internal fun peopleOf(item: MediaItem, credits: Credits?, directing: String, creating: String): List<Person> {
    if (credits == null) return item.cast
    val show = item.kind == MediaKind.SERIES
    val role = if (show) creating else directing
    val direction = credits.crew[if (show) Job.CREATOR else Job.DIRECTOR].orEmpty().map { it.copy(role = role) }
    return (direction + credits.cast).ifEmpty { item.cast }
}

/** Placeholders while a season loads: as many episodes as the screen shows whole. */
private const val EPISODE_SKELETONS = 3
