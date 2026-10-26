package app.houselights.update

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.FileNotFoundException

/** Android's installer as the updater drives it: one session for the app, committed once written, never left behind. */
@RunWith(RobolectricTestRunner::class)
class SessionInstallerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val sessions: PackageInstaller get() = context.packageManager.packageInstaller
    private val installer = SessionInstaller(context, Status::class.java)

    /** Where Android would answer. */
    class Status : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Unit
    }

    @Test
    fun theSessionIsCommittedForThisAppOnceWritten() = runBlocking {
        val apk = folder.newFile("Houselights-0.4.1.apk").apply { writeBytes(ByteArray(100_000) { it.toByte() }) }
        var before: Int? = null
        val id = installer.install(apk) { session ->
            before = session
            assertTrue("Written before the commit", installer.holds(session))
        }
        assertEquals(id, before)
        assertEquals(context.packageName, sessions.getSessionInfo(id)?.appPackageName)
        assertTrue(installer.holds(id))
    }

    @Test
    fun aFileThatCannotBeWrittenLeavesNoSession() {
        val missing = folder.root.resolve("Houselights-0.4.1.apk")
        assertThrows(FileNotFoundException::class.java) { runBlocking { installer.install(missing) { } } }
        assertTrue(sessions.mySessions.isEmpty())
    }

    @Test
    fun leftoverSessionsAreAbandonedAtStart() {
        val left = sessions.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        assertTrue(installer.holds(left))
        installer.abandonAll()
        assertFalse(installer.holds(left))
        assertTrue(sessions.mySessions.isEmpty())
    }

    @Test
    fun androidsConfirmationOpensInItsOwnTask() {
        assertTrue(installer.confirm(Intent("android.content.pm.action.CONFIRM_INSTALL")))
        val started = shadowOf(context).nextStartedActivity
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
