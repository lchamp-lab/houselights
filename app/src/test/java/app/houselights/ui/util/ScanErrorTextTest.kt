package app.houselights.ui.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.FailurePlace
import app.houselights.providers.local.Folder
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.diskFolder
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.toFolder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a folder's showcase in Réglages › Bibliothèque, and home without a film, say of a folder not found: gone from a
 * NAS that answered, moved or deleted there, its titles held; a share or an export missing, a place the NAS did not
 * say, or a folder of the player's own disks, to check for its spelling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class ScanErrorTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nas = nfsFolder("Films", host = "192.168.1.20").toFolder()

    private fun text(place: FailurePlace?, folder: Folder = nas) =
        scanErrorText(context, ScanError(ScanErrorKind.NOT_FOUND, place), folder)

    @Test
    fun aFolderGoneFromANasThatAnswersIsSaidGone() {
        assertEquals("Ce dossier n’est plus sur le NAS$NB: il a peut-être été déplacé ou supprimé.", text(FailurePlace.FOLDER))
    }

    @Test
    fun aShareMissingIsStillToCheckForItsSpelling() {
        assertEquals(MISSPELLED, text(FailurePlace.SHARE))
        assertEquals("a place the NAS did not say", MISSPELLED, text(null))
        assertEquals("never a disk's folder", MISSPELLED, text(FailurePlace.FOLDER, diskFolder("Films").toFolder()))
    }

    private companion object {
        const val NB = Typography.nbsp
        const val MISSPELLED = "Ce partage ou ce dossier n’existe pas sur le NAS. Vérifiez son orthographe."
    }
}
