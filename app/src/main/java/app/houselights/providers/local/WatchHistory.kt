package app.houselights.providers.local

/** What a mark applies to: a row (a film, an episode, a season or a show), or a saga's films. */
sealed interface Marked {
    data class Row(val id: Long) : Marked

    data class Saga(val id: Long) : Marked
}

/** Told what the viewer marks and plays in the folders, for an account that keeps their history (Trakt). */
interface WatchHistory {
    /** Marks [what] [played] with [write], noting the titles it changes. */
    suspend fun mark(what: Marked, played: Boolean, write: suspend () -> Unit) = write()

    /** Row [id] stopped at [percent] of its length. */
    suspend fun stopped(id: Long, percent: Float)

    /** Nothing keeps the history. */
    object None : WatchHistory {
        override suspend fun stopped(id: Long, percent: Float) = Unit
    }
}
