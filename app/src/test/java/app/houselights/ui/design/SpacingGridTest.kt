package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Space and insets sit on the 4 dp grid, so every gap is one of a few steps. */
class SpacingGridTest {
    /** The dp values an object declares (a Dp is stored as its Float value). */
    private fun measures(owner: Any): Map<String, Float> =
        owner.javaClass.declaredFields
            .filter { it.type == Float::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                field.name to field.getFloat(owner)
            }

    @Test
    fun spaceAndInsetsAreOnTheGrid() {
        val all = measures(HlSpace) + measures(HlInset)
        assertTrue("No measures found", all.isNotEmpty())
        all.forEach { (name, value) -> assertEquals("$name is $value dp", 0f, value % 4f) }
    }
}
