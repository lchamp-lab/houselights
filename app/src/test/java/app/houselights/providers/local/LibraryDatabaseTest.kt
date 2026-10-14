package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryDatabaseTest {
    private lateinit var db: LibraryDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun sourcesRoundTrip() = runTest {
        val source = SourceEntity(
            name = "Films", kind = SourceKind.MOVIES, host = "nas", share = "media", path = "films", username = "", password = "",
        )
        val id = db.dao().insertSource(source)
        assertEquals(listOf("Films"), db.dao().sources().map { it.name })
        assertEquals(id, db.dao().sources().single().id)
    }
}
