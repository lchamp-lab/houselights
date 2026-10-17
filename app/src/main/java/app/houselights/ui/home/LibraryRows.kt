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
 * The rows the library writes under home's own: the films of one of its directors, its best rated
 * films. Drawn once a day, the day turning at [DAY_STARTS] in the morning: home stays the same all
 * evening, and Back finds its card where it was. The director changes from one day to the next,
 * among those of the most films; a row too short to be one is left out.
 */
class LibraryRows(
    private val provider: () -> MediaProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private var drawn: Pair<Long, List<HomeSection>>? = null

    /** Today's rows: drawn at the first call of the day, the same after, until [forget]. */
    suspend fun today(): List<HomeSection> {
        val day = dayOf(now(), zone())
        drawn?.takeIf { it.first == day }?.let { return it.second }
        val rows = draw(day)
        drawn = day to rows
        return rows
    }

    /** The library changed: the next call draws again, from what it holds now (the same day, the same director's place). */
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
        /** The hour the day of home's rows turns: past midnight, an evening's film goes on with the same rows. */
        const val DAY_STARTS = 6

        /** Directors drawn from, those of the most films first. */
        private const val DIRECTORS = 20

        /** Best rated films a row holds, and the fewest it needs to be one. */
        private const val BEST = 24
        private const val MIN_BEST = 4

        /** The day of [millis] for home's rows, in [zone]: it turns at [DAY_STARTS]. */
        fun dayOf(millis: Long, zone: ZoneId): Long =
            Instant.ofEpochMilli(millis).atZone(zone).minusHours(DAY_STARTS.toLong()).toLocalDate().toEpochDay()
    }
}
