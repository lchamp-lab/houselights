package app.houselights.ui.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * An open panel's title over two lines (« Les volets »): whole where it fits; else broken at its separator, a dash or a
 * French colon, which goes there; else the separator held to the words around it, no line ending or beginning with it.
 */
class VoletTitleTest {
    /** A line fits when it is [width] characters at most: the measure's stand-in. */
    private fun lines(title: String, width: Int = 24) = titleLines(title) { it.length <= width }

    @Test
    fun aTitleThatFitsStaysWhole() {
        assertEquals("Star Wars : Les Derniers Jedi", lines("Star Wars : Les Derniers Jedi", width = 40))
    }

    @Test
    fun aTitleBreaksAtItsSeparatorWhichGoes() {
        assertEquals("Star Wars, épisode II\nL'Attaque des clones", lines("Star Wars, épisode II - L'Attaque des clones"))
        assertEquals("Star Wars\nLes Derniers Jedi", lines("Star Wars : Les Derniers Jedi", width = 20))
        assertEquals(
            "both sides fitting",
            "Mission : Impossible\nDead Reckoning",
            lines("Mission : Impossible – Dead Reckoning", width = 20),
        )
    }

    /** A side too long for one line: the separator is held to its words, never at a line's end or start. */
    @Test
    fun aSeparatorThatCannotBreakIsHeldMidLine() {
        val held = lines("Star Wars, épisode II - L'Attaque des clones", width = 12)
        assertEquals("Star Wars, épisode II - L'Attaque des clones", held)
        assertFalse(" - " in held)
    }

    @Test
    fun aTitleWithoutSeparatorIsLeftToWrap() {
        assertEquals("La Guerre des étoiles, un nouvel espoir", lines("La Guerre des étoiles, un nouvel espoir"))
    }
}
