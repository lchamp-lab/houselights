package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefinitionTest {
    @Test
    fun definitionComesFromTheWidth() {
        // Scope films are shorter than their definition: 3832 × 1600 is Ultra HD, 1920 × 804 Full HD.
        assertEquals(Definition.ULTRA_HD, Definition.of(3832))
        assertEquals(Definition.FULL_HD, Definition.of(1920))
        assertEquals(Definition.HD, Definition.of(1280))
        assertEquals(Definition.SD, Definition.of(720))
        assertNull(Definition.of(null))
    }

    @Test
    fun badgesShowDefinitionsByName() {
        assertEquals("Ultra HD", Definition.label("4K"))
        assertEquals("Full HD", Definition.label("1080p"))
        assertEquals("HD", Definition.label("720p"))
        assertEquals("Dolby Vision", Definition.label("Dolby Vision"))
    }

    @Test
    fun storedTokensNeverChange() {
        // Stored in scanned libraries' badges and matched by filters: these tokens must not change.
        assertEquals(listOf("4K", "1080p", "720p", "SD"), Definition.entries.map { it.token })
    }
}
