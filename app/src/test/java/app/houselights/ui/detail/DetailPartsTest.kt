package app.houselights.ui.detail

import app.houselights.ui.testing.film
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a film in several files offers its parts beside Lire, without crowding its actions row. */
class DetailPartsTest {

    @Test
    fun oneOrTwoFilesKeepTheirButtons() {
        assertFalse("a film in one file has no parts", partsInMenu(0))
        assertFalse(partsInMenu(2))
    }

    @Test
    fun threePartsOrMoreAreChosenInAMenu() {
        assertTrue("an old rip on three CDs", partsInMenu(3))
        assertTrue(partsInMenu(4))
    }

    @Test
    fun theMenuListsThePartsInOrderAndOpensOnTheFirst() {
        val parts = (1..3).map { film("heat-p$it") }
        val played = mutableListOf<String>()
        val options = partOptions(parts, listOf("Partie 1/3", "Partie 2/3", "Partie 3/3")) { played += it.id }
        assertEquals(listOf("Partie 1/3", "Partie 2/3", "Partie 3/3"), options.map { it.label })
        // A menu opens on its option chosen, else on its first: no part is chosen.
        assertTrue(options.none { it.selected })
        options[1].onSelect()
        assertEquals(listOf("heat-p2"), played)
    }
}
