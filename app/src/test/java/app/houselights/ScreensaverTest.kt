package app.houselights

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The scan goes on under Android's screensaver begun over the app, to its end, the app stopping under it; one begun over
 * another app is not the app's, the scan waiting as that app may play from the NAS again once woken.
 */
@RunWith(RobolectricTestRunner::class)
class ScreensaverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun said(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onlyAScreensaverBegunOverTheAppIsTheApps() {
        var onScreen = true
        val shows = screensaverShows(app) { onScreen }
        assertFalse("none until one starts", shows.value)

        said(Intent.ACTION_DREAMING_STARTED)
        assertTrue("begun over the app", shows.value)
        onScreen = false
        assertTrue("the app stopping under it", shows.value)
        said(Intent.ACTION_DREAMING_STOPPED)
        assertFalse("to its end", shows.value)

        said(Intent.ACTION_DREAMING_STARTED)
        assertFalse("begun over another app", shows.value)
        said(Intent.ACTION_DREAMING_STOPPED)
        assertFalse(shows.value)
    }
}
