package app.houselights.testing

import app.houselights.core.KeyRecord
import app.houselights.core.Keyring
import app.houselights.core.Secrets
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** A new AES key, in place of one from the device's Keystore. */
fun testKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

/** Key versions recorded in memory, as the device records them in its preferences. */
class TestKeyRecord(vararg made: Int) : KeyRecord {
    private val made = made.toMutableSet()

    override fun versions(): Set<Int> = synchronized(made) { made.toSet() }

    override fun add(version: Int) {
        synchronized(made) { made += version }
    }
}

/**
 * A Keystore of numbered keys, absent until made; a version in [unusable] fails with a bare InvalidKeyException,
 * one in [absent] gives no key that many times, as a restarting keystore daemon does. Without [listing], it lists
 * nothing, as Android 9 does when listing fails; while [blank], it reads empty, listing, holding and giving nothing,
 * yet makes the key it is asked for, replacing any under its alias.
 */
class FakeKeystore(val record: KeyRecord = TestKeyRecord(), val listing: Boolean = true) : Keyring {
    val keys = mutableMapOf<Int, SecretKey>()
    val unusable = mutableSetOf<Int>()
    val absent = mutableMapOf<Int, Int>()
    val made = mutableListOf<Int>()
    var blank = false

    override fun listed() = if (listing && !blank) keys.keys.toSet() else emptySet()

    override fun holds(version: Int) = !blank && version in keys

    override fun load(version: Int): SecretKey? {
        if (blank) return null
        val misses = absent[version] ?: 0
        if (misses > 0) {
            absent[version] = misses - 1
            return null
        }
        val key = keys[version] ?: return null
        return if (version in unusable) SecretKeySpec(ByteArray(7), "AES") else key
    }

    override fun create(version: Int): SecretKey {
        made += version
        val key = testKey().also { keys[version] = it }
        return if (version in unusable) SecretKeySpec(ByteArray(7), "AES") else key
    }

    /** [Secrets] as a fresh app process sees them, with nothing cached. */
    fun secrets() = Secrets(this, record)

    /**
     * [plain] as a version from before the record left it, which had moved on to a second key and sealed with its
     * newest: both keys become this Keystore's, and [record] is left as it is.
     */
    fun sealedBeforeTheRecord(plain: String): String {
        val earlier = FakeKeystore()
        earlier.keys += mapOf(Secrets.FIRST_KEY to testKey(), 2 to testKey())
        val sealed = earlier.secrets().seal(plain)
        check(sealed.startsWith("sealed2:")) { "Not sealed under the second key" }
        keys += earlier.keys
        return sealed
    }
}

/** Secrets whose every key version is [key]'s answer, loaded or made alike; its failure fails both. */
fun secretsOf(key: (Int) -> SecretKey): Secrets = Secrets(
    object : Keyring {
        override fun listed(): Set<Int> = emptySet()

        override fun holds(version: Int) = false

        override fun load(version: Int): SecretKey = key(version)

        override fun create(version: Int): SecretKey = key(version)
    },
    TestKeyRecord(),
)

/** Secrets sealed with a key of the test's own ([testKey]). */
fun testSecrets(): Secrets {
    val key = testKey()
    return secretsOf { key }
}
