package app.houselights.providers.local

import java.util.Locale

/** Files that tools like Kodi, tinyMediaManager or Jellyfin place next to a video or a show. */
data class VideoSidecars(
    val nfo: RemoteFile? = null,
    val poster: RemoteFile? = null,
    val fanart: RemoteFile? = null,
    val logo: RemoteFile? = null,
    val thumb: RemoteFile? = null,
)

/**
 * Finds sidecar files by Kodi naming conventions, case-insensitively. [videos]: the titles of the
 * folder ([ScanTitles]), its extras left out, as they decide which folders a title has to itself; a
 * film in several files counts once, and is found in [stacks].
 */
class Sidecars(files: List<RemoteFile>, videos: List<RemoteFile>, private val stacks: Stacks? = null) {
    private val byPath = files.associateBy { it.relativePath.lowercase() }
    private val videosPerFolder = videos.groupingBy { titleFolder(it.relativePath) }.eachCount()

    private fun find(dir: String, names: List<String>): RemoteFile? =
        names.firstNotNullOfOrNull { byPath[(if (dir.isEmpty()) it else "$dir/$it").lowercase()] }

    /**
     * A film: "<name>.nfo", "<name>-poster.jpg"…; folder-wide names only when it has its own folder. A
     * film in several files goes by its name without the part ("Film (2020).nfo"), then by each part's.
     * A disc's lie beside its BDMV or VIDEO_TS folder, and its NFO in it too ("BDMV/index.nfo").
     */
    fun forVideo(video: RemoteFile): VideoSidecars {
        val dir = titleFolder(video.relativePath)
        val bases = FilenameParser.discOf(video.relativePath)?.let { listOf(it.index) }
            ?: stacks?.stackAt(video.relativePath)?.names
            ?: listOf(video.relativePath.substringAfterLast('/').substringBeforeLast('.'))
        val ownFolder = dir.isNotEmpty() && videosPerFolder[dir] == 1
        fun pick(suffixes: List<String>, folderNames: List<String>) =
            find(dir, bases.flatMap { base -> suffixes.map { base + it } } + if (ownFolder) folderNames else emptyList())
        return VideoSidecars(
            nfo = pick(listOf(".nfo"), listOf("movie.nfo")),
            poster = pick(IMAGES.map { "-poster.$it" } + listOf(".tbn"), IMAGES.flatMap { listOf("poster.$it", "folder.$it", "cover.$it") }),
            fanart = pick(IMAGES.map { "-fanart.$it" } + IMAGES.map { "-backdrop.$it" }, IMAGES.flatMap { listOf("fanart.$it", "backdrop.$it") }),
            logo = pick(listOf("-clearlogo.png", "-logo.png"), listOf("clearlogo.png", "logo.png")),
            thumb = pick(IMAGES.map { "-thumb.$it" } + IMAGES.map { "-landscape.$it" }, IMAGES.map { "landscape.$it" }),
        )
    }

    /** A show folder: tvshow.nfo, poster.jpg, fanart.jpg, clearlogo.png. */
    fun forShow(showDir: String): VideoSidecars = VideoSidecars(
        nfo = find(showDir, listOf("tvshow.nfo")),
        poster = find(showDir, IMAGES.flatMap { listOf("poster.$it", "folder.$it", "cover.$it") }),
        fanart = find(showDir, IMAGES.flatMap { listOf("fanart.$it", "backdrop.$it") }),
        logo = find(showDir, listOf("clearlogo.png", "logo.png")),
        thumb = find(showDir, IMAGES.map { "landscape.$it" }),
    )

    fun seasonPoster(showDir: String, season: Int): RemoteFile? {
        val numbered = IMAGES.map { "season%02d-poster.$it".format(Locale.ROOT, season) }
        val specials = if (season == 0) IMAGES.map { "season-specials-poster.$it" } else emptyList()
        return find(showDir, numbered + specials)
    }

    companion object {
        private val IMAGES = listOf("jpg", "jpeg", "png", "webp")
        fun folderOf(path: String) = path.substringBeforeLast('/', "")

        /** The folder a title lies in: a file's own, a disc's the one holding it. */
        private fun titleFolder(path: String) = FilenameParser.discOf(path)?.root ?: folderOf(path)
    }
}
