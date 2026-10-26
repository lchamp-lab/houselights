package app.houselights

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.Text
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.ToIdentify
import app.houselights.ui.design.HlMotion
import app.houselights.ui.edit.IdentifyPage
import app.houselights.ui.edit.IdentifyViewModel
import app.houselights.ui.edit.ToIdentifyPage
import app.houselights.ui.edit.ToIdentifyViewModel
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus, the queue: from « À identifier », OK on a suggestion identifies the title and opens the next one;
 * « Passer » opens it unchanged; after the last, Réglages; Back returns to the list, on the title the queue reached; a
 * second OK given too soon accepts nothing unseen. The app's own navigation ([openQueue], [queueTo]) over a fake source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class IdentifyQueueTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = FakeProvider().apply {
        (1..3).forEach { items["m$it"] = film("m$it", "Film $it") }
        toIdentify = (1..3).map { n -> ToIdentify(film("m$n", "Film $n"), "Films/Film $n.mkv", MatchDoubt.UNSURE, listOf(best(n))) }
    }

    @After
    fun stop() = work.cancel()

    private fun best(n: Int) = MatchCandidate("$n", "Best $n", null, 2020 + n, null, null)

    /** Réglages, the list over it, Identifier over the list; every title's suggestions its own best. */
    private fun app(): NavHostController {
        lateinit var nav: NavHostController
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(
                nav,
                startDestination = Route.Settings,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                composable<Route.Settings> { Text("Settings") }
                composable<Route.ToIdentify> { entry ->
                    val vm: ToIdentifyViewModel =
                        viewModel(factory = viewModelFactory { initializer { ToIdentifyViewModel({ source }, work) } })
                    // Read again on its return, as a change of the library has it.
                    LaunchedEffect(Unit) { vm.load() }
                    val at by entry.savedStateHandle.getStateFlow<String?>(QUEUE_AT, null).collectAsStateWithLifecycle()
                    ToIdentifyPage(vm, onOpen = nav::openQueue, queueAt = at, onQueueSeen = { entry.savedStateHandle[QUEUE_AT] = null })
                }
                composable<Route.Identify> { entry ->
                    val route = entry.toRoute<Route.Identify>()
                    val vm: IdentifyViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                val n = route.id.removePrefix("m").toInt()
                                source.identity = Identity(
                                    "Film $n.mkv", "Film $n", null, current = null, manual = false, canSearch = true,
                                    doubt = MatchDoubt.UNSURE, suggestions = listOf(best(n)),
                                )
                                IdentifyViewModel(route.id, { source }, work, createSavedStateHandle(), route.queue)
                            }
                        },
                    )
                    IdentifyPage(vm, onDone = { nav.popBackStack() }, queue = route.queue, onNext = nav::queueTo)
                }
            }
        }
        compose.runOnIdle { nav.navigate(Route.ToIdentify) }
        compose.waitForIdle()
        return nav
    }

    private fun focusIsOn(name: String) = compose.onNode(isFocused()).assert(hasText(name, substring = true))

    private fun NavHostController.identifying(): String? =
        currentBackStackEntry?.takeIf { it.destination.hasRoute<Route.Identify>() }?.toRoute<Route.Identify>()?.id

    private fun NavHostController.stack(): List<String> = currentBackStack.value.mapNotNull { entry ->
        val d = entry.destination
        when {
            d.hasRoute<Route.Settings>() -> "settings"
            d.hasRoute<Route.ToIdentify>() -> "list"
            d.hasRoute<Route.Identify>() -> entry.toRoute<Route.Identify>().id
            else -> null
        }
    }

    /** Past the calm a title's suggestions come with (`calmAfter`), as a deliberate press is. */
    private fun settled() {
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun okOnASuggestionIdentifiesTheTitleAndOpensTheNext() {
        val nav = app()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("settings", "list", "m1"), nav.stack())
        focusIsOn("Best 1")
        assertTrue("no search", source.calls.none { it.startsWith("search") })
        settled()
        compose.press(Key.DirectionCenter)
        assertTrue("identify m1 1" in source.calls)
        assertEquals("Back returns to the list, not from title to title", listOf("settings", "list", "m2"), nav.stack())
        focusIsOn("Best 2")
    }

    @Test
    fun passerOpensTheNextTitleUnchanged() {
        val nav = app()
        compose.press(Key.DirectionCenter)
        settled()
        compose.press(Key.DirectionLeft)
        focusIsOn("Skip")
        compose.press(Key.DirectionCenter)
        assertEquals("m2", nav.identifying())
        assertTrue(source.calls.none { it.startsWith("identify") })
    }

    @Test
    fun theLastTitleReturnsToSettings() {
        val nav = app()
        compose.press(Key.DirectionDown, times = 2)
        compose.press(Key.DirectionCenter)
        assertEquals("m3", nav.identifying())
        settled()
        compose.press(Key.DirectionCenter)
        assertTrue("identify m3 3" in source.calls)
        assertEquals(listOf("settings"), nav.stack())
        compose.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun backFromTheQueueReturnsToTheListOnTheTitleItReached() {
        val nav = app()
        compose.press(Key.DirectionCenter)
        settled()
        compose.press(Key.DirectionCenter)
        assertEquals("m2", nav.identifying())
        back()
        assertEquals(listOf("settings", "list"), nav.stack())
        // Film 1 identified: Film 2 takes its place, where the queue was.
        compose.onNodeWithText("Film 1").assertDoesNotExist()
        focusIsOn("Film 2")
    }

    /** OK pressed twice: the first accepts Film 1's suggestion, the second, too soon, does not accept Film 2's unseen. */
    @Test
    fun aSecondOkTooSoonAcceptsNothing() {
        val nav = app()
        compose.press(Key.DirectionCenter)
        focusIsOn("Best 1")
        settled()
        compose.mainClock.autoAdvance = false
        compose.press(Key.DirectionCenter)
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals("m2", nav.identifying())
        focusIsOn("Best 2")
        compose.press(Key.DirectionCenter)
        assertTrue("nothing chosen unseen", source.calls.none { it == "identify m2 2" })
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        compose.press(Key.DirectionCenter)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("identify m2 2" in source.calls)
    }

    private companion object {
        /** Frames enough for the next title to show, its suggestions focused, well within [HlMotion.SETTLE]. */
        const val FRAMES = 8
        const val FRAME_MS = 16L
    }
}
