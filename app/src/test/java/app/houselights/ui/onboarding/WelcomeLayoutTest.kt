package app.houselights.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.houselights.R
import app.houselights.ui.design.Gradation
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsType
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.cutTexts
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome screen on the TV canvas, in its longest languages and with every note: the lockup whole in its
 * column, the task's title on one line, the choices alike, a line of words each and no mark, and everything inside the
 * safe area, a focused choice's frame included; at the largest text, the choices alike and whole all the same.
 */
@RunWith(RobolectricTestRunner::class)
// Real fonts, so lines break as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "fr-$TV")
    fun inFrench() = holds()

    @Test
    @Config(qualifiers = "de-$TV")
    fun inGerman() = holds()

    @Test
    @Config(qualifiers = "pt-$TV")
    fun inPortuguese() = holds()

    /** The connection kept but not opened for now: its note, in its column, and Réessayer, in the safe area. */
    @Test
    @Config(qualifiers = "de-$TV")
    fun unreadInGerman() = holds(unread = true)

    @Test
    @Config(qualifiers = "fr-$TV")
    fun unreadInFrench() = holds(unread = true)

    @Test
    @Config(qualifiers = "fr-$TV")
    fun inFrenchAtTheLargestText() = choicesHoldAtTheLargestText()

    @Test
    @Config(qualifiers = "de-$TV")
    fun inGermanAtTheLargestText() = choicesHoldAtTheLargestText()

    @Test
    @Config(qualifiers = "pt-$TV")
    fun inPortugueseAtTheLargestText() = choicesHoldAtTheLargestText()

    /** At the largest text, the choices reserve the lines the longer takes: one height, their words whole, in the safe area. */
    private fun choicesHoldAtTheLargestText() {
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, LARGEST_TEXT)) {
                Welcome(emptyList(), {}, {}, lostSource = false, problem = null)
            }
        }
        fun text(id: Int) = compose.activity.getString(id)
        val choices = listOf(R.string.source_local, R.string.source_jellyfin).map {
            compose.onNodeWithText(text(it)).getUnclippedBoundsInRoot()
        }
        assertEquals("the choices alike", choices[0].height, choices[1].height)
        val cut = compose.cutTexts()
        listOf(R.string.source_local_desc, R.string.welcome_jellyfin_desc).forEach { assertFalse(text(it) in cut) }
        val reach = HlFocus.reach
        choices.forEach {
            assertTrue("$it framed in the safe area", it.top - reach >= HlSpace.safeV && it.bottom + reach <= SCREEN_H - HlSpace.safeV)
        }
        noMarks()
    }

    /** The choices say what they are in words: no protocol or service printed as a mark. */
    private fun noMarks() = listOf("NFS", "SMB", "TMDB", "HTTPS", "HTTP").forEach { compose.onNodeWithText(it).assertDoesNotExist() }

    private fun holds(unread: Boolean = false) {
        compose.setTvContent {
            Welcome(
                emptyList(),
                {},
                {},
                lostSource = !unread,
                problem = UiError.OFFLINE,
                settingsReset = !unread,
                readAgain = {}.takeIf { unread },
            )
        }
        fun bounds(id: Int) = compose.onNodeWithText(compose.activity.getString(id)).getUnclippedBoundsInRoot()
        val name = compose.onNodeWithContentDescription(compose.activity.getString(R.string.wordmark)).getUnclippedBoundsInRoot()
        val caps = HlSize.wordmarkHero
        // The mark from the safe area's edge, centred on the name's capitals and hanging past its box.
        val mark = caps * Gradation.LOCKUP_MARK
        val middle = (name.top + name.bottom) / 2
        val lockup = DpRect(name.left - caps * Gradation.lockupIndent, middle - mark / 2, name.right, middle + mark / 2)
        assertEquals(HlSpace.safeH.value, lockup.left.value, PX)
        assertEquals("the name at its full width: $name", WORDMARK_RATIO, name.width / name.height, RATIO)

        val title = bounds(R.string.welcome_title)
        assertEquals("the task's title on one line", HouselightsType.headline.lineHeight.value, title.height.value, PX)
        val choices = listOf(bounds(R.string.source_local), bounds(R.string.source_jellyfin))
        assertEquals("the choices alike", choices[0].height, choices[1].height)
        noMarks()
        assertTrue("the columns apart: $lockup, $title", lockup.right + HlSpace.xl <= title.left)
        assertTrue("the tagline in its column", bounds(R.string.welcome_tagline).right <= title.left - HlSpace.xl)

        val reach = HlFocus.reach
        val framed = choices.map { DpRect(it.left - reach, it.top - reach, it.right + reach, it.bottom + reach) }
        val said = if (unread) {
            listOf(R.string.error_secrets_unread, R.string.retry)
        } else {
            listOf(R.string.welcome_lost_secrets, R.string.welcome_settings_reset)
        }
        val notes = (said + welcomeProblem(UiError.OFFLINE)).map(::bounds)
        notes.forEach { assertTrue("$it in the left column", it.right <= title.left - HlSpace.xl) }
        (listOf(lockup, title) + framed + notes).forEach { box ->
            assertTrue(
                "$box inside the safe area",
                box.left >= HlSpace.safeH && box.right <= SCREEN_W - HlSpace.safeH &&
                    box.top >= HlSpace.safeV && box.bottom <= SCREEN_H - HlSpace.safeV,
            )
        }
    }

    private companion object {
        val SCREEN_W = 960.dp
        val SCREEN_H = 540.dp

        /** `wordmark.xml`'s viewport: the name's width for its height. */
        const val WORDMARK_RATIO = 1196.847f / 103.79f

        /** Bounds are whole test pixels. */
        const val PX = 1f
        const val RATIO = 0.05f

        /** The largest text size Android TV offers. */
        const val LARGEST_TEXT = 1.3f
    }
}
