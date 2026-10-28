package app.houselights.providers.local

import app.houselights.core.Secrets
import app.houselights.testing.FakeKeystore
import app.houselights.testing.TestKeyRecord
import app.houselights.testing.secretsOf
import app.houselights.testing.testKey
import app.houselights.testing.testSecrets
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.ProviderException

/** What the library does with folders' passwords at launch, beyond its migrations ([MigrationTest]). */
@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseTest {
    private lateinit var db: LibraryDatabase
    private val secrets = testSecrets()

    @Before
    fun open() {
        db = testDatabase(secrets)
    }

    @After
    fun close() = db.close()

    private fun source(name: String, password: String) = SourceEntity(
        name = name,
        kind = SourceKind.MOVIES,
        host = "nas",
        share = "media",
        path = name.lowercase(),
        username = "zidoo",
        password = Secret(password),
    )

    /** The passwords as stored, by folder id. */
    private fun raw(): Map<Long, String> = db.openHelper.readableDatabase.query("SELECT id, password FROM sources").use {
        buildMap { while (it.moveToNext()) put(it.getLong(0), it.getString(1)) }
    }

    /** A password left plain by a faulty Keystore (migration 6 to 7) is sealed at a later launch that can seal. */
    @Test
    fun aPasswordLeftInPlainTextIsSealedAtTheNextLaunch() = runTest {
        val dao = db.dao()
        val films = dao.insertSource(source("Films", "first"))
        val series = dao.insertSource(source("Series", "s3cret"))
        val open = dao.insertSource(source("Open", ""))
        db.openHelper.writableDatabase.execSQL("UPDATE sources SET password = 'pl@in' WHERE id = ?", arrayOf<Any>(films))
        val sealedBefore = raw().getValue(series)

        // The Keystore still fails: the password waits, nothing breaks.
        LibraryDatabase.sealPlainPasswords(dao, secretsOf { throw ProviderException("Keystore operation failed") })
        assertEquals("pl@in", raw().getValue(films))

        LibraryDatabase.sealPlainPasswords(dao, secrets)
        val after = raw()
        assertTrue(after.getValue(films), secrets.isSealed(after.getValue(films)))
        assertEquals(sealedBefore, after.getValue(series))
        assertEquals("", after.getValue(open))
        assertEquals(mapOf(films to "pl@in", series to "s3cret", open to ""), dao.sources().associate { it.id to it.password.value })
    }

    /**
     * Installed over a version from before the record that had moved on to a second key: the launch records the key a
     * folder's password was sealed with before it seals one left plain, so the Keystore reading empty then, and making
     * the key asked for, never gets to make the second again, which would lose that password.
     */
    @Test
    fun theLaunchRecordsThePasswordsKeysBeforeItSealsOne() = runTest {
        val keystore = FakeKeystore(TestKeyRecord(Secrets.FIRST_KEY))
        val dao = db.dao()
        val films = dao.insertSource(source("Films", ""))
        val series = dao.insertSource(source("Series", ""))
        val sealed = keystore.sealedBeforeTheRecord("s3cret")
        db.openHelper.writableDatabase.execSQL("UPDATE sources SET password = ? WHERE id = ?", arrayOf<Any>(sealed, films))
        db.openHelper.writableDatabase.execSQL("UPDATE sources SET password = 'pl@in' WHERE id = ?", arrayOf<Any>(series))

        keystore.blank = true
        LibraryDatabase.sealPlainPasswords(dao, keystore.secrets())
        val after = raw()
        assertTrue(after.getValue(series), after.getValue(series).startsWith("sealed3:"))
        assertEquals(listOf(3), keystore.made)

        keystore.blank = false
        assertEquals("s3cret", keystore.secrets().open(after.getValue(films)))
        assertEquals("pl@in", keystore.secrets().open(after.getValue(series)))
    }

    /** A password the Keystore does not open for now reads as empty, yet saving the folder keeps it as stored. */
    @Test
    fun aPasswordTheKeystoreDoesNotOpenIsKeptAsStored() = runTest {
        val key = testKey()
        var down = true
        val flaky = testDatabase(secretsOf { if (down) throw ProviderException("Keystore operation failed") else key })
        try {
            val dao = flaky.dao()
            val id = dao.insertSource(source("Films", ""))
            val sealed = secretsOf { key }.seal("s3cret")
            flaky.openHelper.writableDatabase.execSQL("UPDATE sources SET password = ? WHERE id = ?", arrayOf<Any>(sealed, id))

            val read = checkNotNull(dao.source(id))
            assertEquals("", read.password.value)
            with(read) { dao.updateConnection(id, "Films 4K", protocol, host, share, path, username, password, uid, gid) }
            val stored = flaky.openHelper.readableDatabase.query("SELECT password FROM sources").use {
                it.moveToFirst()
                it.getString(0)
            }
            assertEquals(sealed, stored)

            down = false
            assertEquals("s3cret", checkNotNull(dao.source(id)).password.value)
        } finally {
            flaky.close()
        }
    }
}
