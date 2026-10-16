package app.houselights.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.AppContainer
import app.houselights.HouselightsApp
import app.houselights.ui.design.HlMotion
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as HouselightsApp).container

fun Context.appContainer(): AppContainer = (applicationContext as HouselightsApp).container

/**
 * Calls [reload] when the library changed since the screen last showed it: at once while the screen is
 * shown, or when it comes back to the front. A screen hidden behind another one does no work. What it
 * last showed is saved with the process it was shown in: after Android stopped the app, the versions
 * start again from 0 and say nothing of the screen restored, whose ViewModel loads anew all the same.
 */
@Composable
fun ReloadOnLibraryChange(reload: () -> Unit) {
    val version by appContainer().libraryVersion.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableStateOf(shownAt(version)) }
    LaunchedEffect(version) {
        if (isBehind(shown, version)) reload()
        shown = shownAt(version)
    }
}

/** This process among others: the library versions of two processes do not compare. */
private val PROCESS = Random.nextLong()

/** What a screen showed, as its saved state keeps it: the process it was shown in, then the library version. */
internal fun shownAt(version: Long, process: Long = PROCESS) = longArrayOf(process, version)

/** True when the library moved on to [current] since [shown]; a version shown in another process never is. */
internal fun isBehind(shown: LongArray, current: Long, process: Long = PROCESS): Boolean = shown[0] == process && shown[1] != current

/**
 * The error a screen shows: [error] when there is one, else the last one while the screen tries
 * again ([retrying]), so its intertitle stays in place with focus on its button, at work; null once
 * the screen has something else to show.
 */
@Composable
fun retainedError(error: UiError?, retrying: Boolean): UiError? {
    // Not state: what it returns follows its arguments, read again at each composition.
    val last = remember { arrayOfNulls<UiError>(1) }
    last[0] = error ?: last[0].takeIf { retrying }
    return last[0]
}

/**
 * False until the caller has been shown for [HlMotion.SKELETON_DELAY]: a placeholder waits for it,
 * so a slow load shows one and a quick load never flashes.
 */
@Composable
fun skeletonDelayPassed(): Boolean {
    val passed by produceState(false) {
        delay(HlMotion.SKELETON_DELAY)
        value = true
    }
    return passed
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
