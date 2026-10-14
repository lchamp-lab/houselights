package app.houselights.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the secrets kept on disk (SMB passwords, the Jellyfin token, a personal TMDB key) with AES-GCM.
 * On the device the key lives in the Android Keystore and never leaves it: a copy of the database or of
 * the settings file is of no use elsewhere.
 *
 * Sealed text starts with a version prefix. Anything else is plain text from before sealing existed:
 * [open] returns it as is, and it is sealed on its next write.
 */
class Secrets(keyProvider: () -> SecretKey) {
    private val key by lazy(keyProvider)

    /** Sealed text -> plain text, so a value read on every query is decrypted once. */
    private val opened = ConcurrentHashMap<String, String>()

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val sealed = PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
        opened[sealed] = plain
        return sealed
    }

    /** The plain text, or null when it can no longer be decrypted (the key is gone): ask the user again. */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        opened[stored]?.let { return it }
        return try {
            val bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)).also { opened[stored] = it }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        private const val PREFIX = "sealed1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "houselights.secrets"
        private const val KEY_BITS = 256

        /** Secrets sealed with this device's Keystore key, created on first use. */
        val device: Secrets by lazy { Secrets(::keystoreKey) }

        private fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }
    }
}
