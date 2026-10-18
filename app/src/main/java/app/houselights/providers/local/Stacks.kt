package app.houselights.providers.local

/** A file's position in a multi-file film: its name without the suffix, and the part number (1 for "CD1" or "a"). */
data class StackPart(val name: String, val part: Int) {
    companion object {
        /** Kodi stacking suffixes except "part" and "pt": "CD1", "dvd.2", "disc-b". */
        private val SUFFIX = Regex("""(?i)^(.+?)[ ._-]+(cd|dvd|dis[ck])[ ._-]*([1-9]|[a-d])$""")

        /**
         * Parses a Kodi stacking suffix right before the extension ("Film (2020) CD1.avi", "Film (2020) - DVD 2.mkv",
         * "film.disc.b.iso"), numbered 1 to 9 or a to d. Null otherwise: "Dune Part Two (2024)", "Kill Bill Vol. 1",
         * "Film CD1 [1080p]". "Part 1" and "pt2" are not parts, as they end separate films' names as often ("Harry
         * Potter and the Deathly Hallows Part 1"). Only [Stacks] decides that sibling parts form one film.
         */
        fun of(fileName: String): StackPart? {
            val match = SUFFIX.find(FilenameParser.stripExtension(fileName)) ?: return null
            val (name, _, number) = match.destructured
            val mark = number.lowercase().single()
            return StackPart(name, if (mark.isDigit()) mark.digitToInt() else mark - 'a' + 1)
        }
    }
}

/**
 * A multi-file film. [first] represents it ([ItemEntity.filePath]) with its size and media info; [rest] are the other
 * parts in order ([ItemEntity.parts]); [name] drops the part ("Film (2020)" for "Film (2020) CD1.avi") and names
 * the title, NFO and artwork.
 */
data class Stack(val name: String, val first: RemoteFile, val rest: List<RemoteFile>) {
    /** The first part's path renamed to [name], parsed for title and year. */
    val namedPath: String get() = renamed(first.relativePath, name)

    /** Candidate sidecar base names: the film's, then each part's. */
    val names: List<String> get() = listOf(name) + (listOf(first) + rest).map { baseName(it.relativePath) }
}

/**
 * Multi-file films (CD1, CD2, disc 1…) of a listing: same name and extension in one folder, numbered from 1, form
 * one title under the first part. A lone part ("Film CD2.avi"), a run not starting at 1, and shows sources are
 * separate titles. Built once per listing, before anything reads it.
 */
class Stacks private constructor(videos: List<RemoteFile>, private val stacks: Map<String, Stack>) {
    /** Later parts mapped to their stack's first part. */
    private val laterParts: Map<String, String> =
        stacks.values.flatMap { s -> s.rest.map { it.relativePath to s.first.relativePath } }.toMap()

    /** Every video except later parts, in listing order. */
    val titles: List<RemoteFile> = videos.filter { it.relativePath !in laterParts }

    /** The stack whose first part is [path]; null for a single-file film. */
    fun stackAt(path: String?): Stack? = stacks[path]

    /** Later parts of the title at [path], as in [ItemEntity.parts]; empty for a single-file film. */
    fun partsOf(path: String?): List<String> = stacks[path]?.rest?.map { it.relativePath }.orEmpty()

    /** First part of the stack [path] is a later part of; null if none. */
    fun firstOf(path: String?): String? = laterParts[path]

    private class Candidate(val file: RemoteFile, val part: StackPart) {
        /** Grouping key: folder, case-insensitive name and extension. */
        val film = Triple(Sidecars.folderOf(file.relativePath), part.name.lowercase(), FilenameParser.extension(file.relativePath))
    }

    companion object {
        /** None for shows, nor for first parts in [apart], matched with two films ([MissingFiles.split]). */
        fun of(kind: SourceKind, videos: List<RemoteFile>, apart: Set<String> = emptySet()): Stacks {
            if (kind != SourceKind.MOVIES) return Stacks(videos, emptyMap())
            val candidates = videos.mapNotNull { v ->
                StackPart.of(v.relativePath.substringAfterLast('/'))?.let { Candidate(v, it) }
            }
            val stacks = candidates.groupBy { it.film }.values.mapNotNull { group ->
                // Consecutive parts from 1; a gap or duplicate ends the run.
                val run = group.sortedBy { it.part.part }.withIndex().takeWhile { (i, c) -> c.part.part == i + 1 }.map { it.value }
                if (run.size < 2 || run.first().file.relativePath in apart) return@mapNotNull null
                Stack(run.first().part.name, run.first().file, run.drop(1).map { it.file })
            }
            return Stacks(videos, stacks.associateBy { it.first.relativePath })
        }

        /** The path a film is named by: "Film (2020) CD1.avi" gives "Film (2020).avi" when [stacked]. */
        fun filmPath(path: String, stacked: Boolean): String {
            if (!stacked) return path
            val name = StackPart.of(path.substringAfterLast('/'))?.name ?: return path
            return renamed(path, name)
        }
    }
}

private fun baseName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

/** [path] with the file renamed to [name], keeping folder and extension. */
private fun renamed(path: String, name: String): String {
    val folder = Sidecars.folderOf(path)
    val file = path.substringAfterLast('/')
    val extension = if ('.' in file) "." + file.substringAfterLast('.') else ""
    return (if (folder.isEmpty()) "" else "$folder/") + name + extension
}

/** A film's title and year from its file name, without any part suffix. */
internal fun ItemEntity.filmName(): ParsedMovie? = filePath?.let { LibraryScanner.parseMovie(Stacks.filmPath(it, parts.isNotEmpty())) }
