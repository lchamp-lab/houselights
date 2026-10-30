package app.houselights.providers.local

/**
 * Rows of files no longer listed. What the user did to a title is kept by title when its row goes ([KeptStates]) and
 * given back when it returns, as when Radarr or Sonarr remove the old file before importing the new one:
 * - a renamed or moved file (Radarr or Sonarr upgrade, renamed folder) keeps its row;
 * - files missing while their folder lists go on the pass that misses them; back, they are new rows, read again (media
 *   info, TMDB details, credits) and given back what the user did;
 * - a NAS can list short without failing (a disk of its pool offline, a volume not mounted yet), which the app cannot
 *   tell from a clean-up: a sudden drop ([isDrop]) removes nothing; its rows are held, still shown, until their files
 *   are back or the user answers ([LibraryScanner.keepHeld], [LibraryScanner.removeHeld]). No clock decides;
 * - a folder its NAS says is gone while it answers (deleted or moved there) is held so too, whole ([folderGone]).
 */
internal object MissingFiles {
    /** The fewest titles a sudden drop holds, so that a handful deleted never asks. */
    const val DROP_MIN = 10

    /** A sudden drop takes more than a folder's titles divided by this: a quarter. */
    private const val DROP_SHARE = 4

    /**
     * Whether [missing] titles newly missing from a folder of [titles] are a sudden drop, as when a disk of the NAS's
     * pool is offline: more than a quarter of them, and [DROP_MIN] at least.
     */
    fun isDrop(missing: Int, titles: Int): Boolean = missing >= DROP_MIN && missing.toLong() * DROP_SHARE > titles

    /** Decisions by row id. */
    data class Outcome(
        /** Rows of renamed files, with their new file. */
        val relinked: List<Pair<Long, RemoteFile>>,
        /** Rows held whose files are listed again: they are as they were. */
        val back: List<Long>,
        /** Rows newly missing in a sudden drop ([isDrop]): held from now, and asked about. */
        val held: List<Long>,
        /** Rows newly missing short of a drop: their titles leave, what the user did kept by title. */
        val removed: List<Long>,
        /** Multi-file films whose parts changed, with their current parts. */
        val parts: List<Pair<Long, List<String>>> = emptyList(),
        /** Rows held by an earlier drop and still missing: they wait for their files or the user's answer. */
        val waiting: List<Long> = emptyList(),
        /** Rows of files now a later part of a film, whose row holds the title: deleted, nothing kept. */
        val absorbed: List<Long> = emptyList(),
    ) {
        /** Whether screens now show something else: a file renamed, a title gone, held or back, a film's parts. */
        val changed: Boolean
            get() = relinked.isNotEmpty() || removed.isNotEmpty() || absorbed.isNotEmpty() || parts.isNotEmpty() ||
                held.isNotEmpty() || back.isNotEmpty()
    }

    /**
     * The rows [known] of a folder its NAS says is gone ([ScanErrors.isFolderGone]): all held, as a sudden drop holds
     * them, those an earlier pass held waiting. Listed again, they are back, and those still missing settled anew ([settle]).
     */
    fun folderGone(known: Collection<KnownFile>): Outcome {
        val (waiting, fresh) = known.partition { it.missingSince != null }
        return Outcome(
            relinked = emptyList(),
            back = emptyList(),
            held = fresh.map { it.id },
            removed = emptyList(),
            waiting = waiting.map { it.id },
        )
    }

    /**
     * Rows under unreadable subfolders (lost+found, another user's) are out of sight, not missing. A folder every row of
     * which is held, as one gone from its NAS is ([folderGone]), that lists again is back: its rows out of sight are
     * shown unmarked, as before it went, and those still missing are settled as on any pass rather than left waiting, a
     * few going and many a sudden drop, asked about anew.
     */
    fun settle(
        kind: SourceKind,
        known: Collection<KnownFile>,
        listing: Listing,
        stacks: Stacks = Stacks.of(kind, listing.videos),
    ): Outcome {
        val hidden = listing.unreadable
        val (out, seen) = known.partition { item -> hidden.any { item.filePath.isIn(it) } }
        val returned = known.isNotEmpty() && known.all { it.missingSince != null }
        val outcome = settle(kind, seen, stacks, anew = returned)
        return if (returned) outcome.copy(back = outcome.back + out.map { it.id }) else outcome
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
     * has none, else is deleted at once, unless the stack is [split]. Rows held and still missing wait, unless [anew].
     */
    fun settle(kind: SourceKind, known: Collection<KnownFile>, stacks: Stacks, anew: Boolean = false): Outcome {
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
        // The folder's titles: its rows but those now parts of a film.
        val gone = gone(missing.filter { it.id !in moved }, titles = rest.size, anew)
        val titles = listed.map { it to it.filePath } + relinked.map { (row, file) -> row to file.relativePath }
        return Outcome(
            relinked = relinked.map { (row, file) -> row.id to file },
            back = listed.filter { it.missingSince != null }.map { it.id },
            held = gone.held,
            removed = gone.removed,
            parts = titles.mapNotNull { (row, path) ->
                stacks.partsOf(path).takeIf { it != row.parts && path !in split }?.let { row.id to it }
            },
            waiting = gone.waiting,
            absorbed = absorbed.filter { it.id !in moved }.map { it.id },
        )
    }

    /** What becomes of rows missing and not renamed ([Outcome]). */
    private class Gone(val held: List<Long>, val removed: List<Long>, val waiting: List<Long>)

    /**
     * Rows [missing] of a folder of [titles]: held by an earlier drop, they wait, unless settled [anew]; newly missing,
     * they go, unless they are a sudden drop ([isDrop]), which holds them all. A film whose first part is missing is
     * missing too, its later parts then waiting with it while it is held ([waitingParts]).
     */
    private fun gone(missing: List<KnownFile>, titles: Int, anew: Boolean): Gone {
        val (waiting, fresh) = if (anew) emptyList<KnownFile>() to missing else missing.partition { it.missingSince != null }
        val drop = isDrop(fresh.size, titles)
        val ids = fresh.map { it.id }
        return Gone(held = if (drop) ids else emptyList(), removed = if (drop) emptyList() else ids, waiting = waiting.map { it.id })
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
                fun named(path: String) = filmName(path, parts(path).isNotEmpty())
                listOf(
                    { path -> parts(path).firstOrNull() },
                    { path -> filmFolder(path)?.takeIf { videos[it] == 1 } },
                    { path -> Sidecars.folderOf(path) to named(path) },
                    // Anywhere, only with a year, as titles alone are often shared.
                    { path -> named(path).takeIf { it.year != null } },
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

    /**
     * The name a film is known by at [path], without a part's suffix when [stacked] ("Heat (1995) CD1" is "Heat"):
     * its title folded as [FilenameParser.showKey] folds it, and its year. Relinks pair files by it, and a title's
     * kept state is found by it ([KeptKeys.filmName]), so both tell films apart alike.
     */
    fun filmName(path: String, stacked: Boolean): ParsedMovie =
        LibraryScanner.parseMovie(Stacks.filmPath(path, stacked)).let { it.copy(title = FilenameParser.showKey(it.title)) }

    private fun episodeKey(path: String): Triple<String, Int, Int>? {
        val episode = FilenameParser.parseEpisode(path.substringAfterLast('/'), path.split('/').dropLast(1)) ?: return null
        val show = episode.showTitle ?: return null
        return Triple(FilenameParser.showKey(show), episode.season, episode.episode)
    }
}
