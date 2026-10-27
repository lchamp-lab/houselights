package app.houselights.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.core.UserState
import app.houselights.core.Version
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.components.SegmentDetail
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.film
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.util.UiError
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** A title's page on a fake source: loading, failures, watched and favourites, seasons, sagas, copies, and process death. */
@RunWith(RobolectricTestRunner::class)
class DetailViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat")
        items["show"] = series("show")
        seasons = mapOf("show" to (1..3).map { season("s$it", it, "show") })
        episodes = (1..3).associate { n -> "s$n" to (1..2).map { episode("s${n}e$it", "s$n", it) } }
    }
    private val events = FakeEvents()

    /** A page on [source], saving in the test's scope, so each test runs its saves to their end. */
    private fun TestScope.detail(id: String = "m1", source: MediaProvider = provider) =
        DetailViewModel(id, { source }, events, this, now = { testScheduler.currentTime })

    @Test
    fun loadsTheTitle() = runTest {
        val vm = detail()
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.item?.title)
        assertTrue(vm.state.value.canEdit)
        assertNull(vm.state.value.error)
    }

    /** The title shows once Lire's target is known, its rows after; focus waits for them. */
    @Test
    fun theTitleIsSaidBeforeItsRows() = runTest {
        val seasons = provider.gate("seasons show")
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals("show", vm.state.value.item?.id)
        assertTrue(vm.state.value.partial)
        assertTrue(vm.state.value.seasons.isEmpty())
        seasons.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.partial)
        assertEquals(3, vm.state.value.seasons.size)
    }

    @Test
    fun aTitleGoneSaysSo() = runTest {
        val vm = detail("gone")
        advanceUntilIdle()
        assertNull(vm.state.value.item)
        assertEquals(UiError.GONE, vm.state.value.error)
    }

    /** The source is asked once per load: one gone right after (signed out) never crashes the page. */
    @Test
    fun aSourceGoneOnceReadNeverCrashesThePage() = runTest {
        var asked = 0
        val vm = DetailViewModel("m1", { if (asked++ == 0) provider else error("No library source configured") }, events, this)
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.item?.title)
        assertTrue(vm.state.value.canEdit)
    }

    @Test
    fun aNewerLoadReplacesAnOlderOne() = runTest {
        val first = provider.gate("item m1")
        val vm = detail()
        advanceUntilIdle()
        provider.gates.clear()
        provider.items["m1"] = film("m1", "Heat (1995)")
        vm.load()
        advanceUntilIdle()
        // The older load, completed after the newer one, changes nothing.
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals("Heat (1995)", vm.state.value.item?.title)
    }

    @Test
    fun watchedShowsAtOnceAndTellsTheOtherScreens() = runTest {
        val vm = detail()
        advanceUntilIdle()
        val saving = provider.gate("played m1 true")
        vm.togglePlayed()
        // The page says so before it is saved; home and the walls reload once it is.
        assertEquals(true, vm.state.value.item?.user?.played)
        advanceUntilIdle()
        assertEquals(0L, events.libraryVersion.value)
        saving.complete(Unit)
        advanceUntilIdle()
        assertEquals(1L, events.libraryVersion.value)
        assertEquals(listOf("item m1", "played m1 true", "item m1"), provider.calls)
        assertEquals(true, vm.state.value.item?.user?.played)

        vm.toggleFavorite()
        assertEquals(true, vm.state.value.item?.user?.favorite)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.item?.user?.favorite)
        assertEquals(2L, events.libraryVersion.value)
    }

    /** Leaving the page while a change is saved: home and the walls are still told once it is. */
    @Test
    fun aChangeIsToldOnceSavedThoughThePageIsGone() = runTest {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { detail() } })[DetailViewModel::class.java]
        advanceUntilIdle()
        val saving = provider.gate("played m1 true")
        vm.togglePlayed()
        advanceUntilIdle()
        store.clear()
        saving.complete(Unit)
        advanceUntilIdle()
        assertEquals(1L, events.libraryVersion.value)
    }

    @Test
    fun aSecondPressWhileSavingDoesNothing() = runTest {
        val vm = detail()
        advanceUntilIdle()
        val saving = provider.gate("played m1 true")
        vm.togglePlayed()
        advanceUntilIdle()
        vm.togglePlayed()
        advanceUntilIdle()
        assertEquals(true, vm.state.value.item?.user?.played)
        saving.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, provider.calls.count { it.startsWith("played") })
        assertEquals(true, vm.state.value.item?.user?.played)
    }

    @Test
    fun aFailedChangeIsSaidBesideTheActions() = runTest {
        val vm = detail()
        advanceUntilIdle()
        provider.fail = { if (it.startsWith("favorite")) IOException("Server away") else null }
        vm.toggleFavorite()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.actionError)
        assertEquals(false, vm.state.value.item?.user?.favorite)
        assertEquals(0L, events.libraryVersion.value)

        // A reload keeps it; the next action clears it.
        vm.load()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.actionError)
        vm.clearActionError()
        assertNull(vm.state.value.actionError)
    }

    /** A show of three seasons of two episodes, none watched, the first episode half seen, as a source counts them. */
    private fun countedShow() = provider.apply {
        items["show"] = series("show").copy(user = UserState(unplayedCount = 6))
        seasons = mapOf("show" to (1..3).map { season("s$it", it, "show").copy(childCount = 2, user = UserState(unplayedCount = 2)) })
        val begun = episode("s1e1", "s1", 1).copy(user = UserState(positionMs = 600_000))
        episodes = episodes + ("s1" to listOf(begun, episode("s1e2", "s1", 2)))
    }

    private val DetailViewModel.season1 get() = state.value.seasons.first { it.id == "s1" }

    /** The watched tile marks the season shown, its episodes from their start, the show counting it: at once, then as saved. */
    @Test
    fun theTileMarksTheSeasonShownThenTheWholeShow() = runTest {
        countedShow()
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals("s1", vm.state.value.shownSeason?.id)
        fun saysTheSeasonWatched() {
            assertTrue(seasonWatched(vm.season1))
            assertEquals(listOf(true, true), vm.state.value.episodes.map { it.user.played })
            assertEquals(listOf(0L, 0L), vm.state.value.episodes.map { it.user.positionMs })
            assertEquals(4, vm.state.value.item?.user?.unplayedCount)
            assertEquals(false, vm.state.value.item?.user?.played)
        }
        vm.toggleSeasonPlayed()
        saysTheSeasonWatched()
        advanceUntilIdle()
        assertTrue("played s1 true" in provider.calls)
        assertEquals(1L, events.libraryVersion.value)
        saysTheSeasonWatched()

        // A long OK: the whole show, every season with it.
        vm.togglePlayed()
        assertEquals(true, vm.state.value.item?.user?.played)
        assertTrue(vm.state.value.seasons.all(::seasonWatched))
        advanceUntilIdle()
        assertTrue("played show true" in provider.calls)
        assertEquals(true, vm.state.value.item?.user?.played)
        assertTrue(vm.state.value.seasons.all(::seasonWatched))
    }

    /** Unmarked, a watched season counts all its episodes again. */
    @Test
    fun aWatchedSeasonIsMarkedUnwatched() = runTest {
        countedShow()
        val watched = UserState(played = true, unplayedCount = 0)
        provider.seasons = mapOf("show" to provider.seasons.getValue("show").map { it.copy(user = watched) })
        provider.episodes = provider.episodes.mapValues { (_, list) -> list.map { it.copy(user = UserState(played = true)) } }
        provider.items["show"] = series("show").copy(user = watched)
        val vm = detail("show")
        advanceUntilIdle()
        vm.toggleSeasonPlayed()
        fun countsItAgain() {
            assertEquals(2, vm.season1.user.unplayedCount)
            assertFalse(seasonWatched(vm.season1))
            assertEquals(UserState(unplayedCount = 2), vm.state.value.item?.user)
        }
        countsItAgain()
        advanceUntilIdle()
        assertTrue("played s1 false" in provider.calls)
        countsItAgain()
    }

    /** A long OK on an episode marks it alone; its season and its show count it at once, then as saved. */
    @Test
    fun anEpisodeIsMarkedAndItsSeasonCountsIt() = runTest {
        countedShow()
        val vm = detail("show")
        advanceUntilIdle()
        fun countsIt() {
            assertEquals(UserState(played = true), vm.state.value.episodes.first().user)
            assertEquals(SegmentDetail.Count("1\u00a0/\u00a02"), seasonDetail(vm.season1))
            assertEquals(5, vm.state.value.item?.user?.unplayedCount)
        }
        vm.toggleEpisodePlayed(vm.state.value.episodes.first())
        countsIt()
        advanceUntilIdle()
        assertTrue("played s1e1 true" in provider.calls)
        countsIt()

        vm.toggleEpisodePlayed(vm.state.value.episodes.first())
        assertEquals(6, vm.state.value.item?.user?.unplayedCount)
        advanceUntilIdle()
        assertTrue("played s1e1 false" in provider.calls)
        assertEquals(false, vm.state.value.episodes.first().user.played)
        assertEquals(6, vm.state.value.item?.user?.unplayedCount)
    }

    /** A second episode marked while the page reloads after the first: the reload landing meanwhile undoes neither. */
    @Test
    fun aSecondMarkWhileTheFirstReloads() = runTest {
        countedShow()
        val vm = detail("show")
        advanceUntilIdle()
        val reload = provider.gate("item show")
        vm.toggleEpisodePlayed(vm.state.value.episodes[0])
        advanceUntilIdle()
        val saving = provider.gate("played s1e2 true")
        vm.toggleEpisodePlayed(vm.state.value.episodes[1])
        fun bothShow() {
            assertEquals(listOf(true, true), vm.state.value.episodes.map { it.user.played })
            assertTrue(seasonWatched(vm.season1))
            assertEquals(4, vm.state.value.item?.user?.unplayedCount)
        }
        bothShow()
        reload.complete(Unit)
        advanceUntilIdle()
        bothShow()
        saving.complete(Unit)
        advanceUntilIdle()
        bothShow()
        assertEquals(2L, events.libraryVersion.value)
    }

    /** A mark that does not save comes undone, the source away, and says why on the episode row, not by the actions. */
    @Test
    fun aMarkNotSavedIsUndoneAndSaidOnItsRow() = runTest {
        countedShow()
        val vm = detail("show")
        advanceUntilIdle()
        provider.fail = { IOException("Server away") }
        vm.toggleEpisodePlayed(vm.state.value.episodes.first())
        assertEquals(true, vm.state.value.episodes.first().user.played)
        advanceUntilIdle()
        assertEquals(UserState(positionMs = 600_000), vm.state.value.episodes.first().user)
        assertNull(seasonDetail(vm.season1))
        assertEquals(6, vm.state.value.item?.user?.unplayedCount)
        assertEquals(UiError.OFFLINE, vm.state.value.episodeMarkError)
        assertNull(vm.state.value.actionError)
        assertEquals(0L, events.libraryVersion.value)

        // Said of its season: another one shown, it goes.
        provider.fail = { null }
        vm.selectSeason("s2")
        assertNull(vm.state.value.episodeMarkError)
    }

    /** Lire follows a mark at once: the season holding the episode in progress marked, it starts that one over. */
    @Test
    fun lireFollowsAMarkAtOnce() = runTest {
        countedShow()
        provider.nextUps = mapOf("show" to "s1e1")
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals(600_000L, vm.state.value.nextUp?.user?.positionMs)
        vm.toggleSeasonPlayed()
        assertEquals(UserState(played = true), vm.state.value.nextUp?.user)
    }

    /** A film marked: the copy Lire plays and the film in its saga's row say so at once, from their start. */
    @Test
    fun aFilmMarkedMarksItsCopiesAndItsPlaceInItsSaga() = runTest {
        val begun = UserState(positionMs = 60_000)
        provider.items["m1"] = film("m1", "Heat").copy(user = begun)
        fun copy(key: String, best: Boolean) =
            Version(film(key).copy(user = begun), null, null, null, null, null, chosen = best, best = best, key = key)
        provider.catalog = FakeCatalog().apply { copies = mapOf("m1" to listOf(copy("long", best = true), copy("short", best = false))) }
        provider.capabilities = provider.capabilities.copy(versions = true)
        val saga = MediaItem("saga", MediaKind.COLLECTION, "Heat")
        val source = object : MediaProvider by provider {
            override suspend fun collectionOf(item: MediaItem) = saga

            override suspend fun collectionItems(collection: MediaItem) = listOf(provider.items.getValue("m1"), film("m2"))
        }
        val vm = detail("m1", source)
        advanceUntilIdle()
        vm.togglePlayed()
        val page = vm.state.value
        assertEquals(UserState(played = true), page.playedCopy?.item?.user)
        assertEquals(listOf(true, true), page.versions.map { it.item.user.played })
        assertEquals(listOf(true, false), page.collectionFilms.map { it.user.played })
    }

    /** One season has no track: the watched tile marks the show. */
    @Test
    fun aShowOfOneSeasonHasNoSeasonShown() = runTest {
        provider.seasons = mapOf("show" to listOf(season("s1", 1, "show")))
        val vm = detail("show")
        advanceUntilIdle()
        assertNull(vm.state.value.shownSeason)
        vm.toggleSeasonPlayed()
        advanceUntilIdle()
        assertTrue(provider.calls.none { it.startsWith("played") })
    }

    /** A season without episodes, which a server may call watched, is not the tile's: it marks the show there. */
    @Test
    fun aSeasonWithoutEpisodesIsNotShown() = runTest {
        val empty = season("s2", 2, "show").copy(childCount = 0, user = UserState(played = true, unplayedCount = 0))
        provider.seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(childCount = 2), empty))
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals("s1", vm.state.value.shownSeason?.id)
        vm.selectSeason("s2")
        assertNull(vm.state.value.shownSeason)
        assertFalse(seasonWatched(empty))
    }

    private val DetailViewModel.shownEpisodes get() = state.value.episodes.map { it.id }

    @Test
    fun seasonsAnsweringOutOfOrderNeverShowAnotherSeason() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals(listOf("s1e1", "s1e2"), vm.shownEpisodes)

        val slow = provider.gate("episodes s2")
        vm.selectSeason("s2")
        advanceTimeBy(HlMotion.SETTLE + 1)
        assertTrue("episodes s2" in provider.calls)
        vm.selectSeason("s3")
        // The tab changes at once and its row empties: the previous season never shows under it.
        assertEquals("s3", vm.state.value.selectedSeasonId)
        assertEquals(emptyList<String>(), vm.shownEpisodes)
        assertTrue(vm.state.value.episodesLoading)
        advanceUntilIdle()
        assertEquals(listOf("s3e1", "s3e2"), vm.shownEpisodes)

        // The slower answer comes last: it changes nothing.
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals("s3", vm.state.value.selectedSeasonId)
        assertEquals(listOf("s3e1", "s3e2"), vm.shownEpisodes)
    }

    @Test
    fun crossingTabsLoadsOnlyTheOneFocusStopsOn() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        vm.selectSeason("s2")
        advanceTimeBy(HlMotion.SETTLE / 2)
        vm.selectSeason("s3")
        advanceUntilIdle()
        assertEquals(listOf("episodes s1", "episodes s3"), provider.calls.filter { it.startsWith("episodes") })
        assertEquals(listOf("s3e1", "s3e2"), vm.shownEpisodes)
    }

    @Test
    fun aReloadKeepsTheSeasonChosenMeanwhile() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        val reloading = provider.gate("episodes s1")
        vm.load()
        advanceUntilIdle()
        vm.selectSeason("s2")
        advanceUntilIdle()
        reloading.complete(Unit)
        advanceUntilIdle()
        assertEquals("s2", vm.state.value.selectedSeasonId)
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }

    @Test
    fun aReloadTakesOverASeasonStillToLoad() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        vm.selectSeason("s2")
        vm.load()
        advanceUntilIdle()
        assertEquals(1, provider.calls.count { it == "episodes s2" })
        assertEquals("s2", vm.state.value.selectedSeasonId)
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }

    @Test
    fun aSeasonThatFailedSaysSoAndCanBeTriedAgain() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        provider.fail = { if (it == "episodes s2") IOException("Server away") else null }
        vm.selectSeason("s2")
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.episodesError)
        assertEquals(emptyList<String>(), vm.shownEpisodes)

        // Trying again keeps the row's error, its Réessayer at work and focused, until the answer.
        provider.fail = { null }
        val answer = provider.gate("episodes s2")
        vm.retrySeason()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.episodesError)
        assertTrue(vm.state.value.episodesLoading)
        answer.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.episodesError)
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }

    /** What Lire starts unread, a show keeps its seasons and says why in Lire's place, until Réessayer reads it. */
    @Test
    fun aShowWhoseNextEpisodeFailsKeepsItsSeasons() = runTest {
        provider.fail = { if (it == "nextUp show") IOException("Server away") else null }
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.nextUpError)
        assertNull(vm.state.value.error)
        assertEquals(3, vm.state.value.seasons.size)
        assertEquals(listOf("s1e1", "s1e2"), vm.shownEpisodes)

        provider.fail = { null }
        provider.nextUps = mapOf("show" to "s1e2")
        val answer = provider.gate("nextUp show")
        vm.retry()
        advanceUntilIdle()
        assertTrue("its Réessayer at work", vm.state.value.retrying)
        assertEquals(UiError.OFFLINE, vm.state.value.nextUpError)
        answer.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.retrying)
        assertNull(vm.state.value.nextUpError)
        assertEquals("s1e2", vm.state.value.nextUp?.id)
    }

    /** The chosen season's episodes unread on a first load: the seasons show, the row says why and offers Réessayer. */
    @Test
    fun episodesThatFailOnAFirstLoadSayItOnTheirRow() = runTest {
        provider.fail = { if (it == "episodes s1") IOException("Server away") else null }
        val vm = detail("show")
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(3, vm.state.value.seasons.size)
        assertEquals(UiError.OFFLINE, vm.state.value.episodesError)

        provider.fail = { null }
        vm.retrySeason()
        advanceUntilIdle()
        assertNull(vm.state.value.episodesError)
        assertEquals(listOf("s1e1", "s1e2"), vm.shownEpisodes)
    }

    /** Episodes shown, a reload that cannot read them again keeps them and says why with the title, not on their row. */
    @Test
    fun episodesShownStayWhenAReloadCannotReadThem() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        assertEquals(listOf("s1e1", "s1e2"), vm.shownEpisodes)

        provider.fail = { if (it == "episodes s1") IOException("Server away") else null }
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(listOf("s1e1", "s1e2"), vm.shownEpisodes)
        assertNull(vm.state.value.episodesError)
        assertEquals(UiError.OFFLINE, vm.state.value.error)
    }

    /** A part failing once the title shows is said with the title, and Réessayer loads the page again. */
    @Test
    fun aPartThatFailsOnceTheTitleShowsIsSaid() = runTest {
        // The title shows once Lire's episode is read; the seasons fail after.
        val seasons = provider.gate("seasons show")
        val vm = detail("show")
        advanceUntilIdle()
        provider.fail = { if (it == "seasons show") IOException("Server away") else null }
        seasons.complete(Unit)
        advanceUntilIdle()
        assertEquals("show", vm.state.value.item?.id)
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        vm.retry()
        assertTrue(vm.state.value.retrying)
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.retrying)
        assertEquals(3, vm.state.value.seasons.size)
    }

    /** A saga whose films cannot be read fails as such, never as an empty saga. */
    @Test
    fun aSagaWhoseFilmsFailSaysSo() = runTest {
        provider.items["saga"] = MediaItem("saga", MediaKind.COLLECTION, "Alien")
        val failing = object : MediaProvider by provider {
            override suspend fun collectionItems(collection: MediaItem): List<MediaItem> = throw IOException("Server away")
        }
        val vm = detail("saga", failing)
        advanceUntilIdle()
        assertNull(vm.state.value.item)
        assertEquals(UiError.OFFLINE, vm.state.value.error)
    }

    /** Marked watched, then the library's signal of that change: the page loads once, not twice. */
    @Test
    fun aChangeOfItsOwnLoadsThePageOnce() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.togglePlayed()
        advanceUntilIdle()
        // The library's version moved on: the screen tells the page.
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(listOf("item m1", "played m1 true", "item m1"), provider.calls)

        // A change from elsewhere (another screen, a scan's end) loads it again.
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(3, provider.calls.count { it == "item m1" })
    }

    /** A scan's progress elsewhere costs the page one light read; once it rewrites this title, the page loads again. */
    @Test
    fun aScansProgressReloadsThePageOnlyWhenItsTitleChanged() = runTest {
        val vm = detail()
        advanceUntilIdle()
        provider.calls.clear()
        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals(listOf("item m1"), provider.calls)

        provider.items["m1"] = film("m1", "Heat (1995)")
        vm.onLibraryChanged(scanOnly = true)
        advanceUntilIdle()
        assertEquals(3, provider.calls.count { it == "item m1" })
        assertEquals("Heat (1995)", vm.state.value.item?.title)
    }

    /** Marked watched during a scan, then Identify and Artwork: back on the page, the late signal reloads it. */
    @Test
    fun aChangeOfItsOwnLongAgoLoadsThePageAgain() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.togglePlayed()
        advanceUntilIdle()

        // Identify, then Artwork: some seconds away, rematching the title and changing its poster.
        advanceTimeBy(OWN_CHANGE_MS + 20_000)
        provider.items["m1"] = film("m1", "Heat (1995)")
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(3, provider.calls.count { it == "item m1" })
        assertEquals("Heat (1995)", vm.state.value.item?.title)
    }

    @Test
    fun tryingAgainWithNoTitleShownSaysSo() = runTest {
        provider.fail = { IOException("Server away") }
        val vm = detail()
        advanceUntilIdle()
        assertEquals(UiError.OFFLINE, vm.state.value.error)

        provider.fail = { null }
        vm.load()
        assertNull(vm.state.value.error)
        advanceUntilIdle()
        assertEquals("Heat", vm.state.value.item?.title)
    }

    @Test
    fun aReloadThatFailsLeavesNoSeasonLoading() = runTest {
        val vm = detail("show")
        advanceUntilIdle()
        vm.selectSeason("s2")
        provider.fail = { if (it == "item show") IOException("Server away") else null }
        vm.load()
        advanceUntilIdle()
        assertFalse(vm.state.value.episodesLoading)
        assertEquals(UiError.OFFLINE, vm.state.value.episodesError)
        provider.fail = { null }
        vm.retrySeason()
        advanceUntilIdle()
        assertEquals(listOf("s2e1", "s2e2"), vm.shownEpisodes)
    }

    /** Android killed the app while an episode played: the page comes back on the chosen season. */
    @Test
    fun theSeasonChosenOutlivesAStoppedProcess() = runTest {
        val saved = SavedStateHandle()
        val before = DetailViewModel("show", { provider }, events, this, saved)
        advanceUntilIdle()
        before.selectSeason("s3")
        advanceUntilIdle()

        val after = DetailViewModel("show", { provider }, events, this, saved.afterProcessDeath())
        advanceUntilIdle()
        assertEquals("s3", after.state.value.selectedSeasonId)
        assertEquals(listOf("s3e1", "s3e2"), after.shownEpisodes)
    }

    /** A saga's page asks for its films once and picks Lire's film among them by the shared rule. */
    @Test
    fun aSagaAsksForItsFilmsOnce() = runTest {
        provider.items["saga"] = MediaItem("saga", MediaKind.COLLECTION, "Alien")
        val films = listOf(
            film("alien").copy(user = UserState(played = true)),
            film("aliens"),
            film("alien3").copy(user = UserState(positionMs = 60_000)),
        )
        val source = OneSaga(provider, films)
        val vm = detail("saga", source)
        advanceUntilIdle()
        assertEquals(listOf("saga"), source.asked)
        assertEquals(films, vm.state.value.collectionFilms)
        // The film in progress comes before the first one not seen.
        assertEquals("alien3", vm.state.value.nextUp?.id)
    }

    /** A film in several copies: each is on its page, and the one chosen becomes the one Lire plays. */
    @Test
    fun aFilmsCopiesAndTheOneChosen() = runTest {
        fun copy(key: String, edition: String?, best: Boolean) =
            Version(film(key), edition, null, null, null, null, chosen = best, best = best, key = key)
        val catalog = FakeCatalog().apply {
            copies = mapOf("m1" to listOf(copy("long", "Extended", best = true), copy("short", "Theatrical", best = false)))
        }
        provider.catalog = catalog
        provider.capabilities = provider.capabilities.copy(versions = true, versionChoice = true)
        val vm = detail()
        advanceUntilIdle()
        assertEquals(listOf("long", "short"), vm.state.value.versions.map { it.key })
        vm.chooseVersion(vm.state.value.versions[1])
        advanceUntilIdle()
        assertEquals(listOf("choose short"), catalog.calls.filter { it.startsWith("choose") })
        assertEquals("short", vm.state.value.versions.single { it.chosen }.key)
    }

    /** A server that keeps no choice shows its copies, and is never asked to choose one. */
    @Test
    fun aSourceThatKeepsNoChoiceIsNotAskedToChoose() = runTest {
        val copies = listOf("first", "second").map { key ->
            Version(film(key), null, null, null, null, null, chosen = key == "first", best = key == "first", key = key)
        }
        val catalog = FakeCatalog().apply { this.copies = mapOf("m1" to copies) }
        provider.catalog = catalog
        provider.capabilities = provider.capabilities.copy(versions = true)
        val vm = detail()
        advanceUntilIdle()
        assertFalse(vm.state.value.canChooseVersion)
        vm.chooseVersion(vm.state.value.versions[1])
        advanceUntilIdle()
        assertEquals(emptyList<String>(), catalog.calls.filter { it.startsWith("choose") })
        assertEquals("first", vm.state.value.versions.single { it.chosen }.key)
    }

    /** A saga's page has it whole when the source knows it: the films the library lacks in their place. */
    @Test
    fun aSagaIsWhole() = runTest {
        val collection = MediaItem("saga", MediaKind.COLLECTION, "Alien")
        provider.items["saga"] = collection
        val whole = Saga(collection, listOf(SagaPart(film("alien"), "Alien", 1979), SagaPart(null, "Aliens", 1986)))
        provider.catalog = FakeCatalog().apply { sagas = mapOf("saga" to whole) }
        val vm = detail("saga", OneSaga(provider, listOf(film("alien"))))
        advanceUntilIdle()
        assertEquals(whole, vm.state.value.saga)
    }
}

/** [source] with one collection of [films], counting requests; Lire's film follows MediaProvider's own rule. */
private class OneSaga(source: FakeProvider, private val films: List<MediaItem>) : MediaProvider by source {
    val asked = mutableListOf<String>()

    override suspend fun collectionItems(collection: MediaItem): List<MediaItem> {
        asked += collection.id
        return films
    }

    override suspend fun nextUp(series: MediaItem): MediaItem? = super.nextUp(series)
}
