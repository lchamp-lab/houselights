package app.houselights.providers.local

import java.io.File

/** The player's disks for tests: folders standing for disks, each mounted from [plug] until [unplug]. */
class TestDisks : Volumes {
    private val plugged = linkedMapOf<String, Volume>()

    /** Disk [id] mounted at [mount], as Android would list it; plugged again, at its new mount. */
    fun plug(id: String, mount: File, name: String = id) {
        plugged[id] = Volume(id, name, internal = id == Volume.INTERNAL, mount)
    }

    fun unplug(id: String) {
        plugged.remove(id)
    }

    override fun mounted(): List<Volume> = plugged.values.toList()
}

/** A USB disk's id, as Android names a FAT or exFAT disk by its UUID. */
const val USB_DISK = "1234-ABCD"

/** A folder at [path] on the player's disk [disk], as a folder of the player's own disks is stored ([Protocol.LOCAL]). */
fun diskFolder(
    name: String = "Films",
    kind: SourceKind = SourceKind.MOVIES,
    disk: String = USB_DISK,
    path: String = name,
) = SourceEntity(
    name = name, kind = kind, protocol = Protocol.LOCAL, host = disk, share = "", path = path, username = "", password = Secret(""),
)

/** Writes [bytes] at [path] under this folder, its folders made, dated [modified] when given; returns the file. */
fun File.put(path: String, bytes: ByteArray = ByteArray(FILE_BYTES), modified: Long? = null): File =
    resolve(path).apply {
        parentFile?.mkdirs()
        writeBytes(bytes)
        modified?.let { check(setLastModified(it)) { "$path not dated" } }
    }

private const val FILE_BYTES = 16
