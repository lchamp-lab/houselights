package app.houselights.providers.local

/**
 * Rows of files no longer listed. What the user did to a title is kept by title when its row goes ([KeptStates]) and
 * given back when it returns, as when Radarr or Sonarr remove the old file before importing the new one:
 * - a renamed or moved file (Radarr or Sonarr upgrade, renamed folder) keeps its row;
 * - a few files missing go on the pass that misses them; back, they are new rows, read again (media info, TMDB
 *   details, credits) and given back what the user did;
 * - a NAS can list short without failing (pool disk offline, disk not mounted yet): many missing at once wait a day,
 *   so the walls are not emptied and refilled; so does a film whose later part is listed, for its first;
 * - the wall clock can jump ([PassTime]), so a waiting row also waits its day on the monotonic clock when this process
 *   held it, else to be seen missing an hour apart by this process.
 */
internal object MissingFiles {
    /** More missing files than this wait a day, whatever the source size, as one pool disk holds a small share. */
    private const val FEW = 20

    /** How long a row held by an earlier process must be seen missing by this one ([MissingSightings.seenLong]). */
    const val SEEN_AGAIN_MS = 60 * 60_000L

    /** How long a row held waits ([Outcome.held]), from when it was first missed. */
    const val HOLD_MS = 24 * 60 * 60_000L

    /**
     * When a pass runs. The wall clock dates rows, but a device without a battery starts on its build date and jumps
     * once the network sets it: a pass earlier than the folder's last one ([lastScanAt]) deletes nothing, and a row
     * is deleted only once [seenLong] tells this process saw it missing long enough for its hold ([MissingSightings]).
     */
    class PassTime(val now: Long, val lastScanAt: Long = 0, val seenLong: (id: Long, hold: Long) -> Boolean = { _, _ -> true })

    /** Decisions by row id. */
    data class Outcome(
        /** Rows of renamed files, with their new file. */
        val relinked: List<Pair<Long, RemoteFile>>,
        val back: List<Long>,
        /**
         * Rows newly missing while many are, films whose later part is listed, and rows missing since a time still to
         * come: they wait [HOLD_MS] from now.
         */
        val held: List<Long>,
        /** Rows of titles whose files left: a few newly missing, or held for [HOLD_MS]. Their user state is kept. */
        val removed: List<Long>,
        /** Multi-file films whose parts changed, with their current parts. */
        val parts: List<Pair<Long, List<String>>> = emptyList(),
        /** Rows held before and still waiting. */
        val waiting: List<Long> = emptyList(),
        /** Rows of files now a later part of a film, whose row holds the title: deleted, nothing kept. */
        val absorbed: List<Long> = emptyList(),
    ) {
        /** Whether screens now show something else: a file renamed, a title gone, a film's parts. */
        val changed: Boolean get() = relinked.isNotEmpty() || removed.isNotEmpty() || absorbed.isNotEmpty() || parts.isNotEmpty()
    }

    /** Rows under unreadable subfolders (lost+found, another user's) are out of sight, not missing. */
    fun settle(
        kind: SourceKind,
        known: Collection<KnownFile>,
        listing: Listing,
        time: PassTime,
        stacks: Stacks = Stacks.of(kind, listing.videos),
    ): Outcome {
        val hidden = listing.unreadable
        val seen = if (hidden.isEmpty()) known else known.filterNot { item -> hidden.any { item.filePath.isIn(it) } }
        return settle(kind, seen, stacks, time)
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
    fun settle(kind: SourceKind, known: Collection<KnownFile>, stacks: Stacks, time: PassTime): Outcome {
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
        val gone = gone(missing.filter { it.id !in moved }, present, time)
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
     * Rows [missing] among titles [present]. A few newly missing go at once, whatever the clock; many wait a day, and
     * a film whose later part is listed too, without counting towards many. A hold is decided when a row is first
     * missed and never shortened: a row held while many were gone waits its day when the others are back.
     */
    private fun gone(missing: List<KnownFile>, present: Set<String>, time: PassTime): Gone {
        val partial = missing.filter { row -> row.parts.any { it in present } }.mapTo(HashSet()) { it.id }
        val many = missing.count { it.id !in partial } > FEW
        // Missing since a time still to come: held before the clock was set back, so it waits again from now.
        val (dated, fresh) = missing.partition { row -> row.missingSince.let { it != null && it <= time.now } }
        val (held, removed) = fresh.partition { many || it.id in partial || it.missingSince != null }
        // A clock set back since the folder's last pass deletes nothing.
        val settled = time.now >= time.lastScanAt
        val (due, waiting) = dated.partition { row ->
            settled && time.now - (row.missingSince ?: time.now) >= HOLD_MS && time.seenLong(row.id, HOLD_MS)
        }
        return Gone(held = held.map { it.id }, removed = (removed + due).map { it.id }, waiting = waiting.map { it.id })
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

/**
 * Rows this process saw missing, with when it first did on the monotonic clock [elapsed], which no setting of the
 * wall clock moves. A restart forgets them, so rows then wait once more: the safe side. Used by one pass at a time.
 */
internal class MissingSightings(private val elapsed: () -> Long) {
    /** First seen missing, and whether this process dated the row ([MissingFiles.Outcome.held]). */
    private class Sighting(val at: Long, var heldHere: Boolean)

    private val since = HashMap<Long, Sighting>()

    /**
     * Whether row [id] was seen missing long enough: its whole [hold] when this process dated it, as a clock jump
     * since would shorten its age; else [MissingFiles.SEEN_AGAIN_MS], its date coming from an earlier process.
     */
    fun seenLong(id: Long, hold: Long): Boolean {
        val seen = since[id] ?: return false
        return elapsed() - seen.at >= if (seen.heldHere) hold else MissingFiles.SEEN_AGAIN_MS
    }

    /** Notes a pass over [known] rows: those still missing are seen, from now if not before; the others forgotten. */
    fun note(known: Collection<KnownFile>, outcome: MissingFiles.Outcome) {
        val held = outcome.held.toHashSet()
        val waiting = outcome.waiting.toHashSet()
        val now = elapsed()
        for (row in known) {
            when (row.id) {
                in held -> since.getOrPut(row.id) { Sighting(now, heldHere = true) }.heldHere = true
                in waiting -> since.getOrPut(row.id) { Sighting(now, heldHere = false) }
                else -> since.remove(row.id)
            }
        }
    }
}
