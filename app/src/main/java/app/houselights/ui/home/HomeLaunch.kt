package app.houselights.ui.home

import android.content.Context
import app.houselights.LaunchGate
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaProvider
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.settings.AppSettings
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

/** Home's first content: the tabs' libraries and the rows. */
data class HomeRows(val libraries: List<Library>, val sections: List<HomeSection>)

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
        val rows = suspendRunCatching {
            val home = provider()
            coroutineScope {
                val libraries = async { home.libraries() }
                val sections = async { home.home() }
                HomeRows(libraries.await(), sections.await())
            }
        }
        // A failure is first content too: Home takes it at once, composed under the launch screen.
        gate.homeLoaded()
        answer.complete(Asked(source, at, rows))
        rows.onSuccess { load(it, pictures) }
        gate.picturesLoaded()
    }

    /** Preloads the pictures until done or the gate opens at its time cap; Home then loads the rest, never twice. */
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

/** Preloads Home's first-view pictures for a [screenWidthPx] screen; Home awaits each one ([LoadingAhead]). */
internal fun loadFirstPictures(context: Context, screenWidthPx: Int): suspend (HomeRows) -> Unit = { rows ->
    LoadingAhead.load(context.imageLoader, firstPictures(PicturesAhead(context, screenWidthPx), rows.sections))
}

/** Home's first-view image requests, built as Home builds them: the hero's pictures, then the first row's cards. */
internal fun firstPictures(ahead: PicturesAhead, sections: List<HomeSection>): List<ImageRequest> {
    val first = sections.firstOrNull() ?: return emptyList()
    val hero = selectedTitle(sections, null)?.let(ahead::hero).orEmpty()
    return hero + ahead.row(first.items, first.landscape, first.spacing)
}
