package app.houselights.providers.local

/**
 * The titles a folder's listing holds, one file each, as the library keeps them: a Blu-ray's or a
 * DVD's streams make one title, the stream a player opens of it ([mainStream]); the extras of a film
 * or an episode make none, whether named so ([FilenameParser.isExtraName]) or in a folder of extras
 * under a title's folder ([FilenameParser.isExtrasFolder]). What is left decides which folders hold a
 * title alone, whose NFO and artwork are then its own ([Sidecars]).
 */
internal object ScanTitles {
    /** [listing], its videos reduced to the titles; in the order listed. */
    fun of(listing: Listing): Listing = listing.copy(videos = titles(listing.videos))

    fun titles(videos: List<RemoteFile>): List<RemoteFile> {
        val (streams, files) = videos.partition { FilenameParser.discOf(it.relativePath) != null }
        val discs = streams.groupBy { FilenameParser.discOf(it.relativePath)?.root }
        val named = files.filterNot { FilenameParser.isExtraName(it.relativePath.substringAfterLast('/')) }
        // Folders holding a title: a folder of extras is one only under one of them, never at the source's root.
        val holders = named.mapTo(HashSet()) { Sidecars.folderOf(it.relativePath) } + discs.keys.filterNotNull()
        val kept = named.filterNot { inExtrasFolder(it.relativePath, holders) } + discs.values.map(::mainStream)
        val paths = kept.mapTo(HashSet()) { it.relativePath }
        return videos.filter { it.relativePath in paths }
    }

    /** [path] lies in a folder of extras whose own folder holds a title: "Dune (2021)/Featurettes/Making of.mkv". */
    private fun inExtrasFolder(path: String, holders: Set<String>): Boolean {
        val folders = path.split('/').dropLast(1)
        return (1 until folders.size).any { i ->
            FilenameParser.isExtrasFolder(folders[i]) && folders.take(i).joinToString("/") in holders
        }
    }

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
