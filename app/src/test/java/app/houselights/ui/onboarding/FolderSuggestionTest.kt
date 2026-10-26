package app.houselights.ui.onboarding

import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the NAS proposes for a folder's path it lacks (#59), read from the board's network (« Ajouter un dossier »'s
 * fixture): an export typed short, a share or a folder within one letter of one it has.
 */
class FolderSuggestionTest {
    private val network = FakeFlowNetwork()

    private val nfs = SourceEntity(
        name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = FakeFlowNetwork.DS920, share = "media",
        path = "Films", username = "", password = Secret(""),
    )

    private val smb = nfs.copy(
        protocol = Protocol.SMB, share = "vido", path = "Films", username = FakeFlowNetwork.USER,
        password = Secret(FakeFlowNetwork.PASSWORD),
    )

    @Test
    fun anExportTypedShortIsTheOneItEnds() = runBlocking {
        val proposed = suggestionFor(nfs, FolderTrouble.ExportMissing("/media"), network)
        assertEquals(FolderSuggestion("volume1/media", "Films"), proposed)
        assertEquals("/volume1/media/Films", proposed?.shown())
        assertNull("nothing near", suggestionFor(nfs.copy(share = "photos2"), FolderTrouble.ExportMissing("/photos2"), network))
    }

    @Test
    fun aShareWithinALetterIsTheOneTheNasHas() = runBlocking {
        assertEquals(FolderSuggestion("video", "Films"), suggestionFor(smb, FolderTrouble.ShareMissing("vido"), network))
    }

    /** Read a level at a time from the shared folder's root: the first name a level lacks is the one proposed for. */
    @Test
    fun aFolderWithinALetterIsTheOneItsLevelHas() = runBlocking {
        val video = nfs.copy(share = "volume1/video")
        fun missing(path: String) = FolderTrouble.SubfolderMissing("/volume1/video", path, nfs = true)
        assertEquals(
            FolderSuggestion("volume1/video", "Spectacles/Extra"),
            suggestionFor(video.copy(path = "Spectacle/Extra"), missing("Spectacle/Extra"), network),
        )
        assertEquals(
            FolderSuggestion("volume1/video", "Films/Heat (1995)"),
            suggestionFor(video.copy(path = "Films/Heat (1996)"), missing("Films/Heat (1996)"), network),
        )
        val typed = smb.copy(share = "video", path = "films/Heat (1995")
        assertEquals(
            "SMB, case aside",
            FolderSuggestion("video", "films/Heat (1995)"),
            suggestionFor(typed, FolderTrouble.SubfolderMissing("video", "films/Heat (1995", nfs = false), network),
        )
        assertNull(suggestionFor(video.copy(path = "Documentaires"), missing("Documentaires"), network))
    }

    @Test
    fun aRefusalHasNoSuggestion() = runBlocking {
        assertNull(suggestionFor(nfs, FolderTrouble.PlayerRefused("/media", "192.168.1.42"), network))
    }
}
