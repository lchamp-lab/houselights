package app.houselights.ui.library

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.EpisodeOrderChoices
import app.houselights.core.EpisodeOrderKind
import app.houselights.core.EpisodeOrderOption
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.MetadataEditor
import app.houselights.core.UserState
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.holdOk
import app.houselights.ui.testing.longClickNamed
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.pressOnRemote
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.formatDuration
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
 * DESIGN.md, Sheets: Menu or a long OK on a poster opens its options (#47), pressed as with the remote: titled with the
 * title's name and where it stands, its mark first and focused, so Menu then OK marks it, then a film's or a show's
 * favourite, then Modifier, the title page's own sheet; a saga's, its mark then Modifier, its poster and its backdrop;
 * a Jellyfin box set's, the mark alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class PosterOptionsTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Saves at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val films = Library("films", LibraryKind.MOVIES, "Films")
    private val grandBleu = MediaItem(
        "grand-bleu",
        MediaKind.MOVIE,
        "Le Grand Bleu",
        year = 1988,
        runtimeMs = 168 * MINUTE,
        user = UserState(positionMs = 94 * MINUTE),
    )
    private val provider = FakeProvider().apply { wall = listOf(grandBleu, film("drive", "Drive My Car")) }

    /** The pictures and the matches Modifier opened, as its screens would. */
    private val edited = mutableListOf<Pair<String, ArtworkKind>>()
    private val identified = mutableListOf<String>()

    @After
    fun stop() = work.cancel()

    private fun open(source: MediaProvider = provider) {
        val vm = LibraryViewModel({ source }, films, work = work)
        compose.setTvContent {
            Wall(
                vm,
                films,
                "Films",
                onOpenItem = {},
                onEditArtwork = { id, kind -> edited += id to kind },
                onIdentify = { identified += it },
            )
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = context.getString(id)

    private fun plural(id: Int, count: Int, vararg args: Any) = context.resources.getQuantityString(id, count, *args)

    private fun poster(title: String) = compose.onNode(hasText(title) and hasClickAction() and hasLongClick)

    /** A row of the open sheet, by its words. */
    private fun option(words: String) = compose.onNode(hasText(words) and hasClickAction() and !hasLongClick)

    private fun option(id: Int) = option(text(id))

    /** Where each of [rows] stands in the open sheet, from the top: they are there, in this order. */
    private fun assertRows(vararg rows: Int) {
        val tops = rows.map { option(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("in the order ${rows.map(::text)}", tops.sorted(), tops)
    }

    /**
     * The open sheet's head says [facts] under its title, in this order, over its rows from [first]: in the sheet itself,
     * never the wall's title line, which says the focused poster's facts too.
     */
    private fun assertHead(first: Int, vararg facts: String) {
        val rows = option(first).fetchSemanticsNode().boundsInRoot.top
        val starts = facts.map { fact ->
            val said = compose.onNode(hasText(fact) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue("$fact over the sheet's rows", said.bottom <= rows)
            said.left
        }
        assertEquals("in the order ${facts.toList()}", starts.sorted(), starts)
    }

    /** Menu then OK marks a film: its options open on marking it, then the favourite and Modifier, and close on its poster. */
    @Test
    fun menuThenOkMarksAFilmAndFocusStaysOnItsPoster() {
        open()
        poster("Le Grand Bleu").assert(longClickNamed(text(R.string.more_options))).assertIsFocused()
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_watched).assertIsFocused()
        assertRows(R.string.mark_watched, R.string.add_favorite, R.string.edit)
        // Where it stands, under its name: started, what is left.
        compose.onAllNodesWithText(context.getString(R.string.time_left, formatDuration(74 * MINUTE))).assertCountEquals(1)

        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("played grand-bleu true" in provider.calls)
        option(R.string.mark_watched).assertDoesNotExist()
        poster("Le Grand Bleu").assertIsFocused()
        // Watched: what Menu then OK does now is its opposite, still first.
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_unwatched).assertIsFocused()
    }

    /** OK held opens the options and marks nothing; Back closes them, doing nothing, focus back on the poster. */
    @Test
    fun aLongOkOpensTheOptionsAndBackClosesThemDoingNothing() {
        open()
        compose.holdOk()
        option(R.string.mark_watched).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        option(R.string.mark_watched).assertDoesNotExist()
        poster("Le Grand Bleu").assertIsFocused()
        assertTrue(provider.calls.none { it.startsWith("played") || it.startsWith("favorite") })
    }

    /** A title watched offers to mark it unwatched, first and focused; a favourite, to take it out of the favourites. */
    @Test
    fun aWatchedFavouriteOffersItsOppositesFirst() {
        provider.wall = listOf(grandBleu.copy(user = UserState(played = true, favorite = true)))
        open()
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_unwatched).assertIsFocused()
        assertRows(R.string.mark_unwatched, R.string.remove_favorite, R.string.edit)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("favorite grand-bleu false" in provider.calls)
        poster("Le Grand Bleu").assertIsFocused()
    }

    /** A show is marked whole, in its page's words, its seasons and its episodes not watched said under its name. */
    @Test
    fun aShowIsMarkedWholeItsEpisodesNotWatchedSaid() {
        val severance = MediaItem("severance", MediaKind.SERIES, "Severance", childCount = 2, user = UserState(unplayedCount = 6))
        provider.wall = listOf(severance)
        open()
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_series_watched).assertIsFocused()
        assertRows(R.string.mark_series_watched, R.string.add_favorite, R.string.edit)
        assertHead(R.string.mark_series_watched, plural(R.plurals.season_count, 2, 2), plural(R.plurals.unwatched_episodes, 6, 6))
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("played severance true" in provider.calls)
    }

    /**
     * A saga is marked with its films, its years, films and progress said, then changed by Modifier, no favourite (#48):
     * Modifier opens the sheet of its page, its poster and its backdrop alone, no Logo nor Identifier, each opening its
     * choice.
     */
    @Test
    fun aSagaIsMarkedWithItsFilmsThenModified() {
        val dune = MediaItem(
            "saga:dune",
            MediaKind.COLLECTION,
            "Dune",
            year = 2021,
            endYear = 2024,
            childCount = 2,
            user = UserState(unplayedCount = 1),
        )
        provider.wall = listOf(dune)
        open()
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_saga_watched).assertIsFocused()
        val watched = plural(R.plurals.saga_watched_of, 1, 1, 2)
        assertHead(R.string.mark_saga_watched, "2021 – 2024", plural(R.plurals.film_count, 2, 2), watched)
        assertRows(R.string.mark_saga_watched, R.string.edit)
        option(R.string.add_favorite).assertDoesNotExist()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("played saga:dune true" in provider.calls)

        compose.pressOnRemote(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        option(R.string.edit).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        option(R.string.mark_saga_watched).assertDoesNotExist()
        val shown = { image: Int -> compose.onAllNodesWithText(text(image), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val images = listOf(R.string.artwork_poster, R.string.artwork_backdrop, R.string.artwork_logo)
        assertEquals("Affiche and Fond d'écran, no Logo", listOf(true, true, false), images.map(shown))
        option(R.string.identify).assertDoesNotExist()
        compose.onNode(hasText("2021 – 2024") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        // Left and Right go from one picture to the other: Fond d'écran, then OK opens its choice.
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("saga:dune" to ArtworkKind.BACKDROP), edited)
    }

    /** A Jellyfin box set, its source keeping no pictures: its mark alone, as a saga's; a film there, no Modifier. */
    @Test
    fun aJellyfinBoxSetHasItsMarkAlone() {
        val johnWick = MediaItem("box:wick", MediaKind.COLLECTION, "John Wick", childCount = 4, user = UserState(unplayedCount = 4))
        provider.wall = listOf(johnWick, film("heat", "Heat"))
        open(jellyfin(provider))
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_saga_watched).assertIsFocused()
        option(R.string.edit).assertDoesNotExist()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)

        poster("Heat").requestFocus()
        compose.pressOnRemote(Key.Menu)
        assertRows(R.string.mark_watched, R.string.add_favorite)
        option(R.string.edit).assertDoesNotExist()
    }

    /** Modifier opens the title page's own sheet as the options close: its images open their choice, Identifier its search. */
    @Test
    fun modifierOpensThePagesOwnSheet() {
        open()
        compose.pressOnRemote(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        option(R.string.edit).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        option(R.string.mark_watched).assertDoesNotExist()
        listOf(R.string.artwork_poster, R.string.artwork_backdrop, R.string.artwork_logo).forEach { image ->
            assertTrue(text(image), compose.onAllNodesWithText(text(image), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        option(R.string.identify).assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("grand-bleu" to ArtworkKind.POSTER), edited)

        compose.pressOnRemote(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        option(R.string.identify).requestFocus()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("grand-bleu"), identified)
    }

    /** Modifier reads a show's orders at each opening: the one chosen from it is the one it says in use the next time. */
    @Test
    fun modifierSaysTheOrderChosenFromItTheNextTime() {
        provider.wall = listOf(MediaItem(SHOW, MediaKind.SERIES, "Firefly", childCount = 1))
        provider.orders = mapOf(SHOW to FIREFLY)
        open()
        edit()
        compose.onNodeWithText(line(text(R.string.episode_order_auto), text(R.string.episode_order_aired))).assertExists()
        compose.onNode(hasText(text(R.string.episode_order)) and hasClickAction()).requestFocus()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        // Automatique focused, then TMDB's seasons, then DVD.
        repeat(2) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("order $SHOW $DVD" in provider.calls)

        edit()
        compose.onNodeWithText(line(text(R.string.episode_order_dvd), "DVD Order")).assertExists()
        assertEquals(2, provider.calls.count { it == "orders $SHOW" })
    }

    /** Menu on the poster with focus, then Modifier, its third row: the title page's own sheet. */
    private fun edit() {
        compose.pressOnRemote(Key.Menu)
        repeat(2) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }

    /** A row's line of [parts], as every setting row joins them. */
    private fun line(vararg parts: String) = parts.joinToString(" · ")

    /** A source that keeps nothing watched nor any favourite, and no pictures, has no options to offer. */
    @Test
    fun aSourceThatKeepsNothingOffersNoOptions() {
        provider.capabilities = provider.capabilities.copy(markPlayed = false, favorites = false)
        open(jellyfin(provider))
        compose.onAllNodes(hasLongClick).assertCountEquals(0)
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_watched).assertDoesNotExist()
    }

    /** [source] as a Jellyfin server's: it keeps no pictures of its own, its interface changing them. */
    private fun jellyfin(source: MediaProvider): MediaProvider = object : MediaProvider by source {
        override val editor: MetadataEditor? = null
    }

    private companion object {
        const val MINUTE = 60_000L
        const val SHOW = "firefly"
        const val DVD = "dvd"

        /** A show TMDB numbers three ways, its episodes placed automatically. */
        val FIREFLY = EpisodeOrderChoices(
            options = listOf(
                EpisodeOrderOption("aired", EpisodeOrderKind.AIRED, episodes = 14, seasons = 1),
                EpisodeOrderOption(DVD, EpisodeOrderKind.DVD, "DVD Order", episodes = 14),
                EpisodeOrderOption("air", EpisodeOrderKind.ORIGINAL_AIR_DATE, "Original Air Date", episodes = 14),
            ),
            chosen = null,
            automatic = EpisodeOrderOption("aired", EpisodeOrderKind.AIRED, episodes = 14, seasons = 1),
        )

        /** A poster, the one thing with a long click: a sheet's rows have none. */
        val hasLongClick = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
    }
}
