package app.houselights.ui.home

import android.content.Context
import app.houselights.LaunchGate
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaProvider
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.settings.AppSettings
import app.houselights.ui.design.components.PicturesAhead
import coil.imageLoader
import coil.request.ImageRequest
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
 * screen holds ([LaunchGate]): the gate opens on them, and home takes the same rows instead of asking
 * the source again ([take]), its pictures found in memory. The rows come from the [provider] of the
 * source in the [settings]; [version] is the library's ([app.houselights.core.LibraryEvents]), and
 * rows read before it moved on are never shown.
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

    /** What the launch asked, until home takes it; its answer is null when nothing was asked (the app started elsewhere). */
    @Volatile
    private var asked: Deferred<Asked?>? = null

    /**
     * Begins the launch as the first window of the process opens, once: [restored], a window the
     * system brings back on the screen it showed, opens the gate at once. Else, once the settings are
     * read, home's libraries and rows are asked in parallel, then [pictures] loads the pictures of its
     * first view into memory, all off the main thread ([work]).
     */
    fun begin(scope: CoroutineScope, restored: Boolean, pictures: suspend (HomeRows) -> Unit, work: CoroutineContext = Dispatchers.Default) {
        synchronized(this) {
            if (begun) return
            begun = true
        }
        scope.launch { gate.openAtCap() }
        if (restored) {
            gate.startsElsewhere()
            return
        }
        asked = scope.async(work) { prepare(pictures) }
    }

    private suspend fun prepare(pictures: suspend (HomeRows) -> Unit): Asked? {
        val read = settings.first { it.loaded || it.readFailed }
        gate.settingsRead()
        // Welcome, or the settings that could not be read: the app does not start on home.
        val source = read.source?.takeIf { read.loaded }
        if (source == null) {
            gate.startsElsewhere()
            return null
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
        // A failure is home's first content too: it says why.
        gate.homeLoaded()
        rows.onSuccess { load(it, pictures) }
        gate.picturesLoaded()
        return Asked(source, at, rows)
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
     * Home's first load: the rows the launch asked of [source], once the launch screen leaves; null
     * when none were asked, once taken, or when they were another source's or the library has moved
     * on since (the first window closed before home showed, and the app opens again later). Their
     * failure, if they failed, is home's to say.
     */
    suspend fun take(source: SourceConfig?): HomeRows? {
        val launched = synchronized(this) { asked.also { asked = null } } ?: return null
        gate.awaitOpen()
        val answer = launched.await() ?: return null
        if (answer.source != source || answer.version != version()) return null
        return answer.rows.getOrThrow()
    }
}

/**
 * Loads into memory the pictures home's first view shows on a screen [screenWidthPx] wide
 * ([firstPictures]), all at once.
 */
internal fun loadFirstPictures(context: Context, screenWidthPx: Int): suspend (HomeRows) -> Unit = { rows ->
    val loader = context.imageLoader
    coroutineScope {
        firstPictures(PicturesAhead(context, screenWidthPx), rows.sections).forEach { launch { loader.execute(it) } }
    }
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
