package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypesetTest {
    @Test
    fun punctuationStaysWithItsWord() {
        assertEquals("un « fixer », connaît", Typeset.text("un « fixer », connaît"))
        assertEquals("Attention : danger !", Typeset.text("Attention : danger !"))
    }

    @Test
    fun englishTextIsUntouched() {
        assertEquals("Two families: a war!", Typeset.text("Two families: a war!"))
        assertNull(Typeset.text(null))
    }
}
