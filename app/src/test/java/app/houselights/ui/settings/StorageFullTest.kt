package app.houselights.ui.settings

import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings, Storage says when the last scan stopped for want of space, whichever folder it was on. */
class StorageFullTest {
    @Test
    fun aScanStoppedForWantOfSpaceIsSaid() {
        assertTrue(ScanState(errors = mapOf(2L to ScanError(ScanErrorKind.STORAGE_FULL))).storageFull)
    }

    @Test
    fun otherFailuresAreTheFolders() {
        assertFalse(ScanState(errors = mapOf(1L to ScanError(ScanErrorKind.UNREACHABLE))).storageFull)
        assertFalse(ScanState().storageFull)
    }
}
