package app.houselights.providers.local

/**
 * The titles a folder's listing holds, one file each, as the library keeps them: a Blu-ray's or a
 * DVD's streams make one title, the stream a player opens of it ([mainStream]); the extras of a film
 * or an episode make none, whether named so ([isExtraName]) or in a folder of extras under a title's
 * folder ([isExtrasFolder]). What is left decides which folders hold a title alone, whose NFO and
 * artwork are then its own ([Sidecars]).
 */
internal object ScanTitles {
    /** [listing], its videos reduced to the titles; in the order listed. */
    fun of(listing: Listing): Listing = listing.copy(videos = titles(listing.videos))

    fun titles(videos: List<RemoteFile>): List<RemoteFile> {
        val (streams, files) = videos.partition { FilenameParser.discOf(it.relativePath) != null }
        val discs = streams.groupBy { FilenameParser.discOf(it.relativePath)?.root }
        val named = files.filterNot { isExtraName(it.relativePath.substringAfterLast('/')) }
        // Folders above a folder of extras that hold a title of their own, or a season's, and discs' folders: a
        // folder of extras is one only in one of them, never at the source's root nor in a folder of kinds.
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
     * Folders holding one title alone, a film in parts being one ([Stacks]): whatever their name ("Amélie" for
     * "Le Fabuleux Destin d'Amélie Poulain (2001).mkv"), they are its own for folders of extras that never
     * hold films ([FILM_EXTRAS_FOLDERS]). "Shorts" and "Other" beside a loose film may: "Animation/Shorts".
     */
    private fun holdingOneTitle(named: List<RemoteFile>): Set<String> =
        Stacks.of(SourceKind.MOVIES, named).titles.groupingBy { Sidecars.folderOf(it.relativePath) }.eachCount()
            .filterValues { it == 1 }.keys

    /**
     * Extras beside a film or an episode, as Plex, Jellyfin and Kodi name them: "Dune (2021)-trailer.mkv".
     * A trailer or a sample also after Jellyfin's other separators: "Dune (2021).trailer.mkv".
     */
    private val EXTRA_SUFFIX = Regex(
        """(?i)(?:-(?:trailer|sample|featurette|behindthescenes|deleted|interview|scene|short|other)|[ ._](?:trailer|sample))$"""
    )

    /** Folders of a title's extras that never hold films, lowercase, as Plex and Jellyfin name them. */
    private val FILM_EXTRAS_FOLDERS = setOf(
        "extras", "featurettes", "behind the scenes", "deleted scenes", "interviews", "scenes", "trailers", "samples",
    )

    /** Folders of a title's extras, lowercase, as Plex and Jellyfin name them; short films and others besides. */
    private val EXTRAS_FOLDERS = FILM_EXTRAS_FOLDERS + setOf("shorts", "other")

    /**
     * A video named as an extra of the title beside it ([EXTRA_SUFFIX]). "The Big Short" and "The
     * Interview" are films: those words count after a dash only. A name that gives an episode is an
     * extra by a dash right after its numbers only, as its whole title ("Show - S01E01-deleted"), not
     * for its title's last word: "Show - S01E05 - Crime-Scene", "Show - S00E01 - Trailer".
     */
    fun isExtraName(fileName: String): Boolean {
        val base = FilenameParser.stripExtension(fileName)
        val suffix = EXTRA_SUFFIX.find(base) ?: return false
        val title = FilenameParser.afterEpisode(fileName) ?: return true
        return base[suffix.range.first] == '-' && title.equals(suffix.value.drop(1), ignoreCase = true)
    }

    /** A folder of a title's extras: "Featurettes", "Behind The Scenes"… (see [EXTRAS_FOLDERS]). */
    fun isExtrasFolder(name: String): Boolean = name.trim().lowercase() in EXTRAS_FOLDERS

    /** A folder of extras by its [name], lowercase, and the folder it lies in, its title's when it holds one. */
    private class ExtrasFolder(val owner: String, val name: String)

    /** The folders of extras [path] lies in, never at the source's root: "Featurettes" in "Dune (2021)" for "Dune (2021)/Featurettes/x". */
    private fun extrasFolders(path: String): List<ExtrasFolder> {
        val folders = path.split('/').dropLast(1)
        return (1 until folders.size).filter { isExtrasFolder(folders[it]) }
            .map { ExtrasFolder(folders.take(it).joinToString("/"), folders[it].trim().lowercase()) }
    }

    /**
     * [file] lies in its title's own folder or in a season's: one named as its title is ("Arrival/arrival.mkv"),
     * by a title and its year as Radarr and Kodi name a film's ("Dune (2021)"), or a season ("Show/Season 1").
     * A folder of kinds holding a loose film is none ("Animation/Coco (2017).mkv"): its "Shorts" are films.
     */
    private fun inItsOwnFolder(file: RemoteFile): Boolean {
        val folder = Sidecars.folderOf(file.relativePath).substringAfterLast('/')
        val named = FilenameParser.parseMovie(folder)
        if (named.year != null || FilenameParser.seasonFromFolder(folder) != null) return true
        val name = file.relativePath.substringAfterLast('/')
        // A film in several files is named without its part: "Heat/Heat CD1.avi".
        val title = FilenameParser.parseEpisode(name, emptyList())?.showTitle
            ?: FilenameParser.parseMovie(StackPart.of(name)?.name ?: name).title
        return letters(named.title).let { it.isNotEmpty() && it == letters(title) }
    }

    private fun letters(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    /** A DVD's title sets: "VTS_01_1.VOB", the first part of set 01 ("VTS_01_0.VOB" is its menu). */
    private val TITLE_SET = Regex("""(?i)^VTS_(\d{2})_([1-9])\.VOB$""")

    /**
     * What the player opens of a disc: a Blu-ray's largest stream, the film's; a DVD's largest title
     * set, by its first part. Played as a folder, with its menus, is for the device to tell.
     */
    private fun mainStream(streams: List<RemoteFile>): RemoteFile {
        val parts = streams.mapNotNull { f -> TITLE_SET.matchEntire(f.relativePath.substringAfterLast('/'))?.let { it to f } }
        val sets = parts.groupBy({ (m, _) -> m.groupValues[1] }, { (m, f) -> m.groupValues[2].toInt() to f })
        val largest = sets.values.maxByOrNull { set -> set.sumOf { (_, f) -> f.size } }
        return largest?.minBy { (part, _) -> part }?.second ?: streams.maxBy { it.size }
    }
}
