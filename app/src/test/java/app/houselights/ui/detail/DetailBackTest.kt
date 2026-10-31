package app.houselights.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.houselights.R
import app.houselights.core.Person
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressBack
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlaybackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back on a title's page scrolled down to its cast returns to its actions, Lire focused at the page's top; a second leaves. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val settle = SettleMainLooper()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** People with pages of their own, whose cards take focus. */
    private val cast = (1..CAST).map { Person("Actor $it", "Role $it", null, id = "tmdb:$it", titleCount = 3) }
    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Sicario").copy(overview = "A long overview. ".repeat(OVERVIEW), cast = cast)
    }

    @After
    fun stop() = work.cancel()

    private val left get() = compose.activity.isFinishing

    private fun play() = compose.onNode(hasText(compose.activity.getString(R.string.play)) and hasClickAction())

    private fun page() {
        val vm = DetailViewModel("m1", { provider }, FakeEvents(), work, tracksOf = { null })
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { _, _, _ -> },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun backFromTheCastReturnsToLireAtThePagesTopThenLeaves() {
        page()
        val top = play().assertIsFocused().getUnclippedBoundsInRoot().top
        val onTheCast = isFocused() and (hasText("Actor 1", substring = true) or hasContentDescription("Actor 1", substring = true))
        var downs = 0
        while (compose.onAllNodes(onTheCast).fetchSemanticsNodes().isEmpty()) {
            check(downs++ < ROWS) { "the cast not reached from Lire in $ROWS presses" }
            compose.press(Key.DirectionDown)
        }

        compose.pressBack()
        assertEquals("Lire where the page opened it", top, play().assertIsFocused().assertIsDisplayed().getUnclippedBoundsInRoot().top)
        assertFalse("the first Back stays on the page", left)
        compose.pressBack()
        assertTrue(left)
    }

    private companion object {
        const val CAST = 8
        const val OVERVIEW = 40

        /** More than the rows between Lire and the cast. */
        const val ROWS = 6
    }
}
