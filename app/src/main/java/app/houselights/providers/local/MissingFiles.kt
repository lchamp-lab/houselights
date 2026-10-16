package app.houselights.providers.local

/**
 * What a scan does with the rows of files a folder no longer lists. A row holds what the user did
 * with the title, so it goes only when nothing else fits:
 * - a file renamed or moved (a Radarr or Sonarr upgrade, a folder renamed) takes its row along;
 * - a file missing waits an hour, and a pass at least: Radarr and Sonarr import in two steps, the old
 *   file gone before the new one is in place, and the new one may come under a name of its own;
 * - a NAS can list a folder short without failing (a disk of a pool offline, a share served before
 *   its disk is mounted): while many files are missing, their rows wait a day, in case they come back.
 */
internal object MissingFiles {
    /**
     * Up to this many files missing wait an hour. More wait a day, however large the folder: a disk
     * of a pool of many holds a small share of it.
     */
    private const val FEW = 20

    /** How long rows of missing files are held before a scan removes them. */
    const val BRIEF_HOLD_MS = 60 * 60_000L

    /** How long they are held while more than [FEW] files are missing. */
    const val HOLD_MS = 24 * 60 * 60_000L

    /** What becomes of a folder's rows after its listing, by id. */
    data class Outcome(
        /** Rows of renamed files, with the file each now stands for. */
        val relinked: List<Pair<Long, RemoteFile>>,
        /** Files listed again after they went missing: their rows are no longer held. */
        val back: List<Long>,
        /** Files missed for the first time: their rows are held from now. */
        val held: List<Long>,
        /** Files missing for [BRIEF_HOLD_MS], or for [HOLD_MS] while many are; files now a later part of a film. */
        val removed: List<Long>,
        /**
         * Films in several files whose parts changed (one renamed, added or gone), with their parts now:
         * the same title, its row kept with what the user did with it.
         */
        val parts: List<Pair<Long, List<String>>> = emptyList(),
    )

    /**
     * As the other [settle], for a folder's [listing], its films in several files found in [stacks]:
     * what the library holds under a subfolder it could not read (lost+found, a folder of another user),
     * or a file it could not ask about, is out of sight, not missing.
     */
    fun settle(
        kind: SourceKind,
        known: Collection<ItemEntity>,
        listing: Listing,
        now: Long,
        stacks: Stacks = Stacks.of(kind, listing.videos),
    ): Outcome {
        val hidden = listing.unreadable
        val seen = if (hidden.isEmpty()) known else known.filterNot { item -> hidden.any { item.filePath?.isIn(it) == true } }
        return settle(kind, seen, stacks, now)
    }

    /**
     * The later parts of the films [known] holds whose first part is none of the folder's [titles] (missing a
     * while, or out of sight) and whose rows stay ([removed] are gone): the rows wait for their first part, and
     * those parts make no title meanwhile, which TMDB would be asked for ("Heat CD2") and might match with
     * another film, whose row would then be kept apart from the film's once its first part is back ([split]).
     */
    fun waitingParts(known: Collection<ItemEntity>, titles: List<RemoteFile>, removed: List<Long>): Set<String> {
        val present = titles.mapTo(HashSet()) { it.relativePath }
        val gone = removed.toHashSet()
        return known.filter { it.parts.isNotEmpty() && it.filePath !in present && it.id !in gone }.flatMapTo(HashSet()) { it.parts }
    }

    /** This path is [path] itself, or lies under the folder it names. */
    private fun String.isIn(path: String) = this == path || startsWith("$path/")

    /**
     * A film in several files is one title, under its first part ([Stacks.titles]): a part gone or added
     * is a change of its parts. A file that has become a later part of a film is not missing either: its
     * row stands for the film when the film has none, else gives way to the film's at once. Unless the
     * rows of the film's files were matched with two titles ([split]): each keeps its file, and its own.
     */
    fun settle(kind: SourceKind, known: Collection<ItemEntity>, stacks: Stacks, now: Long): Outcome {
        val files = stacks.titles
        val present = files.mapTo(HashSet()) { it.relativePath }
        val split = split(known, stacks)
        val (absorbed, rest) = known.partition { row ->
            row.filePath !in present && stacks.firstOf(row.filePath).let { it != null && it !in split }
        }
        // A later part of a film that is two is a title still: its row is listed.
        val (listed, missing) = rest.partition { it.filePath in present || stacks.firstOf(it.filePath) in split }
        val knownPaths = known.mapTo(HashSet()) { it.filePath }
        val standIns = standIns(absorbed, knownPaths, stacks)
        val taken = standIns.mapTo(HashSet()) { it.second.relativePath }
        val added = files.filter { it.relativePath !in knownPaths && it.relativePath !in taken }
        val relinked = standIns + relinks(kind, missing, added, files, stacks)
        val moved = relinked.mapTo(HashSet()) { it.first.id }
        val gone = missing.filter { it.id !in moved }
        val (waiting, fresh) = gone.partition { it.missingSince != null }
        val hold = if (gone.size > FEW) HOLD_MS else BRIEF_HOLD_MS
        val titles = listed.map { it to it.filePath } + relinked.map { (row, file) -> row to file.relativePath }
        return Outcome(
            relinked = relinked.map { (row, file) -> row.id to file },
            back = listed.filter { it.missingSince != null }.map { it.id },
            held = fresh.map { it.id },
            removed = waiting.filter { now - (it.missingSince ?: now) >= hold }.map { it.id } +
                absorbed.filter { it.id !in moved }.map { it.id },
            parts = titles.mapNotNull { (row, path) ->
                stacks.partsOf(path).takeIf { it != row.parts && path !in split }?.let { row.id to it }
            },
        )
    }

    /**
     * The films in several files (by their first part) whose files' rows were matched with two titles, by
     * TMDB or by the user: two films, not one, which Kodi's names may join ("X CD1" and "X CD2" for a film and
     * its sequel). None of their rows gives way to another, and none takes the others' files
     * as its parts; the scan reads each as a title of its own ([Stacks.of]).
     */
    fun split(known: Collection<ItemEntity>, stacks: Stacks): Set<String> =
        known.groupBy { row -> row.filePath?.let { path -> stacks.firstOf(path) ?: path.takeIf { stacks.stackAt(it) != null } } }
            .filter { (first, rows) -> first != null && rows.mapNotNullTo(HashSet()) { it.userTmdbId ?: it.tmdbId }.size > 1 }
            .keys.filterNotNullTo(HashSet())

    /**
     * Rows of files that have become later parts of a film the library has no row for, each paired with
     * the film's first part: the earliest part's row stands for the film.
     */
    private fun standIns(absorbed: List<ItemEntity>, knownPaths: Set<String?>, stacks: Stacks): List<Pair<ItemEntity, RemoteFile>> =
        absorbed.groupBy { stacks.firstOf(it.filePath) }.mapNotNull { (first, rows) ->
            val stack = stacks.stackAt(first)?.takeIf { first !in knownPaths } ?: return@mapNotNull null
            rows.minBy { row -> stack.rest.indexOfFirst { it.relativePath == row.filePath } } to stack.first
        }

    /**
     * Missing rows paired with the new files that replace them, only where one row and one file
     * share a key, tried in turn. Films: the one video of a folder named like a film, whatever its new
     * name; the same title and year in the same folder; the same title and a year anywhere in the
     * folder (a film's folder renamed, films sorted into folders). Episodes: the same show, season and
     * episode. A film in several files goes first by the part after its first (its first renamed alone),
     * and by its name without the part.
     */
    private fun relinks(
        kind: SourceKind,
        missing: List<ItemEntity>,
        added: List<RemoteFile>,
        files: List<RemoteFile>,
        stacks: Stacks,
    ): List<Pair<ItemEntity, RemoteFile>> {
        val keys: List<(String) -> Any?> = when (kind) {
            SourceKind.MOVIES -> {
                val videos = files.groupingBy { Sidecars.folderOf(it.relativePath) }.eachCount()
                // A missing row's path is no title's: one lookup serves rows and files alike.
                val stacked = missing.filter { it.parts.isNotEmpty() }.associate { it.filePath to it.parts }
                fun parts(path: String) = stacked[path] ?: stacks.partsOf(path)
                fun named(path: String) = Stacks.filmPath(path, parts(path).isNotEmpty())
                listOf(
                    { path -> parts(path).firstOrNull() },
                    { path -> filmFolder(path)?.takeIf { videos[it] == 1 } },
                    { path -> filmKey(named(path)) },
                    { path -> titleKey(named(path)) },
                )
            }
            SourceKind.SHOWS -> listOf(::episodeKey)
        }
        var rows = missing
        // A missing film's own part, its first gone a while (a disk of a pool offline), is not the film
        // renamed: the row waits for its first part, as any missing film for its file.
        val ownParts = missing.flatMapTo(HashSet()) { it.parts }
        var candidates = added.filter { it.relativePath !in ownParts }
        return keys.flatMap { key ->
            pairs(rows, candidates, key).also { found ->
                val ids = found.mapTo(HashSet()) { it.first.id }
                val paths = found.mapTo(HashSet()) { it.second.relativePath }
                rows = rows.filter { it.id !in ids }
                candidates = candidates.filter { it.relativePath !in paths }
            }
        }
    }

    private fun pairs(rows: List<ItemEntity>, files: List<RemoteFile>, key: (String) -> Any?): List<Pair<ItemEntity, RemoteFile>> {
        val filesByKey = files.groupBy { key(it.relativePath) }
        return rows.groupBy { it.filePath?.let(key) }.mapNotNull { (k, group) ->
            val row = group.singleOrNull()
            val file = filesByKey[k]?.singleOrNull()
            if (k == null || row == null || file == null) null else row to file
        }
    }

    /**
     * The folder of a film's file, when it is named like a film ("Dune (2021)"), as Radarr and Kodi
     * name them: a folder like "To watch" may hold one film today and another tomorrow.
     */
    private fun filmFolder(path: String): String? =
        Sidecars.folderOf(path).takeIf { FilenameParser.parseMovie(it.substringAfterLast('/')).year != null }

    /** Folder, title and year. */
    private fun filmKey(path: String): Pair<String, ParsedMovie> = Sidecars.folderOf(path) to film(path)

    /** Title and year, wherever the file is; only with a year, as titles alone are often shared. */
    private fun titleKey(path: String): ParsedMovie? = film(path).takeIf { it.year != null }

    /** Title and year as the indexer reads them, the title without case or punctuation. */
    private fun film(path: String): ParsedMovie =
        LibraryScanner.parseMovie(path).let { it.copy(title = FilenameParser.showKey(it.title)) }

    /** Show, season and episode, as the indexer reads them: the show's folder names it when it has one. */
    private fun episodeKey(path: String): Triple<String, Int, Int>? {
        val episode = FilenameParser.parseEpisode(path.substringAfterLast('/'), path.split('/').dropLast(1)) ?: return null
        val show = episode.showTitle ?: return null
        return Triple(FilenameParser.showKey(show), episode.season, episode.episode)
    }
}
