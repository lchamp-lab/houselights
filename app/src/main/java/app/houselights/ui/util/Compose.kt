package app.houselights.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.AppContainer
import app.houselights.HouselightsApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as HouselightsApp).container

fun Context.appContainer(): AppContainer = (applicationContext as HouselightsApp).container

/**
 * Calls [reload] when the library changed since the screen last showed it: at once while the screen is
 * shown, or when it comes back to the front. A screen hidden behind another one does no work.
 */
@Composable
fun ReloadOnLibraryChange(reload: () -> Unit) {
    val version by appContainer().libraryVersion.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableLongStateOf(version) }
    LaunchedEffect(version) {
        if (version != shown) {
            shown = version
            reload()
        }
    }
}

/** Size in physical pixels, to request images at the size they are displayed. */
@Composable
fun Dp.toPx(): Int = with(LocalDensity.current) { this@toPx.roundToPx() }

@Composable
fun screenWidthPx(): Int = LocalConfiguration.current.screenWidthDp.dp.toPx()
