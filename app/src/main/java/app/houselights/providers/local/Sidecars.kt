package app.houselights.providers.local

import java.io.File
import java.security.MessageDigest

/** Files that tools like Kodi, tinyMediaManager or Jellyfin place next to a video or a show. */
data class VideoSidecars(
    val nfo: RemoteFile? = null,
    val poster: RemoteFile? = null,
    val fanart: RemoteFile? = null,
    val logo: RemoteFile? = null,
    val thumb: RemoteFile? = null,
)

/** Finds sidecar files by Kodi naming conventions, case-insensitively. */
class Sidecars(files: List<RemoteFile>, videos: List<RemoteFile>) {
    private val byPath = files.associateBy { it.relativePath.lowercase() }
    private val videosPerFolder = videos.groupingBy { folderOf(it.relativePath) }.eachCount()

    private fun find(dir: String, names: List<String>): RemoteFile? =
        names.firstNotNullOfOrNull { byPath[(if (dir.isEmpty()) it else "$dir/$it").lowercase()] }

    /** A film: "<name>.nfo", "<name>-poster.jpg"…; folder-wide names only when it has its own folder. */
    fun forVideo(video: RemoteFile): VideoSidecars {
        val dir = folderOf(video.relativePath)
        val base = video.relativePath.substringAfterLast('/').substringBeforeLast('.')
        val ownFolder = dir.isNotEmpty() && videosPerFolder[dir] == 1
        fun pick(suffixes: List<String>, folderNames: List<String>) =
            find(dir, suffixes.map { base + it } + if (ownFolder) folderNames else emptyList())
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

    fun seasonPoster(showDir: String, season: Int): RemoteFile? =
        find(showDir, IMAGES.map { "season%02d-poster.$it".format(season) } + if (season == 0) IMAGES.map { "season-specials-poster.$it" } else emptyList())

    companion object {
        private val IMAGES = listOf("jpg", "jpeg", "png", "webp")
        fun folderOf(path: String) = path.substringBeforeLast('/', "")
    }
}

/** Local artwork copied once from the NAS into app storage, so Coil can load it like any image. */
class ArtworkCache(private val dir: File) {
    /** "file://…" path of the cached copy; read again only when the file changes size. */
    suspend fun copy(source: SourceEntity, file: RemoteFile): String? = runCatching {
        val ext = file.relativePath.substringAfterLast('.', "jpg").lowercase().let { if (it == "tbn") "jpg" else it }
        val target = File(dir, "${source.id}/${hash("${file.relativePath}:${file.size}")}.$ext")
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            val bytes = RemoteBrowser.of(source).readPrefix(source, file.relativePath, MAX_BYTES)
            target.writeBytes(bytes)
        }
        "file://${target.absolutePath}"
    }.getOrNull()

    companion object {
        private const val MAX_BYTES = 30 shl 20

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}
