package app.houselights.settings

import app.houselights.core.PathMapping
import app.houselights.core.SourceConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A log line or an error message built from the settings never shows a secret they hold. */
class SecretsInTextTest {
    private val server = SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "j3llyf1n-t0ken")

    @Test
    fun theServerTokenIsNeverWritten() {
        val text = server.toString()
        assertFalse(text, text.contains("j3llyf1n-t0ken"))
        // What says which server it is stays readable.
        assertTrue(text, text.contains("https://nas:8920"))
    }

    @Test
    fun theSettingsWriteNoneOfTheirSecrets() {
        val settings = AppSettings(
            loaded = true,
            source = server,
            tmdbApiKey = "tmdb-k3y",
            pathMappings = listOf(PathMapping("/media", "smb://zidoo:pa55word@nas/media")),
        )
        val text = settings.toString()
        for (secret in listOf("j3llyf1n-t0ken", "tmdb-k3y", "pa55word")) assertFalse(text, text.contains(secret))
        assertTrue(text, text.contains("loaded=true"))
    }
}
