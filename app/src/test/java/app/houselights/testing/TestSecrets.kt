package app.houselights.testing

import app.houselights.core.KeyRecord
import app.houselights.core.Keyring
import app.houselights.core.Secrets
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

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
 * Secrets whose every key version is [key]'s answer, loaded or made alike; its failure fails both. The versions [made]
 * are recorded as this install's, so that it seals with the newest of them.
 */
fun secretsOf(vararg made: Int, key: (Int) -> SecretKey): Secrets = Secrets(
    object : Keyring {
        override fun listed(): Set<Int> = emptySet()

        override fun holds(version: Int) = false

        override fun load(version: Int): SecretKey = key(version)

        override fun create(version: Int): SecretKey = key(version)
    },
    TestKeyRecord(*made),
)

/** Secrets sealed with a key of the test's own ([testKey]). */
fun testSecrets(): Secrets {
    val key = testKey()
    return secretsOf { key }
}
