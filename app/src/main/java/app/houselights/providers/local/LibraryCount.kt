package app.houselights.providers.local

import app.houselights.core.BadgeTokens
import app.houselights.core.Codecs
import app.houselights.core.Definition
import app.houselights.core.HdrFormat
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryNumbers
import app.houselights.core.MediaInfo
import app.houselights.core.Tally

/**
 * The library in numbers ([LibraryNumbers]), from its files' columns alone ([FileFacts]): nothing decoded, one
 * pass over them. A film counts once whatever its copies, which all count in the room taken; a title has the
 * formats one of its files has, a show those of one of its episodes.
 */
internal object LibraryCount {
    fun of(files: List<FileFacts>, shows: Int, directors: List<PersonSummary>): LibraryNumbers {
        val (films, episodes) = files.partition { it.kind == ItemKind.MOVIE }
        // Each title's files: a film's copies under its title's row, a show's episodes under the show.
        val filmFiles = films.groupBy { it.versionOf ?: it.id }
        val showFiles = episodes.filter { it.seriesId != null }.groupBy { it.seriesId!! }
        val titleRuntime = films.filter { it.versionOf == null } + episodes
        return LibraryNumbers(
            films = filmFiles.size,
            shows = shows,
            episodes = episodes.size,
            sizeBytes = files.sumOf { it.fileSize ?: 0L },
            runtimeMs = titleRuntime.sumOf { runtimeMs(it.parts, it.durationMs, it.fileDurationMs, it.runtimeMin) ?: 0L },
            picture = tallies(filmFiles, showFiles, { f -> f.badges.filter { it in LocalFilters.PICTURE_TOKENS } }) { LibraryFilter(picture = it) }
                .sortedBy { LocalFilters.PICTURE_TOKENS.indexOf(it.label) },
            sound = tallies(filmFiles, showFiles, { f -> f.badges.filter(LocalFilters::isSound) }) { LibraryFilter(sound = it) }
                .sortedWith(compareByDescending<Tally> { it.films + it.shows }.thenBy { it.label }),
            files = tallies(filmFiles, showFiles, { f -> listOfNotNull(f.filePath?.let(LocalFilters::fileTypeOf)) }) { LibraryFilter(fileType = it) }
                .sortedWith(compareByDescending<Tally> { it.films + it.shows }.thenBy { it.label }),
            directors = directors.map { Tally(it.name, it.titles, 0, LibraryFilter(person = it.personKey), it.personKey) },
        )
    }

    /** How many films and shows have each value [values] gives one of their files, and the filter that opens them. */
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

/**
 * Which copy of a film plays best on a home cinema, the one "Lire" plays when none was chosen: the higher
 * definition, then the better range (Dolby Vision, HDR10+, HDR10, HLG), then the better sound, then the larger
 * file. From what the file holds, else from its badges.
 */
internal object VersionRank {
    /** A copy as it ranks: its badges, what its file holds when read, its size. */
    class Copy(val badges: List<String>, val media: MediaInfo?, val sizeBytes: Long?)

    /** The better copy is the greater. */
    val comparator: Comparator<Copy> = Comparator { a, b ->
        of(a).zip(of(b)).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
    }

    private fun of(copy: Copy): List<Long> {
        val badges = copy.badges
        val media = copy.media
        val definition = media?.video?.definition ?: Definition.entries.firstOrNull { it.token in badges }
        val ranges = media?.video?.hdr ?: HdrFormat.entries.filter { it.token in badges }
        val range = ranges.minOfOrNull { it.ordinal }?.let { HdrFormat.entries.size - it } ?: if (BadgeTokens.HDR in badges) 1 else 0
        val track = media?.audio?.let(app.houselights.core.Formats::bestTrack)
        val sound = when {
            track == null -> if (BadgeTokens.ATMOS in badges || BadgeTokens.DTS_X in badges) OBJECTS else 0
            track.atmos || track.dtsX -> OBJECTS
            Codecs.isLossless(track.codec) -> LOSSLESS
            track.codec.startsWith(Codecs.DTS) -> DTS
            else -> OTHER
        }
        return listOf(
            definition?.let { Definition.entries.size - it.ordinal } ?: 0,
            range,
            sound,
            track?.channels ?: 0,
        ).map { it.toLong() } + (media?.sizeBytes ?: copy.sizeBytes ?: 0L)
    }

    private const val OBJECTS = 4
    private const val LOSSLESS = 3
    private const val DTS = 2
    private const val OTHER = 1
}
