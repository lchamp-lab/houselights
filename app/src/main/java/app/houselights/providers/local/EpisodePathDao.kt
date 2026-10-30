package app.houselights.providers.local

import androidx.room.Query

/** An episode at a place of its show, and its file ([EpisodePathDao.episodesAt]). */
data class PlacedFile(val id: Long, val filePath: String?)

/** Where episodes lie, read by their files: a show's folder, who holds a place ([LibraryDao] extends it). */
interface EpisodePathDao {
    /** Episodes under [dir] (never ""): paths between "dir/" and "dir0", a range scan on the index. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = 'EPISODE' AND sourceId = :sourceId " +
            "AND filePath > :dir || '/' AND filePath < :dir || '0'",
    )
    suspend fun episodesUnder(sourceId: Long, dir: String): List<ItemEntity>

    /** Show [seriesId]'s episodes at season [season], episode [episode]: who holds a place ([EpisodeNumbering]). */
    @Query(
        "SELECT id, filePath FROM items WHERE kind = 'EPISODE' AND seriesId = :seriesId AND seasonNumber = :season " +
            "AND episodeNumber = :episode",
    )
    suspend fun episodesAt(seriesId: Long, season: Int, episode: Int): List<PlacedFile>

    /** The file of [seriesId]'s first episode that has one, in [LibraryDao.episodesOfSeries]' order, the earliest row on a tie. */
    @Query(
        "SELECT filePath FROM items WHERE seriesId = :seriesId AND kind = 'EPISODE' AND filePath IS NOT NULL " +
            "ORDER BY seasonNumber, episodeNumber, id LIMIT 1",
    )
    suspend fun firstEpisodePath(seriesId: Long): String?

    /** The files of [seriesId]'s episodes, null for one without, their rows unread. */
    @Query("SELECT filePath FROM items WHERE seriesId = :seriesId AND kind = 'EPISODE'")
    suspend fun episodePaths(seriesId: Long): List<String?>
}
