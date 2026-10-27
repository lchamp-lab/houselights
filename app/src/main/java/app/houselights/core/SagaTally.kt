package app.houselights.core

/**
 * What a saga's page counts of its [parts] (release order), pure: the years of the films released, the films held out
 * of those released, their length, the films announced counted apart, and the progress through the films held. [next]
 * is the film Lire plays ([nextUpIn]).
 */
class SagaTally(val parts: List<SagaPart>, next: MediaItem?) {
    private val released = parts.filter { it.released }

    /** The films held, in release order. */
    val owned: List<MediaItem> = parts.mapNotNull { it.item }

    /** The first and last years of the films released, the announced ones aside; the same year twice for one. */
    val years: IntRange? = released.mapNotNull { it.year }.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }

    /** The films released, which the films held are counted out of: « 2 films sur 3 »; all of them for a box set. */
    val releasedCount: Int = released.size

    /** The films held, end to end; null when the length of one is unknown. */
    val lengthMs: Long? = owned.map { it.runtimeMs }.takeIf { all -> all.isNotEmpty() && all.none { it == null } }?.sumOf { it ?: 0L }

    /** The films announced, not out yet: « 1 à venir ». */
    val upcoming: Int = parts.count { !it.released }

    /** The films held watched: « 1 film vu sur 2 ». */
    val watched: Int = owned.count { it.user.played }

    /** Whether a film held is watched or started: the page says the saga's progress. */
    val begun: Boolean = owned.any { it.user.played || it.user.positionMs > 0 }

    /** What is left to watch of the films held: of each not watched, its length less where it was left. */
    val leftMs: Long = owned.filter { !it.user.played }.sumOf { ((it.runtimeMs ?: 0L) - it.user.positionMs).coerceAtLeast(0L) }

    /** The part Lire plays, else the first. */
    val nextPart: SagaPart? = parts.firstOrNull { next != null && it.item?.id == next.id } ?: parts.firstOrNull()

    /**
     * The film Lire plays is « À suivre »: not started, once a film of the saga is watched; never the film in progress,
     * which says what is left, nor one of a saga never begun, whose Lire says where it starts.
     */
    val nextUp: MediaItem? = next?.takeIf { watched > 0 && !it.user.played && it.user.positionMs == 0L }
}
