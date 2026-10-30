package app.houselights.core

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

/** A package's signers and their history, read alike by the updater, its check of an update and the player's trust. */
@RunWith(RobolectricTestRunner::class)
class SigningCertificatesTest {
    private val old = Signature(byteArrayOf(1, 2, 3))
    private val new = Signature(byteArrayOf(4, 5, 6))
    private val other = Signature(byteArrayOf(7, 8, 9))

    @Test
    fun noSigningInfoIsNoCertificate() {
        assertEquals(SigningCertificates.Signing(emptyList(), emptyList()), SigningCertificates.of(null))
        assertEquals(SigningCertificates.Signing(emptyList(), emptyList()), SigningCertificates.of(PackageInfo()))
        assertFalse(SigningCertificates.of(PackageInfo()).signedBy(sha(new)))
    }

    /** As Android 9 reads an archive for GET_SIGNATURES: its signatures, no signing info, no history. */
    @Test
    @Suppress("DEPRECATION") // PackageInfo.signatures, which Android 9 gives an archive.
    fun anArchiveWithoutSigningInfoIsReadByItsSignatures() {
        val signing = SigningCertificates.of(PackageInfo().apply { signatures = arrayOf(new) })
        assertEquals(listOf(sha(new)), signing.signers)
        assertTrue(signing.history.isEmpty())
        assertTrue(signing.signedBy(sha(new)))
    }

    /** Signing info, when given, is the one read: signatures beside it never add a signer. */
    @Test
    @Suppress("DEPRECATION") // PackageInfo.signatures, which Android 9 gives an archive.
    fun signingInfoGoesBeforeSignatures() {
        val signing = SigningCertificates.of(
            PackageInfo().apply {
                signatures = arrayOf(new)
                signingInfo = signingInfo(arrayOf(other))
            },
        )
        assertEquals(listOf(sha(other)), signing.signers)
        assertFalse(signing.signedBy(sha(new)))
    }

    /** Several signers are each read, sorted, and keep no history: one of them alone is not the package's key. */
    @Test
    fun severalSignersHaveNoHistory() {
        val both = signingInfo(arrayOf(other, new), past = arrayOf(old, new))
        val signing = SigningCertificates.of(PackageInfo().apply { signingInfo = both })
        assertEquals(listOf(sha(other), sha(new)).sorted(), signing.signers)
        assertTrue(signing.history.isEmpty())
        assertFalse(signing.signedBy(sha(new)))
        assertFalse(signing.signedBy(sha(old)))
    }

    /** A key rotated since (v3): its one signer is the new key, its history the old one first. */
    @Test
    fun aRotatedKeyKeepsItsHistory() {
        val signing = SigningCertificates.of(PackageInfo().apply { signingInfo = signingInfo(arrayOf(new), past = arrayOf(old, new)) })
        assertEquals(listOf(sha(new)), signing.signers)
        assertEquals(listOf(sha(old), sha(new)), signing.history)
        assertTrue(signing.signedBy(sha(old)))
        assertTrue(signing.signedBy(sha(new)))
        assertFalse(signing.signedBy(sha(other)))
    }

    private fun SigningCertificates.Signing.signedBy(pinned: String) = SigningCertificates.signedBy(pinned, signers, history)

    private fun sha(signature: Signature) = SigningCertificates.sha256(signature)

    private fun signingInfo(signers: Array<Signature>, past: Array<Signature>? = null): SigningInfo =
        Shadow.newInstanceOf(SigningInfo::class.java).also {
            val shadow = Shadow.extract<ShadowSigningInfo>(it)
            shadow.setSignatures(signers)
            if (past != null) shadow.setPastSigningCertificates(past)
        }
}
