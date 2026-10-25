package app.houselights.providers.local

import androidx.sqlite.db.SimpleSQLiteQuery
import app.houselights.core.LibraryFilter
import app.houselights.core.SortOption

/**
 * SQL for a wall of sagas: the sagas of at least [min] films, of which one passes [filter], and with [films] the
 * films that are in none of them. A saga ranks by its films: its latest added, its latest year, their mean rating;
 * by title, by its own name.
 */
internal class SagaWall(private val min: Int, private val filter: LibraryFilter, private val films: Boolean) {
    /** [limit] rows from [start] in [sort] order; ties sort films first, then by id, so pages never overlap or skip. */
    fun page(sort: SortOption, start: Int, limit: Int): SimpleSQLiteQuery {
        val order = when (sort) {
            SortOption.TITLE -> "sortTitle"
            SortOption.ADDED -> "added DESC"
            SortOption.YEAR -> "year DESC, sortTitle"
            SortOption.RATING -> "rating DESC, sortTitle"
        }
        return query("SELECT saga, id FROM", "ORDER BY $order, saga, id LIMIT $limit OFFSET $start")
    }

    /** The titles [page] reads, and how many of them are sagas. */
    fun count(): SimpleSQLiteQuery = query("SELECT COUNT(*) AS total, COALESCE(SUM(saga), 0) AS sagas FROM", "")

    private fun query(select: String, tail: String): SimpleSQLiteQuery {
        val passing = LocalFilters.where(ItemKind.MOVIE, filter)
        val alone = "SELECT 0 AS saga, id, sortTitle, addedAt AS added, year, rating FROM items " +
            "WHERE ${passing.sql} AND (collectionId IS NULL OR collectionId NOT IN (SELECT id FROM sagas))"
        val shown = "SELECT 1 AS saga, c.id AS id, c.sortTitle AS sortTitle, s.added AS added, s.year AS year, s.rating AS rating " +
            "FROM sagas s JOIN collections c ON c.id = s.id WHERE s.id IN (SELECT collectionId FROM items WHERE ${passing.sql})"
        val rows = if (films) "$alone UNION ALL $shown" else shown
        val args = listOf<Any>(min) + (if (films) passing.args else emptyList()) + passing.args
        return SimpleSQLiteQuery("WITH sagas AS ($SAGAS) $select ($rows) $tail", args.toTypedArray())
    }

    private companion object {
        /** Each saga of at least as many films as its argument, with what it ranks by; a film's copies are not counted. */
        const val SAGAS = "SELECT collectionId AS id, MAX(addedAt) AS added, MAX(year) AS year, AVG(rating) AS rating FROM items " +
            "WHERE kind = 'MOVIE' AND versionOf IS NULL AND collectionId IS NOT NULL GROUP BY collectionId HAVING COUNT(*) >= ?"
    }
}
