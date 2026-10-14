package app.houselights.providers.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val secrets = Secrets { key }

    @Test
    fun version7SealsPasswords() = runTest {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 6) {
            val columns = "(id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt)"
            execSQL("INSERT INTO sources $columns VALUES (1, 'Films', 'MOVIES', 'SMB', 'nas', 'media', 'films', 'zidoo', 's3cret', '', 0)")
            execSQL("INSERT INTO sources $columns VALUES (2, 'Séries', 'SHOWS', 'NFS', 'nas', 'volume1/media', 'series', '', '', '', 0)")
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            val raw = db.openHelper.readableDatabase.query("SELECT password FROM sources ORDER BY id").use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            }
            assertTrue(secrets.isSealed(raw[0]))
            assertEquals("", raw[1])
            assertEquals(listOf("s3cret", ""), db.dao().sources().sortedBy { it.id }.map { it.password.value })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun version8KeepsWhatLibrariesHoldFromBeingNew() = runTest {
        val name = "migration-8-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 7) {
            val columns = "(id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt)"
            execSQL("INSERT INTO sources $columns VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000)")
            execSQL("INSERT INTO sources $columns VALUES (2, 'Séries', 'SHOWS', 'NFS', 'nas', 'media', 'series', '', '', '', 0)")
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            assertEquals(listOf(1L to 1000L, 2L to 0L), db.dao().firstScans().sortedBy { it.id }.map { it.id to it.firstScanAt })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
