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
 * once a day from [DAY_STARTS] and per source, so Home stays the same all evening and Back returns to the same card,
 * and again after a change of the library but a running scan's progress ([settled]), so a scan never swaps them.
 */
class LibraryRows(
    private val provider: () -> MediaProvider,
    /** The library's settled version ([app.houselights.core.LibraryEvents.settledVersion]). */
    private val settled: () -> Long = { 0L },
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private class Drawn(val source: MediaProvider, val day: Long, val settled: Long, val rows: List<HomeSection>)

    private var drawn: Drawn? = null

    /** Today's rows, computed on the day's first call and reused while the source and the settled library stay. */
    suspend fun today(): List<HomeSection> {
        val day = dayOf(now(), zone())
        // The provider is made anew with any change of source.
        val source = provider()
        val version = settled()
        drawn?.takeIf { it.day == day && it.source === source && it.settled == version }?.let { return it.rows }
        val rows = draw(source, day)
        drawn = Drawn(source, day, version, rows)
        return rows
    }

    private suspend fun draw(source: MediaProvider, day: Long): List<HomeSection> {
        val catalog = source.catalog ?: return emptyList()
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
