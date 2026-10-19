package app.houselights.providers.local

import app.houselights.core.Secrets
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
        name = name, kind = SourceKind.MOVIES, host = "nas", share = "media", path = name.lowercase(), username = "zidoo",
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
        LibraryDatabase.sealPlainPasswords(dao, Secrets { throw ProviderException("Keystore operation failed") })
        assertEquals("pl@in", raw().getValue(films))

        LibraryDatabase.sealPlainPasswords(dao, secrets)
        val after = raw()
        assertTrue(after.getValue(films), secrets.isSealed(after.getValue(films)))
        assertEquals(sealedBefore, after.getValue(series))
        assertEquals("", after.getValue(open))
        assertEquals(mapOf(films to "pl@in", series to "s3cret", open to ""), dao.sources().associate { it.id to it.password.value })
    }
}
