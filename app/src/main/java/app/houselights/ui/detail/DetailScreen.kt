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

    // Focus returns to what opened another screen; a first visit starts on the play button.
    val memory = rememberFocusMemory()
    val actionRow = remember { FocusRequester() }
    // Wait for the rows: returning focus may target one of their cards.
    RestoreFocus(memory, actionRow, ready = item != null && !state.partial)
    // A film started over is resumed next: back from the player, focus is on the play button.
    LaunchedEffect(Unit) {
        app.playback.playbackEnded.collect { if (memory.last == RESTART) memory.focus(PLAY) }
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
    val crafts = endCrafts(item, state.credits).map { (job, names) -> Craft(jobName(job), names) }
    val tech = techTile(file = media != null, credits = crafts.isNotEmpty())
    if (showTech && tech != null) TechSpecs(item, onDismiss = { showTech = false }, crafts = crafts)
    var showVersions by remember { mutableStateOf(false) }
    if (showVersions) VersionsSheet(item, state.versions, onChoose = vm::chooseVersion, onDismiss = { showVersions = false })
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
        // Null hides the button: nothing for the technical sheet, or a single copy.
        onShowTech = { showTech = true }.takeIf { tech != null },
        techAt = tech ?: TechTile.InPlace,
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
        // Scrolls by blocks: an incoming row rises to the safe area; the wider block gap hides the row above.
        ListScroll(top = HlFocus.edge, toTop = true) { LazyColumn(
                Modifier.fillMaxSize().houseLit(LocalHouseLights.current),
                state = page,
                contentPadding = PaddingValues(bottom = HlFocus.edge),
                verticalArrangement = Arrangement.spacedBy(HlLayout.detailGap),
            ) {
                item(key = "hero") {
                    // The screen minus a peek of the first row; grows rather than squeezes long text.
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
            } }
    }
}

/** A show's season tabs and the chosen season's episodes, below the hero. */
private fun LazyListScope.seasonRows(
    state: DetailState,
    memory: FocusMemory,
    /** The focused episode, read only where its overview shows. */
    focusedEpisode: MutableState<MediaItem?>,
    vm: DetailViewModel,
    onPlay: (MediaItem, Boolean) -> Unit,
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

/** The page's actions, passed down whole; named fields keep same-typed lambdas apart, and null hides a button. */
@Immutable
private data class DetailActions(
    val onPlay: (MediaItem, resume: Boolean) -> Unit,
    val onTogglePlayed: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onOpenSeries: () -> Unit,
    val onOpenNfo: () -> Unit,
    val onShowTech: (() -> Unit)?,
    /** Where the technical sheet's tile stands ([techTile]). */
    val techAt: TechTile = TechTile.InPlace,
    val onShowVersions: (() -> Unit)?,
    val onShowSynopsis: () -> Unit,
    /** The copy Lire plays and its index (to name it); null for one copy. */
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
                FormatLines(item)
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

        Actions(item, nextUp, capabilities, actionError, actions, memory, actionRow)
    }
}

/** The title's [actions] in one row on plates over the picture, with room below for a tile's label so nothing moves. */
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
    OnArtwork { HlActionRow(Modifier.focusRequester(actionRow).fadeOutsideSafeArea()) {
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
        if (item.kind == MediaKind.EPISODE && item.seriesId != null) {
            HlButton(stringResource(R.string.view_series), onClick = actions.onOpenSeries, modifier = Modifier.focusKey(SERIES, memory))
        }
        // Label: what OK does; icon: the current state. Watched is done, not a choice: the check fills white.
        if (capabilities?.markPlayed == true) {
            HlIconButton(
                if (item.user.played) HlIcons.CheckFilled else HlIcons.Check,
                stringResource(if (item.user.played) R.string.mark_unwatched else R.string.mark_watched),
                onClick = actions.onTogglePlayed,
                modifier = Modifier.focusKey(WATCHED, memory),
            )
        }
        TechButton(actions, memory, TechTile.InPlace)
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
        TechButton(actions, memory, TechTile.Last)
        ActionFailure(problem, actionError)
    } }
}

/** The technical sheet's tile, when it stands [at] this place; it bears the name of what it opens. */
@Composable
private fun TechButton(actions: DetailActions, memory: FocusMemory, at: TechTile) {
    val open = actions.onShowTech?.takeIf { actions.techAt == at } ?: return
    HlIconButton(HlIcons.Info, stringResource(R.string.tech_details), onClick = open, modifier = Modifier.focusKey(TECH, memory))
}

/**
 * Where the technical sheet's tile goes: in its place for a [file]'s facts, known as the page shows; last for
 * [credits] alone, which come after the page, so no tile moves under focus. Null without either.
 */
internal fun techTile(file: Boolean, credits: Boolean): TechTile? = when {
    file -> TechTile.InPlace
    credits -> TechTile.Last
    else -> null
}

/** Where the technical sheet's tile stands among the actions. */
internal enum class TechTile { InPlace, Last }

/** Why the last play request failed ([problem]) or a change did not save ([actionError]), beside the actions. */
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

/** Lire or Reprendre (one button, so focus stays put), then start-over; a series or saga plays its next title. */
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

/** A season's progress after its name: a check when fully watched, "6 / 10" when partly, nothing before. */
internal fun seasonDetail(season: MediaItem): SegmentDetail? {
    val episodes = season.childCount?.takeIf { it > 0 } ?: return SegmentDetail.Done.takeIf { season.user.played }
    val watched = episodes - (season.user.unplayedCount ?: if (season.user.played) 0 else episodes)
    return when {
        season.user.played || watched >= episodes -> SegmentDetail.Done
        watched > 0 -> SegmentDetail.Count("$watched$NBSP/$NBSP$episodes")
        else -> null
    }
}

/** An episode's state: watched, or the time left; null when not started. */
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
            // At least three lines at the viewer's text size, so the page stays still as focus moves.
            modifier = Modifier
                .padding(horizontal = HlSpace.safeH)
                .widthIn(max = HlLayout.readable)
                .heightIn(min = textRoom(HlLayout.episodeOverview))
                .fadeOutsideSafeArea(),
        )
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

/** The episode row's error and its fix; while [retrying], the row stays, its button busy and focused. */
@Composable
private fun EpisodesFailed(header: String?, error: UiError, retrying: Boolean, onRetry: () -> Unit, action: Modifier) {
    Column(Modifier.scrollsAsBlock().padding(horizontal = HlSpace.safeH)) {
        header?.let { SectionHeader(it, Modifier.padding(bottom = HlSpace.xxs)) }
        PartFailed(error, retrying, onRetry, action = action)
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
private val RESTART = FocusKey.action("restart")
private val SERIES = FocusKey.action("series")
private val WATCHED = FocusKey.action("watched")
private val TECH = FocusKey.action("tech")
private val NFO = FocusKey.action("nfo")
private val FAVORITE = FocusKey.action("favorite")
private val EDIT = FocusKey.action("edit")
private val PARTS = FocusKey.action("parts")

/** Parts shown as buttons in the action row, beside Lire and the tiles. */
private const val INLINE_PARTS = 2
private const val EPISODES = "episodes"

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

/** The crafts the head omits, for the technical sheet, up to [CRAFT_NAMES] names each; a show's only music (it varies by episode). */
internal fun endCrafts(item: MediaItem, credits: Credits?): List<Pair<Job, List<String>>> {
    val crew = credits?.crew ?: return emptyList()
    val jobs = if (item.kind == MediaKind.SERIES) SHOW_CRAFTS else FILM_CRAFTS
    return jobs.mapNotNull { job ->
        crew[job].orEmpty().map { it.name }.distinct().take(CRAFT_NAMES).takeIf { it.isNotEmpty() }?.let { job to it }
    }
}

private val FILM_CRAFTS = listOf(Job.WRITER, Job.COMPOSER, Job.CINEMATOGRAPHY, Job.EDITING)
private val SHOW_CRAFTS = listOf(Job.COMPOSER)


/** Placeholders while a season loads: as many episodes as the screen shows whole. */
private const val EPISODE_SKELETONS = 3

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
