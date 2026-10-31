package app.houselights.providers.local

import app.houselights.core.Editions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fills in, a few per pass, what pre-v12 rows lack: full credits, votes and edition; saga parts. Unanswered items
 * wait for a later pass; only credits and [LibraryDao.setBackfilled]'s columns are written, never user state.
 */
internal class Backfill(
    private val dao: LibraryDao,
    private val lock: Mutex,
    private val clock: () -> Long,
    /** [LibraryScanner]'s wait while a film plays or the app is in the background; called before each item. */
    private val waitForThePlayer: suspend () -> Unit,
) {
    private class Found(val people: TmdbPeople, val votes: Int?, val imdbId: String?, val tvdbId: Int? = null)

    /** Returns how many items were written; [askTmdb] is false after a recent TMDB failure. */
    suspend fun run(tmdb: TmdbPass, askTmdb: Boolean): Int {
        var written = 0
        for (title in dao.creditsDue(withTmdb = askTmdb && tmdb.open, limit = TITLES_PER_PASS)) {
            // TMDB closed during the pass: titles that need it wait for a later one.
            if (asksTmdb(title) && !tmdb.open) continue
            waitForThePlayer()
            if (credits(title, tmdb)) written++
        }
        if (!askTmdb) return written
        for (saga in dao.sagasDue(SAGAS_PER_PASS)) {
            if (!tmdb.open) break
            waitForThePlayer()
            if (parts(saga, tmdb)) written++
        }
        return written
    }

    private fun asksTmdb(title: ItemEntity) = title.tmdbId != null

    /** False when TMDB did not answer, so a later pass retries. */
    private suspend fun credits(title: ItemEntity, tmdb: TmdbPass): Boolean {
        val show = title.kind == ItemKind.SERIES
        val id = title.tmdbId
        val answer: TmdbAnswer<Found> = if (id == null || !asksTmdb(title)) {
            TmdbAnswer.Given(null)
        } else {
            tmdb.ask(TmdbKey("credits", id to show)) { client -> lookUp(client, id, show) }
        }
        if (answer !is TmdbAnswer.Given) return false
        val found = answer.value
        val raw = title.nfoInfo()
        // Ignore the NFO when it describes another title than the one the user matched.
        val nfo = raw?.takeUnless { MetadataMerge.describesOther(it, title.userTmdbId, found?.imdbId, found?.tvdbId) }
        val votes = if (nfo?.rating != null) nfo.votes else found?.votes ?: title.votes
        val edition = if (show) null else raw?.edition?.let(Editions::of) ?: title.filePath?.let(FilenameParser::edition)
        val credits = CreditRules.credits(title.id, show, nfo, found?.people)
        lock.withLock {
            // Removed, re-identified or re-read by a scan meanwhile: the answer is stale.
            if (!stillDue(title)) return@withLock
            val now = clock()
            dao.inTransaction {
                // Without a TMDB answer, keep TMDB-only credits and replace only the NFO's jobs.
                credits?.let { dao.replaceCredits(title.id, it, now, whole = found != null) }
                dao.setBackfilled(title.id, votes, edition, now)
            }
        }
        return true
    }

    private suspend fun stillDue(title: ItemEntity): Boolean {
        val fresh = dao.rows(listOf(title.id)).singleOrNull() ?: return false
        return fresh.tmdbId == title.tmdbId && fresh.userTmdbId == title.userTmdbId && fresh.creditsAt == null
    }

    private suspend fun lookUp(client: TmdbClient, id: Int, show: Boolean): Found? = if (show) {
        client.tvById(id)?.let { Found(TmdbPeople(it.credits, it.createdBy), it.voteCount, it.externalIds.imdbId, it.externalIds.tvdbId) }
    } else {
        client.movieById(id)?.let { Found(TmdbPeople(it.credits), it.voteCount, it.imdbId) }
    }

    /** False when TMDB did not answer, so a later pass retries. */
    private suspend fun parts(saga: CollectionEntity, tmdb: TmdbPass): Boolean {
        val id = saga.tmdbId ?: return false
        val answer = tmdb.ask(TmdbKey("collection", id)) { it.collection(id) }
        if (answer !is TmdbAnswer.Given) return false
        lock.withLock {
            val fresh = dao.collection(saga.id) ?: return@withLock
            val full = answer.value
            val now = clock()
            dao.inTransaction {
                if (full == null) {
                    dao.updateCollection(fresh.copy(fetchedAt = now))
                } else {
                    dao.updateCollection(
                        fresh.copy(
                            overview = fresh.overview ?: full.overview?.ifBlank { null },
                            posterPath = fresh.posterPath ?: full.posterPath,
                            backdropPath = fresh.backdropPath ?: full.backdropPath,
                            partCount = CollectionRules.released(full.parts.map { it.releaseDate }, CollectionRules.today()),
                            fetchedAt = now,
                        ),
                    )
                    dao.replaceSagaParts(fresh.id, CollectionRules.parts(fresh.id, full))
                }
            }
        }
        return true
    }

    companion object {
        /** One TMDB request each. */
        const val TITLES_PER_PASS = 40

        const val SAGAS_PER_PASS = 20
    }
}
