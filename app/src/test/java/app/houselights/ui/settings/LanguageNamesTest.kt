package app.houselights.ui.settings

import app.houselights.providers.local.TmdbClient
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.components.ChoiceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.Collator
import java.util.Locale

/**
 * The languages Settings' sheets offer: sorted by their own names as they always were, but once, not each time a pane
 * shows; their options made once for the app's language.
 */
@RunWith(RobolectricTestRunner::class)
class LanguageNamesTest {
    private val order = Collator.getInstance(Locale.ROOT)

    @Test
    fun theLanguagesAreSortedByTheirOwnNamesOnce() {
        assertEquals(TmdbClient.LANGUAGES.sortedWith(compareBy(order) { metadataLanguageName(it) }), metadataLanguages.tags)
        assertEquals(InterfaceLanguage.LANGUAGES.sortedWith(compareBy(order) { interfaceLanguageName(it) }), interfaceLanguages.tags)
        val tracks = trackLanguages.tags
        assertEquals(tracks.sortedWith(compareBy(order) { trackLanguageName(it, Locale.forLanguageTag(it)) }), tracks)
        assertTrue("Norwegian as files tag it", "no" in tracks && "nb" !in tracks)
        assertEquals("each language once", tracks.distinct(), tracks)
        assertSame(metadataLanguages.tags, metadataLanguages.tags)
        assertSame(interfaceLanguages.tags, interfaceLanguages.tags)
        assertSame(tracks, trackLanguages.tags)
    }

    @Test
    fun theOptionsAreMadeOnceForTheAppsLanguage() {
        val french = metadataLanguages.options(Locale.FRENCH)
        assertSame(french, metadataLanguages.options(Locale.FRENCH))
        assertEquals(metadataLanguages.tags.size, french.size)
        // Its own name, then the app's where it differs; its region where TMDB offers several.
        assertTrue(ChoiceOption("Deutsch", "Allemand") in french)
        val labels = french.map { it.label }
        assertTrue(labels.toString(), "Français (Canada)" in labels && "Français (France)" in labels)
        val english = metadataLanguages.options(Locale.ENGLISH)
        assertNotSame(french, english)
        assertTrue(ChoiceOption("Deutsch", "German") in english)
    }
}
