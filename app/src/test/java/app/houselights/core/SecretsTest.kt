package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import android.security.keystore.KeyPermanentlyInvalidatedException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** On Robolectric: the Keystore's own exceptions are Android classes. */
@RunWith(RobolectricTestRunner::class)
class SecretsTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /**
     * A Keystore of numbered keys, each made when first asked for, as the device's. A version in
     * [unusable] hands out a key the cipher refuses with a bare InvalidKeyException, as a keymaster
     * at fault does; a key is never deleted.
     */
    private inner class FakeKeystore {
        val keys = mutableMapOf<Int, SecretKey>()
        val unusable = mutableSetOf<Int>()

        fun newest() = keys.keys.maxOrNull() ?: Secrets.FIRST_KEY

        fun keyOf(version: Int): SecretKey {
            val key = keys.getOrPut(version) { key() }
            return if (version in unusable) SecretKeySpec(ByteArray(7), "AES") else key
        }

        /** Secrets as a new process of the app finds them. */
        fun secrets() = Secrets(::newest, ::keyOf)
    }

    @Test
    fun sealsAndOpens() {
        val key = key()
        val sealed = Secrets { key }.seal("p@ss:wörd")
        assertTrue(Secrets { key }.isSealed(sealed))
        assertFalse(sealed.contains("p@ss"))
        // A fresh instance has nothing cached: this decrypts.
        assertEquals("p@ss:wörd", Secrets { key }.open(sealed))
    }

    @Test
    fun eachSealIsDifferent() {
        val key = key()
        val secrets = Secrets { key }
        assertNotEquals(secrets.seal("same"), secrets.seal("same"))
    }

    @Test
    fun opensWithTheSameKeyOnly() {
        val key = key()
        val sealed = Secrets { key }.seal("secret")
        assertEquals("secret", Secrets { key }.open(sealed))
        assertNull(Secrets { key() }.open(sealed))
    }

    @Test
    fun plainTextFromBeforeSealingIsReturnedAsIs() {
        val secrets = Secrets { key() }
        assertEquals("legacy", secrets.open("legacy"))
        assertFalse(secrets.isSealed("legacy"))
        assertEquals("", secrets.seal(""))
    }

    /** Any version of the key seals: what it wrote is never taken for plain text, nor plain text for it. */
    @Test
    fun sealedTextNamesTheVersionOfItsKey() {
        val secrets = Secrets { key() }
        assertTrue(secrets.isSealed("sealed1:AAAA"))
        assertTrue(secrets.isSealed("sealed12:AAAA"))
        listOf("sealed:AAAA", "sealed0:AAAA", "sealed01:AAAA", "sealedx:AAAA", "sealed2", "s3cret").forEach {
            assertFalse(it, secrets.isSealed(it))
            assertEquals(it, secrets.open(it))
        }
    }

    @Test
    fun theFirstKeyKeepsItsAliasAndTheNextOnesAreNumbered() {
        assertEquals("houselights.secrets", Secrets.aliasOf(1))
        assertEquals("houselights.secrets.2", Secrets.aliasOf(2))
        assertEquals(1, Secrets.versionOfAlias("houselights.secrets"))
        assertEquals(3, Secrets.versionOfAlias("houselights.secrets.3"))
        listOf("houselights.secrets.1", "houselights.secrets.03", "houselights.secrets.x", "other.key").forEach {
            assertNull(it, Secrets.versionOfAlias(it))
        }
    }

    /** A keymaster at fault throws beyond its contract: reading a secret says it is gone, the app goes on. */
    @Test
    fun aKeystoreThatFailsOpensNothing() {
        val sealed = Secrets { key() }.seal("secret")
        assertNull(Secrets { throw ProviderException("Keystore operation failed") }.open(sealed))
        assertNull(Secrets { throw IOException("Keystore unavailable") }.open(sealed))
        assertNull(Secrets { key() }.open("sealed1:not base64!"))
    }

    /**
     * The first key fails for good with a bare InvalidKeyException: the second one seals, from then on.
     * The first is never deleted, and what it sealed opens again once it works again.
     */
    @Test
    fun aKeyThatKeepsFailingGivesWayToTheNextOne() {
        val keystore = FakeKeystore()
        val before = keystore.secrets().seal("password")
        assertTrue(before.startsWith("sealed1:"))

        keystore.unusable += 1
        val sealed = keystore.secrets().seal("token")
        assertTrue(sealed, sealed.startsWith("sealed2:"))
        assertEquals(setOf(1, 2), keystore.keys.keys)
        assertEquals("token", keystore.secrets().open(sealed))
        // The next launch seals with the newest key at once.
        assertTrue(keystore.secrets().seal("again").startsWith("sealed2:"))
        assertNull("The first key still fails", keystore.secrets().open(before))

        keystore.unusable -= 1
        assertEquals("password", keystore.secrets().open(before))
        assertEquals(setOf(1, 2), keystore.keys.keys)
    }

    /** A key the Keystore says is invalidated for good gives way to the next one: secrets can be written again. */
    @Test
    fun aKeyInvalidatedForGoodGivesWayToTheNextOne() {
        val good = key()
        val secrets = Secrets { version ->
            if (version == 1) throw InvalidKeyException("Keystore operation failed", KeyPermanentlyInvalidatedException()) else good
        }
        val sealed = secrets.seal("token")
        assertTrue(sealed.startsWith("sealed2:"))
        assertEquals("token", Secrets { good }.open(sealed))
    }

    /**
     * On Android 9, a keystore daemon that restarts throws UnrecoverableKeyException ("Failed to
     * obtain information about key"): the key is fetched again, and seals.
     */
    @Test
    fun aTransientUnrecoverableKeyIsFetchedAgain() {
        var tries = 0
        val good = key()
        val secrets = Secrets { if (tries++ == 0) throw UnrecoverableKeyException("Failed to obtain information about key") else good }
        val sealed = secrets.seal("token")
        assertTrue(sealed.startsWith("sealed1:"))
        assertEquals("token", Secrets { good }.open(sealed))
    }

    @Test
    fun aPassingFailureIsTriedAgainWithTheSameKey() {
        var tries = 0
        val good = key()
        val versions = mutableListOf<Int>()
        val secrets = Secrets { version ->
            versions += version
            if (tries++ == 0) throw ProviderException("Busy") else good
        }
        assertEquals("token", Secrets { good }.open(secrets.seal("token")))
        assertEquals(listOf(1, 1), versions)
    }

    @Test
    fun aKeystoreThatStillFailsSaysSo() {
        val secrets = Secrets { throw ProviderException("Failed to generate key") }
        try {
            secrets.seal("token")
            fail("Sealed without a key")
        } catch (e: SecretsUnavailableException) {
            assertTrue(e.cause is ProviderException)
        }
    }
}
