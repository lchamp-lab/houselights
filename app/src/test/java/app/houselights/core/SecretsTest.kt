package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.InvalidKeyException
import java.security.ProviderException
import javax.crypto.KeyGenerator

class SecretsTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

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

    /** A keymaster at fault throws beyond its contract: reading a secret says it is gone, the app goes on. */
    @Test
    fun aKeystoreThatFailsOpensNothing() {
        val sealed = Secrets { key() }.seal("secret")
        assertNull(Secrets { throw ProviderException("Keystore operation failed") }.open(sealed))
        assertNull(Secrets { throw IOException("Keystore unavailable") }.open(sealed))
        assertNull(Secrets { key() }.open("sealed1:not base64!"))
    }

    /** A key a firmware update made unusable is made anew, once: secrets can be written again. */
    @Test
    fun aKeyThatCanNoLongerBeUsedIsMadeAnew() {
        var forgotten = 0
        val good = key()
        var current: () -> javax.crypto.SecretKey = { throw InvalidKeyException("Key permanently invalidated") }
        val secrets = Secrets(forgetKey = {
            forgotten++
            current = { good }
        }) { current() }
        val sealed = secrets.seal("token")
        assertEquals(1, forgotten)
        assertEquals("token", Secrets { good }.open(sealed))
    }

    @Test
    fun aPassingFailureIsTriedAgainWithoutLosingTheKey() {
        var tries = 0
        val good = key()
        var forgotten = false
        val secrets = Secrets(forgetKey = { forgotten = true }) { if (tries++ == 0) throw ProviderException("Busy") else good }
        assertEquals("token", Secrets { good }.open(secrets.seal("token")))
        assertFalse(forgotten)
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
