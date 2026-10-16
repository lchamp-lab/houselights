package app.houselights.providers.local

/** A file's place in a film split in several files: its name without the suffix, and the part's number (1 for "CD1" or "a"). */
data class StackPart(val name: String, val part: Int) {
    companion object {
        /** Kodi's stacking suffixes but "part" and "pt", after a space, a dot, an underscore or a dash: "CD1", "dvd.2", "disc-b". */
        private val SUFFIX = Regex("""(?i)^(.+?)[ ._-]+(cd|dvd|dis[ck])[ ._-]*([1-9]|[a-d])$""")

        /**
         * Where a file stands in a film split in several files, as Kodi stacks them: the suffix ends its name,
         * just before the extension ("Film (2020) CD1.avi", "Film (2020) - DVD 2.mkv", "film.disc.b.iso"),
         * its number from 1 to 9 or its letter from a to d (b is the second). Null for any other name: "Dune
         * Part Two (2024)", "Kill Bill Vol. 1", "Film CD1 [1080p]". "Part 1" and "pt2", which Kodi stacks too,
         * end two films' names as often as one film's files' ("Harry Potter and the Deathly Hallows Part 1",
         * "Dune Part 2"): a file so named is a film of its own, as the user chose. A name alone says only that
         * the file may be a part; [Stacks] makes a film of parts that sit side by side.
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
 * A film in several files, as Kodi stacks them. [first] stands for it in the library
 * ([ItemEntity.filePath]): its size and what it holds are the film's. [rest] are its other parts, in
 * order ([ItemEntity.parts]). [name] is its file name without the part ("Film (2020)" for
 * "Film (2020) CD1.avi"): its title, NFO and artwork go by it.
 */
data class Stack(val name: String, val first: RemoteFile, val rest: List<RemoteFile>) {
    /** The first part's path under the film's [name]: what its title and year are read from. */
    val namedPath: String get() = renamed(first.relativePath, name)

    /** The names its NFO and artwork may bear, in turn: the film's, then each part's own. */
    val names: List<String> get() = listOf(name) + (listOf(first) + rest).map { baseName(it.relativePath) }
}

/**
 * A folder's films in several files (CD1, CD2, disc 1…): videos of one name and one extension side by
 * side in one folder, numbered from the first on, make one title under their first part. A part alone
 * ("Film CD2.avi"), parts that do not start at the first, and the files of a folder of shows are titles
 * of their own. Built once a listing, before anything else reads it.
 */
class Stacks private constructor(videos: List<RemoteFile>, private val stacks: Map<String, Stack>) {
    /** Parts after a first, with their stack's first part. */
    private val laterParts: Map<String, String> =
        stacks.values.flatMap { s -> s.rest.map { it.relativePath to s.first.relativePath } }.toMap()

    /** What the scan takes for titles: every video but the parts after a stack's first, in the listing's order. */
    val titles: List<RemoteFile> = videos.filter { it.relativePath !in laterParts }

    /** The stack whose first part lies at [path]; null for a film of one file. */
    fun stackAt(path: String?): Stack? = stacks[path]

    /** The parts after the first of the title at [path], as [ItemEntity.parts] keeps them: none for a film of one file. */
    fun partsOf(path: String?): List<String> = stacks[path]?.rest?.map { it.relativePath }.orEmpty()

    /** The first part of the stack the file at [path] is a later part of; null when it is not one. */
    fun firstOf(path: String?): String? = laterParts[path]

    /** A video whose name reads as a part. */
    private class Candidate(val file: RemoteFile, val part: StackPart) {
        /** Parts of one film share their folder, their name (whatever its case) and their extension. */
        val film = Triple(Sidecars.folderOf(file.relativePath), part.name.lowercase(), FilenameParser.extension(file.relativePath))
    }

    companion object {
        /**
         * The films in several files among a folder's [videos]; a folder of shows has none. Those whose first
         * part is in [apart] are not: their files' rows were matched with two films ([MissingFiles.split]).
         */
        fun of(kind: SourceKind, videos: List<RemoteFile>, apart: Set<String> = emptySet()): Stacks {
            if (kind != SourceKind.MOVIES) return Stacks(videos, emptyMap())
            val candidates = videos.mapNotNull { v ->
                StackPart.of(v.relativePath.substringAfterLast('/'))?.let { Candidate(v, it) }
            }
            val stacks = candidates.groupBy { it.film }.values.mapNotNull { group ->
                // 1, 2, 3… each once, from the first: a gap, or a part twice, ends the film there.
                val run = group.sortedBy { it.part.part }.withIndex().takeWhile { (i, c) -> c.part.part == i + 1 }.map { it.value }
                if (run.size < 2 || run.first().file.relativePath in apart) return@mapNotNull null
                Stack(run.first().part.name, run.first().file, run.drop(1).map { it.file })
            }
            return Stacks(videos, stacks.associateBy { it.first.relativePath })
        }

        /**
         * The path the film at [path] is named by: for a film in several files ([stacked]), its first part's
         * without the part ("Film (2020)/Film (2020).avi" for "Film (2020)/Film (2020) CD1.avi").
         */
        fun filmPath(path: String, stacked: Boolean): String {
            if (!stacked) return path
            val name = StackPart.of(path.substringAfterLast('/'))?.name ?: return path
            return renamed(path, name)
        }
    }
}

/** The file name at [path], without its extension. */
private fun baseName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

/** [path] with its file named [name], its folder and extension kept. */
private fun renamed(path: String, name: String): String {
    val folder = Sidecars.folderOf(path)
    val file = path.substringAfterLast('/')
    val extension = if ('.' in file) "." + file.substringAfterLast('.') else ""
    return (if (folder.isEmpty()) "" else "$folder/") + name + extension
}

/** A film's title and year as its files name it: a film in several files by its first part without the part. */
internal fun ItemEntity.filmName(): ParsedMovie? = filePath?.let { LibraryScanner.parseMovie(Stacks.filmPath(it, parts.isNotEmpty())) }
