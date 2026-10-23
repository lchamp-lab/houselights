package app.houselights.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page a phone opens: the line to paste, Copier and Télécharger, then the lines newest first, escaped. */
class JournalPageTest {
    private val head =
        JournalHead(listOf("Houselights 0.3.0", "ZIDOO Z1000 Pro (6.4.68)", "Mes fichiers (NFS)", "Trakt connecté"), "23/10/2026 10:42")
    private val words =
        JournalWords("fr", "Journal", "Les dernières lignes écrites par l’app.", "Copier", "Copié", "Télécharger", "Aucune ligne.")
    private val lines = listOf(
        "10-23 10:31:04.200  4321  4350 D Jellyfin: GET /Items?api_key=•••&ParentId=42",
        "10-23 10:41:58.100  4321  4350 I Trakt   : Sync started <check>",
        "10-23 10:42:11.123  4321  4350 W Trakt   : Trakt could not be synced: \"unreadable\" & left out",
        "10-23 10:42:12.000  4321  4350 E Houselights: Background task failed",
        "--------- beginning of crash",
    )

    @Test
    fun theHeadIsOneLineToPaste() {
        assertEquals("Houselights 0.3.0 · ZIDOO Z1000 Pro (6.4.68) · Mes fichiers (NFS) · Trakt connecté · 23/10/2026 10:42", head.line)
        val page = page()
        val line = "<b>Houselights 0.3.0</b> · ZIDOO Z1000 Pro (6.4.68) · Mes fichiers (NFS) · Trakt connecté · 23/10/2026 10:42"
        assertTrue(page, page.contains(line))
    }

    @Test
    fun theLinesAreNewestFirstWithTheirTimeLevelAndTag() {
        val page = page()
        val newest = page.indexOf("10:42:12 E Houselights Background task failed")
        val warning = page.indexOf("10:42:11 W Trakt    Trakt could not be synced")
        val oldest = page.indexOf("10:31:04 D Jellyfin GET /Items")
        assertTrue(page, newest in 0 until warning && warning < oldest)
        // A line logcat writes otherwise is shown as it is.
        assertTrue(page.indexOf("--------- beginning of crash") in 0 until newest)
    }

    @Test
    fun warningsAndErrorsAreInColourAndMasksLit() {
        val page = page()
        assertTrue(page, page.contains("<span class=\"w\">10:42:11 W Trakt"))
        assertTrue(page, page.contains("<span class=\"e\">10:42:12 E Houselights"))
        assertTrue(page, page.contains("api_key=<span class=\"m\">•••</span>&amp;ParentId=42"))
    }

    @Test
    fun everyLineIsEscaped() {
        val page = page(listOf("10-23 10:42:11.123  4321  4350 I Scan    : <script>alert('x')</script> \"a\" & b"))
        assertFalse(page, page.contains("<script>alert"))
        assertTrue(page, page.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &quot;a&quot; &amp; b"))
    }

    /** Whatever gave the lines, the page and the text mask them, and what was masked stays so. */
    @Test
    fun everyLineIsMaskedAgain() {
        val raw = listOf(
            "10-23 10:42:11.123  4321  4350 I Playback: Launch smb://lea:my pass@nas/film.mkv",
            "10-23 10:42:12.000  4321  4350 D Settings: password: hunter2",
        )
        val page = page(raw + lines.first())
        val text = JournalPage.text(head, raw + lines.first())
        listOf(page, text).forEach { shown ->
            listOf("lea:", "my pass", "hunter2").forEach { assertFalse(shown, shown.contains(it)) }
        }
        assertTrue(page, page.contains("smb://<span class=\"m\">•••</span>@nas/film.mkv"))
        assertTrue(page, page.contains("password: <span class=\"m\">•••</span>"))
        assertTrue(page, page.contains("api_key=<span class=\"m\">•••</span>&amp;ParentId=42"))
        assertTrue(text, text.contains("smb://•••@nas/film.mkv\n"))
        assertTrue(text, text.contains("${lines.first()}\n"))
    }

    @Test
    fun itsStyleAndScriptCarryTheNonceAndNothingComesFromElsewhere() {
        val page = page()
        assertTrue(page.contains("<style nonce=\"n0nce\">"))
        assertTrue(page.contains("<script nonce=\"n0nce\">"))
        assertFalse(page, Regex("""(src|href)=["']?(https?:)?//""").containsMatchIn(page))
        assertFalse(page, page.contains("@import"))
        assertTrue(page.contains("<html lang=\"fr\">"))
        assertTrue(page.contains("<a class=\"g\" href=\"k7f2.txt\" download>Télécharger</a>"))
        assertTrue(page.contains("data-done=\"Copié\">Copier</button>"))
    }

    @Test
    fun withoutLinesThePageSaysSo() {
        val page = page(emptyList())
        assertTrue(page.contains("Aucune ligne."))
        assertFalse(page.contains("<pre"))
    }

    @Test
    fun theTextIsTheHeadThenTheLinesOldestFirst() {
        val text = JournalPage.text(head, lines.take(2))
        assertEquals("${head.line}\n\n${lines[0]}\n${lines[1]}\n", text)
    }

    private fun page(of: List<String> = lines) = JournalPage.html(head, of, words, "k7f2.txt", "n0nce")
}
