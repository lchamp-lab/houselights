package app.houselights.core

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.houselights.testing.FakeKeystore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The key versions made on this install, kept across launches in its preferences. */
@RunWith(RobolectricTestRunner::class)
class SavedKeyRecordTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("secret_keys", Context.MODE_PRIVATE)

    /** Preferences that write nothing, as on a full disk: commit() says so. */
    private class Unwritable(private val prefs: SharedPreferences) : SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by prefs.edit() {
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

            override fun commit() = false
        }
    }

    @Test
    fun aFreshInstallStartsEmptyAndKeepsWhatItMakes() {
        val record = SavedKeyRecord(prefs) { false }
        assertEquals(emptySet<Int>(), record.versions())
        record.add(1)
        record.add(1)
        // The next launch reads it back, though the app has been updated since.
        assertEquals(setOf(1), SavedKeyRecord(prefs) { true }.versions())
    }

    /** Installed over a version from before the record, which may have made the first key: the record starts with it. */
    @Test
    fun anUpdateFromBeforeTheRecordStartsWithTheFirstKey() {
        val record = SavedKeyRecord(prefs) { true }
        assertEquals(setOf(Secrets.FIRST_KEY), record.versions())
        record.add(2)
        assertEquals(setOf(1, 2), SavedKeyRecord(prefs) { false }.versions())
    }

    /**
     * Updated over 0.2, which kept no record and had moved on to a second key, never opened since: the launch records
     * it from the secret it sealed, so the Keystore reading empty when the next seal asks, then making the key asked
     * for, never gets to make it again, and the next launch, the app updated again since, still has it.
     */
    @Test
    fun anUpdateFrom02RecordsTheKeysItsSecretsName() = firstLaunchThroughABlankKeystore()

    /** Updated over 0.3, whose record began with the first key alone and never saw the second: the same. */
    @Test
    fun anUpdateFrom03AddsTheKeysItsSecretsName() {
        assertEquals(setOf(Secrets.FIRST_KEY), SavedKeyRecord(prefs) { true }.versions())
        firstLaunchThroughABlankKeystore()
    }

    /** The first launch after an update, the Keystore reading empty from its start, then making the key asked for. */
    private fun firstLaunchThroughABlankKeystore() {
        val keystore = FakeKeystore(SavedKeyRecord(prefs) { true })
        val token = keystore.sealedBeforeTheRecord("token")
        keystore.blank = true
        keystore.secrets().recordKeys(listOf(token))
        val sealed = keystore.secrets().seal("new")
        assertTrue(sealed, sealed.startsWith("sealed3:"))
        assertEquals(listOf(3), keystore.made)

        keystore.blank = false
        assertEquals("token", keystore.secrets().open(token))
        assertEquals(setOf(1, 2, 3), SavedKeyRecord(prefs) { true }.versions())
    }

    /** A fresh install finds no key at launch, then makes its first when it first seals, recorded for the next launch. */
    @Test
    fun aFreshInstallFindsNoKeyAtLaunchThenMakesItsFirst() {
        val keystore = FakeKeystore(SavedKeyRecord(prefs) { false })
        keystore.secrets().recordKeys(listOf("legacy"))
        assertEquals(emptySet<Int>(), keystore.record.versions())
        assertTrue(keystore.secrets().seal("token").startsWith("sealed1:"))
        assertEquals(listOf(1), keystore.made)
        assertEquals(setOf(1), SavedKeyRecord(prefs) { true }.versions())
    }

    /**
     * A record that cannot be written keeps nothing the launch finds, and sealing falls back to asking the Keystore: the
     * first key giving none, the seal moves on to the second, which giving none once but held is never made again, the
     * seal failing rather than lose what it sealed.
     */
    @Test
    fun aRecordThatCannotBeWrittenFallsBackToAskingTheKeystore() {
        val record = SavedKeyRecord(Unwritable(prefs)) { true }
        val keystore = FakeKeystore(record, listing = false)
        val token = keystore.sealedBeforeTheRecord("token")
        keystore.secrets().recordKeys(listOf(token))
        assertEquals(setOf(Secrets.FIRST_KEY), record.versions())

        keystore.absent += mapOf(1 to 2, 2 to 1)
        assertThrows(SecretsUnavailableException::class.java) { keystore.secrets().seal("new") }
        assertEquals(emptyList<Int>(), keystore.made)
        assertEquals("token", keystore.secrets().open(token))
    }
}
