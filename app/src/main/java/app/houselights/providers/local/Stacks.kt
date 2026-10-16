package app.houselights.providers.local

/**
 * A file's place in a film split in several files: its name without the suffix, and the part's number (1 for "CD1" or "a").
 * [titleWord]: its suffix is "part" or "pt", which ends titles as well ("Dune Part 2"), unlike "CD2" (see [Stacks.of]).
 */
data class StackPart(val name: String, val part: Int, val titleWord: Boolean = false) {
    companion object {
        /** Kodi's stacking suffixes, after a space, a dot, an underscore or a dash: "CD1", "part 2", "pt.3", "disc-b". */
        private val SUFFIX = Regex("""(?i)^(.+?)[ ._-]+(cd|dvd|part|pt|dis[ck])[ ._-]*([1-9]|[a-d])$""")

        private val TITLE_WORDS = setOf("part", "pt")

        /**
         * Where a file stands in a film split in several files, as Kodi stacks them: the suffix ends its name,
         * just before the extension ("Film (2020) CD1.avi", "Film (2020) - Part 2.mkv", "film.disc.b.iso"),
         * its number from 1 to 9 or its letter from a to d (b is the second). Null for any other name: "Dune
         * Part Two (2024)", "Kill Bill Vol. 1", "Film CD1 [1080p]". A name alone says only that the file may be
         * a part; [Stacks] makes a film of parts that sit side by side.
         */
        fun of(fileName: String): StackPart? {
            val match = SUFFIX.find(FilenameParser.stripExtension(fileName)) ?: return null
            val (name, word, number) = match.destructured
            val mark = number.lowercase().single()
            return StackPart(name, if (mark.isDigit()) mark.digitToInt() else mark - 'a' + 1, word.lowercase() in TITLE_WORDS)
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
 * A folder's films in several files (CD1, CD2, part 1…): videos of one name and one extension side by
 * side in one folder, numbered from the first on, make one title under their first part. A part alone
 * ("Dune Part 2.mkv"), parts that do not start at the first, films named "… Part 1" and "… Part 2"
 * among others with no year to tell them one ([isOneFilm]), and the files of a folder of shows are
 * titles of their own. Built once a listing, before anything else reads it.
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
            val videosIn = videos.groupingBy { Sidecars.folderOf(it.relativePath) }.eachCount()
            val stacks = candidates.groupBy { it.film }.values.mapNotNull { group ->
                // 1, 2, 3… each once, from the first: a gap, or a part twice, ends the film there.
                val run = group.sortedBy { it.part.part }.withIndex().takeWhile { (i, c) -> c.part.part == i + 1 }.map { it.value }
                if (run.size < 2 || run.first().file.relativePath in apart || !isOneFilm(run, videosIn)) return@mapNotNull null
                Stack(run.first().part.name, run.first().file, run.drop(1).map { it.file })
            }
            return Stacks(videos, stacks.associateBy { it.first.relativePath })
        }

        /**
         * Whether the parts of [run] are one film's files ([videosIn]: how many videos each folder holds). "Part 1"
         * and "Part 2" end two films' names as well ("Harry Potter and the Deathly Hallows Part 1", "Dune Part 2"),
         * and a NAS may hold its films side by side in one folder, named without their year: stacked as Kodi
         * stacks them, the second film would lose its row and what was done with it. Parts so named are one film
         * only when their name gives its year ("Film (2020) - Part 1") or they are the only videos of a folder of
         * their own ("Film/Film Part 1.mkv"), the source's root being none. CD, DVD, disc and disk end no title.
         */
        private fun isOneFilm(run: List<Candidate>, videosIn: Map<String, Int>): Boolean {
            if (run.none { it.part.titleWord }) return true
            val folder = run.first().film.first
            return FilenameParser.parseMovie(run.first().part.name).year != null || (folder.isNotEmpty() && videosIn[folder] == run.size)
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
