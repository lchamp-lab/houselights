package app.houselights.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The key versions made on this install, kept across launches in its preferences. */
@RunWith(RobolectricTestRunner::class)
class SavedKeyRecordTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("secret_keys", Context.MODE_PRIVATE)

    @Test
    fun aFreshInstallStartsEmptyAndKeepsWhatItMakes() {
        val record = SavedKeyRecord(prefs) { false }
        assertEquals(emptySet<Int>(), record.versions())
        record.add(1)
        record.add(1)
        // The next launch reads it back, though the app has been updated since.
        assertEquals(setOf(1), SavedKeyRecord(prefs) { true }.versions())
    }

    /** Installed over a version from before the record, which may have made the first key: the record starts with it. */
    @Test
    fun anUpdateFromBeforeTheRecordStartsWithTheFirstKey() {
        val record = SavedKeyRecord(prefs) { true }
        assertEquals(setOf(Secrets.FIRST_KEY), record.versions())
        record.add(2)
        assertEquals(setOf(1, 2), SavedKeyRecord(prefs) { false }.versions())
    }
}
