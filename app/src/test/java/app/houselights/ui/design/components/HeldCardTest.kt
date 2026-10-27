package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A title held while its file is missing wears the ring before any count, check or dot, and is read as "not found": a
 * new film held is no news of its shelf, a show held no count of episodes to watch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HeldCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    /** Added an hour ago, not watched: new. */
    private val dune = MediaItem("m1", MediaKind.MOVIE, "Dune", addedAt = now - HOUR)

    /** Six episodes to watch. */
    private val mobLand = MediaItem("s1", MediaKind.SERIES, "MobLand", user = UserState(unplayedCount = 6))

    private fun readAs(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    @Test
    fun aTitleThereIsReadAsItStands() {
        assertTrue(dune.wearsNewDot())
        compose.setTvContent {
            Row {
                PosterCard(dune, onClick = {})
                PosterCard(mobLand, onClick = {})
            }
        }
        compose.onAllNodes(readAs("new")).assertCountEquals(1)
        compose.onAllNodes(readAs("6${Typography.nbsp}unwatched episodes")).assertCountEquals(1)
    }

    @Test
    fun aTitleHeldIsReadAsNotFoundFirst() {
        val held = listOf(dune.copy(missingSince = now - HOUR), mobLand.copy(missingSince = now - HOUR))
        held.forEach { assertFalse("${it.title} wears no new dot, nor counts as news", it.wearsNewDot()) }
        compose.setTvContent {
            Row {
                PosterCard(held[0], onClick = {})
                LandscapeCard(held[1], onClick = {})
            }
        }
        compose.onAllNodes(readAs("not found")).assertCountEquals(2)
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
