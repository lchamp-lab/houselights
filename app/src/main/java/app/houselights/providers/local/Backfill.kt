package app.houselights.providers.local

import app.houselights.core.Editions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What titles and sagas known before the library kept credits and sagas whole (version 12) are given at later
 * passes, a few a pass, after the folders were read: a title's credits ([CreditRules]) with its votes and its
 * edition, from TMDB's answer for the title it is matched with and from the NFO it keeps; a saga's films
 * ([SagaPartEntity]). TMDB is asked as the pass asks it ([TmdbPass]), its failures ending the pass's questions;
 * a title it does not answer waits for a later pass, as one the pass has no time for. Nothing the user did is
 * written: only the credits, and those columns ([LibraryDao.setBackfilled]).
 */
internal class Backfill(private val dao: LibraryDao, private val lock: Mutex, private val clock: () -> Long) {
    /** What TMDB says of a title that the backfill keeps. */
    private class Found(val people: TmdbPeople, val votes: Int?, val imdbId: String?, val tvdbId: Int? = null)

    /**
     * One pass's share: [TITLES_PER_PASS] titles and [SAGAS_PER_PASS] sagas at most. [askTmdb]: TMDB may be
     * asked (it did not fail a recent pass); without it, titles TMDB knows wait. Returns what was written.
     */
    suspend fun run(tmdb: TmdbPass, askTmdb: Boolean): Int {
        var written = 0
        for (title in dao.creditsDue(withTmdb = askTmdb && tmdb.open, limit = TITLES_PER_PASS)) {
            if (title.tmdbId != null && !tmdb.open) continue
            if (credits(title, tmdb)) written++
        }
        if (!askTmdb) return written
        for (saga in dao.sagasDue(SAGAS_PER_PASS)) {
            if (!tmdb.open) break
            if (parts(saga, tmdb)) written++
        }
        return written
    }

    /** The credits of [title]: false when TMDB did not answer for it, which a later pass asks again. */
    private suspend fun credits(title: ItemEntity, tmdb: TmdbPass): Boolean {
        val show = title.kind == ItemKind.SERIES
        val id = title.tmdbId
        val answer: TmdbAnswer<Found> = if (id == null) TmdbAnswer.Given(null) else tmdb.ask { client -> lookUp(client, id, show) }
        if (answer !is TmdbAnswer.Given) return false
        val found = answer.value
        val raw = title.nfoInfo()
        // The NFO beside the file, unless it describes another title than the one the user matched it with.
        val nfo = raw?.takeUnless { MetadataMerge.describesOther(it, title.userTmdbId, found?.imdbId, found?.tvdbId) }
        val votes = if (nfo?.rating != null) nfo.votes else found?.votes ?: title.votes
        val edition = if (show) null else raw?.edition?.let(Editions::of) ?: title.filePath?.let(FilenameParser::edition)
        val credits = CreditRules.credits(title.id, show, nfo, found?.people)
        lock.withLock {
            // Removed meanwhile: nothing to give it.
            if (dao.rows(listOf(title.id)).isEmpty()) return@withLock
            val now = clock()
            dao.inTransaction {
                credits?.let { dao.replaceCredits(title.id, it, now) }
                dao.setBackfilled(title.id, votes, edition, now)
            }
        }
        return true
    }

    private suspend fun lookUp(client: TmdbClient, id: Int, show: Boolean): Found? = if (show) {
        client.tvById(id)?.let { Found(TmdbPeople(it.credits, it.createdBy), it.voteCount, it.externalIds.imdbId, it.externalIds.tvdbId) }
    } else {
        client.movieById(id)?.let { Found(TmdbPeople(it.credits), it.voteCount, it.imdbId) }
    }

    /** The films TMDB lists of [saga]: false when it did not answer, which a later pass asks again. */
    private suspend fun parts(saga: CollectionEntity, tmdb: TmdbPass): Boolean {
        val id = saga.tmdbId ?: return false
        val answer = tmdb.ask { it.collection(id) }
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
                        )
                    )
                    dao.replaceSagaParts(fresh.id, CollectionRules.parts(fresh.id, full))
                }
            }
        }
        return true
    }

    companion object {
        /** Titles given their credits a pass at most: one question to TMDB each. */
        const val TITLES_PER_PASS = 40

        /** Sagas given their films a pass at most: one question to TMDB each. */
        const val SAGAS_PER_PASS = 20
    }
}
