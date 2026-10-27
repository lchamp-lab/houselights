package app.houselights.update

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Android must let the app install apps: the screen that allows it, the way back, and the words where there is none. */
@RunWith(RobolectricTestRunner::class)
class InstallPermissionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val packages = shadowOf(context.packageManager)
    private val installer = SessionInstaller(context, BroadcastReceiver::class.java)

    private val settings = "com.android.settings"

    init {
        packages.installPackage(
            PackageInfo().apply {
                packageName = settings
                applicationInfo = ApplicationInfo().apply { packageName = settings }
            },
        )
    }

    /** The Settings app's screen answering [action], at a package's address for [scheme]. */
    private fun screen(action: String, scheme: String? = null) {
        val component = ComponentName(settings, "$settings.${action.substringAfterLast('.')}")
        packages.addActivityIfNotPresent(component).exported = true
        packages.addIntentFilterForActivity(
            component,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                scheme?.let(::addDataScheme)
            },
        )
    }

    @Test
    fun theAppsOwnScreenComesFirst() {
        screen(Settings.ACTION_SETTINGS)
        screen(Settings.ACTION_SECURITY_SETTINGS)
        screen(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package")
        assertEquals(PermissionScreen.APP, installer.permissionScreen())
        assertTrue(installer.open(PermissionScreen.APP))
        val opened = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened.action)
        assertEquals(Uri.parse("package:${context.packageName}"), opened.data)
    }

    @Test
    fun thenSecurityThenSettings() {
        screen(Settings.ACTION_SETTINGS)
        assertEquals(PermissionScreen.SETTINGS, installer.permissionScreen())
        screen(Settings.ACTION_SECURITY_SETTINGS)
        assertEquals(PermissionScreen.SECURITY, installer.permissionScreen())
    }

    @Test
    fun withNoScreenItIsSaidInWords() = runTest {
        assertEquals(PermissionScreen.NONE, installer.permissionScreen())
        assertFalse(installer.open(PermissionScreen.NONE))
        val f = UpdaterFixture(this, folder.root)
        f.installer.allowed = false
        f.installer.screen = PermissionScreen.NONE
        f.offered()
        f.updater.update()
        runCurrent()
        assertEquals(UpdateStep.NeedsPermission(PermissionScreen.NONE), f.updater.state.value.step)
        assertTrue((f.updater.state.value.step as UpdateStep.NeedsPermission).stuck)
        f.updater.allow()
        runCurrent()
        assertTrue("Nothing to open", f.installer.opened.isEmpty())
        assertEquals(0, f.downloads.fetched)
    }

    @Test
    fun aPermissionGrantedMeanwhileContinuesOnComingBack() = runTest {
        val f = UpdaterFixture(this, folder.root)
        f.installer.allowed = false
        f.offered()
        f.updater.update()
        runCurrent()
        val asked = f.updater.state.value.step as UpdateStep.NeedsPermission
        assertFalse(asked.stuck)
        assertEquals(0, f.downloads.fetched)
        f.updater.allow()
        runCurrent()
        assertEquals(listOf(PermissionScreen.APP), f.installer.opened)
        assertEquals(Trip(1010, START), f.store.read().trip)
        f.installer.allowed = true
        f.updater.returned()
        runCurrent()
        assertEquals(1, f.downloads.fetched)
        assertEquals(UpdateStep.Installing(), f.updater.state.value.step)
        assertNull(f.store.read().trip)
    }

    @Test
    fun comingBackStillRefusedSaysTheOtherWays() = runTest {
        val f = UpdaterFixture(this, folder.root)
        f.installer.allowed = false
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.allow()
        runCurrent()
        f.updater.returned()
        runCurrent()
        assertEquals(UpdateStep.NeedsPermission(PermissionScreen.APP, refused = true), f.updater.state.value.step)
        assertTrue("Never installed anyway", f.installer.installed.isEmpty())
        assertNull(f.store.read().trip)
    }

    /** Allowed another way (adb's line from a computer, Android's settings): the sheet sees it as soon as it is asked. */
    @Test
    fun aPermissionGivenAnotherWayIsSeenAtOnce() = runTest {
        val f = UpdaterFixture(this, folder.root)
        f.installer.allowed = false
        f.installer.screen = PermissionScreen.NONE
        f.offered()
        f.updater.update()
        runCurrent()
        assertTrue((f.updater.state.value.step as UpdateStep.NeedsPermission).stuck)
        f.installer.allowed = true
        // « Rechercher maintenant », the stuck sheet's row.
        f.updater.checkNow()
        runCurrent()
        assertEquals(UpdateStep.Idle, f.updater.state.value.step)
        f.updater.update()
        runCurrent()
        assertEquals(1, f.downloads.fetched)

        val g = UpdaterFixture(this, folder.newFolder())
        g.installer.allowed = false
        g.offered()
        g.updater.update()
        runCurrent()
        g.updater.closed()
        g.installer.allowed = true
        g.updater.opened()
        assertEquals("The sheet opened again", UpdateStep.Idle, g.updater.state.value.step)

        val h = UpdaterFixture(this, folder.newFolder())
        h.installer.allowed = false
        h.offered()
        h.updater.update()
        runCurrent()
        h.installer.allowed = true
        h.updater.returned()
        assertEquals("The app back in front", UpdateStep.Idle, h.updater.state.value.step)
    }

    @Test
    fun aScreenThatDoesNotOpenSaysTheOtherWays() = runTest {
        val f = UpdaterFixture(this, folder.root)
        f.installer.allowed = false
        f.installer.opens = false
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.allow()
        runCurrent()
        assertTrue((f.updater.state.value.step as UpdateStep.NeedsPermission).stuck)
    }

    /** Android may close the app while the user is in its settings: the sheet opens again on home where it stood. */
    @Test
    fun theTripToAndroidsSettingsSurvivesTheProcess() = runTest {
        val f = UpdaterFixture(this, folder.root)
        f.installer.allowed = false
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.allow()
        runCurrent()
        f.updater.shownOnHome(1010)
        runCurrent()
        f.start()
        val back = f.updater.state.value
        assertEquals(UpdateStep.NeedsPermission(PermissionScreen.APP, refused = true), back.step)
        assertTrue("Even after « Plus tard »", back.backOnHome)
        assertTrue("Even with the check off", back.offeredOnHome)
        assertNull("Settled once", f.store.read().trip)

        val g = UpdaterFixture(this, folder.newFolder())
        g.installer.allowed = false
        g.offered()
        g.updater.update()
        runCurrent()
        g.updater.allow()
        runCurrent()
        g.installer.allowed = true
        g.start()
        assertEquals(UpdateStep.Idle, g.updater.state.value.step)
        assertEquals("v0.4.1", g.updater.state.value.offer?.tag)
        assertTrue(g.updater.state.value.backOnHome)
    }
}
