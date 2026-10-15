package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a title's formats say on screen, from the badges stored for it. */
class FormatsTest {
    @Test
    fun definitionsAreNamed() {
        assertEquals(listOf("Ultra HD", "HDR10"), Formats.shown(listOf(Definition.ULTRA_HD.token, "HDR10")))
    }

    @Test
    fun dolbyVisionSaysItsBaseAlready() {
        assertEquals(
            listOf("Ultra HD", "Dolby Vision", "TrueHD Atmos", "7.1"),
            Formats.shown(listOf(Definition.ULTRA_HD.token, "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1")),
        )
        assertEquals(listOf("Dolby Vision"), Formats.shown(listOf("Dolby Vision", "HDR")))
        // HDR10+ is not the base of Dolby Vision: it stays.
        assertEquals(listOf("Dolby Vision", "HDR10+"), Formats.shown(listOf("Dolby Vision", "HDR10+")))
    }

    @Test
    fun aNameReadAsTrueHdAndAtmosIsOneFormat() {
        assertEquals(listOf("Ultra HD", "TrueHD Atmos"), Formats.shown(listOf(Definition.ULTRA_HD.token, "Atmos", "TrueHD")))
        assertEquals(listOf("Atmos"), Formats.shown(listOf("Atmos")))
    }

    @Test
    fun fourAtMostInTheirOrder() {
        val many = listOf(Definition.ULTRA_HD.token, "HDR10+", "TrueHD Atmos", "7.1", "Remux")
        assertEquals(listOf("Ultra HD", "HDR10+", "TrueHD Atmos", "7.1"), Formats.shown(many))
        assertEquals(listOf("Ultra HD", "HDR10+"), Formats.shown(many, max = 2))
    }
}
