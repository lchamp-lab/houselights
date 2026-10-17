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

/** What home shows first: the tabs' libraries and the rows. */
data class HomeRows(val libraries: List<Library>, val sections: List<HomeSection>)

/**
 * Home's first rows and the pictures of its first view, asked once at a cold start, while the launch
 * screen holds ([LaunchGate]). Home takes the same rows as soon as they come instead of asking the
 * source again ([take]), and is composed and drawn under the launch screen while the pictures load,
 * finding them in memory; the gate opens once they are in and home drew them. The rows come from the
 * [provider] of the source in the [settings]; [version] is the library's
 * ([app.houselights.core.LibraryEvents]), and rows read before it moved on are never shown.
 */
class HomeLaunch(
    private val gate: LaunchGate,
    private val settings: StateFlow<AppSettings>,
    private val provider: () -> MediaProvider,
    private val version: () -> Long,
) {
    /** What the launch asked: of which source, at which version of the library, and the answer (the rows, or why not). */
    private class Asked(val source: SourceConfig, val version: Long, val rows: Result<HomeRows>)

    private var begun = false

    /**
     * What the launch asked, until home takes it or the first window finishes ([drop]): its answer
     * comes with the rows, before the pictures, and is null when nothing was asked (the app started
     * elsewhere).
     */
    @Volatile
    private var asked: Deferred<Asked?>? = null

    /**
     * Begins the launch as the first window of the process opens, once: [restored], a window the
     * system brings back on the screen it showed, opens the gate at once. Else, once the settings are
     * read, home's libraries and rows are asked in parallel, then [pictures] loads the pictures of its
     * first view into memory while home takes the rows, all off the main thread ([work]).
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
                // Nothing asked, or the launch's work cut short: home asks itself.
                answer.complete(null)
            }
        }
    }

    private suspend fun prepare(pictures: suspend (HomeRows) -> Unit, answer: CompletableDeferred<Asked?>) {
        val read = settings.first { it.loaded || it.readFailed }
        gate.settingsRead()
        // Welcome, or the settings that could not be read: the app does not start on home.
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
        // A failure is home's first content too: it says why. Home takes it at once, and is
        // composed under the launch screen while the pictures load.
        gate.homeLoaded()
        answer.complete(Asked(source, at, rows))
        rows.onSuccess { load(it, pictures) }
        gate.picturesLoaded()
    }

    /**
     * The pictures, until they are in memory or the gate opens without them (the cap): home shows
     * then, and asks for the ones left itself, never twice.
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
     * Home's first load: the rows the launch asked of [source], as soon as they come, under the launch
     * screen; null when none were asked, once taken or dropped, or when they were another source's or
     * the library has moved on since. Their failure, if they failed, is home's to say.
     */
    suspend fun take(source: SourceConfig?): HomeRows? {
        val launched = synchronized(this) { asked.also { asked = null } } ?: return null
        val answer = launched.await() ?: return null
        if (answer.source != source || answer.version != version()) return null
        return answer.rows.getOrThrow()
    }

    /**
     * The first window finished before home took the rows (Back under the launch screen), the
     * process kept: they go with it, and a later window's home asks the source, never showing rows
     * of that moment (a Jellyfin server's changes never move the library's version).
     */
    fun drop() = synchronized(this) { asked = null }
}

/**
 * Loads into memory the pictures home's first view shows on a screen [screenWidthPx] wide
 * ([firstPictures]), all at once: home, composed meanwhile, waits for each one it asks for
 * ([LoadingAhead]), never loading it twice.
 */
internal fun loadFirstPictures(context: Context, screenWidthPx: Int): suspend (HomeRows) -> Unit = { rows ->
    LoadingAhead.load(context.imageLoader, firstPictures(PicturesAhead(context, screenWidthPx), rows.sections))
}

/**
 * What home's first view shows, as it asks Coil for it: the hero's pictures (the first title of the
 * first row, where focus lands), then the cards of the first row on the screen.
 */
internal fun firstPictures(ahead: PicturesAhead, sections: List<HomeSection>): List<ImageRequest> {
    val first = sections.firstOrNull() ?: return emptyList()
    val hero = selectedTitle(sections, null)?.let(ahead::hero).orEmpty()
    return hero + ahead.row(first.items, first.landscape, first.spacing)
}
