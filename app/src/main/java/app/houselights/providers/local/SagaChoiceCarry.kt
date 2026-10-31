package app.houselights.providers.local

/**
 * Carries a saga's pictures as the user chose them ([SagaChoiceEntity]) from its name's key (`name:`, a saga its films'
 * NFO sets name) to its TMDB id's (`tmdb:`) once its films join the saga TMDB knows, a TMDB key added or a film read
 * again or identified, where the choice stayed under the name and the saga showed Automatique. Run in the transaction
 * that moves a film ([moved]), as a title's kept state is given back in the one writing its TMDB id ([LibraryDao.giveBack]):
 * the cached pictures the sweep spares ([LibraryDao.cachedArtworkPaths]) are never read between the two keys.
 *
 * - TMDB's saga keeps a choice of its own: the name's fills only a saga without one.
 * - Two sagas of names joining one: the one of the most films wins, whatever the order the pass reads them in, the
 *   names' keys in order between two of as many; never over a choice made since it was carried.
 * - A saga's films joining several of TMDB's: each takes the choice.
 * - The name's choice goes with its saga's last film, carried or given way to TMDB's saga's own; while films remain,
 *   the name's saga keeps it.
 */
internal class SagaChoiceCarry(private val dao: LibraryDao) {
    /** A choice carried during this pass to a saga, by that saga's key: from [from], a saga of [films] films then. */
    private class Carried(val from: String, val films: Int, val choice: SagaChoiceEntity) {
        /** Whether the choice of another saga, [other], of [more] films, takes this one's place. */
        fun givesWayTo(other: String, more: Int): Boolean = other != from && (more > films || more == films && other < from)
    }

    private val carried = mutableMapOf<String, Carried>()

    fun startPass() = carried.clear()

    /** The choices carried so far, put back by the function returned ([PassState]). */
    fun mark(): () -> Unit {
        val was = carried.toMap()
        return {
            carried.clear()
            carried += was
        }
    }

    /** A film has moved from saga [left] to saga [joined], in the transaction that moved it. */
    suspend fun moved(left: Long?, joined: Long?) {
        if (left == null || joined == null || left == joined) return
        val from = dao.collection(left)?.takeIf { it.tmdbId == null } ?: return
        val to = dao.collection(joined)?.takeIf { it.tmdbId != null } ?: return
        val remain = dao.sagaItemCount(from.id)
        dao.sagaChoice(from.matchKey)?.let { carry(it, from.matchKey, films = remain + 1, to.matchKey) }
        // Its last film gone to TMDB's saga, the name's choice is there now or gave way to that saga's own.
        if (remain == 0) dao.deleteSagaChoice(from.matchKey)
    }

    private suspend fun carry(choice: SagaChoiceEntity, from: String, films: Int, to: String) {
        val held = dao.sagaChoice(to)
        val rival = carried[to]
        // A choice carried this pass and left as it was gives way to a saga of more films; any other is the saga's own.
        val wins = held == null || rival != null && rival.choice == held && rival.givesWayTo(from, films)
        if (!wins) return
        val given = choice.copy(matchKey = to)
        dao.saveSagaChoice(given)
        carried[to] = Carried(from, films, given)
    }
}
