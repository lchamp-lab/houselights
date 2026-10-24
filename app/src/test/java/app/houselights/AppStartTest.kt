package app.houselights

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.houselights.ui.design.components.CutPoster
import app.houselights.ui.design.components.CutPosters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app starts as Android starts it, the only test that does: every part of its container is wired, the first
 * launch records the language the library is read in, and the system short of memory takes the doors' cut posters.
 * One test, as the settings' DataStore is one per process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HouselightsApp::class)
class AppStartTest {
    @Test
    fun theAppStartsWiredInTheDevicesLanguageAndRecordsTheLanguageOfTheLibrary() {
        val app = ApplicationProvider.getApplicationContext<HouselightsApp>()
        val container = app.container
        assertSame(app.language, container.language)
        assertNull("no language chosen", app.language.chosen.value)
        assertSame(app.language.resources, app.resources)
        assertEquals(container.deviceLocale.value, container.metadataLocale())
        // The settings are read and written on DataStore's own threads, the collectors run on the main looper.
        val until = System.nanoTime() + WAIT_NS
        while (container.settings.state.value.libraryLanguage == null) {
            check(System.nanoTime() < until) { "No language recorded after ${WAIT_NS / 1_000_000_000} s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertEquals(container.metadataLocale().toLanguageTag(), container.settings.state.value.libraryLanguage)

        val cut = listOf("door")
        container.cutPosters.keep(cut, CutPosters(listOf(CutPoster(Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888), Color.Red))))
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertNull("in the background, short of memory: the cut posters go", container.cutPosters.of(cut))
    }

    private companion object {
        const val WAIT_NS = 10_000_000_000L
        const val POLL_MS = 10L
    }
}
