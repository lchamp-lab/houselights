package app.houselights.providers.local

import app.houselights.core.AudioTrack
import app.houselights.core.BadgeTokens
import app.houselights.core.Codecs
import app.houselights.core.Definition
import app.houselights.core.Formats
import app.houselights.core.HdrFormat
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryNumbers
import app.houselights.core.MediaInfo
import app.houselights.core.Tally

/**
 * Library statistics in one pass over [FileFacts], decoding nothing. A film counts once but all copies count
 * towards size; a title has a format when any of its files (or episodes) has it.
 */
internal object LibraryCount {
    fun of(files: List<FileFacts>, shows: Int, directors: List<PersonSummary>): LibraryNumbers {
        val (films, episodes) = files.partition { it.kind == ItemKind.MOVIE }
        // Files per title: a film's copies under the main row, a show's episodes under the show.
        val filmFiles = films.groupBy { it.versionOf ?: it.id }
        val showFiles = episodes.mapNotNull { e -> e.seriesId?.let { it to e } }.groupBy({ it.first }, { it.second })
        val titleRuntime = films.filter { it.versionOf == null } + episodes
        return LibraryNumbers(
            films = filmFiles.size,
            shows = shows,
            episodes = episodes.size,
            sizeBytes = files.sumOf { it.fileSize ?: 0L },
            runtimeMs = titleRuntime.sumOf { runtimeMs(it.parts, it.durationMs, it.fileDurationMs, it.runtimeMin) ?: 0L },
            picture = tallies(filmFiles, showFiles, { f -> f.badges.filter { it in LocalFilters.PICTURE_TOKENS } }) {
                LibraryFilter(picture = it)
            }.sortedBy { LocalFilters.PICTURE_TOKENS.indexOf(it.label) },
            sound = tallies(filmFiles, showFiles, { f -> f.badges.filter(LocalFilters::isSound) }) { LibraryFilter(sound = it) }
                .sortedWith(compareByDescending<Tally> { it.films + it.shows }.thenBy { it.label }),
            files = tallies(filmFiles, showFiles, { f -> listOfNotNull(f.filePath?.let(LocalFilters::fileTypeOf)) }) {
                LibraryFilter(fileType = it)
            }.sortedWith(compareByDescending<Tally> { it.films + it.shows }.thenBy { it.label }),
            directors = directors.map { Tally(it.name, it.titles, 0, LibraryFilter(person = it.personKey), it.personKey) },
        )
    }

    private fun tallies(
        films: Map<Long, List<FileFacts>>,
        shows: Map<Long, List<FileFacts>>,
        values: (FileFacts) -> List<String>,
        filter: (String) -> LibraryFilter,
    ): List<Tally> {
        fun counts(titles: Map<Long, List<FileFacts>>) =
            titles.values.flatMap { files -> files.flatMapTo(HashSet(), values) }.groupingBy { it }.eachCount()
        val ofFilms = counts(films)
        val ofShows = counts(shows)
        return (ofFilms.keys + ofShows.keys).map { Tally(it, ofFilms[it] ?: 0, ofShows[it] ?: 0, filter(it)) }
    }
}

/** Ranks a film's copies by definition, dynamic range, sound, then size; the best plays by default. */
internal object VersionRank {
    class Copy(val badges: List<String>, val media: MediaInfo?, val sizeBytes: Long?)

    fun best(copies: List<ItemEntity>): Long =
        copies.maxWith(compareBy(comparator) { Copy(it.badges, MediaProbe.decode(it.mediaInfo), it.fileSize) }).id

    /** The better copy compares greater. */
    val comparator: Comparator<Copy> = Comparator { a, b ->
        of(a).zip(of(b)).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
    }

    private fun of(copy: Copy): List<Long> {
        val track = copy.media?.audio?.let(Formats::bestTrack)
        return listOf(definition(copy), range(copy), sound(copy, track), track?.channels ?: 0).map { it.toLong() } +
            (copy.media?.sizeBytes ?: copy.sizeBytes ?: 0L)
    }

    private fun definition(copy: Copy): Int {
        val definition = copy.media?.video?.definition ?: Definition.entries.firstOrNull { it.token in copy.badges }
        return definition?.let { Definition.entries.size - it.ordinal } ?: 0
    }

    private fun range(copy: Copy): Int {
        val ranges = copy.media?.video?.hdr ?: HdrFormat.entries.filter { it.token in copy.badges }
        return ranges.minOfOrNull { it.ordinal }?.let { HdrFormat.entries.size - it } ?: if (BadgeTokens.HDR in copy.badges) 1 else 0
    }

    private fun sound(copy: Copy, track: AudioTrack?): Int = when {
        track == null -> if (BadgeTokens.ATMOS in copy.badges || BadgeTokens.DTS_X in copy.badges) OBJECTS else 0
        track.atmos || track.dtsX -> OBJECTS
        Codecs.isLossless(track.codec) -> LOSSLESS
        track.codec.startsWith(Codecs.DTS) -> DTS
        else -> OTHER
    }

    private const val OBJECTS = 4
    private const val LOSSLESS = 3
    private const val DTS = 2
    private const val OTHER = 1
}
