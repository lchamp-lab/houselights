package app.houselights

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.houselights.core.SourceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.KClass

/** A process restored once its source can no longer be read: every screen that shows one gives way to the welcome screen. */
@RunWith(RobolectricTestRunner::class)
class LostSourceTest {
    @get:Rule
    val compose = createComposeRule()

    /** The app's screens as navigation holds them. */
    private fun screens(): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Welcome) {
                composable<Route.Welcome> {}
                composable<Route.JellyfinLogin> {}
                composable<Route.AddFolder> {}
                composable<Route.Home> {}
                composable<Route.Library> {}
                composable<Route.Item> {}
                composable<Route.Search> {}
                composable<Route.Settings> {}
            }
        }
        compose.waitForIdle()
        return nav
    }

    private fun NavHostController.screen(route: KClass<*>): NavDestination = graph.first { it.hasRoute(route) }

    @Test
    fun withoutASourceOnlyTheScreensThatSetOneUpStay() {
        val nav = screens()
        val stay = listOf(Route.Welcome::class, Route.JellyfinLogin::class, Route.AddFolder::class)
        val leave = listOf(Route.Home::class, Route.Library::class, Route.Item::class, Route.Search::class, Route.Settings::class)
        stay.forEach { assertFalse("$it", mustWelcome(null, nav.screen(it))) }
        leave.forEach { assertTrue("$it", mustWelcome(null, nav.screen(it))) }
        assertEquals(stay.size + leave.size, nav.graph.count())
    }

    @Test
    fun withASourceEveryScreenStays() {
        val nav = screens()
        nav.graph.forEach { screen ->
            assertFalse(mustWelcome(SourceConfig.Local, screen))
            assertFalse(mustWelcome(SourceConfig.Jellyfin("https://nas", null, "u1", null, "t"), screen))
        }
        // Before navigation has a screen, nothing is decided.
        assertFalse(mustWelcome(null, null))
    }
}
