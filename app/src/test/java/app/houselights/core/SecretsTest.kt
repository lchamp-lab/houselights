package app.houselights.core

import app.houselights.testing.testKey
import app.houselights.testing.testSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Secrets sealed under versioned Keystore keys, and what a failing Keystore leaves of them. */
class SecretsTest {
    /** Numbered keys created on first request; a version in [unusable] fails with a bare InvalidKeyException. */
    private inner class FakeKeystore {
        val keys = mutableMapOf<Int, SecretKey>()
        val unusable = mutableSetOf<Int>()

        fun newest() = keys.keys.maxOrNull() ?: Secrets.FIRST_KEY

        fun keyOf(version: Int): SecretKey {
            val key = keys.getOrPut(version) { testKey() }
            return if (version in unusable) SecretKeySpec(ByteArray(7), "AES") else key
        }

        /** [Secrets] as a fresh app process sees them, with nothing cached. */
        fun secrets() = Secrets(::newest, ::keyOf)
    }

    @Test
    fun sealsAndOpensWithTheSameKeyOnly() {
        val key = testKey()
        val sealed = Secrets { key }.seal("p@ss:wörd")
        assertTrue(Secrets { key }.isSealed(sealed))
        assertFalse(sealed.contains("p@ss"))
        // A fresh instance has nothing cached: this decrypts.
        assertEquals("p@ss:wörd", Secrets { key }.open(sealed))
        assertNull("Opened with another key", testSecrets().open(sealed))
    }

    @Test
    fun eachSealIsDifferent() {
        val key = testKey()
        val secrets = Secrets { key }
        assertNotEquals(secrets.seal("same"), secrets.seal("same"))
    }

    @Test
    fun plainTextFromBeforeSealingIsReturnedAsIs() {
        val secrets = testSecrets()
        assertEquals("legacy", secrets.open("legacy"))
        assertFalse(secrets.isSealed("legacy"))
        assertEquals("", secrets.seal(""))
    }

    /** Every key version seals; sealed text is never taken for plain text, nor plain text for sealed. */
    @Test
    fun sealedTextNamesTheVersionOfItsKey() {
        val secrets = testSecrets()
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

    /** A faulty keymaster throws outside its contract: the secret reads as missing and the app carries on. */
    @Test
    fun aKeystoreThatFailsOpensNothing() {
        val sealed = testSecrets().seal("secret")
        assertNull(Secrets { throw ProviderException("Keystore operation failed") }.open(sealed))
        assertNull(Secrets { throw IOException("Keystore unavailable") }.open(sealed))
        assertNull(testSecrets().open("sealed1:not base64!"))
    }

    /**
     * The first key fails for good (invalidated, or a keymaster that keeps failing): the second seals from then on,
     * and the first is kept for what it sealed.
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

    @Test
    fun aNewKeyThatFailedIsFetchedAgainAtTheNextSeal() {
        val keystore = FakeKeystore()
        keystore.secrets().seal("password")
        keystore.unusable += listOf(1, 2)
        val secrets = keystore.secrets()
        assertThrows(SecretsUnavailableException::class.java) { secrets.seal("token") }
        keystore.unusable -= 2
        assertTrue(secrets.seal("token").startsWith("sealed2:"))
        assertEquals(setOf(1, 2), keystore.keys.keys)
    }

    /** On Android 9 a restarting keystore daemon throws UnrecoverableKeyException, a busy one ProviderException. */
    @Test
    fun aPassingFailureIsTriedAgainWithTheSameKey() {
        listOf(UnrecoverableKeyException("Failed to obtain information about key"), ProviderException("Busy")).forEach { failure ->
            var tries = 0
            val good = testKey()
            val versions = mutableListOf<Int>()
            val secrets = Secrets { version ->
                versions += version
                if (tries++ == 0) throw failure else good
            }
            val sealed = secrets.seal("token")
            assertTrue("$failure: $sealed", sealed.startsWith("sealed1:"))
            assertEquals("$failure", "token", Secrets { good }.open(sealed))
            assertEquals("$failure", listOf(1, 1), versions)
        }
    }

    @Test
    fun aKeystoreThatStillFailsSaysSo() {
        val secrets = Secrets { throw ProviderException("Failed to generate key") }
        val e = assertThrows(SecretsUnavailableException::class.java) { secrets.seal("token") }
        assertTrue(e.cause is ProviderException)
    }
}
