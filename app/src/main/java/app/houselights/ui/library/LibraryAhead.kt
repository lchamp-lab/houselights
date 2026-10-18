package app.houselights.ui.library

import android.os.SystemClock
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.MediaProvider
import app.houselights.core.Page
import app.houselights.core.SortOption
import app.houselights.core.suspendRunCatching
import app.houselights.ui.design.components.LoadingAhead
import app.houselights.ui.design.components.PicturesAhead
import coil.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Prefetches a wall's first page and first two poster lines while Home's track moves to that library, so the
 * wall opens filled. Consumed once, by that wall opening soon after with the default sort and no filter.
 */
class LibraryAhead(private val provider: () -> MediaProvider, private val now: () -> Long = SystemClock::uptimeMillis) {
    private class Ahead(val libraryId: String, val page: Deferred<Result<Page>>, val at: Long)

    private var ahead: Ahead? = null

    /** Requests [library]'s first page in [scope], then preloads its first two lines of posters with [loader]. */
    fun ask(scope: CoroutineScope, library: Library, pictures: PicturesAhead, loader: ImageLoader) {
        val page = scope.async {
            suspendRunCatching { provider().libraryPage(library, FIRST_SORT, LibraryFilter(), start = 0, limit = LibraryViewModel.PAGE) }
        }
        ahead = Ahead(library.id, page, now())
        scope.launch {
            page.await().onSuccess { LoadingAhead.load(loader, pictures.wall(it.items.take(COLUMNS * FIRST_LINES))) }
        }
    }

    /** Consumes [library]'s prefetched page; null if none, stale, failed, or [sort] and [filter] are not defaults. */
    suspend fun take(library: Library, sort: SortOption, filter: LibraryFilter): Page? {
        val taken = ahead?.takeIf { it.libraryId == library.id && now() - it.at <= FRESH_MS } ?: return null
        ahead = null
        if (sort != FIRST_SORT || filter != LibraryFilter()) return null
        return taken.page.await().getOrNull()
    }

    private companion object {
        /** The sort a wall opens with. */
        val FIRST_SORT = SortOption.TITLE

        /** Poster lines visible before the wall scrolls. */
        const val FIRST_LINES = 2

        /** A prefetch older than this may be stale: the wall loads again. */
        const val FRESH_MS = 5_000L
    }
}
