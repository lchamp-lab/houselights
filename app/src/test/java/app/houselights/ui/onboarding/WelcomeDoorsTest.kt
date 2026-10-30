package app.houselights.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.R
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Welcome's doors from the remote: focus starts on Mes fichiers, Right reaches Jellyfin, whose servers are then looked for. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class WelcomeDoorsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun focusStartsOnMyFilesAndRightReachesJellyfin() {
        var asked = 0
        compose.setTvContent { Welcome(emptyList(), {}, {}, lostSource = false, problem = null, onJellyfinFocus = { asked++ }) }
        fun door(id: Int) = compose.onNodeWithText(compose.activity.getString(id))
        door(R.string.source_local).assertIsFocused()
        assertEquals(0, asked)
        compose.press(Key.DirectionRight)
        door(R.string.source_jellyfin).assertIsFocused()
        assertEquals(1, asked)
    }
}
