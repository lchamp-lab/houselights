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
 * A wall's first page, asked while home's track slides its pill to the library chosen: the wall
 * opens on it, the posters of its first two lines in memory, instead of on its placeholders. Taken
 * once, by the wall of that library opening on its first sort without a filter, soon after; any
 * other wall asks for its own.
 */
class LibraryAhead(private val provider: () -> MediaProvider, private val now: () -> Long = SystemClock::uptimeMillis) {
    private class Ahead(val libraryId: String, val page: Deferred<Result<Page>>, val at: Long)

    private var ahead: Ahead? = null

    /** Asks for [library]'s first page in [scope], then loads its first two lines of posters with [loader]. */
    fun ask(scope: CoroutineScope, library: Library, pictures: PicturesAhead, loader: ImageLoader) {
        val page = scope.async {
            suspendRunCatching { provider().libraryPage(library, FIRST_SORT, LibraryFilter(), start = 0, limit = LibraryViewModel.PAGE) }
        }
        ahead = Ahead(library.id, page, now())
        scope.launch {
            page.await().onSuccess { LoadingAhead.load(loader, pictures.wall(it.items.take(COLUMNS * FIRST_LINES))) }
        }
    }

    /**
     * The first page asked ahead for [library], sorted by [sort] and filtered by [filter]: null when
     * none was, for that wall, or it failed (the wall then asks itself, and says why if it fails).
     */
    suspend fun take(library: Library, sort: SortOption, filter: LibraryFilter): Page? {
        val taken = ahead?.takeIf { it.libraryId == library.id && now() - it.at <= FRESH_MS } ?: return null
        ahead = null
        if (sort != FIRST_SORT || filter != LibraryFilter()) return null
        return taken.page.await().getOrNull()
    }

    private companion object {
        /** How a wall opens, before it is sorted otherwise. */
        val FIRST_SORT = SortOption.TITLE

        /** The lines of posters a wall shows before it scrolls. */
        const val FIRST_LINES = 2

        /** A page asked this long ago is not the library as it is now: the wall asks again. */
        const val FRESH_MS = 5_000L
    }
}
