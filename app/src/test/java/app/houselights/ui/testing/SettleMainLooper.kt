package app.houselights.ui.testing

import android.os.Looper
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/**
 * Runs what a test left on the main looper before Robolectric clears it, or Compose's dispatcher waits forever
 * and later Compose tests in the sandbox never settle; for every test that writes Compose state.
 */
class SettleMainLooper : TestWatcher() {
    override fun finished(description: Description) {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
