package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
