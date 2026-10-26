package app.houselights.providers.local

import android.app.Application
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

/**
 * The player's disks as a folder names them: the storage as "internal", a USB disk by its UUID, with Android's name for
 * each and where it is mounted now. On the Zidoo's Android 9 that path is hidden, read so, then from /proc/mounts.
 */
@RunWith(RobolectricTestRunner::class)
class VolumesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun volume(
        id: String,
        path: String,
        name: String,
        uuid: String? = null,
        primary: Boolean = false,
        state: String = Environment.MEDIA_MOUNTED,
    ): StorageVolume = StorageVolumeBuilder(id, File(path), name, Process.myUserHandle(), state)
        .setIsPrimary(primary).setIsEmulated(primary).setIsRemovable(!primary).setFsUuid(uuid).build()

    /** The Zidoo's storage, a USB disk mounted, one still being checked, one ejected and one read-only. */
    private fun plugged() {
        val storage = shadowOf(context.getSystemService(StorageManager::class.java))
        storage.addStorageVolume(volume("emulated", "/storage/emulated/0", "Stockage partagé interne", primary = true))
        storage.addStorageVolume(volume("public:8,1", "/storage/1234-ABCD", "Samsung T7", uuid = "1234-ABCD"))
        storage.addStorageVolume(volume("public:8,17", "/storage/5678-EF01", "Clé", "5678-EF01", state = Environment.MEDIA_CHECKING))
        storage.addStorageVolume(volume("public:8,33", "/storage/9ABC-DEF0", "Clé", "9ABC-DEF0", state = Environment.MEDIA_UNMOUNTED))
        val readOnly = Environment.MEDIA_MOUNTED_READ_ONLY
        storage.addStorageVolume(volume("public:8,49", "/storage/0FED-CBA9", "Archives", "0FED-CBA9", state = readOnly))
    }

    private val storage = Volume(Volume.INTERNAL, "Stockage partagé interne", internal = true, File("/storage/emulated/0"))
    private val samsung = Volume("1234-ABCD", "Samsung T7", internal = false, File("/storage/1234-ABCD"))
    private val archives = Volume("0FED-CBA9", "Archives", internal = false, File("/storage/0FED-CBA9"))

    /** Android 9: StorageVolume's hidden path, which the Zidoo's Android allows apps. */
    @Test
    fun theDisksMountedAreNamedAndFoundWhereAndroidMountsThem() {
        plugged()
        val volumes = AndroidVolumes(context)

        assertEquals(listOf(storage, samsung, archives), volumes.mounted())
        assertEquals("its UUID whatever its case", File("/storage/1234-ABCD"), volumes.mountOf("1234-abcd"))
        assertEquals(File("/storage/emulated/0"), volumes.mountOf(Volume.INTERNAL))
        assertNull("still being checked", volumes.mountOf("5678-EF01"))
        assertNull("ejected", volumes.mountOf("9ABC-DEF0"))
        assertNull("never plugged", volumes.mountOf("1111-2222"))
    }

    /** A firmware refusing the hidden path: a disk's view under /storage in /proc/mounts, the storage's own path. */
    @Test
    fun withoutTheHiddenPathADiskIsFoundInProcMounts() {
        plugged()
        val volumes = AndroidVolumes(context, hidden = { null }) { ANDROID_9_MOUNTS }

        @Suppress("DEPRECATION") // What Android 9 gives the primary storage.
        val own = Environment.getExternalStorageDirectory()
        assertEquals(listOf(storage.copy(mount = own), samsung), volumes.mounted())
    }

    /** Android 11 and later say where a volume is mounted. */
    @Test
    @Config(sdk = [30])
    fun androidElevenSaysWhereADiskIsMounted() {
        plugged()
        assertEquals(listOf(storage, samsung, archives), AndroidVolumes(context, hidden = { error("Not on Android 11") }).mounted())
    }

    /** /proc/mounts lists a disk at each of its mounts: apps read the one under /storage, the others are the system's. */
    @Test
    fun procMountsGivesTheMountAppsRead() {
        assertEquals(File("/storage/1234-ABCD"), AndroidVolumes.mountIn(ANDROID_9_MOUNTS, "1234-abcd"))
        assertNull("only under /mnt", AndroidVolumes.mountIn(ANDROID_9_MOUNTS, "AAAA-BBBB"))
        assertNull(AndroidVolumes.mountIn(ANDROID_9_MOUNTS, "0FED-CBA9"))
    }

    private companion object {
        /** An Android 9 player's /proc/mounts with a USB disk (sdcardfs), another disk under /mnt only. */
        val ANDROID_9_MOUNTS = """
            /dev/root / ext4 ro,seclabel,relatime,data=ordered 0 0
            /data/media /mnt/runtime/default/emulated sdcardfs rw,nosuid,nodev,noexec,noatime,fsuid=1023,fsgid=1023,gid=1015,multiuser,mask=6,derive_gid 0 0
            /data/media /storage/emulated sdcardfs rw,nosuid,nodev,noexec,noatime,fsuid=1023,fsgid=1023,gid=1015,multiuser,mask=6,derive_gid 0 0
            /dev/block/vold/public:8,1 /mnt/media_rw/1234-ABCD vfat rw,dirsync,nosuid,nodev,noexec,noatime,uid=1023,gid=1023,fmask=0007,dmask=0007 0 0
            /mnt/media_rw/1234-ABCD /mnt/runtime/default/1234-ABCD sdcardfs rw,nosuid,nodev,noexec,noatime,fsuid=1023,fsgid=1023,gid=1015,mask=6 0 0
            /mnt/media_rw/1234-ABCD /storage/1234-ABCD sdcardfs rw,nosuid,nodev,noexec,noatime,fsuid=1023,fsgid=1023,gid=1015,mask=6 0 0
            /mnt/media_rw/1234-ABCD /mnt/runtime/read/1234-ABCD sdcardfs rw,nosuid,nodev,noexec,noatime,fsuid=1023,fsgid=1023,gid=9997,mask=18 0 0
            /dev/block/vold/public:8,65 /mnt/media_rw/AAAA-BBBB exfat rw,dirsync,nosuid,nodev,noexec,noatime 0 0
        """.trimIndent()
    }
}
