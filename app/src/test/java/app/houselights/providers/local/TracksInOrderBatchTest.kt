package app.houselights.providers.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.houselights.testing.testSecrets
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Version 16 marks the tracks the probe read in each file's order a batch of rows at a time: every one is marked, however
 * many batches, in the library the app opens ([LibraryDatabase.create]).
 */
@RunWith(RobolectricTestRunner::class)
class TracksInOrderBatchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun version16MarksEveryProbedFileWhateverTheirNumber() {
        val probed = (MkvProbe.probe(Matroska.sample()) as MkvProbe.Result.Found).info.copy(sizeBytes = 42, probedWith = 4)
        SchemaHistory.create(context, LibraryDatabase.NAME, version = 15) {
            execSQL(
                "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, " +
                    "firstScanAt) VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000, 1000)",
            )
            for (id in 1..FILES) {
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, parts, " +
                        "badges, mediaInfo, probeVersion, addedAt, positionMs, played, favorite) " +
                        "VALUES (?, 'MOVIE', 1, 'Film', 'film', 0, '', '', ?, '', '', ?, 4, 0, 0, 0, 0)",
                    arrayOf<Any?>(id, "Film $id.mkv", MediaProbe.encode(probed)),
                )
            }
        }
        val db = LibraryDatabase.create(context, testSecrets())
        try {
            val marked = db.openHelper.readableDatabase.query("SELECT mediaInfo FROM items").use { rows ->
                generateSequence { if (rows.moveToNext()) rows.getString(0) else null }
                    .count { MediaProbe.decode(it)?.tracksInOrder == true }
            }
            assertEquals(FILES, marked)
        } finally {
            db.close()
        }
    }

    private companion object {
        /** Two of the migration's batches and one row more: the last batch, short, ends the reading. */
        const val FILES = 1_001
    }
}
