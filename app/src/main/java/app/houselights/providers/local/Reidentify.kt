package app.houselights.providers.local

import android.util.Log
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Asks TMDB again for films and shows it found nothing for or hesitated on ([TO_IDENTIFY]), a few per pass, as
 * entries appear on TMDB within days of a release and months later for others: daily in a title's first week, then
 * weekly; an hour after the last time on a scan the user asks for; never while TMDB is out of reach. Reads nothing on
 * the NAS: a title is asked by what its row holds (its name, its NFO, its artwork). A title found at last is saved as
 * an identification saves it, a show with its seasons, then placed in the order it follows; one still not found keeps all
 * it has but its state, its candidates and when it was asked. Each title is a run of its own ([Rounds]): TMDB is asked
 * with the scan's lock released. After an update whose matching rules ([MatchPlan.RULES]) may find them, every title to
 * identify is asked again, once ([newRules]).
 */
internal class Reidentify(
    private val dao: LibraryDao,
    private val rounds: Rounds,
    private val movies: MovieIndexer,
    private val shows: ShowIndexer,
    private val clock: () -> Long,
    /** [LibraryScanner]'s wait while a film plays or the app is in the background; called before each title. */
    private val waitForThePlayer: suspend () -> Unit,
) {
    private val placing = EpisodePlacing(dao, clock)

    /**
     * Returns how many titles changed: found, or found otherwise (another state, other candidates); [films] and [state],
     * the pass's.
     */
    suspend fun run(tmdb: TmdbPass, films: MovieIndexer.FilmCache, asked: Boolean, state: PassState): Int {
        val now = clock()
        val newDue = now - (if (asked) ASKED_EVERY_MS else NEW_EVERY_MS)
        val due = now - (if (asked) ASKED_EVERY_MS else EVERY_MS)
        val newSince = now - NEW_FOR_MS
        val dueFilms = dao.recheckDue(ItemKind.MOVIE, newSince, newDue, due, TITLES_PER_PASS)
        val series = withinCalls(dao.recheckDue(ItemKind.SERIES, newSince, newDue, due, TITLES_PER_PASS))
        var changed = 0
        for (title in dueFilms + series) {
            // TMDB closed during the pass: the others wait for a later one.
            if (!tmdb.open) break
            if (recheck(title, tmdb, films, state)) changed++
        }
        return changed
    }

    /**
     * Makes every title to identify due at once ([RecheckDao.recheckAllNow]) when the rules in use are newer than those
     * [rules] keeps, then keeps them: called as a pass starts, under its lock, so that titles it asks are left alone. The
     * titles are then asked [TITLES_PER_PASS] at a time, those waiting longest first.
     */
    suspend fun newRules(rules: RulesMark) {
        if (rules.read() >= MatchPlan.RULES) return
        val due = dao.recheckAllNow()
        if (due > 0) Log.i(TAG, "Matching rules ${MatchPlan.RULES}: $due titles to identify asked again")
        rules.write(MatchPlan.RULES)
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
    private suspend fun recheck(title: ItemEntity, tmdb: TmdbPass, films: MovieIndexer.FilmCache, state: PassState): Boolean {
        waitForThePlayer()
        return rounds.run(tmdb, state) {
            // Identified, left as it is, re-read by a scan or gone meanwhile: nothing to ask.
            val fresh = dao.item(title.id)?.takeIf { stillDue(it, title) } ?: return@run false
            // The seasons asked for a show found are those its placing reads: each asked once.
            val seasons = ShowIndexer.SeasonCache(tmdb)
            val writes = when (fresh.kind) {
                ItemKind.MOVIE -> movies.recheck(fresh, tmdb, films)
                ItemKind.SERIES -> shows.recheck(fresh, tmdb, seasons)
                else -> null
            } ?: return@run false
            var changed = false
            withContext(NonCancellable) {
                dao.inTransaction {
                    // Left as it is while TMDB was asked ([LocalEditor.keepUnidentified], at once): what it found is not written.
                    if (dao.item(title.id)?.userNoMatch == false) changed = writes()
                }
                if (changed && fresh.kind == ItemKind.SERIES) placed(title.id, tmdb, seasons)
            }
            changed
        }
    }

    /**
     * A show found at last placed in the order it follows ([EpisodePlacing.reorder]), its tvshow.nfo's or a Kodi link's,
     * as an identification places it, its episodes saved in TMDB's seasons until then; TMDB not answering, the show is
     * read again whole at the next pass, which places them.
     */
    private suspend fun placed(seriesId: Long, tmdb: TmdbPass, seasons: ShowIndexer.SeasonCache) {
        if (dao.item(seriesId)?.tmdbId == null) return
        val placed = suspendRunCatching { placing.reorder(seriesId, tmdb, seasons = seasons) }.uncollectedThrown()
            .onFailure { Log.w(TAG, "Episodes of show $seriesId wait for the next pass to be placed", it) }
        if (placed.getOrNull() == null) dao.readAgain(seriesId)
    }

    private fun stillDue(fresh: ItemEntity, title: ItemEntity) = fresh.tmdbId == null && fresh.userTmdbId == null &&
        !fresh.userNoMatch && fresh.matchedAt == title.matchedAt && fresh.missingSince == null

    companion object {
        private const val TAG = "Reidentify"

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
