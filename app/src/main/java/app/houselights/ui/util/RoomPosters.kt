package app.houselights.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.AppContainer
import app.houselights.core.ImageRef
import app.houselights.core.LibraryFilter
import app.houselights.core.MediaProvider
import app.houselights.core.SortOption
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.ui.design.components.FIELD_POSTERS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** The posters the settings and their forms lie on ([PosterField][app.houselights.ui.design.components.PosterField]). */
@Composable
fun roomPosters(): List<ImageRef> {
    val app = appContainer()
    val posters by remember(app) { roomPosters(app) }.collectAsStateWithLifecycle(emptyList())
    return posters
}

/** The folders' latest posters, each folder's in turn; without folders, the source's latest, each library's in turn. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun roomPosters(app: AppContainer): Flow<List<ImageRef>> = app.library.folders.flatMapLatest { folders ->
    if (folders.isEmpty()) {
        flow { emit(suspendRunCatching { latestPosters(app.provider) }.getOrDefault(emptyList())) }
    } else {
        combine(folders.map { app.library.latestPosters(it, FIELD_POSTERS) }) { strips ->
            inTurn(strips.toList()).map { LocalProvider.image(it, ImageType.POSTER) }
        }
    }
}

/** Up to [FIELD_POSTERS] of [provider]'s latest posters, each library's in turn. */
private suspend fun latestPosters(provider: MediaProvider): List<ImageRef> = inTurn(
    provider.libraries().map { library ->
        provider.libraryPage(library, SortOption.ADDED, LibraryFilter(), 0, FIELD_POSTERS).items.mapNotNull { it.poster }
    },
)

/** The first of each list, then the second of each, up to [FIELD_POSTERS]. */
private fun <T> inTurn(lists: List<List<T>>): List<T> =
    (0 until FIELD_POSTERS).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.take(FIELD_POSTERS)
