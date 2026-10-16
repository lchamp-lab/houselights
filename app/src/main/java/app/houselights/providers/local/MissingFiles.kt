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
        /** Files missing for [BRIEF_HOLD_MS], or for [HOLD_MS] while many are. */
        val removed: List<Long>,
    )

    fun settle(kind: SourceKind, known: Collection<ItemEntity>, files: List<RemoteFile>, now: Long): Outcome {
        val present = files.mapTo(HashSet()) { it.relativePath }
        val (listed, missing) = known.partition { it.filePath in present }
        val knownPaths = known.mapTo(HashSet()) { it.filePath }
        val relinked = relinks(kind, missing, files.filter { it.relativePath !in knownPaths }, files)
        val moved = relinked.mapTo(HashSet()) { it.first.id }
        val gone = missing.filter { it.id !in moved }
        val (waiting, fresh) = gone.partition { it.missingSince != null }
        val hold = if (gone.size > FEW) HOLD_MS else BRIEF_HOLD_MS
        return Outcome(
            relinked = relinked.map { (row, file) -> row.id to file },
            back = listed.filter { it.missingSince != null }.map { it.id },
            held = fresh.map { it.id },
            removed = waiting.filter { now - (it.missingSince ?: now) >= hold }.map { it.id },
        )
    }

    /**
     * Missing rows paired with the new files that replace them, only where one row and one file
     * share a key, tried in turn. Films: the one video of a folder named like a film, whatever its new
     * name; the same title and year in the same folder; the same title and a year anywhere in the
     * folder (a film's folder renamed, films sorted into folders). Episodes: the same show, season and
     * episode.
     */
    private fun relinks(
        kind: SourceKind,
        missing: List<ItemEntity>,
        added: List<RemoteFile>,
        files: List<RemoteFile>,
    ): List<Pair<ItemEntity, RemoteFile>> {
        val keys: List<(String) -> Any?> = when (kind) {
            SourceKind.MOVIES -> {
                val videos = files.groupingBy { Sidecars.folderOf(it.relativePath) }.eachCount()
                listOf({ path -> filmFolder(path)?.takeIf { videos[it] == 1 } }, ::filmKey, ::titleKey)
            }
            SourceKind.SHOWS -> listOf(::episodeKey)
        }
        var rows = missing
        var candidates = added
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
