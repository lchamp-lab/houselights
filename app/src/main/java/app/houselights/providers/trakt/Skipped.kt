package app.houselights.providers.trakt

import app.houselights.core.Typeset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Which of Trakt's lists holds an entry a sync could not read. */
enum class SkippedKind { MOVIE_HISTORY, EPISODE_HISTORY, PAUSED }

/** An entry of Trakt's a sync left out unread, by the [title] and [year] it still gives, null when it gives none. */
data class SkippedEntry(val kind: SkippedKind, val title: String? = null, val year: Int? = null)

/** The histories [this] leaves read in part: what of their kind seems to lack on Trakt is not sent ([WholeHistory]). */
val List<SkippedEntry>.readInPart: Set<SkippedKind>
    get() = mapTo(LinkedHashSet()) { it.kind } - SkippedKind.PAUSED

/** The entries each list left out ([app.houselights.core.Decoded.left]), in the order read. */
internal fun skippedOf(movies: List<JsonElement>, shows: List<JsonElement>, paused: List<JsonElement>): List<SkippedEntry> =
    movies.map { it.skipped(SkippedKind.MOVIE_HISTORY) } +
        shows.map { it.skipped(SkippedKind.EPISODE_HISTORY) } +
        paused.map { it.skipped(SkippedKind.PAUSED) }

/**
 * What an entry still names, whatever shape the rest has: the title under "movie" or "show" when it is text, and its
 * year when it is a number.
 */
internal fun JsonElement.skipped(kind: SkippedKind): SkippedEntry =
    NAMED.firstNotNullOfOrNull { key -> ((this as? JsonObject)?.get(key) as? JsonObject)?.named(kind) } ?: SkippedEntry(kind)

/** The title this object gives as text, with its year; null without one. */
private fun JsonObject.named(kind: SkippedKind): SkippedEntry? {
    val title = (get("title") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val year = (get("year") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    // Trakt names titles in English.
    return SkippedEntry(kind, Typeset.text(title, french = false), year)
}

/** Where an entry names its title: a film, else the show of an episode. */
private val NAMED = listOf("movie", "show")
