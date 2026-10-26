package app.houselights.providers.local

import app.houselights.core.DiskAbsentException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Lists and reads folders on the player's own disks ([Protocol.LOCAL]) as files, behind the [RemoteBrowser] a NAS's
 * folders have, so scans, the MKV probe, NFOs, artwork and missing files read them alike; a single instance lives in
 * [RemoteBrowsers]. A folder keeps its disk's id ([SourceEntity.host]) and its path inside it ([SourceEntity.path]);
 * where the disk is mounted is asked of [volumes] at a pass's first read, and kept until it ends ([closeIdle]). A
 * disk not mounted, or unplugged meanwhile, is out of reach as a NAS asleep ([DiskAbsentException]): its folder does
 * not list, so nothing leaves the library, and the next pass reads it again once it is back. Without the access Android
 * grants ([readable]), the disk is refused before it is read ([AccessDeniedException]): from Android 11 its storage would
 * list the folders but leave out every file, an answer of nothing rather than a refusal.
 */
internal class LocalBrowser(
    private val volumes: Volumes,
    /** Opens a folder's entries; a test's may fail as they come. */
    private val directories: (Path) -> DirectoryStream<Path> = Files::newDirectoryStream,
    /** Whether the app may read the player's disks now ([DiskPermission]), asked once a pass with where each is. */
    private val readable: () -> Boolean,
) : RemoteBrowser {
    /** Mounts by disk id, read once a pass. */
    private val mounts = ConcurrentHashMap<String, Path>()

    private val unreadable = UnreadablePaths()

    override suspend fun list(source: SourceEntity): Listing = withContext(Dispatchers.IO) {
        val mount = mountOf(source)
        val listing = reading(source, mount) {
            val root = mount.resolve(remotePath(source))
            walk(Folders(source, mount).folder(root, setOf(root.toRealPath())), unreadable.of(source)) { ScanErrors.isUnreachable(it) }
        }
        // Unplugged while it listed: a folder it did not reach is not gone.
        absent(source, mount)?.let { throw it }
        listing
    }

    /** Asks Android where the disk is now, as the form tests what it would save. */
    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        mounts.remove(key(source))
        val mount = mountOf(source)
        reading(source, mount) { Folders(source, mount).listed(mount.resolve(remotePath(source))).count { !RemoteBrowser.skip(it.name()) } }
    }

    /**
     * Where Android has the disk now, as the form's test: the add-folder flow opens a disk at its root, Android's own
     * folders left out there ([VOLUME_SYSTEM]), and a link back up the walk left out as an entry that cannot be read.
     */
    override suspend fun folders(source: SourceEntity, path: String): FolderLevel = withContext(Dispatchers.IO) {
        mounts.remove(key(source))
        val mount = mountOf(source)
        reading(source, mount) {
            val dir = mount.resolve(remotePath(source, cleanPath(path)))
            level(Folders(source, mount).folder(dir, setOf(dir.toRealPath()))) { ScanErrors.isUnreachable(it) }
        }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val mount = mountOf(source)
            reading(source, mount) {
                FileChannel.open(mount.resolve(remotePath(source, relativePath)), StandardOpenOption.READ).use { file ->
                    readFully(length) { bytes, at, count -> file.read(ByteBuffer.wrap(bytes, at, count), offset + at) }
                }
            }
        }

    /** Forgets where [source]'s disk was, after the folder changed: the next read asks Android again. */
    fun forget(source: SourceEntity) {
        mounts.remove(key(source))
    }

    /** Forgets every disk's mount; called when a scan ends, so the next one asks Android where each is. */
    fun closeIdle() = mounts.clear()

    private fun key(source: SourceEntity) = source.host.lowercase(Locale.ROOT)

    /**
     * Where [source]'s disk is mounted, as this pass read it; [DiskAbsentException] when Android has it nowhere, and
     * [AccessDeniedException] when the app may not read it, which is never kept.
     */
    private fun mountOf(source: SourceEntity): Path = mounts.getOrPut(key(source)) {
        val mount = volumes.mountOf(source.host)?.toPath() ?: throw DiskAbsentException(source.host)
        if (!readable()) throw AccessDeniedException(mount.toString(), null, "the app may not read the player's disks")
        mount
    }

    /** Runs [block] on the disk at [mount]; a failure once the disk is no longer there throws [DiskAbsentException]. */
    private fun <T> reading(source: SourceEntity, mount: Path, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw absent(source, mount, e) ?: e
    }

    /**
     * [DiskAbsentException] when [source]'s disk is no longer mounted at [mount] (unplugged, or mounted elsewhere since,
     * which the next read takes), with the failure it explains, [cause]; null while it still is.
     */
    private fun absent(source: SourceEntity, mount: Path, cause: Throwable? = null): DiskAbsentException? {
        if (volumes.mountOf(source.host)?.toPath() == mount) return null
        mounts.remove(key(source), mount)
        return DiskAbsentException(source.host, cause)
    }

    /** The folders of one listing of [source], on its disk at [mount]. */
    private inner class Folders(private val source: SourceEntity, private val mount: Path) {
        /**
         * The folder at [dir], in the folders [above] it by their real paths. Symbolic links are followed, except to a
         * folder the walk is in, which it would list again down to [RemoteBrowser.MAX_DEPTH]. Each entry's type, size and
         * date come from one read of its attributes.
         */
        fun folder(dir: Path, above: Set<Path>): RemoteFolder = RemoteFolder {
            listed(dir).map { path ->
                object : RemoteEntry {
                    override val name = path.name()

                    // A failed read throws (a file gone, a disk unplugged) and is tried again on the next access.
                    private val attributes by lazy {
                        reading(source, mount) { Files.readAttributes(path, BasicFileAttributes::class.java) }
                    }

                    override fun folder(): RemoteFolder? {
                        if (!attributes.isDirectory) return null
                        val real = reading(source, mount) { path.toRealPath() }
                        if (real in above) throw UnfollowedLinkException("it leads back up the walk")
                        return folder(path, above + real)
                    }

                    override fun size() = attributes.size()

                    override fun lastModified() = attributes.lastModifiedTime().toMillis()
                }
            }
        }

        /**
         * The entries of [dir], but the disk's own folders at its root ([VOLUME_SYSTEM]); throws when it cannot be read,
         * a failure as its entries come (a disk unplugged) as one opening it, which Java wraps in an unchecked exception.
         */
        fun listed(dir: Path): List<Path> = reading(source, mount) {
            directories(dir).use { entries ->
                try {
                    entries.filterNot { dir == mount && VOLUME_SYSTEM.any { name -> name.equals(it.name(), ignoreCase = true) } }
                } catch (e: DirectoryIteratorException) {
                    // The read's own failure, an IOException.
                    throw e.cause ?: e
                }
            }
        }
    }

    private companion object {
        /** At a disk's root, Android's own folders: the apps' data and files (Android), what a disk check recovered (LOST.DIR). */
        val VOLUME_SYSTEM = listOf("Android", "LOST.DIR")

        fun Path.name() = fileName.toString()
    }
}
