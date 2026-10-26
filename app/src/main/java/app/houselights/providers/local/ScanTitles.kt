package app.houselights.providers.local

/**
 * Reduces a listing to one file per title: a disc's streams give one ([mainStream]); extras give none, by name
 * ([isExtraName]) or in an extras folder under a title's folder ([isExtrasFolder]). The result decides which
 * folders hold a single title and so own their sidecars ([Sidecars]).
 */
internal object ScanTitles {
    fun of(listing: Listing): Listing = listing.copy(videos = titles(listing.videos))

    fun titles(videos: List<RemoteFile>): List<RemoteFile> {
        val (streams, files) = videos.partition { FilenameParser.discOf(it.relativePath) != null }
        val discs = streams.groupBy { FilenameParser.discOf(it.relativePath)?.root }
        val named = files.filterNot { isExtraName(it.relativePath.substringAfterLast('/')) }
        // Extras folders count only under a title, season or disc folder, never at the root or under a genre folder.
        val above = named.flatMapTo(HashSet()) { f -> extrasFolders(f.relativePath).map { it.owner } }
        val owners = named.filter { Sidecars.folderOf(it.relativePath) in above && inItsOwnFolder(it) }
            .mapTo(HashSet()) { Sidecars.folderOf(it.relativePath) } + discs.keys.filterNotNull()
        val alone = holdingOneTitle(named)
        val kept = named.filterNot { f ->
            extrasFolders(f.relativePath).any { it.owner in owners || it.owner in alone && it.name in FILM_EXTRAS_FOLDERS }
        } + discs.values.map(::mainStream)
        val paths = kept.mapTo(HashSet()) { it.relativePath }
        return videos.filter { it.relativePath in paths }
    }

    /**
     * Folders holding a single title ([Stacks]), whatever their name ("Amélie" for "Le Fabuleux Destin d'Amélie
     * Poulain (2001).mkv"). Only [FILM_EXTRAS_FOLDERS] count as extras there: "Animation/Shorts" may hold films.
     */
    private fun holdingOneTitle(named: List<RemoteFile>): Set<String> =
        holding(Stacks.of(SourceKind.MOVIES, named).titles.map { it.relativePath }) { it }

    /**
     * Folders holding one film ([NameIds.ofFilm]): [holdingOneTitle] widened to its copies, every title there named
     * alike by its own name, title and year ("Dune (2021) 1080p" beside "Dune (2021) 2160p"), its parts gone into
     * [films] already ([Stack.namedPath]); never a remake beside its original ("Dune (1984)", "Dune (2021)"), nor a
     * film beside a file named otherwise ("movie.mkv", "trailer.mkv").
     */
    fun holdingOneFilm(films: Collection<String>): Set<String> =
        holding(films) { path -> FilenameParser.parseMovie(path.substringAfterLast('/')).let { FilenameParser.showKey(it.title, it.year) } }

    /** Folders of [paths] whose paths all give one [key]. */
    private fun holding(paths: Collection<String>, key: (String) -> String): Set<String> =
        paths.groupBy { Sidecars.folderOf(it) }.filterValues { inFolder -> inFolder.mapTo(HashSet(), key).size == 1 }.keys

    /** Plex, Jellyfin and Kodi extras suffixes: "Dune (2021)-trailer.mkv", Jellyfin's "Dune (2021).trailer.mkv". */
    private val EXTRA_SUFFIX = Regex(
        """(?i)(?:-(?:trailer|sample|featurette|behindthescenes|deleted|interview|scene|short|other)|[ ._](?:trailer|sample))$"""
    )

    /** Plex and Jellyfin extras folders that never hold films (lowercase). */
    private val FILM_EXTRAS_FOLDERS = setOf(
        "extras", "featurettes", "behind the scenes", "deleted scenes", "interviews", "scenes", "trailers", "samples",
    )

    /** All Plex and Jellyfin extras folders (lowercase), including those that may hold films. */
    private val EXTRAS_FOLDERS = FILM_EXTRAS_FOLDERS + setOf("shorts", "other")

    /**
     * A video named as an extra ([EXTRA_SUFFIX]); words like "Short" or "Interview" count only after a dash, since
     * "The Big Short" is a film. An episode is an extra only when the suffix is its whole title right after the
     * numbers ("Show - S01E01-deleted"), not "Show - S01E05 - Crime-Scene" or "Show - S00E01 - Trailer".
     */
    fun isExtraName(fileName: String): Boolean {
        val base = FilenameParser.stripExtension(fileName)
        val suffix = EXTRA_SUFFIX.find(base) ?: return false
        val title = FilenameParser.afterEpisode(fileName) ?: return true
        return base[suffix.range.first] == '-' && title.equals(suffix.value.drop(1), ignoreCase = true)
    }

    fun isExtrasFolder(name: String): Boolean = name.trim().lowercase() in EXTRAS_FOLDERS

    /** An extras folder [name] (lowercase) and the folder that contains it. */
    private class ExtrasFolder(val owner: String, val name: String)

    /** Extras folders on [path] below the root: "Featurettes" in "Dune (2021)" for "Dune (2021)/Featurettes/x". */
    private fun extrasFolders(path: String): List<ExtrasFolder> {
        val folders = path.split('/').dropLast(1)
        return (1 until folders.size).filter { isExtrasFolder(folders[it]) }
            .map { ExtrasFolder(folders.take(it).joinToString("/"), folders[it].trim().lowercase()) }
    }

    /**
     * True when [file] is in its own title folder ("Arrival/arrival.mkv", "Dune (2021)") or a season folder, unlike a
     * genre folder with loose films ("Animation/Coco (2017).mkv"), whose "Shorts" are films.
     */
    private fun inItsOwnFolder(file: RemoteFile): Boolean {
        val folder = Sidecars.folderOf(file.relativePath).substringAfterLast('/')
        val named = FilenameParser.parseMovie(folder)
        if (named.year != null || FilenameParser.seasonFromFolder(folder) != null) return true
        val name = file.relativePath.substringAfterLast('/')
        // Multi-file films compare without the part suffix: "Heat/Heat CD1.avi".
        val title = FilenameParser.parseEpisode(name, emptyList(), named = true)?.showTitle
            ?: FilenameParser.parseMovie(StackPart.of(name)?.name ?: name).title
        return letters(named.title).let { it.isNotEmpty() && it == letters(title) }
    }

    private fun letters(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    /** DVD title set parts: "VTS_01_1.VOB" is part 1 of set 01 ("VTS_01_0.VOB" is the menu). */
    private val TITLE_SET = Regex("""(?i)^VTS_(\d{2})_([1-9])\.VOB$""")

    /** The stream that represents a disc: a Blu-ray's largest stream, a DVD's largest title set by its first part. */
    private fun mainStream(streams: List<RemoteFile>): RemoteFile {
        val parts = streams.mapNotNull { f -> TITLE_SET.matchEntire(f.relativePath.substringAfterLast('/'))?.let { it to f } }
        val sets = parts.groupBy({ (m, _) -> m.groupValues[1] }, { (m, f) -> m.groupValues[2].toInt() to f })
        val largest = sets.values.maxByOrNull { set -> set.sumOf { (_, f) -> f.size } }
        return largest?.minBy { (part, _) -> part }?.second ?: streams.maxBy { it.size }
    }
}
