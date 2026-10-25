package app.houselights.providers.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.houselights.core.SubtitleChoice
import app.houselights.core.TrackChoice

/** A title's choice of tracks ([TrackChoice]), for any source: [title] is its key ([PlaybackPlan.Launch.titleKey]). */
@Entity(tableName = "track_choices")
data class TrackChoiceEntity(
    @PrimaryKey val title: String,
    /** Audio language code; null follows Settings. */
    val audio: String?,
    /** [SubtitleChoice.stored]; null follows Settings. */
    val subtitles: String?,
    val at: Long,
) {
    fun choice() = TrackChoice(audio, subtitles?.let(SubtitleChoice::parse))
}

/**
 * Where a local title's choice of tracks is kept ([PlaybackPlan.Launch.titleKey]): its show; a matched film's TMDB id,
 * which groups its copies, so the choice outlives any of them; else its row. [LibraryDao.deleteOrphanTrackChoices]
 * keys them alike.
 */
internal fun tracksKey(row: ItemEntity) = tracksKey(row.id, row.kind, row.seriesId, row.tmdbId)

/** [tracksKey] of the row [id] of [kind]. */
internal fun tracksKey(id: Long, kind: ItemKind, seriesId: Long?, tmdbId: Int?) = when {
    seriesId != null -> "local:$seriesId"
    kind == ItemKind.MOVIE && tmdbId != null -> "local:film:$tmdbId"
    else -> "local:$id"
}

@Dao
interface TrackChoiceDao {
    @Query("SELECT * FROM track_choices WHERE title = :title")
    suspend fun choice(title: String): TrackChoiceEntity?

    @Upsert
    suspend fun save(choice: TrackChoiceEntity)

    @Query("DELETE FROM track_choices WHERE title = :title")
    suspend fun forget(title: String)
}
