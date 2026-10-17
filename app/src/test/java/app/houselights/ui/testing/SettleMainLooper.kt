package app.houselights.ui.testing

import android.os.Looper
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/**
 * Runs what a test left on the main looper before Robolectric clears it. Compose state written
 * outside a composition (a ViewModel's) is handed to Compose there once a Compose test has run in
 * the same sandbox: cleared unrun, Compose's dispatcher waits for it forever, and no Compose test
 * after it settles. Every Robolectric test that writes Compose state takes this rule.
 */
class SettleMainLooper : TestWatcher() {
    override fun finished(description: Description) {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
