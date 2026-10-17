package app.houselights.ui.library

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.Person
import app.houselights.core.ProviderCapabilities
import app.houselights.core.Quality
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wall's filter menu: the picture, the sound and the kind of the files as the library holds them, never
 * an empty choice, only where the source filters by them; a person set from elsewhere, which it takes off and
 * puts back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "fr-$TV")
class FilterMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val local = ProviderCapabilities(
        markPlayed = true, favorites = true, similar = true, pathMapping = false, formatNumbers = true, formatFilters = true,
        personFilter = true,
    )
    private val jellyfin =
        ProviderCapabilities(markPlayed = true, favorites = true, similar = true, pathMapping = true, personFilter = true)

    private val facets = LibraryFacets(
        genres = listOf("Drame"),
        qualities = listOf(Quality.UHD_4K, Quality.HDR, Quality.DOLBY_VISION),
        pictures = listOf("4K", "1080p", "Dolby Vision", "HDR10", "HDR"),
        sounds = listOf("TrueHD Atmos", "E-AC3"),
        fileTypes = listOf("mkv", "bdmv"),
    )

    private var filter by mutableStateOf(LibraryFilter())

    /** The menu open, as OK on the button opens it. */
    private fun open(facets: LibraryFacets, can: ProviderCapabilities, person: Person? = null) {
        compose.setTvContent { FilterButton(filter, facets, can, person) { filter = it } }
        compose.onNodeWithText("Filtrer").requestFocus()
        compose.press(Key.DirectionCenter)
    }

    /** The menu scrolled to [option]: it is offered. */
    private fun offered(option: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(option))
    }

    private fun choose(option: String) {
        offered(option)
        compose.onNodeWithText(option).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun picturesSoundsAndKindsOfFileAreOfferedAsTheLibraryHoldsThem() {
        open(facets, local)
        // Their picture, finer than their quality, in its place: definitions, any HDR, then its kinds.
        listOf("Image", "Toutes les images", "Ultra HD", "Full HD", "HDR", "Dolby Vision", "HDR10").forEach(::offered)
        listOf("Son", "Tous les sons", "Dolby TrueHD Atmos", "Dolby Digital Plus").forEach(::offered)
        listOf("Fichier", "Tous les fichiers", "MKV", "Blu-ray").forEach(::offered)
        compose.onNodeWithText("Qualité").assertDoesNotExist()
        // A name's plain HDR is one of any HDR: offered once.
        assertEquals(1, compose.onAllNodesWithText("HDR").fetchSemanticsNodes().size)
        compose.onNodeWithText("Personne").assertDoesNotExist()
    }

    @Test
    fun aChoiceTheLibraryDoesNotHoldIsNeverOffered() {
        open(LibraryFacets(genres = listOf("Drame")), local)
        offered("Genre")
        listOf("Image", "Son", "Fichier", "Qualité").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun aSourceThatCannotFilterByFormatsOffersTheirQualityAlone() {
        open(facets, jellyfin)
        offered("Qualité")
        listOf("Image", "Son", "Fichier", "Dolby TrueHD Atmos", "MKV").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun choosingASoundFiltersByItAndTheButtonNamesIt() {
        open(facets, local)
        choose("Dolby TrueHD Atmos")
        assertEquals(LibraryFilter(sound = "TrueHD Atmos"), filter)
        // The menu stays open while choosing: the button under it names the filter.
        assertEquals(2, compose.onAllNodesWithText("Dolby TrueHD Atmos").fetchSemanticsNodes().size)
    }

    @Test
    fun aPictureTakesThePlaceOfAQualityAndAnyHdrOfAPicture() {
        filter = LibraryFilter(quality = Quality.UHD_4K)
        open(facets, local)
        choose("Dolby Vision")
        assertEquals(LibraryFilter(picture = "Dolby Vision"), filter)
        choose("HDR")
        assertEquals(LibraryFilter(quality = Quality.HDR), filter)
        choose("Toutes les images")
        assertEquals(LibraryFilter(), filter)
    }

    /** Opened from the library in numbers before its menu knew what it holds: the filter shows, to be taken off. */
    @Test
    fun aFilterSetElsewhereIsOfferedSoItCanBeTakenOff() {
        filter = LibraryFilter(sound = "DTS-HD MA")
        open(LibraryFacets(), local)
        offered("Son")
        choose("Tous les sons")
        assertEquals(LibraryFilter(), filter)
    }

    @Test
    fun aPersonTheWallWasOpenedForIsTakenOffAndPutBack() {
        val person = Person("Denis Villeneuve", null, null, "p7")
        filter = LibraryFilter(person = "p7")
        open(facets, local, person)
        offered("Personne")
        choose("Toutes les personnes")
        assertEquals(LibraryFilter(), filter)
        // Still offered while the wall shows: put back.
        choose("Denis Villeneuve")
        assertEquals(LibraryFilter(person = "p7"), filter)
    }
}
