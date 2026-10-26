package app.houselights.providers.local

import app.houselights.testing.mountFailure
import com.emc.ecs.nfsclient.mount.MountStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException

/**
 * A full path typed in the add-folder flow, split by the app into NAS, share or export, and folder. On Robolectric for
 * the failures' classification.
 */
@RunWith(RobolectricTestRunner::class)
class TypedPathsTest {
    private fun read(typed: String, nas: String? = null) = TypedPaths.read(typed, nas)

    @Test
    fun aPathIsReadInEachFormItIsWrittenIn() {
        assertEquals(TypedPath(null, null, listOf("volume1", "video", "Films")), read("/volume1/video/Films"))
        assertEquals(TypedPath(null, null, listOf("video", "Films")), read("video/Films"))
        assertEquals(TypedPath("NAS", Protocol.SMB, listOf("video", "Films")), read("""\\NAS\video\Films"""))
        assertEquals(TypedPath("NAS", Protocol.SMB, listOf("video", "Films")), read("//NAS/video/Films"))
        assertEquals(TypedPath("nas", Protocol.SMB, listOf("video", "Films")), read("smb://nas/video/Films"))
        assertEquals(TypedPath("nas", Protocol.NFS, listOf("volume1", "video", "Films")), read("nfs://nas/volume1/video/Films"))
        assertEquals(TypedPath("192.168.1.20", Protocol.NFS, listOf("volume1", "video")), read("192.168.1.20:/volume1/video"))
    }

    /** Spaces around, doubled or trailing separators, "." and either slash; an account or a port in a URL is dropped. */
    @Test
    fun aPathIsReadAsMeantRatherThanAsTyped() {
        assertEquals(TypedPath(null, null, listOf("volume1", "video", "Films")), read("  /volume1//video/./Films/ "))
        assertEquals(TypedPath(null, null, listOf("video", "Films")), read("""video\Films"""))
        assertEquals(TypedPath("NAS", Protocol.SMB, listOf("Video")), read("SMB://kodi@NAS:445/Video/"))
        assertEquals(TypedPath("nas", Protocol.SMB, emptyList()), read("""\\nas\"""))
        assertEquals(TypedPath(null, null, listOf("C:", "Videos")), read("""C:\Videos"""))
        assertNull(read("   "))
    }

    /**
     * A path typed for the NAS chosen: the name after "\\", "//" or "smb://" is dropped as its own when it names it, as
     * written or by its first name, an address whole, and "\\NAS" alone names no share; as the folder's form reads it
     * (FolderFormTest.aShareTypedAsUncDropsItsHost).
     */
    @Test
    fun aNameBeforeThePathNamingTheNasChosenIsDropped() = runTest {
        val typedForIt = listOf(
            """\\NAS\video\Films2""",
            """\\NAS\video\Films2\""",
            "smb://nas.local/video/Films2",
            "SMB://NAS.LOCAL/video/Films2",
        )
        typedForIt.forEach { typed -> assertEquals(typed, listOf("video", "Films2"), read(typed, nas = "nas.local")?.names) }
        assertEquals(TypedPath("NAS", Protocol.SMB, listOf("video")), read("""\\NAS\video""", nas = "nas.local"))
        assertEquals(listOf("video", "Films2"), read("""\\192.168.1.10\video\Films2""", nas = "192.168.1.10")?.names)
        val nothingMounted: suspend (String) -> Unit = { error("SMB mounts nothing") }
        listOf("""\\NAS\""", """\\NAS""").forEach { typed ->
            val names = read(typed, nas = "nas.local")?.names.orEmpty()
            assertNull("the address alone names no share", TypedPaths.locate(names, Protocol.SMB, null, nothingMounted))
        }
    }

    /**
     * A name after "\\" or "//" that is not the NAS chosen is the share it names, typed after two slashes, rather than
     * giving way to the next name; another NAS's path is looked for as typed, never as the same path on this one
     * (FolderFormTest.aNameAfterTwoSlashesThatIsNotTheNasIsTheShare). Without a NAS chosen, that name is the NAS typed.
     */
    @Test
    fun aNameAfterTwoSlashesThatIsNotTheNasChosenIsTheShare() = runTest {
        val nothingMounted: suspend (String) -> Unit = { error("SMB mounts nothing") }
        listOf("""\\video\Films""", "//video/Films", """\\video\Films\""", "smb://video/Films").forEach { typed ->
            val path = read(typed, nas = "192.168.1.10")
            assertEquals(typed, TypedPath(null, Protocol.SMB, listOf("video", "Films")), path)
            assertEquals(typed, SharePath("video", "Films"), TypedPaths.locate(path?.names.orEmpty(), Protocol.SMB, null, nothingMounted))
        }
        assertEquals("another NAS's share", listOf("192.168.1.20", "video"), read("""\\192.168.1.20\video""", nas = "192.168.1.10")?.names)
        val longer = read("//192.168.1.100/video", nas = "192.168.1.10")
        assertEquals("an address is never another's first name", listOf("192.168.1.100", "video"), longer?.names)
        assertEquals("nfs:// names its NAS by its form", "other", read("nfs://other/volume1/video", nas = "192.168.1.10")?.host)
        assertEquals(TypedPath("video", Protocol.SMB, listOf("Films")), read("""\\video\Films"""))
    }

    private val exports = listOf("/volume1/video", "/volume1/video/films", "/volume1/photo")

    /** The longest export starting the path is the folder's, as NFS compares names: by their case. */
    @Test
    fun anNfsPathIsSplitAtTheLongestExportStartingIt() {
        fun split(vararg names: String, known: List<String> = exports) = TypedPaths.split(names.toList(), known, Protocol.NFS)
        assertEquals(SharePath("volume1/video/films", "4K"), split("volume1", "video", "films", "4K"))
        assertEquals(SharePath("volume1/video", "Séries"), split("volume1", "video", "Séries"))
        assertEquals(SharePath("volume1/photo", ""), split("volume1", "photo"))
        assertNull(split("volume1", "Video", "Films"))
        assertEquals("the server's root", SharePath("", "media/Films"), split("media", "Films", known = listOf("/")))
    }

    /** An SMB share starts the path, any case, as the NAS spells it; without the list, it is the first name. */
    @Test
    fun anSmbPathIsSplitAtItsShare() = runTest {
        val shares = listOf("Video", "photo")
        assertEquals(SharePath("Video", "Films/4K"), TypedPaths.split(listOf("video", "Films", "4K"), shares, Protocol.SMB))
        assertNull(TypedPaths.split(listOf("videos", "Films"), shares, Protocol.SMB))
        val nothingMounted: suspend (String) -> Unit = { error("SMB mounts nothing") }
        assertEquals(SharePath("video", "Films"), TypedPaths.locate(listOf("video", "Films"), Protocol.SMB, null, nothingMounted))
        assertNull(TypedPaths.locate(emptyList(), Protocol.SMB, null, nothingMounted))
    }

    /** Without the exports' list, each start of the path is mounted from the longest, until one is. */
    @Test
    fun anNfsPathWithoutTheListIsSplitAtTheLongestStartThatMounts() = runTest {
        val asked = mutableListOf<String>()
        val mounts: suspend (String) -> Unit = { export ->
            asked += export
            if (export != "/volume1/video") throw mountFailure(MountStatus.MNT3ERR_NOENT)
        }
        val place = TypedPaths.locate(listOf("volume1", "video", "Films", "4K"), Protocol.NFS, null, mounts)
        assertEquals(SharePath("volume1/video", "Films/4K"), place)
        assertEquals(listOf("/volume1/video/Films/4K", "/volume1/video/Films", "/volume1/video"), asked)
    }

    /**
     * None mounting, the longest start's failure says what is wrong: a name misspelled is not found, though "/volume1"
     * itself, there but not exported, is refused; an export not open to this player is refused.
     */
    @Test
    fun noStartMountingFailsAsTheLongestDid() = runTest {
        val misspelled: suspend (String) -> Unit = { export ->
            throw mountFailure(if (export == "/volume1") MountStatus.MNT3ERR_ACCES else MountStatus.MNT3ERR_NOENT)
        }
        val missing = runCatching { TypedPaths.mounting(listOf("volume1", "vidoe", "Films"), misspelled) }.exceptionOrNull()
        assertEquals(ScanErrorKind.NOT_FOUND, missing?.let { ScanErrors.classify(it).kind })

        val closed: suspend (String) -> Unit = { throw mountFailure(MountStatus.MNT3ERR_ACCES) }
        val refused = runCatching { TypedPaths.mounting(listOf("volume1", "video"), closed) }.exceptionOrNull()
        assertEquals(ScanErrorKind.ACCESS_DENIED, refused?.let { ScanErrors.classify(it).kind })
    }

    /** A NAS out of reach would make each try wait it out: the first ends them. Deep paths try six starts at most. */
    @Test
    fun theTriesStopAtANasOutOfReachAndAreBounded() = runTest {
        val asked = mutableListOf<String>()
        val silent: suspend (String) -> Unit = { export ->
            asked += export
            throw SocketTimeoutException("timed out")
        }
        val failure = runCatching { TypedPaths.mounting(listOf("volume1", "video", "Films"), silent) }.exceptionOrNull()
        assertEquals(ScanErrorKind.UNREACHABLE, failure?.let { ScanErrors.classify(it).kind })
        assertEquals(listOf("/volume1/video/Films"), asked)

        asked.clear()
        val missing: suspend (String) -> Unit = { export ->
            asked += export
            throw mountFailure(MountStatus.MNT3ERR_NOENT)
        }
        runCatching { TypedPaths.mounting((1..9).map { "level$it" }, missing) }
        assertEquals(TypedPaths.MAX_TRIES, asked.size)
        assertEquals("/level1/level2/level3/level4/level5/level6/level7/level8/level9", asked.first())
    }

    /**
     * A path no export starts suggests what it meant (#59): Synology's shared folders under /volume1 ("media" for
     * "/volume1/media"), or an export spelled otherwise, case and accents aside.
     */
    @Test
    fun aPathNoExportStartsSuggestsTheExportItMeant() {
        assertEquals(listOf(SharePath("volume1/video", "Films")), TypedPaths.meant(listOf("video", "Films"), exports))
        assertEquals(listOf(SharePath("volume1/video", "")), TypedPaths.meant(listOf("Video"), exports))
        assertEquals(
            listOf(SharePath("volume1/video", "Films/4K"), SharePath("volume1/video/films", "4K")),
            TypedPaths.meant(listOf("Volume1", "Vidéo", "Films", "4K"), exports),
        )
        assertEquals(emptyList<SharePath>(), TypedPaths.meant(listOf("music"), exports))
        assertEquals(emptyList<SharePath>(), TypedPaths.meant(emptyList(), exports))
    }
}
