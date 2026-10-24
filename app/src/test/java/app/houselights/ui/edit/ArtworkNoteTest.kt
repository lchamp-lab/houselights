package app.houselights.ui.edit

import app.houselights.R
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Test

/** Why TMDB's images are missing, said as what fixes it. */
class ArtworkNoteTest {
    @Test
    fun aRefusedCertificateIsSaidAsOne() {
        assertEquals(R.string.error_certificate, onlineNote(UiError.CERTIFICATE))
        assertEquals(R.string.error_tmdb_key_refused, onlineNote(UiError.TMDB_KEY_REFUSED))
        assertEquals(R.string.artwork_offline, onlineNote(UiError.TMDB_OFFLINE))
    }
}
