package app.houselights.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The device's Keystore could not seal a secret, even with a new key; nothing was written. */
class SecretsUnavailableException(cause: Throwable) : IllegalStateException("Secrets cannot be sealed on this device", cause)

/**
 * Seals secrets kept on disk (SMB passwords, Jellyfin token, TMDB key) with AES-GCM under an Android Keystore
 * key that never leaves the device. Sealed text starts with its key version ("sealed1:"); anything else is
 * plain text, returned as is and sealed on its next write.
 *
 * A failing Keystore never takes the app down: [open] returns null, and [seal] retries once, then seals with a
 * new key version, throwing [SecretsUnavailableException] only if that fails too. A failing key is never
 * deleted: on Android 9 a restarting keystore daemon also throws UnrecoverableKeyException or a bare
 * InvalidKeyException, and deleting the key would lose every secret it sealed.
 */
class Secrets(
    /** The newest key version, which seals; [FIRST_KEY] when none exists yet. */
    private val newestKey: () -> Int = { FIRST_KEY },
    /** The key of a version, created when missing. */
    private val keyProvider: (Int) -> SecretKey,
) {
    /** The sealing version, resolved on first success: a lazy would cache a failing Keystore's first answer. */
    @Volatile
    private var inUse: Int? = null

    /** Keys by version, fetched on first use and again after a failure. */
    private val keys = ConcurrentHashMap<Int, SecretKey>()

    /** Sealed text to plain text, so a value read on every query is decrypted once. */
    private val opened = ConcurrentHashMap<String, String>()

    private fun key(version: Int): SecretKey =
        keys[version] ?: synchronized(this) { keys[version] ?: keyProvider(version).also { keys[version] = it } }

    private fun versionInUse(): Int =
        inUse ?: synchronized(this) { inUse ?: keystore { newestKey() }.getOrNull()?.also { inUse = it } ?: FIRST_KEY }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val version = versionInUse()
        val sealed = keystore { sealWith(version, plain) }.getOrElse {
            // Retry once with the key fetched again: a restarting keystore daemon fails briefly.
            keys.remove(version)
            keystore { sealWith(version, plain) }.getOrElse { sealWithNextKey(version, plain) }
        }
        opened[sealed] = plain
        return sealed
    }

    /** Seals with the version after [failing] from then on, unless another seal already moved on. */
    private fun sealWithNextKey(failing: Int, plain: String): String = synchronized(this) {
        val next = inUse?.takeIf { it > failing } ?: (failing + 1)
        keystore { sealWith(next, plain) }
            .onSuccess { inUse = next }
            .getOrElse {
                keys.remove(next)
                throw SecretsUnavailableException(it)
            }
    }

    private fun sealWith(version: Int, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(version)) }
        return "$PREFIX$version$SEPARATOR" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    /** The plain text, or null when it cannot be decrypted (key lost, Keystore failing): ask for it again. */
    fun open(stored: String): String? {
        val version = versionOf(stored) ?: return stored
        opened[stored]?.let { return it }
        return try {
            keystore {
                val bytes = Base64.getDecoder().decode(stored.substringAfter(SEPARATOR))
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(version), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
                String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
            }.getOrNull()?.also { opened[stored] = it }
        } catch (_: IllegalArgumentException) {
            null // Not valid sealed text.
        }
    }

    /** Sealed by any version of the key ("sealed1:", "sealed2:"…), not plain text. */
    fun isSealed(stored: String): Boolean = versionOf(stored) != null

    companion object {
        /** The first key version; its alias has no number. */
        const val FIRST_KEY = 1

        /** Sealed text is "sealed", the key's version, ":" and the sealed bytes in Base64. */
        private const val PREFIX = "sealed"
        private const val SEPARATOR = ':'
        private const val MAX_VERSION_DIGITS = 6
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "houselights.secrets"
        private const val KEY_BITS = 256

        /** The version of the key [stored] was sealed with ("sealed2:…" is 2); null for plain text. */
        private fun versionOf(stored: String): Int? {
            if (!stored.startsWith(PREFIX)) return null
            val end = stored.indexOf(SEPARATOR, PREFIX.length)
            if (end - PREFIX.length !in 1..MAX_VERSION_DIGITS) return null
            val digits = stored.substring(PREFIX.length, end)
            return digits.takeIf { it.first() != '0' && it.all { c -> c in '0'..'9' } }?.toInt()
        }

        /** Runs [block], catching Keystore failures, including ProviderException and the store's IOException. */
        private inline fun <T> keystore(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: GeneralSecurityException) {
            Result.failure(e)
        } catch (e: ProviderException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }

        /** Secrets sealed with this device's Keystore keys, the newest made on first use. */
        val device: Secrets by lazy { Secrets(newestKey = ::newestKeystoreKey, keyProvider = ::keystoreKey) }

        /** Keystore alias of a key version: the first is unnumbered, later ones are numbered. */
        internal fun aliasOf(version: Int) = if (version == FIRST_KEY) ALIAS else "$ALIAS.$version"

        /** The version one of our aliases names ("houselights.secrets.2" is 2); null for any other alias. */
        internal fun versionOfAlias(alias: String): Int? {
            if (alias == ALIAS) return FIRST_KEY
            val version = alias.removePrefix("$ALIAS.").toIntOrNull() ?: return null
            return version.takeIf { it > FIRST_KEY && aliasOf(it) == alias }
        }

        private fun androidKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        private fun newestKeystoreKey(): Int = androidKeyStore().aliases().toList().mapNotNull(::versionOfAlias).maxOrNull() ?: FIRST_KEY

        private fun keystoreKey(version: Int): SecretKey {
            val alias = aliasOf(version)
            (androidKeyStore().getKey(alias, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
