package app.posterwall.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.posterwall.AppContainer
import app.posterwall.PosterwallApp

/** TV safe-area margins (~5 %). */
val SafeH = 48.dp
val SafeV = 28.dp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as PosterwallApp).container

fun Context.appContainer(): AppContainer = (applicationContext as PosterwallApp).container

/** Size in physical pixels, to request images at the right size. */
@Composable
fun Dp.toPx(): Int = with(LocalDensity.current) { this@toPx.roundToPx() }

@Composable
fun screenWidthPx(): Int = LocalConfiguration.current.screenWidthDp.dp.toPx()
