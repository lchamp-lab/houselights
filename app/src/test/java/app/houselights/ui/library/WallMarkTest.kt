package app.houselights.ui.library

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.UserState
import app.houselights.core.WatchStatus
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.components.FAVORITE_HEART
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.longClickNamed
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.pressOnRemote
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
import java.net.ConnectException

/**
 * DESIGN.md, Layout: a filtered wall losing a title. A title its poster's options mark out of the wall's filter (Non vus,
 * Vus, En cours), or take out of Favoris, leaves at once, focus on the poster taking its place, else the one before it,
 * else « Retirer les filtres »; the same after Back from its page; a change not saved comes back in its place with focus,
 * its line saying why; unfiltered, the poster stays, a favourite's heart on its line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class WallMarkTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Saves at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val films = Library("films", LibraryKind.MOVIES, "Films")

    /** Eight films not seen, as the wall filtered on Non vus shows them. */
    private val provider = FakeProvider().apply { wall = TITLES.map { film(it, it) } }

    @After
    fun stop() = work.cancel()

    private fun open(status: WatchStatus = WatchStatus.UNWATCHED): LibraryViewModel {
        val vm = LibraryViewModel({ provider }, films, opensOn = LibraryFilter(status = status), work = work)
        compose.setTvContent { Wall(vm, films, "Films", onOpenItem = {}) }
        compose.waitForIdle()
        return vm
    }

    private fun text(id: Int) = context.getString(id)

    private fun poster(title: String) = compose.onNode(hasText(title) and hasClickAction() and isAPoster())

    /** A poster on the wall, not a row of the sheet: only a poster names its long click. */
    private fun isAPoster() = longClickNamed(text(R.string.more_options))

    private fun count(n: Int) = context.resources.getQuantityString(R.plurals.title_count, n, "$n")

    /** Menu on the poster with focus, then OK on the sheet's row [row] down from its first. */
    private fun options(row: Int = 0) {
        compose.pressOnRemote(Key.Menu)
        repeat(row) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Marked from its poster, a film leaves the wall at once, the count with it, focus on the one taking its place. */
    @Test
    fun aTitleMarkedOnAnUnwatchedWallLeavesFocusOnTheOneInItsPlace() {
        open()
        compose.onNodeWithText(count(8)).assertExists()
        compose.press(Key.DirectionRight, times = 2)
        poster(DRIVE).assertIsFocused()
        options()
        assertTrue("played $DRIVE true" in provider.calls)
        poster(DRIVE).assertDoesNotExist()
        compose.onNodeWithText(count(7)).assertExists()
        poster(NUIT).assertIsFocused()
    }

    /** The poster that took the place of one marked, marked in turn, leaves focus to the next one taking that place. */
    @Test
    fun postersMarkedOneAfterAnotherEachLeaveFocusOnTheOneInTheirPlace() {
        open()
        compose.press(Key.DirectionRight, times = 2)
        options()
        poster(NUIT).assertIsFocused()
        options()
        assertTrue("played $NUIT true" in provider.calls)
        poster(NUIT).assertDoesNotExist()
        compose.onNodeWithText(count(6)).assertExists()
        poster(PARAPLUIES).assertIsFocused()
    }

    /** The last poster marked, focus goes to the one before it. */
    @Test
    fun theLastPosterMarkedLeavesFocusOnTheOneBefore() {
        open()
        poster(SAMOURAI).requestFocus()
        options()
        poster(SAMOURAI).assertDoesNotExist()
        poster(PLAYTIME).assertIsFocused()
    }

    /** A wall filtered on Vus lets go of a title marked unwatched; one on En cours, of a title marked watched. */
    @Test
    fun watchedAndInProgressWallsLetGoOfATitleTheyNoLongerHold() {
        provider.wall = TITLES.map { film(it, it).copy(user = UserState(played = true)) }
        open(WatchStatus.WATCHED)
        options()
        poster(ALIEN).assertDoesNotExist()
        poster(CERCLE).assertIsFocused()
    }

    @Test
    fun anInProgressWallLetsGoOfATitleMarkedWatched() {
        provider.wall = TITLES.map { film(it, it).copy(runtimeMs = HOUR, user = UserState(positionMs = HOUR / 2)) }
        open(WatchStatus.IN_PROGRESS)
        options()
        poster(ALIEN).assertDoesNotExist()
        poster(CERCLE).assertIsFocused()
    }

    /** Taken out of the favourites from its poster, a title leaves a wall filtered on Favoris. */
    @Test
    fun aFavouriteTakenOutLeavesTheFavoritesWall() {
        provider.wall = TITLES.map { film(it, it).copy(user = UserState(favorite = true)) }
        open(WatchStatus.FAVORITES)
        // Its mark first, then the favourite.
        options(row = 1)
        assertTrue("favorite $ALIEN false" in provider.calls)
        poster(ALIEN).assertDoesNotExist()
        poster(CERCLE).assertIsFocused()
    }

    /** The wall emptied says nothing matches its filters, « Retirer les filtres » focused. */
    @Test
    fun theWallEmptiedFocusesClearFilters() {
        provider.wall = listOf(film(ALIEN, ALIEN))
        open()
        options()
        compose.onNodeWithText(text(R.string.no_match)).assertExists()
        compose.onNode(hasText(text(R.string.reset_filters)) and hasClickAction()).assertIsFocused()
    }

    /** A mark not saved comes undone: the title back in its place with focus, its line saying why, as long as it has it. */
    @Test
    fun aMarkNotSavedIsUndoneAndSaid() {
        provider.fail = { if (it.startsWith("played")) ConnectException("Server away") else null }
        val asked = provider.gate("played $DRIVE true")
        open()
        compose.press(Key.DirectionRight, times = 2)
        options()
        // Shown at once while the source is asked: gone, focus on the poster in its place.
        poster(DRIVE).assertDoesNotExist()
        poster(NUIT).assertIsFocused()

        asked.complete(Unit)
        compose.waitForIdle()
        poster(DRIVE).assertIsFocused()
        compose.onNodeWithText(count(8)).assertExists()
        val said = compose.onNode(hasText(text(R.string.action_not_saved), substring = true))
        said.assertExists()

        compose.press(Key.DirectionRight)
        poster(NUIT).assertIsFocused()
        said.assertDoesNotExist()
    }

    /** A mark not saved while a page shows over the wall: back on it, focus is where it was left, not on that title. */
    @Test
    fun aMarkNotSavedWhileAwayLeavesFocusWhereItWasLeft() {
        provider.fail = { if (it.startsWith("played")) ConnectException("Server away") else null }
        val asked = provider.gate("played $DRIVE true")
        var away by mutableStateOf(false)
        val vm = LibraryViewModel({ provider }, films, opensOn = LibraryFilter(status = WatchStatus.UNWATCHED), work = work)
        compose.setTvContent {
            val screens = rememberSaveableStateHolder()
            if (away) {
                HlButton(
                    "Lire",
                    onClick = {},
                )
            } else {
                screens.SaveableStateProvider("wall") { Wall(vm, films, "Films", onOpenItem = {}) }
            }
        }
        compose.waitForIdle()
        compose.press(Key.DirectionRight, times = 2)
        options()
        poster(NUIT).assertIsFocused()
        compose.press(Key.DirectionRight)
        poster(PARAPLUIES).assertIsFocused()

        away = true
        compose.waitForIdle()
        asked.complete(Unit)
        compose.waitForIdle()
        away = false
        compose.waitForIdle()
        poster(DRIVE).assertExists()
        poster(PARAPLUIES).assertIsFocused()
    }

    /** Marked on its page, then Back: the wall reads again, focus going to the poster that took its place. */
    @Test
    fun backFromItsPageMarkedFocusesTheOneInItsPlace() {
        var away by mutableStateOf(false)
        val vm = LibraryViewModel({ provider }, films, opensOn = LibraryFilter(status = WatchStatus.UNWATCHED), work = work)
        compose.setTvContent {
            val screens = rememberSaveableStateHolder()
            if (away) {
                HlButton(
                    "Lire",
                    onClick = {},
                )
            } else {
                screens.SaveableStateProvider("wall") { Wall(vm, films, "Films", onOpenItem = {}) }
            }
        }
        compose.waitForIdle()
        compose.press(Key.DirectionRight, times = 2)
        poster(DRIVE).assertIsFocused()

        // Its page marks it; back on the wall, the library says it changed.
        away = true
        compose.waitForIdle()
        provider.wall = provider.wall.map { if (it.id == DRIVE) it.copy(user = UserState(played = true)) else it }
        away = false
        compose.waitForIdle()
        poster(DRIVE).assertIsFocused()
        vm.onLibraryChanged()
        compose.waitForIdle()
        poster(DRIVE).assertDoesNotExist()
        poster(NUIT).assertIsFocused()
    }

    /** Read again while its page showed, the wall comes back without it: focus on the poster now in its place. */
    @Test
    fun backToAWallReadWhileAwayFocusesTheOneInItsPlace() {
        var away by mutableStateOf(false)
        val vm = LibraryViewModel({ provider }, films, opensOn = LibraryFilter(status = WatchStatus.UNWATCHED), work = work)
        compose.setTvContent {
            val screens = rememberSaveableStateHolder()
            if (away) {
                HlButton(
                    "Lire",
                    onClick = {},
                )
            } else {
                screens.SaveableStateProvider("wall") { Wall(vm, films, "Films", onOpenItem = {}) }
            }
        }
        compose.waitForIdle()
        compose.press(Key.DirectionRight, times = 2)
        away = true
        compose.waitForIdle()
        provider.wall = provider.wall.filterNot { it.id == DRIVE }
        vm.onLibraryChanged()
        compose.waitForIdle()
        away = false
        compose.waitForIdle()
        poster(NUIT).assertIsFocused()
    }

    /** Unfiltered, a title marked keeps its place and focus; made a favourite, its line ends with the filled heart. */
    @Test
    fun anUnfilteredWallKeepsItsTitleAFavouriteHeartOnItsLine() {
        open(WatchStatus.ALL)
        compose.onAllNodes(hasTestTag(FAVORITE_HEART), useUnmergedTree = true).assertCountEquals(0)
        options()
        poster(ALIEN).assertIsFocused()
        options(row = 1)
        assertTrue("favorite $ALIEN true" in provider.calls)
        poster(ALIEN).assertIsFocused()
        // The line names the title with focus once focus settles on it as it now stands.
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE * 2)
        compose.onAllNodes(hasTestTag(FAVORITE_HEART), useUnmergedTree = true).assertCountEquals(1)
        assertEquals(TITLES.size, compose.onAllNodes(isAPoster()).fetchSemanticsNodes().size)
    }

    private companion object {
        const val ALIEN = "Alien"
        const val CERCLE = "Le Cercle rouge"
        const val DRIVE = "Drive My Car"
        const val NUIT = "La Nuit américaine"
        const val PARAPLUIES = "Les Parapluies de Cherbourg"
        const val PLAYTIME = "PlayTime"
        const val SAMOURAI = "Le Samouraï"
        const val HOUR = 3_600_000L

        /** The wall's films by title, as it sorts them, the article aside. */
        val TITLES = listOf(ALIEN, CERCLE, DRIVE, NUIT, PARAPLUIES, "Paris, Texas", PLAYTIME, SAMOURAI)
    }
}
