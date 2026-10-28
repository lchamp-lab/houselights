package app.houselights.core

import android.content.Context
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The device's Keystore could not seal a secret, even with a new key; nothing was written. */
class SecretsUnavailableException(cause: Throwable) : IllegalStateException("Secrets cannot be sealed on this device", cause)

/** Where sealed secrets are stored, each counted at every launch ([Secrets.recordKeys]) before a key is made. */
enum class SecretStore {
    /** The settings: the connection, the kept server, the TMDB key, path mappings and Trakt's tokens. */
    SETTINGS,

    /** The library's folders: their passwords. */
    FOLDERS,
}

/** A saved secret the device's Keystore did not open for now ([Opened.Unavailable]) was needed: it is kept, never used empty. */
class SecretUnreadException : IllegalStateException("A saved secret is not opened for now")

/** What opening a stored secret gave. */
sealed interface Opened {
    /** Its plain text. */
    data class Text(val plain: String) : Opened {
        override fun toString() = "Text(***)"
    }

    /** Never readable again: the key under its alias is not the one that sealed it, or the text is damaged. */
    data object Undecryptable : Opened

    /** The Keystore failed or gave no key: the value is intact and may open on a later try. */
    data object Unavailable : Opened
}

/**
 * Seals secrets kept on disk (SMB passwords, Jellyfin token, TMDB key) with AES-GCM under an Android Keystore
 * key that never leaves the device. Sealed text starts with its key version ("sealed1:"); anything else is
 * plain text, returned as is and sealed on its next write.
 *
 * A failing Keystore never takes the app down: [read] says the secret is unavailable for now, and [seal] retries
 * once, then seals with a new key version, throwing [SecretsUnavailableException] only if that fails too. A key is
 * never deleted, nor made again once made ([KeyRecord], which [recordKeys] fills at each launch) or while the Keystore
 * holds it: on Android 9 a restarting keystore daemon gives no key, UnrecoverableKeyException or a bare
 * InvalidKeyException, and replacing the key would lose every secret it sealed. None is made before each of [stores]
 * has been counted at this launch, as a store not yet counted may hold secrets a key not yet recorded sealed.
 */
class Secrets(private val keyring: Keyring, private val record: KeyRecord, stores: Set<SecretStore> = emptySet()) {
    /** The sealing version, resolved on first success: a lazy would cache a failing Keystore's first answer. */
    @Volatile
    private var inUse: Int? = null

    /** The stores this launch has yet to count ([recordKeys]); a seal that would make a key fails while one is left. */
    private val uncounted: MutableSet<SecretStore> = ConcurrentHashMap.newKeySet<SecretStore>().apply { addAll(stores) }

    /** Keys by version, loaded on first use and again after a failure. */
    private val keys = ConcurrentHashMap<Int, SecretKey>()

    /** Sealed text to plain text, so a value read on every query is decrypted once. */
    private val opened = ConcurrentHashMap<String, String>()

    /** The key of [version], recorded as made once loaded; null when the Keystore gives none. Never makes one. */
    private fun loaded(version: Int): SecretKey? =
        keys[version] ?: keyring.load(version)?.also {
            keys[version] = it
            record.add(version)
        }

    /**
     * The key [version] seals with: loaded, else made if this install never made it and the Keystore holds none under
     * its alias (made by a version from before the record); one made before is failing. Nothing is made before every
     * store is counted: until then the record may lack a version whose secrets wait in a store not yet counted.
     */
    private fun sealingKey(version: Int): SecretKey = loaded(version) ?: synchronized(this) {
        keys[version] ?: run {
            if (uncounted.isNotEmpty()) throw KeyStoreException("Key $version gives no key, and stores are not counted yet")
            if (version in record.versions() || keyring.holds(version)) throw KeyStoreException("Key $version gives no key")
            keyring.create(version).also {
                record.add(version)
                keys[version] = it
            }
        }
    }

    /**
     * The newest version listed or recorded, kept once listed and every store counted, the record then whole; a listed
     * one is recorded, as made.
     */
    private fun versionInUse(): Int = inUse ?: synchronized(this) {
        inUse ?: run {
            val listed = keystore { keyring.listed() }.getOrNull()
            listed?.forEach(record::add)
            ((listed.orEmpty() + record.versions()).maxOrNull() ?: FIRST_KEY).also {
                if (listed != null && uncounted.isEmpty()) inUse = it
            }
        }
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val version = versionInUse()
        val sealed = keystore { sealWith(version, plain) }.getOrElse {
            // Retry once with the key loaded again: a restarting keystore daemon fails briefly.
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
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, sealingKey(version)) }
        return "$PREFIX$version$SEPARATOR" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    /** The plain text of [stored], or why there is none. */
    fun read(stored: String): Opened {
        val version = versionOf(stored) ?: return Opened.Text(stored)
        opened[stored]?.let { return Opened.Text(it) }
        // Text sealed under a version proves its key was made here: it is never made again.
        record.add(version)
        val bytes = sealedBytes(stored) ?: return Opened.Undecryptable
        return decrypted(version, bytes).also { if (it is Opened.Text) opened[stored] = it.plain }
    }

    private fun decrypted(version: Int, bytes: ByteArray): Opened = try {
        loaded(version)?.let { key ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            Opened.Text(String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)))
        } ?: Opened.Unavailable
    } catch (_: AEADBadTagException) {
        Opened.Undecryptable
    } catch (_: GeneralSecurityException) {
        unavailable(version)
    } catch (_: ProviderException) {
        unavailable(version)
    } catch (_: IOException) {
        unavailable(version)
    }

    /** A failing Keystore: the key is loaded again at the next try. */
    private fun unavailable(version: Int): Opened {
        keys.remove(version)
        return Opened.Unavailable
    }

    /** The plain text, or null when it cannot be decrypted, for now or for good ([read] says which). */
    fun open(stored: String): String? = (read(stored) as? Opened.Text)?.plain

    /** Sealed by any version of the key ("sealed1:", "sealed2:"…), not plain text. */
    fun isSealed(stored: String): Boolean = versionOf(stored) != null

    /**
     * Counts [store]: records as made every key version its secrets ([stored], all it holds) were sealed with, from
     * their text alone. Nothing is opened, nor the Keystore asked, so a failing one never stops a store from loading.
     * Run at each launch before the store seals anything, so that a version an earlier version made, and nothing has
     * opened since, is never made again while the Keystore reads empty for a moment; once every store is counted, a
     * seal may make a key.
     */
    fun recordKeys(store: SecretStore, stored: Iterable<String>) {
        stored.forEach { versionOf(it)?.let(record::add) }
        uncounted -= store
    }

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
        private const val ALIAS = "houselights.secrets"

        /** The version of the key [stored] was sealed with ("sealed2:…" is 2); null for plain text. */
        private fun versionOf(stored: String): Int? {
            if (!stored.startsWith(PREFIX)) return null
            val end = stored.indexOf(SEPARATOR, PREFIX.length)
            if (end - PREFIX.length !in 1..MAX_VERSION_DIGITS) return null
            val digits = stored.substring(PREFIX.length, end)
            return digits.takeIf { it.first() != '0' && it.all { c -> c in '0'..'9' } }?.toInt()
        }

        /** The IV and sealed bytes of [stored]; null when it is damaged. */
        private fun sealedBytes(stored: String): ByteArray? = try {
            Base64.getDecoder().decode(stored.substringAfter(SEPARATOR)).takeIf { it.size > IV_BYTES }
        } catch (_: IllegalArgumentException) {
            null
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

        @Volatile
        private var device: Secrets? = null

        /**
         * Secrets sealed with this device's Keystore keys, the versions made recorded in [context]'s preferences; no
         * key is made before both stores are counted at launch.
         */
        fun device(context: Context): Secrets = device ?: synchronized(this) {
            device ?: Secrets(DeviceKeyring, SavedKeyRecord.of(context.applicationContext), SecretStore.entries.toSet())
                .also { device = it }
        }

        /** Keystore alias of a key version: the first is unnumbered, later ones are numbered. */
        internal fun aliasOf(version: Int) = if (version == FIRST_KEY) ALIAS else "$ALIAS.$version"

        /** The version one of our aliases names ("houselights.secrets.2" is 2); null for any other alias. */
        internal fun versionOfAlias(alias: String): Int? {
            if (alias == ALIAS) return FIRST_KEY
            val version = alias.removePrefix("$ALIAS.").toIntOrNull() ?: return null
            return version.takeIf { it > FIRST_KEY && aliasOf(it) == alias }
        }
    }
}
