package app.houselights.providers.local

import app.houselights.core.Job
import app.houselights.core.NfoInfo

/** What TMDB credits a title with: its cast and crew, and a show's creators. */
internal class TmdbPeople(val credits: TmdbCredits, val creators: List<TmdbCreator> = emptyList())

/**
 * How a title's credits are chosen ([CreditEntity]), without the network nor the database. For the cast, a film's
 * direction and writing, the NFO's people when it lists any (what the user's media manager decided, as for a
 * title's details), else TMDB's; for the other jobs, TMDB's alone. A show keeps its cast, its creators and its
 * composers: its direction, writing, image and editing change from episode to episode.
 *
 * A person is keyed by their TMDB id: TMDB's own, the NFO's (tinyMediaManager's <tmdbid>), or that of the person
 * TMDB credits on the same title under the same name; else by their name ([PersonKeys]), which the library gives
 * TMDB's key once it meets the person under it ([LibraryDao.replaceCredits]).
 */
internal object CreditRules {
    /** People billed in the cast, at most. */
    private const val CAST_SIZE = 30

    /** People of a job of the crew, at most. */
    private const val CREW_SIZE = 10

    /** TMDB's crew jobs, by the job of the end credits they are. */
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

    /** Jobs an NFO can list. */
    private val NFO_JOBS = setOf(Job.CAST, Job.DIRECTOR, Job.WRITER)

    /** Someone credited, before they are keyed. */
    private data class Credited(val name: String, val character: String? = null, val profilePath: String? = null, val tmdbId: Int? = null)

    /**
     * The credits of the title [itemId], a [show] or a film, from its [nfo] (one that describes it) and what
     * [tmdb] credits it with. Null when neither says anything: what the title had stays.
     */
    fun credits(itemId: Long, show: Boolean, nfo: NfoInfo?, tmdb: TmdbPeople?): List<CreditEntity>? {
        val fromNfo = nfo?.let { nfoPeople(it, show) }.orEmpty().filterValues { it.isNotEmpty() }
        if (tmdb == null && fromNfo.isEmpty()) return null
        val fromTmdb = tmdb?.let { tmdbPeople(it, show) }.orEmpty()
        // TMDB's ids and portraits, by name, for the NFO's people.
        val known = fromTmdb.values.flatten().filter { it.tmdbId != null }.groupBy { PersonKeys.fold(it.name) }
            .mapValues { (_, people) -> people.first() }
        return Job.entries.flatMap { job ->
            val people = (if (job in NFO_JOBS) fromNfo[job] else null) ?: fromTmdb[job].orEmpty()
            val size = if (job == Job.CAST) CAST_SIZE else CREW_SIZE
            people.map { it.keyed(known) }.distinctBy { it.first }.take(size).mapIndexed { i, (key, person) ->
                CreditEntity(
                    itemId = itemId, job = job, ord = i, personKey = key, name = person.name, foldedName = PersonKeys.fold(person.name),
                    character = person.character?.ifBlank { null }, profilePath = person.profilePath,
                )
            }
        }
    }

    /** The person's key, and the person with TMDB's portrait when theirs is missing. */
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
