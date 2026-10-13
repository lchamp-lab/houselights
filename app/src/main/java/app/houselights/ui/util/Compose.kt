package app.houselights.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.AppContainer
import app.houselights.HouselightsApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as HouselightsApp).container

fun Context.appContainer(): AppContainer = (applicationContext as HouselightsApp).container

/** Size in physical pixels, to request images at the size they are displayed. */
@Composable
fun Dp.toPx(): Int = with(LocalDensity.current) { this@toPx.roundToPx() }

@Composable
fun screenWidthPx(): Int = LocalConfiguration.current.screenWidthDp.dp.toPx()
