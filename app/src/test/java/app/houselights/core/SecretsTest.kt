package app.houselights.core

import app.houselights.testing.FakeKeystore
import app.houselights.testing.TestKeyRecord
import app.houselights.testing.secretsOf
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
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey

/** Secrets sealed under versioned Keystore keys, and what a failing Keystore leaves of them. */
class SecretsTest {
    @Test
    fun sealsAndOpensWithTheSameKeyOnly() {
        val key = testKey()
        val sealed = secretsOf { key }.seal("p@ss:wörd")
        assertTrue(secretsOf { key }.isSealed(sealed))
        assertFalse(sealed.contains("p@ss"))
        // A fresh instance has nothing cached: this decrypts.
        assertEquals("p@ss:wörd", secretsOf { key }.open(sealed))
        assertNull("Opened with another key", testSecrets().open(sealed))
    }

    @Test
    fun eachSealIsDifferent() {
        val key = testKey()
        val secrets = secretsOf { key }
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

    /** A faulty keymaster throws outside its contract: the secret reads as unavailable and the app carries on. */
    @Test
    fun aKeystoreThatFailsOpensNothingForNow() {
        val key = testKey()
        val sealed = secretsOf { key }.seal("secret")
        listOf(
            ProviderException("Keystore operation failed"),
            KeyStoreException("System error"),
            UnrecoverableKeyException("Failed to obtain information about key"),
            InvalidKeyException("Keystore operation failed"),
            IOException("Keystore unavailable"),
        ).forEach { failure ->
            assertEquals("$failure", Opened.Unavailable, secretsOf { throw failure }.read(sealed))
        }
        assertNull(secretsOf { throw ProviderException("Keystore operation failed") }.open(sealed))
    }

    /** Only a key that is not the one that sealed it, or damaged text, makes a secret unreadable for good. */
    @Test
    fun aSecretSealedByAnotherKeyIsUndecryptable() {
        val sealed = testSecrets().seal("secret")
        assertEquals(Opened.Undecryptable, testSecrets().read(sealed))
        assertEquals(Opened.Undecryptable, testSecrets().read("sealed1:not base64!"))
        assertEquals(Opened.Undecryptable, testSecrets().read("sealed1:AAAA"))
    }

    /** A fresh install makes its first key when it first seals, and records it. */
    @Test
    fun aFreshInstallMakesItsFirstKeyOnce() {
        val keystore = FakeKeystore()
        val sealed = keystore.secrets().seal("token")
        assertTrue(sealed, sealed.startsWith("sealed1:"))
        assertEquals(listOf(1), keystore.made)
        assertEquals(setOf(1), keystore.record.versions())
        assertEquals("token", keystore.secrets().open(sealed))
        assertTrue(keystore.secrets().seal("again").startsWith("sealed1:"))
        assertEquals(listOf(1), keystore.made)
    }

    /** The key giving none once, as a restarting daemon does: opening says so and never makes one, the next open reads. */
    @Test
    fun openingNeverMakesAKey() {
        val keystore = FakeKeystore()
        val sealed = keystore.secrets().seal("token")
        keystore.absent[1] = 1
        val secrets = keystore.secrets()
        assertEquals(Opened.Unavailable, secrets.read(sealed))
        assertEquals(listOf(1), keystore.made)
        assertEquals(Opened.Text("token"), secrets.read(sealed))
    }

    /** A key made before that gives none is failing, never made again: sealing tries it again, then the next version. */
    @Test
    fun aRecordedKeyGivingNoneIsNeverMadeAgain() {
        val keystore = FakeKeystore()
        val before = keystore.secrets().seal("password")

        keystore.absent[1] = 1
        assertTrue(keystore.secrets().seal("token").startsWith("sealed1:"))
        assertEquals(listOf(1), keystore.made)

        keystore.absent[1] = 2
        val sealed = keystore.secrets().seal("token")
        assertTrue(sealed, sealed.startsWith("sealed2:"))
        assertEquals(listOf(1, 2), keystore.made)
        assertEquals("password", keystore.secrets().open(before))
    }

    /**
     * Installed over a version from before the record: sealed data and its key, no record, and a listing that fails.
     * A key that loads is recorded at once; one giving none, even before anything was opened, is never made again.
     */
    @Test
    fun anInstallFromBeforeTheRecordKeepsItsKeys() {
        val earlier = FakeKeystore()
        val sealed = earlier.secrets().seal("token")
        fun upgraded(record: TestKeyRecord) = FakeKeystore(record, listing = false).apply { keys += earlier.keys }

        // Opened first: the sealed text alone says its key was made.
        val opened = upgraded(TestKeyRecord())
        opened.absent[1] = 1
        assertEquals(Opened.Unavailable, opened.secrets().read(sealed))
        opened.absent[1] = 2
        assertTrue(opened.secrets().seal("new").startsWith("sealed2:"))
        assertEquals("token", opened.secrets().open(sealed))
        assertEquals(listOf(2), opened.made)

        // Sealed first: the record an update starts holds the first key (SavedKeyRecord).
        val updated = upgraded(TestKeyRecord(Secrets.FIRST_KEY))
        updated.absent[1] = 1
        assertTrue(updated.secrets().seal("new").startsWith("sealed1:"))
        assertEquals("token", updated.secrets().open(sealed))
        assertEquals(emptyList<Int>(), updated.made)

        // Loaded: recorded at once.
        val loaded = upgraded(TestKeyRecord())
        assertTrue(loaded.secrets().seal("new").startsWith("sealed1:"))
        assertEquals(setOf(1), loaded.record.versions())
        assertEquals(emptyList<Int>(), loaded.made)
    }

    /**
     * Installed over a version from before the record that had moved on to a second key: unrecorded, unlisted, and
     * giving none once. The Keystore holding it, it is never made again, which would delete it with all it sealed.
     */
    @Test
    fun aKeyTheKeystoreHoldsIsNeverMadeAgainThoughUnrecorded() {
        val earlier = FakeKeystore()
        earlier.secrets().seal("password")
        earlier.unusable += 1
        val sealed = earlier.secrets().seal("token")
        assertTrue(sealed, sealed.startsWith("sealed2:"))

        val updated = FakeKeystore(TestKeyRecord(Secrets.FIRST_KEY), listing = false).apply {
            keys += earlier.keys
            unusable += 1
            absent[2] = 1
        }
        assertThrows(SecretsUnavailableException::class.java) { updated.secrets().seal("new") }
        assertEquals(emptyList<Int>(), updated.made)
        assertEquals("token", updated.secrets().open(sealed))
        assertTrue(updated.secrets().seal("new").startsWith("sealed2:"))
    }

    /**
     * Installed over a version from before the record that had moved on to a second key, never opened since: the
     * launch records it from the text it sealed, so the Keystore reading empty when the next seal asks, then making the
     * key asked for, never gets to make it again, which would lose all it sealed. The seal moves on to a third.
     */
    @Test
    fun aKeyTheStoredSecretsNameIsNeverMadeAgainThoughTheKeystoreReadsEmpty() {
        val keystore = FakeKeystore(TestKeyRecord(Secrets.FIRST_KEY))
        val token = keystore.sealedBeforeTheRecord("token")

        keystore.blank = true
        keystore.secrets().recordKeys(SecretStore.SETTINGS, listOf("legacy", token))
        assertEquals(setOf(1, 2), keystore.record.versions())
        val sealed = keystore.secrets().seal("new")
        assertTrue(sealed, sealed.startsWith("sealed3:"))
        assertEquals(listOf(3), keystore.made)

        keystore.blank = false
        assertEquals("token", keystore.secrets().open(token))
        assertEquals("new", keystore.secrets().open(sealed))
    }

    /**
     * The first launch after an update counts each store as it opens, the folders' passwords once the library has
     * migrated: a seal meanwhile, the Keystore reading empty, fails for now rather than make a key the store not yet
     * counted may name, and once both are counted the seal moves on past every key they name.
     */
    @Test
    fun noKeyIsMadeBeforeEveryStoreIsCounted() {
        val keystore = FakeKeystore(TestKeyRecord(Secrets.FIRST_KEY))
        val password = keystore.sealedBeforeTheRecord("password")
        keystore.blank = true
        val secrets = keystore.secrets(SecretStore.entries.toSet())

        secrets.recordKeys(SecretStore.SETTINGS, listOf("legacy"))
        assertThrows(SecretsUnavailableException::class.java) { secrets.seal("token") }
        assertEquals(emptyList<Int>(), keystore.made)

        secrets.recordKeys(SecretStore.FOLDERS, listOf(password))
        val sealed = secrets.seal("token")
        assertTrue(sealed, sealed.startsWith("sealed3:"))
        assertEquals(listOf(3), keystore.made)

        keystore.blank = false
        assertEquals("password", keystore.secrets().open(password))
        assertEquals("token", keystore.secrets().open(sealed))
    }

    /** Recording at launch reads the stored text alone: a Keystore failing at every call is never asked. */
    @Test
    fun recordingAtLaunchAsksTheKeystoreNothing() {
        val failing = object : Keyring {
            override fun listed(): Set<Int> = error("Listed")

            override fun holds(version: Int): Boolean = error("Asked")

            override fun load(version: Int): SecretKey = error("Loaded")

            override fun create(version: Int): SecretKey = error("Made")
        }
        val record = TestKeyRecord()
        Secrets(failing, record).recordKeys(SecretStore.FOLDERS, listOf("sealed2:AAAA", "sealed7:not base64!", "legacy", ""))
        assertEquals(setOf(2, 7), record.versions())
    }

    /** A key the Keystore lists is made, even if the record missed it: the newest seals, none is made again. */
    @Test
    fun aListedKeyIsRecordedAndSealsWhenNewest() {
        val keystore = FakeKeystore().apply { keys[2] = testKey() }
        assertTrue(keystore.secrets().seal("token").startsWith("sealed2:"))
        assertEquals(setOf(2), keystore.record.versions())
        assertEquals(emptyList<Int>(), keystore.made)
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
        assertEquals(Opened.Unavailable, keystore.secrets().read(before))

        keystore.unusable -= 1
        assertEquals("password", keystore.secrets().open(before))
        assertEquals(listOf(1, 2), keystore.made)
    }

    @Test
    fun aNewKeyThatFailedIsLoadedAgainAtTheNextSeal() {
        val keystore = FakeKeystore()
        keystore.secrets().seal("password")
        keystore.unusable += listOf(1, 2)
        val secrets = keystore.secrets()
        assertThrows(SecretsUnavailableException::class.java) { secrets.seal("token") }
        keystore.unusable -= 2
        assertTrue(secrets.seal("token").startsWith("sealed2:"))
        assertEquals(listOf(1, 2), keystore.made)
    }

    /** On Android 9 a restarting keystore daemon throws UnrecoverableKeyException, a busy one ProviderException. */
    @Test
    fun aPassingFailureIsTriedAgainWithTheSameKey() {
        listOf(UnrecoverableKeyException("Failed to obtain information about key"), ProviderException("Busy")).forEach { failure ->
            var tries = 0
            val good = testKey()
            val versions = mutableListOf<Int>()
            val secrets = secretsOf { version ->
                versions += version
                if (tries++ == 0) throw failure else good
            }
            val sealed = secrets.seal("token")
            assertTrue("$failure: $sealed", sealed.startsWith("sealed1:"))
            assertEquals("$failure", "token", secretsOf { good }.open(sealed))
            assertEquals("$failure", listOf(1, 1), versions)
        }
    }

    @Test
    fun aKeystoreThatStillFailsSaysSo() {
        val secrets = secretsOf { throw ProviderException("Failed to generate key") }
        val e = assertThrows(SecretsUnavailableException::class.java) { secrets.seal("token") }
        assertTrue(e.cause is ProviderException)
    }
}
