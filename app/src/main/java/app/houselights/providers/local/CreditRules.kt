package app.houselights.providers.local

import app.houselights.core.Job
import app.houselights.core.NfoInfo

/** TMDB credits of a title: cast and crew, plus a show's creators. */
internal class TmdbPeople(val credits: TmdbCredits, val creators: List<TmdbCreator> = emptyList())

/**
 * Pure credit rules ([CreditEntity]): cast, direction and writing from the NFO when it lists any, else TMDB; other
 * jobs from TMDB. Shows keep cast, creators and composers only, as other jobs vary per episode. People are keyed
 * by TMDB id (TMDB's, tinyMediaManager's <tmdbid>, or a same-name TMDB credit), else by name ([PersonKeys]).
 */
internal object CreditRules {
    private const val CAST_SIZE = 30

    private const val CREW_SIZE = 10

    private val FILM_JOBS = mapOf(
        "Director" to Job.DIRECTOR,
        "Screenplay" to Job.WRITER,
        "Writer" to Job.WRITER,
        "Story" to Job.WRITER,
        "Original Music Composer" to Job.COMPOSER,
        "Music" to Job.COMPOSER,
        "Director of Photography" to Job.CINEMATOGRAPHY,
        "Editor" to Job.EDITING,
    )
    private val SHOW_JOBS = mapOf("Original Music Composer" to Job.COMPOSER, "Music" to Job.COMPOSER)

    private val NFO_JOBS = setOf(Job.CAST, Job.DIRECTOR, Job.WRITER)

    private data class Credited(val name: String, val character: String? = null, val profilePath: String? = null, val tmdbId: Int? = null)

    /** A film's [credits] with only what [credits] reads of them: its whole cast, its crew in the jobs it keeps. */
    fun ofFilm(credits: TmdbCredits): TmdbCredits = credits.copy(crew = credits.crew.filter { it.job in FILM_JOBS })

    /** Credits from [nfo] (which must describe the title) and [tmdb]; null keeps the stored ones. */
    fun credits(itemId: Long, show: Boolean, nfo: NfoInfo?, tmdb: TmdbPeople?): List<CreditEntity>? {
        val fromNfo = nfo?.let { nfoPeople(it, show) }.orEmpty().filterValues { it.isNotEmpty() }
        if (tmdb == null && fromNfo.isEmpty()) return null
        val fromTmdb = tmdb?.let { tmdbPeople(it, show) }.orEmpty()
        // TMDB ids and portraits by folded name, to complete the NFO's people.
        val known = fromTmdb.values.flatten().filter { it.tmdbId != null }.groupBy { PersonKeys.fold(it.name) }
            .mapValues { (_, people) -> people.first() }
        return Job.entries.flatMap { job ->
            val people = (if (job in NFO_JOBS) fromNfo[job] else null) ?: fromTmdb[job].orEmpty()
            val size = if (job == Job.CAST) CAST_SIZE else CREW_SIZE
            people.map { it.keyed(known) }.distinctBy { it.first }.take(size).mapIndexed { i, (key, person) ->
                CreditEntity(
                    itemId = itemId,
                    job = job,
                    ord = i,
                    personKey = key,
                    name = person.name,
                    foldedName = PersonKeys.fold(person.name),
                    character = person.character?.ifBlank { null },
                    profilePath = person.profilePath,
                )
            }
        }
    }

    /** The person's key, and the person with TMDB's portrait when missing. */
    private fun Credited.keyed(known: Map<String, Credited>): Pair<String, Credited> {
        val folded = PersonKeys.fold(name)
        val tmdb = known[folded]
        val id = tmdbId ?: tmdb?.tmdbId
        val key = id?.let(PersonKeys::tmdb) ?: PersonKeys.name(folded)
        return key to copy(profilePath = profilePath ?: tmdb?.profilePath)
    }

    private fun nfoPeople(nfo: NfoInfo, show: Boolean): Map<Job, List<Credited>> = buildMap {
        put(Job.CAST, nfo.actors.sortedBy { it.order ?: Int.MAX_VALUE }.map { Credited(it.name, it.role, it.thumb, it.tmdbId) })
        if (!show) {
            put(Job.DIRECTOR, nfo.directors.map { Credited(it) })
            put(Job.WRITER, nfo.writers.map { Credited(it) })
        }
    }

    private fun tmdbPeople(tmdb: TmdbPeople, show: Boolean): Map<Job, List<Credited>> {
        val jobs = if (show) SHOW_JOBS else FILM_JOBS
        val crew = tmdb.credits.crew
            .mapNotNull { member -> jobs[member.job]?.let { it to Credited(member.name, null, member.profilePath, member.id) } }
            .groupBy({ it.first }, { it.second })
        val cast = tmdb.credits.cast.sortedBy { it.order }.map { Credited(it.name, it.character, it.profilePath, it.id) }
        val creators = tmdb.creators.map { Credited(it.name, null, it.profilePath, it.id) }
        return crew + (Job.CAST to cast) + (if (show) mapOf(Job.CREATOR to creators) else emptyMap())
    }
}
