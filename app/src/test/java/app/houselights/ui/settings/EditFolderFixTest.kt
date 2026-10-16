package app.houselights.ui.settings

import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.ui.onboarding.FolderField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A folder whose last scan failed: editing comes first when it can mend it, and opens on what to change. */
class EditFolderFixTest {
    @Test
    fun editingComesFirstForAnAddressAShareOrAPasswordChanged() {
        listOf(ScanErrorKind.UNREACHABLE, ScanErrorKind.NOT_FOUND, ScanErrorKind.LOGIN_FAILED).forEach { kind ->
            assertEquals(kind, editFixes(ScanError(kind)))
        }
    }

    @Test
    fun editingWaitsItsTurnForWhatItCannotMend() {
        listOf(ScanErrorKind.ACCESS_DENIED, ScanErrorKind.EMPTY, ScanErrorKind.OTHER).forEach { kind ->
            assertNull(kind.name, editFixes(ScanError(kind)))
        }
        assertNull(editFixes(null))
    }

    @Test
    fun theFormOpensOnTheFieldToChange() {
        assertEquals(FolderField.PASSWORD, FolderField.after(ScanErrorKind.LOGIN_FAILED))
        assertEquals(FolderField.HOST, FolderField.after(ScanErrorKind.UNREACHABLE))
        assertEquals(FolderField.HOST, FolderField.after(ScanErrorKind.NOT_FOUND))
        assertEquals(FolderField.FIRST, FolderField.after(null))
        assertEquals(FolderField.FIRST, FolderField.after(ScanErrorKind.ACCESS_DENIED))
    }
}
