package app.houselights.providers.local

import android.Manifest
import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What reading the player's own disks needs: on the Zidoo's Android 9 (and 10) the storage permission, asked in
 * Android's window; from Android 11, all-files access, which only Android's settings grant.
 */
@RunWith(RobolectricTestRunner::class)
class DiskPermissionTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private val app = Uri.parse("package:${context.packageName}")

    @Test
    fun onAndroidNineTheStoragePermissionIsAsked() {
        val permission = DiskPermission(context)
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), permission.asked)
        assertEquals(DiskAccess.TO_ASK, permission.access())
        assertEquals("refused for good, only the settings give it", DiskAccess.IN_SETTINGS, permission.access(refusedForGood = true))

        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(DiskAccess.GRANTED, permission.access())
        assertEquals(DiskAccess.GRANTED, permission.access(refusedForGood = true))
    }

    @Test
    fun fromAndroidElevenAllFilesAccessIsGrantedInTheSettings() {
        var granted = false
        val permission = DiskPermission(context, sdk = Build.VERSION_CODES.R) { granted }
        assertArrayEquals("nothing Android's window can give", emptyArray<String>(), permission.asked)
        assertEquals(DiskAccess.IN_SETTINGS, permission.access())

        granted = true
        assertEquals(DiskAccess.GRANTED, permission.access())
    }

    /** The app's own all-files screen first, from Android 11; then the app's details, where its permissions are. */
    @Test
    fun theSettingsOpenWhereTheAccessIsGranted() {
        val eleven = DiskPermission(context, sdk = Build.VERSION_CODES.R) { false }.settings()
        assertEquals(
            listOf(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            eleven.map { it.action },
        )
        assertEquals(listOf(app, app), eleven.map { it.data })
        val nine = DiskPermission(context).settings()
        assertEquals("Android 9 has no all-files screen", listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), nine.map { it.action })
        assertEquals(listOf(app), nine.map { it.data })
    }
}
