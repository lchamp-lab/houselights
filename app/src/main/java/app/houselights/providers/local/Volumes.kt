package app.houselights.providers.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * A disk of the player's own a folder can be on ([Protocol.LOCAL]): its internal shared storage, or a USB disk or SD
 * card. A folder keeps the disk's [id] and a path inside it; where the disk is mounted is asked again at each scan.
 */
data class Volume(
    /**
     * Stable across plugs, ports and boots: the filesystem's UUID for a USB disk or SD card ("1234-ABCD", as Android
     * names it), [INTERNAL] for the player's own storage.
     */
    val id: String,
    /** As Android names it, in the device's language: a disk's label ("Samsung T7"), else its maker's; the storage's own. */
    val name: String,
    /** The player's own storage (Android's primary volume), which is never unplugged; else a USB disk or SD card. */
    val internal: Boolean,
    /** Where Android mounts it now, as apps read it ("/storage/emulated/0", "/storage/1234-ABCD"). */
    val mount: File,
) {
    companion object {
        /** The id of the player's own storage, which has no UUID of its own. */
        const val INTERNAL = "internal"
    }
}

/** The player's disks Android has mounted, read again at each call. */
fun interface Volumes {
    fun mounted(): List<Volume>

    /** Where disk [id] is mounted now; null while it is not (unplugged, being checked, ejected). */
    fun mountOf(id: String): File? = mounted().firstOrNull { it.id.equals(id, ignoreCase = true) }?.mount

    companion object {
        /** No disk at all, for what never reads one. */
        val NONE = Volumes { emptyList() }
    }
}

/**
 * The disks [StorageManager] lists, mounted (read-only included). Their mount comes from Android 11's public
 * [StorageVolume.getDirectory]; Android 9 and 10 have none, so it is the hidden `getPath()` they still allow ([hidden]),
 * then the volume's view under /storage that [mounts] (/proc/mounts) lists, then for the primary storage
 * [Environment.getExternalStorageDirectory].
 */
class AndroidVolumes internal constructor(
    private val context: Context,
    /** Android 9 and 10's hidden path of a volume; null when the firmware refuses it. */
    private val hidden: (StorageVolume) -> File?,
    /** What /proc/mounts says, read only when [hidden] gave nothing. */
    private val mounts: () -> String = { File(PROC_MOUNTS).readText() },
) : Volumes {
    constructor(context: Context) : this(context, { hiddenPath(it) })

    override fun mounted(): List<Volume> {
        val storage = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return storage.storageVolumes.mapNotNull(::volumeOf)
    }

    private fun volumeOf(volume: StorageVolume): Volume? {
        if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return null
        // A volume without a UUID could not be found again once unplugged.
        val id = if (volume.isPrimary) Volume.INTERNAL else volume.uuid ?: return null
        val mount = mountOf(volume) ?: return null
        return Volume(id, volume.getDescription(context).orEmpty(), internal = volume.isPrimary, mount)
    }

    @Suppress("DEPRECATION") // The primary storage's path, public until Android 10, where nothing else gives it.
    private fun mountOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        val uuid = volume.uuid
        return hidden(volume) ?: when {
            uuid != null -> listedMount(uuid)
            volume.isPrimary -> Environment.getExternalStorageDirectory()
            else -> null
        }
    }

    /** Where /proc/mounts shows apps the volume of [uuid]; null when it does not, or cannot be read. */
    private fun listedMount(uuid: String): File? = try {
        mountIn(mounts(), uuid)
    } catch (e: IOException) {
        Log.w(TAG, "Could not read $PROC_MOUNTS", e)
        null
    }

    companion object {
        private const val TAG = "Volumes"
        private const val PROC_MOUNTS = "/proc/mounts"

        /** Where Android shows apps a volume: under /storage, as the volume's UUID (vold's own rule). */
        private const val STORAGE = "/storage/"

        /**
         * The mount point the /proc/mounts [text] gives the volume of [uuid] under /storage, apps' view of it ("/storage/1234-ABCD");
         * its other mount points (/mnt/media_rw, /mnt/runtime) are the system's, which apps cannot read. Null when none.
         */
        internal fun mountIn(text: String, uuid: String): File? =
            text.lineSequence().mapNotNull { line -> line.split(' ').getOrNull(1) }
                .firstOrNull { it.startsWith(STORAGE) && it.substringAfterLast('/').equals(uuid, ignoreCase = true) }
                ?.let(::File)

        /** StorageVolume's hidden `getPath()`, which Android 9 and 10 allow apps; null when the firmware refuses it. */
        private fun hiddenPath(volume: StorageVolume): File? = try {
            (StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String)?.let(::File)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Android gave no path for ${volume.uuid ?: "the primary storage"}", e)
            null
        }
    }
}
