package app.houselights.providers.local

import kotlin.math.max

/**
 * What a scan does with the rows of files a folder no longer lists. A NAS can list a folder short
 * without failing (a disk of a pool offline, a share served before its disk is mounted), and a row
 * holds what the user did with the title: so when many files go at once, their rows wait a day,
 * in case the files come back.
 */
internal object MissingFiles {
    /** Up to this many files gone at once are removed at once, however small the folder. */
    private const val FEW = 20

    /** More than a quarter of a folder gone at once is held. */
    private const val SHARE_HELD = 4

    /** How long rows of missing files are held before a scan removes them. */
    const val HOLD_MS = 24 * 60 * 60_000L

    /** What becomes of a folder's rows after its listing, by id. */
    data class Outcome(
        /** Files listed again after they went missing: their rows are no longer held. */
        val back: List<Long>,
        /** Files missed for the first time, among too many at once: their rows are held from now. */
        val held: List<Long>,
        /** Few files went missing, or they have been missing for [HOLD_MS]. */
        val removed: List<Long>,
    )

    fun settle(known: Collection<ItemEntity>, present: Set<String>, now: Long): Outcome {
        val (listed, gone) = known.partition { it.filePath in present }
        val (waiting, fresh) = gone.partition { it.missingSince != null }
        val hold = gone.size > max(FEW, known.size / SHARE_HELD)
        val expired = waiting.filter { now - (it.missingSince ?: now) >= HOLD_MS }
        return Outcome(
            back = listed.filter { it.missingSince != null }.map { it.id },
            held = if (hold) fresh.map { it.id } else emptyList(),
            removed = (if (hold) expired else fresh + expired).map { it.id },
        )
    }
}
