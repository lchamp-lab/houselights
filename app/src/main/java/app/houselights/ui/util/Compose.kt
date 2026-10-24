package app.houselights.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.AppContainer
import app.houselights.HouselightsApp
import app.houselights.ui.design.HlMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.random.Random

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as HouselightsApp).container

fun Context.appContainer(): AppContainer = (applicationContext as HouselightsApp).container

/**
 * Calls [reload] when the library changed since the screen last showed it, at once or on its return to the
 * front, telling whether nothing but a running scan's progress moved it (scanOnly). What was shown is saved with its
 * process, as versions restart from 0 after process death.
 */
@Composable
fun ReloadOnLibraryChange(reload: (scanOnly: Boolean) -> Unit) {
    val app = appContainer()
    ReloadOnLibraryChange(app.libraryVersion, app.settledVersion, reload)
}

@Composable
internal fun ReloadOnLibraryChange(version: StateFlow<Long>, settled: StateFlow<Long>, reload: (scanOnly: Boolean) -> Unit) {
    val at by version.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableStateOf(shownAt(at, settled.value)) }
    LaunchedEffect(at) {
        if (isBehind(shown, at)) reload(scanOnly(shown, settled.value))
        shown = shownAt(at, settled.value)
    }
}

/**
 * The library version last shown by the screens of one view model (home and « À regarder »), so a change reloads it
 * once, whichever shows it first. It lives with the view model: a new process makes both anew, reading the library then.
 */
class LibraryShown {
    private var at: Long? = null

    /** Whether the library moved on since it was last shown, now shown at [version]. */
    @Synchronized
    fun moveTo(version: Long): Boolean = (at != null && at != version).also { at = version }
}

/** Calls [reload] when the library changed since the screens sharing [shown] last showed it, at once or on their return. */
@Composable
fun ReloadOnLibraryChange(shown: LibraryShown, reload: () -> Unit) = ReloadOnLibraryChange(appContainer().libraryVersion, shown, reload)

@Composable
internal fun ReloadOnLibraryChange(version: StateFlow<Long>, shown: LibraryShown, reload: () -> Unit) {
    val at by version.collectAsStateWithLifecycle()
    LaunchedEffect(at) { if (shown.moveTo(at)) reload() }
}

/** Identifies this process: library versions from two processes do not compare. */
private val PROCESS = Random.nextLong()

/** What a screen showed, as its saved state keeps it: the process it was shown in, the library version, the settled one. */
internal fun shownAt(version: Long, settled: Long, process: Long = PROCESS) = longArrayOf(process, version, settled)

/** True when the library moved on to [current] since [shown]; a version shown in another process never is. */
internal fun isBehind(shown: LongArray, current: Long, process: Long = PROCESS): Boolean = shown[0] == process && shown[1] != current

/** True when the [settled] version is still the one [shown]: only a running scan's progress moved the library since. */
internal fun scanOnly(shown: LongArray, settled: Long): Boolean = shown.getOrNull(2) == settled

/** [error], or the last one while [retrying], so the intertitle and its busy button stay; else null. */
@Composable
fun retainedError(error: UiError?, retrying: Boolean): UiError? {
    // Not state: the result follows the arguments, read again at each composition.
    val last = remember { arrayOfNulls<UiError>(1) }
    last[0] = error ?: last[0].takeIf { retrying }
    return last[0]
}

/** False until [HlMotion.SKELETON_DELAY] has passed, so a quick load never flashes a placeholder. */
@Composable
fun skeletonDelayPassed(): Boolean {
    val passed by produceState(false) {
        delay(HlMotion.SKELETON_DELAY)
        value = true
    }
    return passed
}

/**
 * [value] once it has held for [HlMotion.SETTLE], so what follows focus loads no image per step; null at
 * first, immediate while nothing is shown. Read in an effect, so steps never recompose the caller.
 */
@Composable
fun <T : Any> rememberSettled(value: () -> T?): State<T?> {
    val latest = rememberUpdatedState(value)
    val settled = remember { mutableStateOf<T?>(null) }
    LaunchedEffect(Unit) { snapshotFlow { latest.value() }.settleInto(settled) }
    return settled
}

/** Feeds [settled] for [rememberSettled]: a value changing before [settle] has passed waits again. */
internal suspend fun <T : Any> Flow<T?>.settleInto(settled: MutableState<T?>, settle: Long = HlMotion.SETTLE) {
    collectLatest { next ->
        if (settled.value != null && next != settled.value) delay(settle)
        settled.value = next
    }
}

/** Size in physical pixels, to request images at the size they are displayed. */
@Composable
fun Dp.toPx(): Int = with(LocalDensity.current) { this@toPx.roundToPx() }

/** The screen in physical pixels, whatever density the device declares. */
@Composable
fun screenSizePx(): IntSize {
    // Read so a configuration change recomposes the callers.
    LocalConfiguration.current
    val metrics = LocalContext.current.resources.displayMetrics
    return IntSize(metrics.widthPixels, metrics.heightPixels)
}

@Composable
fun screenWidthPx(): Int = screenSizePx().width

/** The screen's height on the canvas the app lays out for (540 dp on a TV). */
@Composable
fun screenHeight(): Dp = with(LocalDensity.current) { screenSizePx().height.toDp() }
