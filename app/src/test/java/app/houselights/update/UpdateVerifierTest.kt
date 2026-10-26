package app.houselights.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import app.houselights.core.SigningCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Only the project's release, newer than the one installed, is handed to Android. */
@RunWith(RobolectricTestRunner::class)
class UpdateVerifierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val offered = offer()
    private val installed = 946L

    private fun facts(
        packageName: String = UpdaterAvailability.RELEASE_PACKAGE,
        versionCode: Long = offered.versionCode,
        versionName: String? = offered.versionName,
        signers: List<String> = listOf(PINNED),
        history: List<String> = emptyList(),
    ) = ArchiveFacts(packageName, versionCode, versionName, signers, history)

    private fun verdict(facts: ArchiveFacts?, of: UpdateOffer = offered): UpdateFailure? = try {
        UpdateVerifier(FakeArchive(facts), PINNED).verify(folder.newFile(), of, UpdaterAvailability.RELEASE_PACKAGE, installed)
        null
    } catch (e: UpdateException) {
        e.failure
    }

    @Test
    fun theProjectsReleaseIsAccepted() {
        assertEquals(null, verdict(facts()))
    }

    @Test
    fun anApkSignedWithAnotherKeyIsRefused() {
        assertEquals(UpdateFailure.NOT_GENUINE, verdict(facts(signers = listOf(OTHER_KEY))))
        assertEquals("Every signer is the project's", UpdateFailure.NOT_GENUINE, verdict(facts(signers = listOf(PINNED, OTHER_KEY))))
        assertEquals("Unsigned", UpdateFailure.NOT_GENUINE, verdict(facts(signers = emptyList())))
        assertEquals("Unreadable", UpdateFailure.NOT_GENUINE, verdict(null))
    }

    @Test
    fun aRotatedKeyWhoseHistoryHoldsThePinnedOneIsAccepted() {
        assertEquals(null, verdict(facts(signers = listOf(OTHER_KEY), history = listOf(PINNED, OTHER_KEY))))
    }

    @Test
    fun anApkForAnotherPackageIsRefused() {
        assertEquals(UpdateFailure.NOT_GENUINE, verdict(facts(packageName = "app.houselights.debug")))
    }

    @Test
    fun anApkNoNewerThanTheInstalledOneIsRefused() {
        val old = offer(tag = "v0.3.0", versionCode = 900)
        assertEquals(UpdateFailure.NOT_NEWER, verdict(facts(versionCode = 900, versionName = "0.3.0"), old))
    }

    @Test
    fun aCodeOtherThanTheManifestsIsRefused() {
        assertEquals(UpdateFailure.NOT_GENUINE, verdict(facts(versionCode = 1011)))
    }

    @Test
    fun aVersionNameOtherThanTheTagsIsRefused() {
        assertEquals(UpdateFailure.NOT_GENUINE, verdict(facts(versionName = "0.4.0")))
        assertEquals(UpdateFailure.NOT_GENUINE, verdict(facts(versionName = null)))
        val renamed = offered.copy(versionName = "0.4.2")
        assertEquals("The manifest's own name, not the tag's", UpdateFailure.NOT_GENUINE, verdict(facts(), renamed))
    }

    /** A beta's APK published again under a release's tag would put a stable user on a beta. */
    @Test
    fun aBetaNameOnAReleaseIsRefused() {
        val beta = offer(tag = "v0.4.1-beta.1")
        assertEquals(null, verdict(facts(versionName = "0.4.1-beta.1"), beta))
        assertEquals(UpdateFailure.NOT_GENUINE, verdict(facts(versionName = "0.4.1-beta.1"), beta.copy(prerelease = false)))
    }

    @Test
    @Suppress("DEPRECATION") // As Android 9 fills an archive's package: its signatures, no signing info.
    fun archivesAreReadWithBothSignatureFlags() {
        var asked = 0
        val certificate = Signature(byteArrayOf(1, 2, 3, 4))
        val archive = PackageArchive { _, flags ->
            asked = flags
            PackageInfo().apply {
                packageName = UpdaterAvailability.RELEASE_PACKAGE
                longVersionCode = 1010
                versionName = "0.4.1"
                signatures = arrayOf(certificate)
            }
        }
        val facts = archive.read(folder.newFile())!!
        val both = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        assertEquals(both, asked and both)
        assertEquals(listOf(SigningCertificates.sha256(certificate)), facts.signers)
        assertEquals(1010L, facts.versionCode)
        assertTrue(facts.history.isEmpty())
    }
}
