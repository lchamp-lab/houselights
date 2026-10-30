package app.houselights.ui.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HlMotion
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each moves over its own pair ([HlMotion.inOut]): a card steps back over FOCUS_IN and comes forward over FOCUS_OUT,
 * a sheet travels in over FOCUS_IN while its opacity takes PLATE_IN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FocusTimingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aCardRecedesOverTheFocusPair() {
        val rowFocused = mutableStateOf(false)
        var recede: () -> Float = { 0f }
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            CompositionLocalProvider(LocalGroupFocused provides rowFocused.value) {
                ArtworkSurface({}, Modifier.size(SIDE.dp)) { recede = LocalArtworkRecede.current }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME)
        turn(rowFocused, true)
        compose.mainClock.advanceTimeBy(PAST_PLATE_IN)
        assertTrue("still stepping back past PLATE_IN: ${recede()}", recede() < 1f)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(1f, recede(), 0f)
        turn(rowFocused, false)
        compose.mainClock.advanceTimeBy(PAST_PLATE_OUT)
        assertTrue("still coming forward past PLATE_OUT: ${recede()}", recede() > 0f)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(0f, recede(), 0f)
    }

    @Test
    fun aSheetsOpacityTakesThePlatePairAndItsTravelTheFocusPair() {
        var motion: SheetMotion? = null
        compose.mainClock.autoAdvance = false
        compose.setTvContent { motion = rememberSheetMotion {} }
        compose.mainClock.advanceTimeBy(PAST_PLATE_IN)
        val opened = checkNotNull(motion)
        assertEquals("lit by PLATE_IN", 1f, opened.opacity, 0f)
        assertTrue("still travelling until FOCUS_IN: ${opened.travel}", opened.travel < 1f)
        compose.mainClock.advanceTimeBy(SETTLED)
        assertEquals(1f, opened.travel, 0f)
    }

    /** [state] set to [on], told to the composition at once, as the clock does not move by itself. */
    private fun turn(state: MutableState<Boolean>, on: Boolean) {
        state.value = on
        Snapshot.sendApplyNotifications()
    }

    private companion object {
        const val SIDE = 100
        const val FRAME = 16L

        /** Past PLATE_IN and a frame or two for the animation to start, short of FOCUS_IN. */
        const val PAST_PLATE_IN = HlMotion.PLATE_IN + 3 * FRAME + 2

        /** Past PLATE_OUT and the frames it starts on, short of FOCUS_OUT. */
        const val PAST_PLATE_OUT = HlMotion.PLATE_OUT + 3 * FRAME + 2

        const val SETTLED = 1_000L
    }
}
