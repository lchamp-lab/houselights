package app.houselights.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Layout: the films wall with its sagas, as the remote drives it: its count, its view, a saga's poster. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class FilmsWallTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createComposeRule()

    private val films = Library("films", LibraryKind.MOVIES, "Films")
    private val sagas = Library("sagas", LibraryKind.COLLECTIONS, null)
    private val alien = MediaItem("c1", MediaKind.COLLECTION, "Alien", childCount = 2)

    /** Eight films, the saga second on the second line, and the saga alone. */
    private val provider = FakeProvider().apply {
        val shown = (1..8).map { film("m$it", "Film $it") }
        walls = mapOf(films.id to shown + alien, sagas.id to listOf(alien))
        capabilities = capabilities.copy(sagaFilters = true)
    }

    private fun wall(vm: LibraryViewModel = LibraryViewModel({ provider }, films, sagas = sagas)) {
        compose.setTvContent { Wall(vm, films, "Films", onOpenItem = {}) }
        compose.waitForIdle()
    }

    @Test
    fun theCountSaysTheFilmsThenTheSagas() {
        wall()
        compose.onNodeWithText("8\u00A0titres · 1\u00A0saga").assertExists()
    }

    /** Jellyfin gathers the sagas without counting them: every title is counted as one. */
    @Test
    fun sagasTheSourceDoesNotCountAreCountedAsTitles() {
        provider.countsSagas = false
        wall()
        compose.onNodeWithText("9\u00A0titres").assertExists()
    }

    /** The menu stays open while the wall behind it turns to the sagas alone, focus on the choice. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theMenuStaysOpenWhileTheWallTurnsToTheSagas() {
        wall()
        compose.onNodeWithText("Filtrer").requestFocus()
        compose.press(Key.DirectionCenter)
        val sagasOption = hasText("Sagas") and hasClickAction() and !hasText("Filtrer")
        compose.onNode(sagasOption).requestFocus()
        compose.onNode(sagasOption).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        compose.onNode(sagasOption and isFocused()).assertExists()
        compose.onNodeWithText("Films et sagas").assertExists()
        // Behind it, the wall is the sagas', named on the button.
        compose.onNode(hasText("Sagas") and !isSelectable() and !hasClickAction()).assertExists()
        compose.onNode(hasText("Filtrer") and hasText("Sagas")).assertExists()
        compose.onNodeWithText("1\u00A0saga").assertExists()
    }

    /** Nothing passes the filters: taking them off keeps the sagas alone. */
    @Test
    fun takingOffTheFiltersOfNoSagaKeepsTheSagas() {
        provider.walls += sagas.id to emptyList<MediaItem>()
        val vm = LibraryViewModel({ provider }, films, sagas = sagas).apply {
            setFilter(LibraryFilter(genre = "Western"))
            setView(WallView.SAGAS)
        }
        wall(vm)
        compose.onNodeWithText("Aucun titre avec ces filtres").assertExists()
        compose.onNode(hasText("Retirer les filtres") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(WallView.SAGAS to LibraryFilter(), vm.state.value.view to vm.state.value.filter)
    }

    /** Back from a saga's page, focus is on its poster again. */
    @Test
    fun comingBackFromASagaFindsItsPoster() {
        val vm = LibraryViewModel({ provider }, films, sagas = sagas)
        var opened by mutableStateOf<MediaItem?>(null)
        compose.setTvContent {
            val screens = rememberSaveableStateHolder()
            val shown = opened
            if (shown != null) {
                HlButton(shown.title, onClick = {}, modifier = Modifier.testTag("page"))
            } else {
                screens.SaveableStateProvider("wall") { Wall(vm, films, "Films", onOpenItem = { opened = it }) }
            }
        }
        compose.waitForIdle()
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionRight)
        compose.onNode(isFocused()).assert(hasText("Alien", substring = true))
        compose.press(Key.DirectionCenter)
        compose.onNodeWithTag("page").assertExists()
        assertEquals(alien.id, opened?.id)

        opened = null
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasText("Alien", substring = true))
    }
}
