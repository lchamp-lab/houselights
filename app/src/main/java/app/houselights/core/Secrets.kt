package app.houselights.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The device's Keystore could not seal a secret, even with a key made again: it was not written. */
class SecretsUnavailableException(cause: Throwable) : IllegalStateException("Secrets cannot be sealed on this device", cause)

/**
 * Seals the secrets kept on disk (SMB passwords, the Jellyfin token, a personal TMDB key) with AES-GCM.
 * On the device the key lives in the Android Keystore and never leaves it: a copy of the database or of
 * the settings file is of no use elsewhere.
 *
 * Sealed text starts with a version prefix. Anything else is plain text from before sealing existed:
 * [open] returns it as is, and it is sealed on its next write.
 *
 * A Keystore that fails (a keymaster at fault, a key a firmware update made unusable) never takes the
 * app down: [open] gives null, as for a key that is gone, and [seal] tries once more with the key
 * fetched again, made anew when the key itself is at fault ([forgetKey]), before it throws
 * [SecretsUnavailableException].
 */
class Secrets(private val forgetKey: () -> Unit = {}, private val keyProvider: () -> SecretKey) {
    /** Fetched on first use, and again after a failure: not a lazy that would keep a key that can't be used. */
    @Volatile
    private var key: SecretKey? = null

    /** Sealed text -> plain text, so a value read on every query is decrypted once. */
    private val opened = ConcurrentHashMap<String, String>()

    private fun key(): SecretKey = key ?: synchronized(this) { key ?: keyProvider().also { key = it } }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val sealed = keystore { sealWith(plain) }.getOrElse { e ->
            // Once more, with the key fetched again; made anew when the key itself is at fault.
            key = null
            if (e is InvalidKeyException || e is UnrecoverableKeyException) forgetKey()
            keystore { sealWith(plain) }.getOrElse { throw SecretsUnavailableException(it) }
        }
        opened[sealed] = plain
        return sealed
    }

    private fun sealWith(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    /** The plain text, or null when it can no longer be decrypted (the key is gone, the Keystore fails): ask the user again. */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        opened[stored]?.let { return it }
        return try {
            keystore {
                val bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length))
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
                String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
            }.getOrNull()?.also { opened[stored] = it }
        } catch (_: IllegalArgumentException) {
            null // Not what sealing writes.
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

        /**
         * [block], or how the Keystore failed in it: its own exceptions, and those beyond its contract (a
         * ProviderException is a RuntimeException; loading the store throws IOException).
         */
        private inline fun <T> keystore(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: GeneralSecurityException) {
            Result.failure(e)
        } catch (e: ProviderException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }

        /** Secrets sealed with this device's Keystore key, created on first use. */
        val device: Secrets by lazy { Secrets(forgetKey = ::forgetKeystoreKey, keyProvider = ::keystoreKey) }

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

        /** Deletes a key that can no longer be used: the next one is made anew. What it sealed is lost with it. */
        private fun forgetKeystoreKey() {
            runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
        }
    }
}
