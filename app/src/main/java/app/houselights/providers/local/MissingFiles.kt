package app.houselights.providers.local

/**
 * Rows of files no longer listed hold user state, so they are deleted only as a last resort:
 * - a renamed or moved file (Radarr or Sonarr upgrade, renamed folder) keeps its row;
 * - a missing file waits an hour and a pass, as Radarr and Sonarr remove the old file before importing the new one;
 * - a NAS can list short without failing (pool disk offline, disk not mounted yet): many missing wait a day.
 */
internal object MissingFiles {
    /** More missing files than this wait a day, whatever the source size, as one pool disk holds a small share. */
    private const val FEW = 20

    const val BRIEF_HOLD_MS = 60 * 60_000L

    const val HOLD_MS = 24 * 60 * 60_000L

    /** Decisions by row id. */
    data class Outcome(
        /** Rows of renamed files, with their new file. */
        val relinked: List<Pair<Long, RemoteFile>>,
        val back: List<Long>,
        val held: List<Long>,
        /** Rows missing for [BRIEF_HOLD_MS] (or [HOLD_MS] while many are), and files now a later part of a film. */
        val removed: List<Long>,
        /** Multi-file films whose parts changed, with their current parts. */
        val parts: List<Pair<Long, List<String>>> = emptyList(),
    )

    /** Rows under unreadable subfolders (lost+found, another user's) are out of sight, not missing. */
    fun settle(
        kind: SourceKind,
        known: Collection<KnownFile>,
        listing: Listing,
        now: Long,
        stacks: Stacks = Stacks.of(kind, listing.videos),
    ): Outcome {
        val hidden = listing.unreadable
        val seen = if (hidden.isEmpty()) known else known.filterNot { item -> hidden.any { item.filePath.isIn(it) } }
        return settle(kind, seen, stacks, now)
    }

    /**
     * Later parts of kept films whose first part is missing: they must not become titles meanwhile, as "Heat CD2"
     * might match another film on TMDB and stay separate once the first part is back ([split]).
     */
    fun waitingParts(known: Collection<KnownFile>, titles: List<RemoteFile>, removed: List<Long>): Set<String> {
        val present = titles.mapTo(HashSet()) { it.relativePath }
        val gone = removed.toHashSet()
        return known.filter { it.parts.isNotEmpty() && it.filePath !in present && it.id !in gone }.flatMapTo(HashSet()) { it.parts }
    }

    private fun String.isIn(path: String) = this == path || startsWith("$path/")

    /**
     * A file that became a later part ([Stacks.titles]) is not missing: its row stands in for the film when the film
     * has none, else is deleted at once, unless the stack is [split].
     */
    fun settle(kind: SourceKind, known: Collection<KnownFile>, stacks: Stacks, now: Long): Outcome {
        val files = stacks.titles
        val present = files.mapTo(HashSet()) { it.relativePath }
        val split = split(known, stacks)
        val (absorbed, rest) = known.partition { row ->
            row.filePath !in present && stacks.firstOf(row.filePath).let { it != null && it !in split }
        }
        // A later part of a split stack is still a title, so its row counts as listed.
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

    /** First parts of stacks whose rows match different titles ("X CD1" and "X CD2" as a film and its sequel). */
    fun split(known: Collection<KnownFile>, stacks: Stacks): Set<String> =
        known.groupBy { row -> stacks.firstOf(row.filePath) ?: row.filePath.takeIf { stacks.stackAt(it) != null } }
            .filter { (first, rows) -> first != null && rows.mapNotNullTo(HashSet()) { it.userTmdbId ?: it.tmdbId }.size > 1 }
            .keys.filterNotNullTo(HashSet())

    /** For films without a row, pairs their earliest part's row with the first part, to stand in for the film. */
    private fun standIns(absorbed: List<KnownFile>, knownPaths: Set<String>, stacks: Stacks): List<Pair<KnownFile, RemoteFile>> =
        absorbed.groupBy { stacks.firstOf(it.filePath) }.mapNotNull { (first, rows) ->
            val stack = stacks.stackAt(first)?.takeIf { first !in knownPaths } ?: return@mapNotNull null
            rows.minBy { row -> stack.rest.indexOfFirst { it.relativePath == row.filePath } } to stack.first
        }

    /**
     * Pairs missing rows with replacement files where exactly one of each shares a key, trying keys in turn. Films:
     * second part, sole video of a film-named folder, title and year in the folder, then anywhere. Episodes: show,
     * season and episode.
     */
    private fun relinks(
        kind: SourceKind,
        missing: List<KnownFile>,
        added: List<RemoteFile>,
        files: List<RemoteFile>,
        stacks: Stacks,
    ): List<Pair<KnownFile, RemoteFile>> {
        val keys: List<(String) -> Any?> = when (kind) {
            SourceKind.MOVIES -> {
                val videos = files.groupingBy { Sidecars.folderOf(it.relativePath) }.eachCount()
                // Missing paths are not titles, so one lookup serves rows and files alike.
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
        // A missing film's own later part is not a rename: the row waits for its first part.
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

    private fun pairs(rows: List<KnownFile>, files: List<RemoteFile>, key: (String) -> Any?): List<Pair<KnownFile, RemoteFile>> {
        val filesByKey = files.groupBy { key(it.relativePath) }
        return rows.groupBy { key(it.filePath) }.mapNotNull { (k, group) ->
            val row = group.singleOrNull()
            val file = filesByKey[k]?.singleOrNull()
            if (k == null || row == null || file == null) null else row to file
        }
    }

    /** The file's folder only when named like a film ("Dune (2021)"); "To watch" may hold another film tomorrow. */
    private fun filmFolder(path: String): String? =
        Sidecars.folderOf(path).takeIf { FilenameParser.parseMovie(it.substringAfterLast('/')).year != null }

    private fun filmKey(path: String): Pair<String, ParsedMovie> = Sidecars.folderOf(path) to film(path)

    /** Key: title and year in any folder; requires a year, as titles alone are often shared. */
    private fun titleKey(path: String): ParsedMovie? = film(path).takeIf { it.year != null }

    private fun film(path: String): ParsedMovie =
        LibraryScanner.parseMovie(path).let { it.copy(title = FilenameParser.showKey(it.title)) }

    private fun episodeKey(path: String): Triple<String, Int, Int>? {
        val episode = FilenameParser.parseEpisode(path.substringAfterLast('/'), path.split('/').dropLast(1)) ?: return null
        val show = episode.showTitle ?: return null
        return Triple(FilenameParser.showKey(show), episode.season, episode.episode)
    }
}
