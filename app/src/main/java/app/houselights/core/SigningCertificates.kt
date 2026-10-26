package app.houselights.core

import android.content.pm.Signature
import java.security.MessageDigest
import java.util.Locale

/** Signing certificates as apksigner prints them: the SHA-256 of each, in lowercase hex. */
object SigningCertificates {
    /** The SHA-256 of each of [signers], sorted; empty when there are none. */
    fun sha256(signers: Array<out Signature>?): List<String> = signers.orEmpty().map { sha256(it) }.sorted()

    /** The SHA-256 of [signer], for a list whose order matters (a signing history, oldest first). */
    fun sha256(signer: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }

    /**
     * Whether a package whose content [signers] and signing [history] (v3 key rotation, oldest first) are these is the
     * one signed with [pinned]: every signer is it, or the single signer's history holds it (the key rotated since).
     */
    fun signedBy(pinned: String, signers: List<String>, history: List<String>): Boolean {
        val rotated = signers.size == 1 && pinned in history
        return signers.isNotEmpty() && (signers.all { it == pinned } || rotated)
    }
}
