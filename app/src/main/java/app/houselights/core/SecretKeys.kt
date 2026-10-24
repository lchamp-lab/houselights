package app.houselights.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Where the keys of [Secrets] are kept, by version. */
interface Keyring {
    /** The versions it lists; empty when it holds none, or when listing failed (Android 9 tells no difference). */
    fun listed(): Set<Int>

    /** The key of [version]; null when none is given: absent, or a keystore daemon restarting. */
    fun load(version: Int): SecretKey?

    /** Whether a key is under [version]'s alias; false also when the Keystore did not answer (Android 9). */
    fun holds(version: Int): Boolean

    /** Makes the key of [version], replacing any key under its alias. */
    fun create(version: Int): SecretKey
}

/** The key versions made on this install, kept apart from the keys: a version made once is never made again. */
interface KeyRecord {
    fun versions(): Set<Int>

    fun add(version: Int)
}

/** The Android Keystore's keys: the first version's alias is unnumbered, later ones are numbered. */
internal object DeviceKeyring : Keyring {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_BITS = 256

    private fun store(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun listed(): Set<Int> = store().aliases().toList().mapNotNull(Secrets::versionOfAlias).toSet()

    override fun load(version: Int): SecretKey? = store().getKey(Secrets.aliasOf(version), null) as? SecretKey

    override fun holds(version: Int): Boolean = store().containsAlias(Secrets.aliasOf(version))

    override fun create(version: Int): SecretKey {
        val spec = KeyGenParameterSpec.Builder(Secrets.aliasOf(version), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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

/**
 * Versions in private preferences, never backed up (backup_rules.xml). The app [updated] over a version from before
 * this record may have made the first key unrecorded: its record starts with it.
 */
internal class SavedKeyRecord(private val prefs: SharedPreferences, private val updated: () -> Boolean) : KeyRecord {
    @Synchronized
    override fun versions(): Set<Int> = prefs.getStringSet(MADE, null)?.mapNotNull(String::toIntOrNull)?.toSet() ?: begin()

    @Synchronized
    override fun add(version: Int) {
        val made = versions()
        if (version !in made) save(made + version)
    }

    private fun begin(): Set<Int> = (if (updated()) setOf(Secrets.FIRST_KEY) else emptySet()).also(::save)

    /** Written at once: text sealed with a new key is stored only once its version is recorded. */
    private fun save(versions: Set<Int>) {
        if (!prefs.edit().putStringSet(MADE, versions.mapTo(HashSet(), Int::toString)).commit()) Log.w(TAG, "Key versions not recorded")
    }

    companion object {
        private const val MADE = "made"
        private const val TAG = "Secrets"

        fun of(context: Context) = SavedKeyRecord(context.getSharedPreferences("secret_keys", Context.MODE_PRIVATE)) {
            // Installed over an earlier version, rather than anew; unknown counts as updated.
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
                .map { it.firstInstallTime != it.lastUpdateTime }
                .getOrDefault(true)
        }
    }
}
