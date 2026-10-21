package app.houselights.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import app.houselights.core.ImageRef
import app.houselights.core.SourceConfig
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.components.FIELD_POSTERS
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The posters the settings and their forms lie on (`PosterField`): those last added to the folders in use, else,
 * before any library, the films the welcome wall shows; empty until read, and when neither can be.
 */
@Composable
fun roomPosters(): List<ImageRef> {
    val app = appContainer()
    val width = HlSize.welcomePoster.toPx()
    val latest by remember(app) { app.library.latestPosters(FIELD_POSTERS).distinctUntilChanged() }
        .collectAsState(emptyList())
    val folders = app.settings.state.collectAsState().value.source == SourceConfig.Local
    var paths by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(latest, folders, width) {
        paths = latest.takeIf { folders && it.isNotEmpty() } ?: app.trendingPosters(width).take(FIELD_POSTERS)
    }
    return remember(paths) { paths.map { LocalProvider.image(it, ImageType.POSTER) } }
}
