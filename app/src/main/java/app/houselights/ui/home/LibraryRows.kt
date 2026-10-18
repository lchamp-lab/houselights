package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.Job
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.PERSON_MIN_TITLES
import app.houselights.core.SectionType
import java.time.Instant
import java.time.ZoneId

/**
 * The library's rows below Home's own: one director's films (rotating daily) and the best rated films. Computed
 * once a day from [DAY_STARTS], so Home stays the same all evening and Back returns to the same card.
 */
class LibraryRows(
    private val provider: () -> MediaProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private var drawn: Pair<Long, List<HomeSection>>? = null

    /** Today's rows, computed on the day's first call and reused until [forget]. */
    suspend fun today(): List<HomeSection> {
        val day = dayOf(now(), zone())
        drawn?.takeIf { it.first == day }?.let { return it.second }
        val rows = draw(day)
        drawn = day to rows
        return rows
    }

    /** Drops the cached rows after a library change; the next call recomputes them for the same day. */
    fun forget() {
        drawn = null
    }

    private suspend fun draw(day: Long): List<HomeSection> {
        val catalog = provider().catalog ?: return emptyList()
        val directors = catalog.people(Job.DIRECTOR, MediaKind.MOVIE, PERSON_MIN_TITLES, limit = DIRECTORS)
        val director = directors.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(day, it.size.toLong()).toInt()] }
        val films = director?.id?.let { catalog.person(it) }?.titles.orEmpty()
            .filter { Job.DIRECTOR in it.jobs && it.item.kind == MediaKind.MOVIE }
            .map { it.item }
        val best = catalog.bestRated(MediaKind.MOVIE, limit = BEST)
        return listOfNotNull(
            films.takeIf { it.size >= PERSON_MIN_TITLES }?.let { own ->
                HomeSection(SectionType.DIRECTOR, own, landscape = false, person = director)
            },
            best.takeIf { it.size >= MIN_BEST }?.let { HomeSection(SectionType.BEST_RATED, it, landscape = false) },
        )
    }

    companion object {
        /** Hour at which Home's day changes, so a film watched past midnight keeps the same rows. */
        const val DAY_STARTS = 6

        /** Directors the row rotates through, those with the most films first. */
        private const val DIRECTORS = 20

        /** Size of the best rated row, and the minimum it needs to show. */
        private const val BEST = 24
        private const val MIN_BEST = 4

        /** The day of [millis] in [zone] for Home's rows, starting at [DAY_STARTS]. */
        fun dayOf(millis: Long, zone: ZoneId): Long =
            Instant.ofEpochMilli(millis).atZone(zone).minusHours(DAY_STARTS.toLong()).toLocalDate().toEpochDay()
    }
}
