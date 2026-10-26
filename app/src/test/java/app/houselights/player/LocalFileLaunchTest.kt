package app.houselights.player

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.FileUriExposedException
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackPlan
import app.houselights.core.escapeUriPath
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.Z1000_PRO_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_KEY
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * A film of the player's own disks reaches the player as NFS and SMB films do, but never as a file:// address, which
 * Android refuses to hand another app (FileUriExposedException, the app targeting Android 15): the Zidoo player gets
 * its path, any other player a content:// address it may read for that file alone.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileLaunchTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apps = FakeApps(context)

    private fun TestScope.manager(device: DeviceIdentity) =
        PlaybackManager(context, backgroundScope, status = { PlayerAnswer.NoAnswer }, players = ZidooPlayer(context) { device }) { null }

    /** A film whose name holds what would end a URI or read as its scheme, on a disk mounted in the temporary folder. */
    private fun film(): File = temp.root.resolve("1234-ABCD/Films/Mission: Impossible # 100%?.mkv").apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(16))
    }

    /** As LocalProvider.fileUri writes a disk's file: "#", "?" and "%" escaped, spaces and accents as they are. */
    private fun plan(file: File) = PlaybackPlan.Launch(
        uri = "file://" + escapeUriPath(file.path), container = "mkv", title = "Mission: Impossible", backdropUrl = null, logoUrl = null,
        startMs = 0, reporter = null, resumeKey = "m1",
    )

    /** What Android checks as an intent leaves the app, death on a file:// address as for an app targeting Android 7 or later. */
    private fun leave(intent: Intent) {
        val before = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectFileUriExposure().penaltyDeathOnFileUriExposure().build())
        try {
            Intent::class.java.getMethod("prepareToLeaveProcess", Boolean::class.javaPrimitiveType).invoke(intent, true)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } finally {
            StrictMode.setVmPolicy(before)
        }
    }

    /** What this test holds the hand-off against: Android stops a file:// address. */
    @Test
    fun androidStopsAFileAddressLeavingTheApp() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(film()), "video/*")
        assertThrows(FileUriExposedException::class.java) { leave(intent) }
    }

    /** The Zidoo player opens the path, as it opens a share's address: escaped as one, read back whole, with no scheme. */
    @Test
    fun theZidooPlayerGetsTheFilesPath() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, certificate = ZIDOO_KEY, schemes = listOf("http", "file", "content"))
        val film = film()
        val intent = manager(Z1000_PRO_BUILD).intentFor(plan(film))

        assertEquals(MOVIE_ACTIVITY, intent.component?.className)
        assertNull("no scheme", intent.data?.scheme)
        assertEquals(escapeUriPath(film.path), intent.dataString)
        assertEquals(film.path, intent.data?.path)
        assertEquals("video/mkv", intent.type)
        assertTrue(intent.getBooleanExtra("return_result", false))
        leave(intent)
    }

    /** Another player reads the file through the app's provider, for this file alone, as Android hands files between apps. */
    @Test
    fun anotherPlayerGetsAContentAddressForTheFileAlone() = runTest {
        val film = film()
        val intent = manager(ZIDOO_BUILD).intentFor(plan(film))

        assertNull(intent.component)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.files", intent.data?.authority)
        assertEquals(film.name, intent.data?.lastPathSegment)
        assertEquals("video/*", intent.type)
        assertTrue("its reading granted", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("Mission: Impossible", intent.getStringExtra("title"))
        leave(intent)
    }
}
