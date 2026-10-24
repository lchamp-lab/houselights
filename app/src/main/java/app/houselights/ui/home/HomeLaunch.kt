package app.houselights.ui.home

import android.content.Context
import app.houselights.LaunchGate
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.settings.AppSettings
import app.houselights.ui.design.components.CutPosterCache
import app.houselights.ui.design.components.LoadingAhead
import app.houselights.ui.design.components.PicturesAhead
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Home's first content: the source's libraries, the rows and what the doors show, read after them: [doors] once read,
 * or the launch's read still under way ([doorsAhead]), which home awaits rather than read them again.
 */
data class HomeRows(
    val libraries: List<Library>,
    val sections: List<HomeSection>,
    val doors: HomeDoors? = null,
    val doorsAhead: DoorsRead? = null,
)

/**
 * Loads Home's first rows and first-view pictures once at a cold start, while the launch screen holds
 * ([LaunchGate]); Home takes the rows ([take]) and is composed underneath, so the gate opens on a drawn Home.
 * Rows read before the library [version] ([app.houselights.core.LibraryEvents]) changed are never shown.
 */
class HomeLaunch(
    private val gate: LaunchGate,
    private val settings: StateFlow<AppSettings>,
    private val provider: () -> MediaProvider,
    private val version: () -> Long,
    /** The film the case proposes when nothing is in progress, as home keeps it. */
    private val pick: UnseenPick = UnseenPick(),
) {
    /** The launch's request: its source, the library version at the time, and the rows or the failure. */
    private class Asked(val source: SourceConfig, val version: Long, val rows: Result<HomeRows>)

    private var begun = false

    /** The request until Home takes it or [drop]; completes with the rows, before the pictures, or null if none. */
    @Volatile
    private var asked: Deferred<Asked?>? = null

    /**
     * Starts the launch once, as the process's first window opens; a [restored] window opens the gate at once.
     * Otherwise loads Home's libraries and rows in parallel, then [pictures], all on [work], off the main thread.
     */
    fun begin(
        scope: CoroutineScope,
        restored: Boolean,
        pictures: suspend (HomeRows) -> Unit,
        work: CoroutineContext = Dispatchers.Default,
    ) {
        synchronized(this) {
            if (begun) return
            begun = true
        }
        scope.launch { gate.openAtCap() }
        if (restored) {
            gate.startsElsewhere()
            return
        }
        val answer = CompletableDeferred<Asked?>()
        asked = answer
        scope.launch(work) {
            try {
                prepare(pictures, answer)
            } finally {
                // Nothing requested, or the work was cancelled: Home loads by itself.
                answer.complete(null)
            }
        }
    }

    private suspend fun prepare(pictures: suspend (HomeRows) -> Unit, answer: CompletableDeferred<Asked?>) {
        val read = settings.first { it.loaded || it.readFailed }
        gate.settingsRead()
        // No source yet (Welcome) or unreadable settings: the app does not start on Home.
        val source = read.source?.takeIf { read.loaded }
        if (source == null) {
            gate.startsElsewhere()
            return
        }
        val at = version()
        var home: MediaProvider? = null
        val rows = suspendRunCatching {
            val provided = provider().also { home = it }
            coroutineScope {
                val libraries = async { provided.libraries() }
                val sections = async { provided.home() }
                HomeRows(libraries.await(), sections.await())
            }
        }
        // A failure is first content too: Home takes it at once, composed under the launch screen.
        gate.homeLoaded()
        coroutineScope {
            // The doors are read after the rows, which home shows meanwhile; it awaits this read rather than make its own.
            val doors = rows.getOrNull()?.let { read -> readDoorsAsync(checkNotNull(home), read.libraries, read.sections, pick) }
            val launched = rows.map { it.copy(doorsAhead = doors) }
            answer.complete(Asked(source, at, launched))
            launched.onSuccess { read -> load(read, pictures) }
        }
        gate.picturesLoaded()
    }

    /**
     * Preloads the pictures of [rows], their doors under way ([HomeRows.doorsAhead]), until done or the gate opens at
     * its time cap; Home then loads the rest, never twice.
     */
    private suspend fun load(rows: HomeRows, pictures: suspend (HomeRows) -> Unit) = coroutineScope {
        if (gate.isOpen) return@coroutineScope
        val loading = launch { suspendRunCatching { pictures(rows) } }
        val cap = launch {
            gate.awaitOpen()
            loading.cancel()
        }
        loading.join()
        cap.cancel()
    }

    /**
     * The launch's rows for [source], as soon as they arrive. Null when none were requested, already taken or
     * dropped, from another source, or stale by library version; a failed request rethrows for Home to report.
     */
    suspend fun take(source: SourceConfig?): HomeRows? {
        val launched = synchronized(this) { asked.also { asked = null } } ?: return null
        val answer = launched.await() ?: return null
        if (answer.source != source || answer.version != version()) return null
        return answer.rows.getOrThrow()
    }

    /**
     * Discards the rows when the first window finishes before Home takes them, so a later window asks the source
     * rather than show stale rows (a Jellyfin server's changes do not move the library version).
     */
    fun drop() = synchronized(this) { asked = null }
}

/**
 * Preloads Home's first-view pictures for a [screenWidthPx] screen, each as soon as what shows it is read; Home awaits
 * each one ([LoadingAhead]). What the rows show loads at once, the case proposed once drawn ([DoorsRead.first]), the
 * doors' posters once read ([DoorsRead.whole]), their counts cut into [cuts] so they show on home's first frame.
 */
internal fun loadFirstPictures(
    context: Context,
    screenWidthPx: Int,
    cuts: CutPosterCache,
    canSearch: () -> Boolean = { true },
): suspend (HomeRows) -> Unit = { rows ->
    val ahead = PicturesAhead(context, screenWidthPx)
    val search = canSearch()
    val loader = context.imageLoader
    coroutineScope {
        launch { LoadingAhead.load(loader, rowPictures(ahead, planOf(rows, search))) }
        // A read gone away (cancelled) ends its part alone.
        launch {
            val first = rows.doors ?: rows.doorsAhead?.first?.await() ?: return@launch
            (planOf(rows.copy(doors = first), search).case as? CaseShows.Proposed)?.let { LoadingAhead.load(loader, ahead.case(it.item)) }
        }
        launch {
            val doors = rows.doors ?: rows.doorsAhead?.whole?.await() ?: return@launch
            val plan = planOf(rows.copy(doors = doors), search)
            launch { LoadingAhead.load(loader, plan.doors.filterNot { it.ofRows }.flatMap { it.pictures(ahead) }) }
            plan.doors.mapNotNull(::cutIn).forEach { posters -> launch { ahead.cutAhead(posters, cuts) } }
        }
    }
}

/** Home's grid for the launch's [rows]. */
private fun planOf(rows: HomeRows, canSearch: Boolean): HomePlan {
    val tabs = rows.libraries.filter { it.kind != LibraryKind.COLLECTIONS }
    val sagas = rows.libraries.firstOrNull { it.kind == LibraryKind.COLLECTIONS }
    return homePlan(rows.sections, tabs, sagas, rows.doors, canSearch)
}

/** The posters a door's count is cut in; none for a door that shows no count. */
private fun cutIn(door: Door): List<MediaItem>? = when (door) {
    is Door.Library -> door.posters
    is Door.Libraries -> door.posters
    else -> null
}

/**
 * Home's first-view image requests, built as its grid builds them: the case's picture and logo, the next episode's
 * still and logo, then the doors' posters, those a count is cut in read for their pixels.
 */
internal fun firstPictures(ahead: PicturesAhead, rows: HomeRows, canSearch: Boolean = true): List<ImageRequest> =
    planOf(rows, canSearch).let { plan -> plan.case?.item?.let(ahead::case).orEmpty() + plan.doors.flatMap { it.pictures(ahead) } }

/** What [plan] shows from the rows alone, before any door is read: the case resuming, the next episode, the latest titles. */
private fun rowPictures(ahead: PicturesAhead, plan: HomePlan): List<ImageRequest> =
    (plan.case as? CaseShows.Resume)?.item?.let(ahead::case).orEmpty() + plan.doors.filter { it.ofRows }.flatMap { it.pictures(ahead) }

/** Whether the rows alone say what this door shows. */
private val Door.ofRows: Boolean get() = this is Door.Next || this is Door.AlsoResume || this is Door.News

/** The pictures this door shows, as it requests them. */
private fun Door.pictures(ahead: PicturesAhead): List<ImageRequest> = when (this) {
    is Door.Next -> ahead.still(item)
    is Door.AlsoResume -> ahead.still(item)
    is Door.News -> ahead.strip(posters)
    is Door.Library -> ahead.cut(posters)
    is Door.Libraries -> ahead.cut(posters)
    is Door.Sagas -> ahead.sagas(shelf.posters)
    is Door.Search -> ahead.thumbs(posters)
    Door.Settings -> emptyList()
}
