package app.houselights.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import app.houselights.core.Credits
import app.houselights.core.EpisodeOrderChoices
import app.houselights.core.Job
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.ProviderCapabilities
import app.houselights.core.SagaPart
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan
import app.houselights.core.Version
import app.houselights.core.hasPage
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.components.AbsentCard
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.BillingBlock
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.EpisodeHeading
import app.houselights.ui.design.components.FormatLines
import app.houselights.ui.design.components.HeadOverview
import app.houselights.ui.design.components.HeroSkeleton
import app.houselights.ui.design.components.HlActionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.SegmentDetail
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.SynopsisSheet
import app.houselights.ui.design.components.TechSpecs
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.design.components.TracksSheet
import app.houselights.ui.design.components.VersionsSheet
import app.houselights.ui.design.components.filmCount
import app.houselights.ui.design.components.tracksHint
import app.houselights.ui.design.fadeOutsideSafeArea
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.scrollsAsBlock
import app.houselights.ui.design.textRoom
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.edit.EditMenu
import app.houselights.ui.edit.doubtText
import app.houselights.ui.util.ErrorFixButton
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.LocalLibraryPlace
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.PlayOrigin
import app.houselights.ui.util.PlaybackOutcome
import app.houselights.ui.util.PlaybackProblem
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.heldSince
import app.houselights.ui.util.jobName
import app.houselights.ui.util.okClears
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.screenHeight
import app.houselights.ui.util.skeletonDelayPassed
import app.houselights.ui.util.timeLeft
import app.houselights.ui.util.titleAt
import app.houselights.ui.util.versionName

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
    vm: DetailViewModel = viewModel(key = "detail-$itemId", factory = DetailViewModel.factory(appContainer(), itemId)),
) {
    val app = appContainer()
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
    // Modifier open on a saga's poster and backdrop (#48).
    var sagaEditing by remember { mutableStateOf(false) }
    // A film started over is resumed next: back from the player, focus is on its play button, a saga's film's too.
    LaunchedEffect(Unit) {
        playback.playbackEnded.collect { memory.last?.let(::playAfterRestart)?.let(memory::focus) }
    }
    val page = rememberLazyListState()
    val directing = jobName(Job.DIRECTOR)
    val creating = jobName(Job.CREATOR)
    val people = state.item?.let { peopleOf(it, state.credits, directing, creating) }.orEmpty()

    if (item == null) {
        NoTitleYet(state.error, onRetry = vm::load)
        return
    }

    // A saga is a polyptych, a tall panel a film beside its own (« Les volets »), on a page of its own that never scrolls.
    if (item.kind == MediaKind.COLLECTION) {
        val links = SagaLinks(
            onPlay = { title, resume -> onPlay(title, resume, PlayOrigin(screen)) },
            onMark = vm::togglePlayed,
            onOpenItem = onOpenItem,
            onRetry = vm::retry,
            onClearProblem = playback::clearProblem,
            onClearActionError = vm::clearActionError,
            onEdit = { sagaEditing = true }.takeIf { state.canEdit },
        )
        SagaPage(item, state, memory, actionRow, problem, links, SagaEdit(sagaEditing, { sagaEditing = it }, onEditArtwork))
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
    val heldFocus = episodesHeldFocus(memory, state.selectedSeasonId)
    var showTech by remember { mutableStateOf(false) }
    val media = item.media?.takeIf { it.video != null || it.audio.isNotEmpty() }
    var showVersions by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    val tracks = state.tracksToChoose
    var showSynopsis by remember { mutableStateOf(false) }
    // A film of its saga the library lacks, or not out yet, whose synopsis is open from its row.
    var partShown by remember { mutableStateOf<SagaPart?>(null) }
    var showNfo by remember { mutableStateOf(false) }
    val editing = remember { mutableStateOf(false) }
    // The episode whose options are open, and the watched tile's.
    var episodeOptions by remember { mutableStateOf<String?>(null) }
    var showMarks by remember { mutableStateOf(false) }
    // Its Identifier button and « À identifier » come and go together.
    val identifiable = state.canEdit && item.matchDoubt != null && !item.held
    // The player changed (#58), the play asked from the page or one of its episodes: it is the player, not the title.
    val trustHold = remember { RemedyHold() }
    val trusting = trustHold.shows(trustsPlayer(asked?.error))
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
        onEdit = { editing.value = true }.takeIf { state.canEdit },
        season = state.shownSeason,
        soleSeason = state.soleSeason,
        onShowMarks = { showMarks = true },
        nextUpError = state.lireError,
        retrying = state.retrying,
        onRetry = vm::retry,
        // Held, a title to identify says it is held and its Identifier waits with « À identifier », Modifier opening it.
        onIdentify = { onIdentify(item.id) }.takeIf { identifiable },
        // A title held while its file is missing: out of the library alone, nothing deleted on the NAS.
        onRemove = vm::removeHeld.takeIf { item.held },
        trustPlayer = trustHold.takeIf { trusting },
    )
    // Réessayer gone with the failure it said, or Lire and the Réessayer in its place swapped: focus goes to Lire.
    FocusOnPlayAfter(swap = state.error != null, held = RETRY_PAGE, memory, actionRow)
    FocusOnPlayAfter(swap = state.nextUpError != null, held = PLAY, memory, actionRow)
    // The player changed (#58), from Lire or an episode: what fixes it and Lire swap places, focus following.
    FocusOnPlayAfter(swap = trusting, held = PLAY, memory, actionRow)
    // Identified or held meanwhile, Identifier leaves the row with the state it said: focus goes to Lire.
    FocusOnPlayAfter(swap = identifiable, held = IDENTIFY, memory, actionRow)

    val recede = rememberRecede { scrolled }

    AmbientBackdrop(
        item.backdrop,
        BackdropStyle.Full,
        Modifier.okClears(playback::clearProblem),
        recede = { recede.value },
    ) {
        // Scrolls by blocks: an incoming row rises to the safe area; the wider block gap hides the row above.
        ListScroll(top = HlFocus.edge, toTop = true) {
            LazyColumn(
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
                seasonRows(state, memory, focusedEpisode, vm, links, heldFocus)
                titleRows(item, state, memory, people, RowLinks(onOpenItem, onOpenPerson, onOpenPart = { partShown = it }))
            }
        }
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
        PartSynopsis(partShown, onDismiss = { partShown = null })
        if (showNfo) NfoSheet(item, onDismiss = { showNfo = false })
        EditSheets(
            item,
            state.episodeOrders,
            editing = editing.value,
            onEndEditing = { editing.value = false },
            onEditArtwork = onEditArtwork,
            onIdentify = onIdentify,
            onChooseOrder = vm::chooseEpisodeOrder,
        )
    }
}

/** Modifier on a saga's poster and backdrop: open while [editing], closed by [onEditing], each image opening its choice. */
@Immutable
private class SagaEdit(val editing: Boolean, val onEditing: (Boolean) -> Unit, val onEditArtwork: (String, ArtworkKind) -> Unit)

/** A saga's page ([SagaVolets]): what it does ([links]), Modifier on its poster and its backdrop ([edit]). */
@Composable
private fun SagaPage(
    item: MediaItem,
    state: DetailState,
    memory: FocusMemory,
    actionRow: FocusRequester,
    problem: UiError?,
    links: SagaLinks,
    edit: SagaEdit,
) {
    SagaVolets(item, state, memory, actionRow, problem, links) {
        if (edit.editing) {
            EditMenu(
                item = item,
                onDismiss = { edit.onEditing(false) },
                onArtwork = { kind ->
                    edit.onEditing(false)
                    edit.onEditArtwork(item.id, kind)
                },
                onIdentify = null,
            )
        }
    }
}

/**
 * Modifier while [editing], until it ends ([onEndEditing]), and the order of a show's episodes it opens
 * ([EpisodeOrderSheet]) from its row, offered while TMDB numbers the show more than one way ([orders]).
 */
@Composable
private fun EditSheets(
    item: MediaItem,
    orders: EpisodeOrderChoices?,
    editing: Boolean,
    onEndEditing: () -> Unit,
    onEditArtwork: (String, ArtworkKind) -> Unit,
    onIdentify: (String) -> Unit,
    onChooseOrder: (String?) -> Unit,
) {
    var showOrders by remember { mutableStateOf(false) }
    if (editing) {
        EditMenu(
            item = item,
            onDismiss = onEndEditing,
            onArtwork = { kind ->
                onEndEditing()
                onEditArtwork(item.id, kind)
            },
            onIdentify = {
                onEndEditing()
                onIdentify(item.id)
            },
            episodeOrder = orders?.let { orderInUse(it) },
            onEpisodeOrder = { showOrders = true }.takeIf { orders != null },
        )
    }
    EpisodeOrderSheet(item, orders, open = showOrders, onChoose = onChooseOrder, onDismiss = { showOrders = false })
}

/**
 * What failed once the title showed (its seasons, a saga's films), under the hero, with Réessayer ([onRetry]); with
 * Lire's failure, it is said in Lire's place instead.
 */
private fun LazyListScope.partFailed(state: DetailState, memory: FocusMemory, onRetry: () -> Unit) {
    val error = state.error?.takeUnless { state.failedWithLire } ?: return
    item(key = "failed") {
        Box(Modifier.scrollsAsBlock().padding(horizontal = HlSpace.safeH)) {
            PartFailed(error, state.retrying, onRetry, actionModifier = Modifier.focusKey(RETRY_PAGE, memory))
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
    /** Focus was in the episode row as its season last changed. */
    heldFocus: Boolean = false,
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
            FocusFirstEpisode(state.seasonMoved, heldFocus, state.episodes.firstOrNull(), memory)
            val canMark = state.capabilities?.markPlayed == true
            when {
                failed != null -> EpisodesFailed(
                    single?.title,
                    failed.takeUnless { state.failedWithLire },
                    retrying = state.episodesLoading,
                    onRetry = vm::retrySeason,
                    actionModifier = Modifier.focusKey(RETRY_EPISODES, memory),
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
                        {
                            links.onOptions(ep)
                        }.takeIf { episodeHasOptions(ep, canMark, state.tracksToChoose) }
                    },
                    markError = state.episodeMarkError,
                    onClearMarkError = vm::clearEpisodeMarkError,
                    playError = links.problem?.takeIf { p -> state.episodes.any { it.id == p.origin.episode } }?.error,
                    pending = state.reordering,
                )
            }
        }
    }
}

/**
 * Whether focus was in the episode row as the season shown became [seasonId]: read as the change composes, before the
 * row of the season before leaves.
 */
@Composable
private fun episodesHeldFocus(memory: FocusMemory, seasonId: String?): Boolean =
    remember(seasonId) { memory.last?.startsWith(FocusKey.within(FocusKey.row(EPISODES), "")) == true }

/**
 * Focus on [first], the first episode of the season the episodes shown [moved] to, theirs gone, as after a change of
 * their order, when their row [held] it.
 */
@Composable
private fun FocusFirstEpisode(moved: Boolean, held: Boolean, first: MediaItem?, memory: FocusMemory) {
    LaunchedEffect(moved, first?.id) {
        if (moved && held && first != null) {
            withFrameNanos { }
            memory.focus(FocusKey.within(FocusKey.row(EPISODES), FocusKey.item(first.id)))
        }
    }
}

/**
 * A film's saga, the film in its place: the whole saga in release order when the source knows it, a film held as its
 * poster, one the library lacks or not out yet as its poster in grey, OK opening its synopsis ([RowLinks.onOpenPart]);
 * else the films held. Its heading never changes: a grey poster is named by its synopsis and by the screen reader.
 */
private fun LazyListScope.sagaRows(item: MediaItem, state: DetailState, memory: FocusMemory, links: RowLinks) {
    // No row when the saga holds only this film.
    state.collection?.takeIf { state.collectionFilms.any { film -> film.id != item.id } }?.let { collection ->
        val parts = state.saga?.parts ?: state.collectionFilms.map { SagaPart(it, it.title, it.year) }
        item(key = "collection") {
            MediaRow(collection.title, memory.rowGroup(SAGA), count = filmCount(state.collectionFilms.size, collection.totalCount)) {
                itemsIndexed(parts, key = { i, part -> part.item?.id ?: "absent-$i" }) { _, part ->
                    val f = part.item
                    if (f != null) {
                        PosterCard(f, onClick = { if (f.id != item.id) links.onOpenItem(f.id) }, modifier = memory.card(SAGA, f))
                    } else {
                        AbsentCard(
                            part.title,
                            part.year?.takeIf { part.released },
                            poster = part.poster,
                            said = absentWords(part),
                            onClick = { links.onOpenPart(part) }.takeIf { !part.overview.isNullOrBlank() },
                        )
                    }
                }
            }
        }
    }
}

/** The rows below a title's head: a film's saga, its [people], similar titles. */
private fun LazyListScope.titleRows(
    item: MediaItem,
    state: DetailState,
    memory: FocusMemory,
    people: List<Person>,
    links: RowLinks,
) {
    val onOpenItem = links.onOpenItem
    val onOpenPerson = links.onOpenPerson
    sagaRows(item, state, memory, links)

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

/**
 * Focuses Lire ([play], the page's own by default), else the actions ([fallback]), once [swap] changes while the
 * element keyed [held] had focus.
 */
@Composable
internal fun FocusOnPlayAfter(swap: Boolean, held: String, memory: FocusMemory, fallback: FocusRequester, play: String = PLAY) {
    // Read as the change composes: the element it removes still has focus, focus moved elsewhere is left there.
    val had = remember(swap) { memory.hasFocus(held) }
    LaunchedEffect(swap) {
        if (had) {
            withFrameNanos { }
            if (!memory.focus(play)) fallback.tryRequestFocus()
        }
    }
}

/** How far the backdrop recedes once scrolled ([away]), so rows read on the room; snaps at first, then animates. */
@Composable
private fun rememberRecede(away: () -> Boolean): Animatable<Float, *> {
    val recede = remember { Animatable(0f) }
    val currentAway by rememberUpdatedState(away)
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { currentAway() }.collect { down ->
            val target = if (down) 1f else 0f
            if (first) recede.snapTo(target) else recede.animateTo(target, tween(HlMotion.SLOW, easing = HlMotion.fallBack))
            first = false
        }
    }
    return recede
}

/** What the rows under a title open: a title's page, a person's, the synopsis of a film of its saga the library lacks. */
@Immutable
private class RowLinks(val onOpenItem: (String) -> Unit, val onOpenPerson: (String) -> Unit, val onOpenPart: (SagaPart) -> Unit)

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
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        LaunchedEffect(Unit) { currentOnDismiss() }
        return
    }
    val show = item.kind == MediaKind.SERIES || item.kind == MediaKind.EPISODE
    TracksSheet(item.headTitle, plan, show, onChoose = onChoose, onDismiss = onDismiss)
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
    /** The title is a show of one season ([DetailState.soleSeason]): the tile marks it whole, said as its season. */
    val soleSeason: Boolean = false,
    /** Opens the watched tile's options, the season and the whole show, where it marks a [season]. */
    val onShowMarks: () -> Unit = {},
    /**
     * Why what a series' Lire starts could not be read, or what failed with it ([DetailState.lireError]): what fixes it
     * in Lire's place, Réessayer ([onRetry]) busy while [retrying].
     */
    val nextUpError: UiError? = null,
    val retrying: Boolean = false,
    val onRetry: () -> Unit = {},
    /** Opens Identifier on a title to identify, its state said at the row's end; null for any other, or one held. */
    val onIdentify: (() -> Unit)? = null,
    /** Removes the title held while its file is missing from the library alone; null hides the tile. */
    val onRemove: (() -> Unit)? = null,
    /**
     * The player changed (#58): « Faire confiance au nouveau lecteur » in Lire's place, Depuis le début gone, held from
     * the OK pressed on it until its click ([RemedyHold]); null otherwise.
     */
    val trustPlayer: RemedyHold? = null,
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
    modifier: Modifier = Modifier,
) {
    val isEpisode = item.kind == MediaKind.EPISODE

    Column(modifier) {
        // Reading measure; at the page's end the hero's remnant fades rather than showing a sliver.
        Column(Modifier.widthIn(max = HlLayout.readableNarrow).fadeOutsideSafeArea()) {
            TitleArt(
                title = item.headTitle,
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
            // The tagline opens the overview in white, the overview reads in grey under it.
            item.tagline?.let {
                Spacer(Modifier.height(HlSpace.l))
                Text(it, style = Hl.type.lead, color = Hl.colors.content)
            }
            if (!item.overview.isNullOrBlank()) {
                Spacer(Modifier.height(if (item.tagline != null) HlSpace.s else HlSpace.l))
                // Opens in full beside the page when cut; Down goes to the first action, mirroring Up.
                HeadOverview(item.overview, onOpen = actions.onShowSynopsis, Modifier.focusProperties { down = actionRow })
            }
            // The billing block apart, at the foot as on a poster.
            if (item.directors.isNotEmpty() || item.cast.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.l))
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
    OnArtwork {
        HlActionRow(Modifier.focusRequester(actionRow).fadeOutsideSafeArea(), hinted = hinted, gap = HlLayout.detailGap) {
            PlayButtons(item, nextUp, memory, actions)
            Parts(item, memory, actions.onPlay)
            // A title to identify: Right then OK from Lire reaches it.
            if (actions.onIdentify != null) {
                HlButton(
                    stringResource(R.string.identify),
                    onClick = actions.onIdentify,
                    modifier = Modifier.focusKey(IDENTIFY, memory),
                    icon = HlIcons.Search,
                )
            }
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
                HlIconButton(
                    HlIcons.Edit,
                    stringResource(R.string.edit),
                    onClick = actions.onEdit,
                    modifier = Modifier.focusKey(EDIT, memory),
                )
            }
            RemoveTile(actions.onRemove, memory)
            ActionFailure(
                problem,
                actionError,
                actions.nextUpError,
                heldSince = item.missingSince,
                doubt = item.matchDoubt.takeIf { actions.onIdentify != null },
            )
        }
    }
}

/**
 * The watched tile: the title, or the season shown ([DetailActions.season]), Menu or a long OK then opening its options,
 * which the Menu key under its label says; a show of one season said as its season ([DetailActions.soleSeason]), with no
 * options. Label: what OK does; icon: the current state. Watched is done, not a choice: the check fills white.
 */
@Composable
private fun WatchedTile(item: MediaItem, actions: DetailActions, modifier: Modifier = Modifier) {
    val season = actions.season
    val show = item.user.played
    val watched = season?.let(::seasonWatched) ?: show
    val label = when {
        season == null && !actions.soleSeason -> if (watched) R.string.mark_unwatched else R.string.mark_watched
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
 * missing ([heldSince]); else, for a title to identify, why ([doubt]). Both are said by the ring, never ember, as neither
 * may be a failure, Lire then saying a title held is gone and what to do; a title held and to identify says it is held,
 * its file not there and identifying able to wait.
 */
@Composable
internal fun RowScope.ActionFailure(
    problem: UiError?,
    actionError: UiError?,
    nextUpError: UiError?,
    heldSince: Long? = null,
    doubt: MatchDoubt? = null,
) {
    val at = Modifier.align(Alignment.CenterVertically).padding(start = HlSpace.m).weight(1f, fill = false)
    FailureStatus(problem, actionError, nextUpError, at, heldSince, doubt)
}

/** Whether [ActionFailure] has a failure to say: a play, a change not saved, what Lire starts unread. */
internal fun failing(problem: UiError?, actionError: UiError?, nextUpError: UiError?): Boolean =
    problem != null || actionError != null || nextUpError != null

/** What [ActionFailure] says, wherever it stands: a saga's panel says it over its actions. */
@Composable
internal fun FailureStatus(
    problem: UiError?,
    actionError: UiError?,
    nextUpError: UiError?,
    modifier: Modifier = Modifier,
    heldSince: Long? = null,
    doubt: MatchDoubt? = null,
) {
    if (problem == UiError.HELD) {
        HlStatus(StatusState.Off, stringResource(problem.title), modifier, detail = stringResource(problem.message))
        return
    }
    val failure = problem?.let { R.string.play_failed to it }
        ?: actionError?.let { R.string.action_not_saved to it }
        ?: nextUpError?.let { it.titleAt(LocalLibraryPlace.current) to it }
    when {
        failure != null -> {
            val (title, error) = failure
            HlStatus(StatusState.Attention, stringResource(title), modifier, detail = stringResource(error.message))
        }
        heldSince != null -> HeldState(heldSince, modifier)
        doubt != null -> HlStatus(
            StatusState.Off,
            stringResource(R.string.to_identify),
            modifier,
            detail = stringResource(doubtText(doubt)),
        )
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
    val currentOnLeave by rememberUpdatedState(onLeave)
    LaunchedEffect(removed) { if (removed != null) currentOnLeave(removed.stays) }
}

/** Since when the file of a title held ([since]) is missing, by the ring; nothing for a title there. */
@Composable
private fun HeldState(since: Long?, modifier: Modifier = Modifier) {
    if (since != null) HlStatus(StatusState.Off, heldSince(since), modifier)
}

/**
 * Lire or Reprendre (one button, so focus stays put), then start-over; a series or saga plays its next title. A series
 * whose next episode could not be read has what fixes it in Lire's place, as `PartFailed` does (Réessayer, Se
 * reconnecter; nothing when nothing does), keyed as Lire, why said at the row's end; so does a player that changed
 * (« Faire confiance au nouveau lecteur »), Depuis le début gone with Lire: it would start the same player.
 */
@Composable
private fun RowScope.PlayButtons(item: MediaItem, nextUp: MediaItem?, memory: FocusMemory, actions: DetailActions) {
    val onPlay = actions.onPlay
    val series = item.kind == MediaKind.SERIES || item.kind == MediaKind.COLLECTION
    val failed = actions.nextUpError
    if (series && failed != null) {
        val fixedBy = failed.action ?: return
        ErrorFixButton(fixedBy, actions.retrying, actions.onRetry, Modifier.focusKey(PLAY, memory))
        return
    }
    val trust = actions.trustPlayer
    if (trust != null) {
        TrustPlayerButton(trust, Modifier.focusKey(PLAY, memory))
        return
    }
    // A film in several copies plays the one chosen, from where that copy was left; a server keeps one place for the film.
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
 * that failed ([playError]), else a mark that did not save ([markError]), is said under them until an OK there. While
 * the show's episodes are placed in another order ([pending]), the row says so by its title, its cards kept until then.
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
    pending: Boolean = false,
) {
    // The row and the overview of its focused episode come into view together.
    Column(Modifier.scrollsAsBlock().okClears(onClearMarkError)) {
        MediaRow(
            header = header,
            modifier = memory.rowGroup(EPISODES),
            spacing = HlSpace.l,
            pending = stringResource(R.string.episode_order_applying).takeIf { pending },
        ) {
            items(episodes, key = { it.id }) { ep ->
                LandscapeCard(
                    item = ep,
                    modifier = memory.card(EPISODES, ep),
                    onClick = { onPlay(ep) },
                    width = HlSize.episode,
                    title = ep.title,
                    number = ep.episodeNumbers,
                    facts = listOf(ep.runtimeMs?.let(::formatDuration), episodeState(ep)),
                    onFocus = { onFocus(ep) },
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
                HlStatus(StatusState.Off, stringResource(playError.title), detail = stringResource(playError.message), reads = true)
            } else if (playError != null) {
                HlStatus(
                    StatusState.Attention,
                    stringResource(R.string.play_failed),
                    detail = stringResource(playError.message),
                    reads = true,
                )
            } else if (markError != null) {
                HlStatus(
                    StatusState.Attention,
                    stringResource(R.string.action_not_saved),
                    detail = stringResource(markError.message),
                    reads = true,
                )
            } else {
                Text(
                    shown?.overview.orEmpty(),
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
 * [retrying], the row stays, its button busy and focused, keyed by [actionModifier].
 */
@Composable
private fun EpisodesFailed(
    header: String?,
    error: UiError?,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
) {
    Column(modifier.scrollsAsBlock().padding(horizontal = HlSpace.safeH)) {
        // Its failure a block's line under it, as panels stand under a header.
        header?.let { SectionHeader(it, Modifier.padding(bottom = HlGap.inBlock)) }
        error?.let { PartFailed(it, retrying, onRetry, actionModifier = actionModifier) }
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

internal val PLAY = FocusKey.action("play")

/**
 * Where focus goes back from the player once Depuis le début keyed [key] started a title: its Lire, the page's or that
 * of the row it is in (a saga's film's); null for any other key.
 */
internal fun playAfterRestart(key: String): String? = when {
    key == RESTART -> PLAY
    key.endsWith("/$RESTART") -> key.removeSuffix(RESTART) + PLAY
    else -> null
}
private val VERSIONS = FocusKey.action("versions")
private val TRACKS = FocusKey.action("tracks")
internal val RESTART = FocusKey.action("restart")
private val SERIES = FocusKey.action("series")
internal val WATCHED = FocusKey.action("watched")
private val TECH = FocusKey.action("tech")
private val NFO = FocusKey.action("nfo")
private val FAVORITE = FocusKey.action("favorite")
private val EDIT = FocusKey.action("edit")
private val IDENTIFY = FocusKey.action("identify")
private val REMOVE = FocusKey.action("remove")
private val PARTS = FocusKey.action("parts")

/** Parts shown as buttons in the action row, beside Lire and the tiles. */
private const val INLINE_PARTS = 2
private const val EPISODES = "episodes"

/** Réessayer of a page whose part failed once its title showed. */
private val RETRY_PAGE = FocusKey.action("retry-page")

/** Réessayer of a failed season, keyed within its row so returning focus finds the row. */
private val RETRY_EPISODES = FocusKey.within(FocusKey.row(EPISODES), FocusKey.action("retry"))
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
