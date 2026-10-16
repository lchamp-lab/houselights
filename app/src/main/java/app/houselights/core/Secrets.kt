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

/** The device's Keystore could not seal a secret, even with a key made anew: it was not written. */
class SecretsUnavailableException(cause: Throwable) : IllegalStateException("Secrets cannot be sealed on this device", cause)

/**
 * Seals the secrets kept on disk (SMB passwords, the Jellyfin token, a personal TMDB key) with AES-GCM.
 * On the device the key lives in the Android Keystore and never leaves it: a copy of the database or of
 * the settings file is of no use elsewhere.
 *
 * Sealed text starts with the version of the key that sealed it ("sealed1:", "sealed2:"…). Anything
 * else is plain text from before sealing existed: [open] returns it as is, and it is sealed on its next
 * write.
 *
 * A Keystore that fails (a keymaster at fault, a key a firmware update made unusable) never takes the
 * app down: [open] gives null, as for a key that is gone, and [seal] tries once more with the key
 * fetched again. A key that still fails is never deleted: on Android 9, an UnrecoverableKeyException
 * or a bare InvalidKeyException also comes from a keystore daemon that restarts, and deleting the key
 * would lose every secret it sealed. The next version is made instead, and seals from then on; the
 * old one stays for what it sealed, which opens again whenever it works again. Only a new key that
 * fails as well makes [seal] throw [SecretsUnavailableException].
 */
class Secrets(
    /** The newest version of the key there is: the one that seals. [FIRST_KEY] when there is none yet. */
    private val newestKey: () -> Int = { FIRST_KEY },
    /** The key of a version, made when there is none yet. */
    private val keyProvider: (Int) -> SecretKey,
) {
    /** The version that seals, found on first use: not a lazy that would keep the first answer of a Keystore that failed. */
    @Volatile
    private var inUse: Int? = null

    /** Keys by version, fetched on first use, and again after a failure. */
    private val keys = ConcurrentHashMap<Int, SecretKey>()

    /** Sealed text -> plain text, so a value read on every query is decrypted once. */
    private val opened = ConcurrentHashMap<String, String>()

    private fun key(version: Int): SecretKey =
        keys[version] ?: synchronized(this) { keys[version] ?: keyProvider(version).also { keys[version] = it } }

    /** The version that seals; the first while the Keystore cannot say which is the newest. */
    private fun versionInUse(): Int =
        inUse ?: synchronized(this) { inUse ?: keystore { newestKey() }.getOrNull()?.also { inUse = it } ?: FIRST_KEY }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val version = versionInUse()
        val sealed = keystore { sealWith(version, plain) }.getOrElse {
            // Once more, with the key fetched again: a keystore daemon that restarts fails for a moment.
            keys.remove(version)
            keystore { sealWith(version, plain) }.getOrElse { sealWithNextKey(version, plain) }
        }
        opened[sealed] = plain
        return sealed
    }

    /**
     * Seals with the version after [failing], made for it unless another seal already moved on: from
     * then on, it seals. [failing] is kept for what it sealed.
     */
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

    /** The plain text, or null when it can no longer be decrypted (the key is gone, the Keystore fails): ask the user again. */
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
            null // Not what sealing writes.
        }
    }

    /** Sealed by any version of the key ("sealed1:", "sealed2:"…), not plain text. */
    fun isSealed(stored: String): Boolean = versionOf(stored) != null

    companion object {
        /** The version of the key made first, whose alias has no number: what every secret was sealed with before versions. */
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

        /** Secrets sealed with this device's Keystore keys, the newest made on first use. */
        val device: Secrets by lazy { Secrets(newestKey = ::newestKeystoreKey, keyProvider = ::keystoreKey) }

        /** The Keystore alias of a version of the key: the first has none of its own, the next ones are numbered. */
        internal fun aliasOf(version: Int) = if (version == FIRST_KEY) ALIAS else "$ALIAS.$version"

        /** The version an alias of ours names ("houselights.secrets.2" is 2); null for any other alias. */
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
