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
 * wall opens filled. Consumed once, by that wall opening soon after as it opens by default: sorted by title, not
 * filtered, a films wall with its [sagas][groups] among its films.
 */
class LibraryAhead(private val provider: () -> MediaProvider, private val now: () -> Long = SystemClock::uptimeMillis) {
    private class Ahead(val libraryId: String, val grouped: Boolean, val page: Deferred<Result<Page>>, val at: Long)

    private var ahead: Ahead? = null

    /**
     * Requests [library]'s first page in [scope], its films grouped into the source's [sagas] if any, then preloads its
     * first two lines of posters with [loader].
     */
    fun ask(scope: CoroutineScope, library: Library, sagas: Library?, pictures: PicturesAhead, loader: ImageLoader) {
        val grouped = groups(library, sagas)
        val page = scope.async {
            suspendRunCatching { provider().libraryPage(library, FIRST_SORT, LibraryFilter(), 0, LibraryViewModel.PAGE, grouped) }
        }
        ahead = Ahead(library.id, grouped, page, now())
        scope.launch {
            page.await().onSuccess { LoadingAhead.load(loader, pictures.wall(it.items.take(COLUMNS * FIRST_LINES))) }
        }
    }

    /** Consumes [library]'s prefetched page; null if none, stale, failed, or not asked as [sort], [filter] and [grouped]. */
    suspend fun take(library: Library, sort: SortOption, filter: LibraryFilter, grouped: Boolean): Page? {
        val taken = ahead?.takeIf { it.libraryId == library.id && now() - it.at <= FRESH_MS } ?: return null
        ahead = null
        if (sort != FIRST_SORT || filter != LibraryFilter() || grouped != taken.grouped) return null
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
