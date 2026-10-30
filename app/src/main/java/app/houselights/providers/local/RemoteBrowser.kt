package app.houselights.providers.local

import app.houselights.core.RepeatedFailures
import app.houselights.core.suspendRunCatching
import java.io.IOException
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class RemoteFile(
    /** Path relative to the source folder, with "/" separators. */
    val relativePath: String,
    val size: Long,
    /** Last write time in epoch ms (SMB last write time, NFS mtime), null if unknown; catches same-size rewrites. */
    val lastModified: Long? = null,
)

/** Videos and sidecars of a source; [unreadable] lists paths that failed, which are not missing. */
data class Listing(val videos: List<RemoteFile>, val sidecars: List<RemoteFile>, val unreadable: List<String> = emptyList())

/** One level of a folder, as the add-folder flow browses it ([RemoteBrowser.folders]) and a form tests it ([RemoteBrowser.test]). */
data class FolderLevel(
    /** Its subfolders' names, sorted as a reader looks for them ([byName]), hidden and system folders left out. */
    val folders: List<String>,
    /** The videos it holds itself, its subfolders' not counted. */
    val videos: Int,
) {
    /** Neither a subfolder nor a video: « Ce dossier est vide ». */
    val isEmpty: Boolean get() = folders.isEmpty() && videos == 0
}

/** Lists and reads a network source, whatever the protocol; [RemoteBrowsers] holds the app's instances. */
interface RemoteBrowser {
    /**
     * The videos and sidecars of [source]'s folder; [found] is told how many videos the walk has found so far as each
     * comes, counted as a scan counts titles where their paths tell ([VideoCount]), so screens say it while it lists.
     * Between two folders, the walk stops once [pauses] says so (a film started), throwing [ListingPausedException]: its
     * session or mount is left then, for the caller to wait and list again.
     */
    suspend fun list(source: SourceEntity, pauses: () -> Boolean = { false }, found: (Int) -> Unit = {}): Listing

    /**
     * What the source's folder holds at its first level, its subfolders and its videos ([FolderLevel]), signed in or
     * mounted anew so the NAS checks the form's credentials and rules now; throws with a readable message on failure.
     */
    suspend fun test(source: SourceEntity): FolderLevel

    /**
     * The subfolders of [path] (from [source]'s folder, "/" separators, "" for that folder itself), one level, and the
     * videos it holds, for the add-folder flow to browse. Throws as [test] does when [path] cannot be listed; an entry
     * whose type cannot be read (a link leading nowhere, a file gone since listed) is left out.
     */
    suspend fun folders(source: SourceEntity, path: String = ""): FolderLevel

    /** The first [length] bytes of a file, or the whole file when shorter. */
    suspend fun readPrefix(source: SourceEntity, relativePath: String, length: Int): ByteArray = readAt(source, relativePath, 0, length)

    /** Up to [length] bytes from [offset], fewer past the end of the file. */
    suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray

    companion object {
        const val MAX_DEPTH = 6

        /**
         * Skips dot files (".DS_Store", ".AppleDouble", ".Trash-1000", ".@__thumb", macOS "._" twins) and [SYSTEM]
         * folders only, as "#Alive" and "@Zombie" are films.
         */
        fun skip(name: String) = name.startsWith(".") || SYSTEM.any { it.equals(name, ignoreCase = true) }

        /** Synology and QNAP recycle bins, snapshots, thumbnails, transcodes; Windows folders; lost+found. */
        private val SYSTEM = listOf(
            "#recycle", "#snapshot", "#SynoRecycle", "@eaDir", "@tmp", "@Recycle", "@Recently-Snapshot", "@__thumb", "@Transcode",
            "\$RECYCLE.BIN", "System Volume Information", "lost+found",
        )

        fun isWanted(name: String) = FilenameParser.isVideo(name) && !isSample(name)

        private val SIDECAR_EXTENSIONS = setOf("nfo", "jpg", "jpeg", "png", "webp", "tbn")

        fun isSidecar(name: String) = name.substringAfterLast('.', "").lowercase() in SIDECAR_EXTENSIONS

        private val SAMPLE = Regex("""(?i)(^|[ ._-])sample([ ._-]|$)""")

        private fun isSample(name: String) = SAMPLE.containsMatchIn(name.substringBeforeLast('.'))
    }
}

/**
 * An entry that leads nowhere: a symbolic link broken, in a loop or out of the folder's reach. It is no entry: what
 * was behind it is missing, as a file gone is, never out of sight as an unreadable folder keeps it (#52).
 */
internal open class LeadsNowhereException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A remote folder, protocol-independent. */
internal fun interface RemoteFolder {
    /** Throws when the folder cannot be read. */
    fun entries(): List<RemoteEntry>
}

/** An entry of a [RemoteFolder]; its type and size may each cost a request, so they are queried lazily. */
internal interface RemoteEntry {
    val name: String

    /** Null for a file. */
    fun folder(): RemoteFolder?

    fun size(): Long

    /** Last write time from the same request as [size]; null if unknown. */
    fun lastModified(): Long? = null
}

/**
 * Videos and sidecars under [root], at most [RemoteBrowser.MAX_DEPTH] levels deep, [found] told how many videos so far
 * as each comes ([VideoCount]). Unreadable subfolders (lost+found, another user's) and entries that fail (a file Radarr
 * replaced mid-listing) are skipped and recorded in [Listing.unreadable], and logged in [failures]; an entry that leads
 * nowhere ([LeadsNowhereException]) is logged and left out, what was behind it then missing. A failure on
 * [root], or one [isFatal] accepts (NAS gone, connection broken), throws, as does [pauses] saying so before a subfolder
 * is read ([ListingPausedException]).
 */
internal fun walk(
    root: RemoteFolder,
    failures: RepeatedFailures,
    found: (Int) -> Unit = {},
    pauses: () -> Boolean = { false },
    isFatal: (Throwable) -> Boolean,
): Listing =
    Walk(isFatal, pauses, failures, VideoCount(found)).run {
        try {
            visit(root, "", 0)
        } finally {
            failures.summarize { "Paths unreadable again, each logged before: $it" }
        }
        Listing(videos, sidecars, unreadable)
    }

/** Paths of each source that could not be read, for the process: [walk] logs each whole only the first time. */
internal class UnreadablePaths {
    private val bySource = ConcurrentHashMap<Long, RepeatedFailures>()

    fun of(source: SourceEntity): RepeatedFailures = bySource.getOrPut(source.id) { RepeatedFailures(WALK_TAG) }
}

private const val WALK_TAG = "RemoteBrowser"

/**
 * A listing stopped between two folders, as its caller asked ([RemoteBrowser.list]): what it found is dropped, and the
 * folder is listed anew once it may, on a session or mount of its own, none kept open through a film.
 */
class ListingPausedException : IllegalStateException("Listing stopped between two folders, to begin anew")

/**
 * How many titles the videos a listing finds make so far, as a scan counts them where their paths tell
 * ([ScanTitles.titles]): a disc's streams once ([FilenameParser.discOf]), an extra named as such never
 * ([ScanTitles.isExtraName]); [found] is told each new count. What the whole listing alone tells (an extras folder, a
 * film's parts) still counts, so the files to read may be fewer.
 */
internal class VideoCount(private val found: (Int) -> Unit) {
    private val discs = HashSet<String>()
    private var count = 0

    /** A video at [path], from the folder listed. */
    fun take(path: String) {
        val disc = FilenameParser.discOf(path)
        val title = if (disc != null) discs.add(disc.root) else !ScanTitles.isExtraName(path.substringAfterLast('/'))
        if (title) found(++count)
    }
}

private class Walk(
    private val isFatal: (Throwable) -> Boolean,
    private val pauses: () -> Boolean,
    private val failures: RepeatedFailures,
    private val count: VideoCount,
) {
    val videos = mutableListOf<RemoteFile>()
    val sidecars = mutableListOf<RemoteFile>()
    val unreadable = mutableListOf<String>()

    fun visit(folder: RemoteFolder, relative: String, depth: Int) {
        if (depth > RemoteBrowser.MAX_DEPTH) return
        // Between two folders: the NAS is left to the player before the next one is read.
        if (relative.isNotEmpty() && pauses()) throw ListingPausedException()
        val entries = suspendRunCatching { folder.entries() }.getOrElse { e ->
            if (relative.isEmpty()) throw e
            passOver(relative, e)
            return
        }
        failures.recovered(relative)
        for (entry in entries) {
            if (RemoteBrowser.skip(entry.name)) continue
            val path = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
            // Type and size may each cost a request (an NFS GETATTR), so failures are per entry.
            val subfolder = suspendRunCatching { take(entry, path) }
                // A subfolder reads again once it lists.
                .onSuccess { if (it == null) failures.recovered(path) }
                .getOrElse { e ->
                    passOver(path, e)
                    null
                }
            subfolder?.let { visit(it, path, depth + 1) }
        }
    }

    /** Returns a subfolder to walk, or records a file as a video or sidecar. */
    private fun take(entry: RemoteEntry, path: String): RemoteFolder? {
        entry.folder()?.let { return it }
        when {
            RemoteBrowser.isWanted(entry.name) -> {
                videos += RemoteFile(path, entry.size(), entry.lastModified())
                count.take(path)
            }
            RemoteBrowser.isSidecar(entry.name) -> sidecars += RemoteFile(path, entry.size(), entry.lastModified())
        }
        return null
    }

    private fun passOver(path: String, e: Throwable) {
        if (isFatal(e)) throw e
        if (e is LeadsNowhereException) {
            failures.failed(path, "Passed over $path, which leads nowhere", e)
            return
        }
        failures.failed(path, "Could not read $path", e)
        unreadable += path
    }
}

/**
 * One level of [folder] ([RemoteBrowser.folders]): hidden and system entries left out ([RemoteBrowser.skip]), as is an
 * entry whose type cannot be read, unless [isFatal] accepts its failure (NAS gone, connection broken), which throws, as
 * a failure listing [folder] does. Asks only what telling a folder from a file needs: nothing more over SMB, a LOOKUP
 * over NFS for an entry READDIRPLUS answered without its type, and a link's target.
 */
internal fun level(folder: RemoteFolder, locale: Locale = Locale.getDefault(), isFatal: (Throwable) -> Boolean): FolderLevel {
    val read = folder.entries().filterNot { RemoteBrowser.skip(it.name) }.mapNotNull { entry ->
        suspendRunCatching { entry.name to (entry.folder() != null) }.getOrElse { e -> if (isFatal(e)) throw e else null }
    }
    val folders = read.filter { (_, isFolder) -> isFolder }.map { (name, _) -> name }
    return FolderLevel(folders.sortedWith(byName(locale)), read.count { (name, isFolder) -> !isFolder && RemoteBrowser.isWanted(name) })
}

/**
 * Names as a reader looks for them in [locale]'s order, accents and case aside ("films" beside "Films", "Été" beside
 * "Ete", not after "Z" as code points would put it); names equal but for them follow code points, so the order never
 * varies.
 */
fun byName(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return Comparator<String> { a, b -> collator.compare(a, b) }.then(naturalOrder())
}

/** [path] without its empty segments: "/Films//4K/" reads "Films/4K". */
internal fun cleanPath(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString("/")

/** [relativePath] under [source]'s folder, from the share or export root, joined by [separator]; "" for the root. */
internal fun remotePath(source: SourceEntity, relativePath: String = "", separator: Char = '/'): String =
    listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/").replace('/', separator)

/** Reads up to [length] bytes via [read], which fills from `at` and returns the byte count, or 0 or less at the end. */
internal inline fun readFully(length: Int, read: (bytes: ByteArray, at: Int, count: Int) -> Int): ByteArray {
    val bytes = ByteArray(length)
    var total = 0
    while (total < length) {
        val n = read(bytes, total, length - total)
        if (n <= 0) break
        total += n
    }
    return if (total == length) bytes else bytes.copyOf(total)
}
