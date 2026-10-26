package app.houselights.providers.local

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Asks TMDB again for films and shows it found nothing for or hesitated on ([TO_IDENTIFY]), a few per pass, as
 * entries appear on TMDB within days of a release and months later for others: daily in a title's first week, then
 * weekly; an hour after the last time on a scan the user asks for; never while TMDB is out of reach. Reads nothing on
 * the NAS: a title is asked by what its row holds (its name, its NFO, its artwork). A title found at last is saved as
 * an identification saves it, a show with its seasons; one still not found keeps all it has but its state, its
 * candidates and when it was asked.
 */
internal class Reidentify(
    private val dao: LibraryDao,
    private val lock: Mutex,
    private val movies: MovieIndexer,
    private val shows: ShowIndexer,
    private val clock: () -> Long,
    /** [LibraryScanner]'s wait while a film plays or the app is in the background; called before each title. */
    private val waitForThePlayer: suspend () -> Unit,
) {
    /** Returns how many titles changed: found, or found otherwise (another state, other candidates). */
    suspend fun run(tmdb: TmdbPass, asked: Boolean): Int {
        val now = clock()
        val newDue = now - (if (asked) ASKED_EVERY_MS else NEW_EVERY_MS)
        val due = now - (if (asked) ASKED_EVERY_MS else EVERY_MS)
        val newSince = now - NEW_FOR_MS
        val films = dao.recheckDue(ItemKind.MOVIE, newSince, newDue, due, TITLES_PER_PASS)
        val series = withinCalls(dao.recheckDue(ItemKind.SERIES, newSince, newDue, due, TITLES_PER_PASS))
        var changed = 0
        for (title in films + series) {
            // TMDB closed during the pass: the others wait for a later one.
            if (!tmdb.open) break
            if (recheck(title, tmdb)) changed++
        }
        return changed
    }

    /**
     * The first of [series] whose TMDB calls fit [SHOW_CALLS_PER_PASS], the first at least: a show found is fetched
     * with each of its seasons, its searches, its details and a call per season.
     */
    private suspend fun withinCalls(series: List<ItemEntity>): List<ItemEntity> {
        val fitting = mutableListOf<ItemEntity>()
        var calls = 0
        for (show in series) {
            calls += MatchPlan.MAX_SEARCHES + 1 + dao.seasonCount(show.id)
            if (fitting.isNotEmpty() && calls > SHOW_CALLS_PER_PASS) break
            fitting += show
        }
        return fitting
    }

    /** False when nothing changed, or TMDB did not answer: a later pass asks again. */
    private suspend fun recheck(title: ItemEntity, tmdb: TmdbPass): Boolean {
        waitForThePlayer()
        return lock.withLock {
            // Identified, left as it is, re-read by a scan or gone meanwhile: nothing to ask.
            val fresh = dao.item(title.id)?.takeIf { stillDue(it, title) } ?: return@withLock false
            val writes = when (fresh.kind) {
                ItemKind.MOVIE -> movies.recheck(fresh, tmdb)
                ItemKind.SERIES -> shows.recheck(fresh, tmdb)
                else -> null
            } ?: return@withLock false
            var changed = false
            withContext(NonCancellable) {
                dao.inTransaction { changed = writes() }
            }
            changed
        }
    }

    private fun stillDue(fresh: ItemEntity, title: ItemEntity) = fresh.tmdbId == null && fresh.userTmdbId == null &&
        !fresh.userNoMatch && fresh.matchedAt == title.matchedAt && fresh.missingSince == null

    companion object {
        /** Films asked again per pass at most, as many shows; shows are counted by their calls too ([SHOW_CALLS_PER_PASS]). */
        const val TITLES_PER_PASS = 40

        /** TMDB calls shows asked again may take per pass, as many as films take at most ([MatchPlan.MAX_SEARCHES]). */
        const val SHOW_CALLS_PER_PASS = TITLES_PER_PASS * (MatchPlan.MAX_SEARCHES + 1)

        private const val HOUR_MS = 60 * 60_000L
        private const val DAY_MS = 24 * HOUR_MS

        /** A title's first week, by when it came, it is asked daily ([NEW_EVERY_MS]); then weekly ([EVERY_MS]). */
        const val NEW_FOR_MS = 7 * DAY_MS
        const val NEW_EVERY_MS = DAY_MS
        const val EVERY_MS = 7 * DAY_MS

        /** On a scan the user asks for, a title is asked again an hour after the last time. */
        const val ASKED_EVERY_MS = HOUR_MS
    }
}
