package app.houselights.core

import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.security.MessageDigest
import java.util.Locale

/** Signing certificates as apksigner prints them: the SHA-256 of each, in lowercase hex. */
object SigningCertificates {
    /** A package's content signers ([sha256], sorted) and its single signer's history (v3 key rotation, oldest first). */
    data class Signing(val signers: List<String>, val history: List<String>)

    /**
     * [info]'s signers and history, from its signing info; no history for several signers, nor any certificate without
     * signers. The legacy signatures stand in for an archive alone, which Android 9 reads for GET_SIGNATURES only: an
     * installed package always has signing info from API 28 (minSdk), so a caller never asks GET_SIGNATURES to lean on it.
     */
    fun of(info: PackageInfo?): Signing {
        val signing = info?.signingInfo

        @Suppress("DEPRECATION") // PackageInfo.signatures, where Android gave no signing info.
        val signers = sha256(signing?.apkContentsSigners ?: info?.signatures)
        val history = signing?.takeUnless { it.hasMultipleSigners() }?.signingCertificateHistory.orEmpty().map { sha256(it) }
        return Signing(signers, history)
    }

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
