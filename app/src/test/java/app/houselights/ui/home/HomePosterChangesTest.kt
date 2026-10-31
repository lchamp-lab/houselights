package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import app.houselights.core.UserState
import app.houselights.providers.local.Folder
import app.houselights.providers.local.ScanState
import app.houselights.settings.AppSettings
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * What a title's card in « À regarder » changes, its mark or its favourite, against the reads of the rows: a read begun
 * before a change never undoes it, and a change not saved leaves the title as the source has it, where it stood.
 */
@RunWith(RobolectricTestRunner::class)
class HomePosterChangesTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val provider = FakeProvider()
    private val scan = MutableStateFlow(ScanState())

    /** The folders as the database gives them; null: not read yet. */
    private val folders = MutableStateFlow<List<Folder>?>(emptyList())
    private val settings = MutableStateFlow(AppSettings(loaded = true, source = SourceConfig.Local))

    private fun HomeViewModel.content() = state.value.content

    /**
     * In « À regarder », a read of the rows begun before a title is marked from its card, that lands after its save with
     * the rows as they stood, never brings it back to Reprendre; a read begun after its save shows the rows as the source
     * then says them, the title in progress again as it was marked again elsewhere.
     */
    @Test
    fun aReadBegunBeforeAMarkNeverBringsItBackToReprendre() = runTest {
        val heat = film("heat").copy(user = UserState(positionMs = 60_000))
        val drive = film("drive").copy(user = UserState(positionMs = 60_000))
        provider.home = listOf(
            HomeSection(SectionType.CONTINUE, listOf(heat, drive), landscape = true),
            HomeSection(SectionType.LATEST, listOf(heat), landscape = false),
        )
        val vm = HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = {}, work = this, events = FakeEvents())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        fun row(type: SectionType) = (vm.content() as HomeContent.Rows).sections.single { it.type == type }.items

        val reread = provider.gate("home")
        vm.onLibraryChanged()
        runCurrent()
        vm.mark(heat)
        advanceUntilIdle()
        assertTrue("played heat true" in provider.calls)

        reread.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("drive"), row(SectionType.CONTINUE).map { it.id })
        assertTrue("marked where it stays", row(SectionType.LATEST).single().user.played)

        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(listOf("heat", "drive"), row(SectionType.CONTINUE).map { it.id })
    }

    /**
     * In « À regarder », a title of Reprendre marked then made a favourite from Nouveautés, or marked then marked
     * unwatched, neither saved, the rows not read again either, offline (#118): it is back in Reprendre at its place, as
     * the source has it, whichever fails first.
     */
    @Test
    fun aTitleOfReprendreChangedTwiceNotSavedIsBackAtItsPlaceOffline() = runTest {
        listOf("favorite heat true", "played heat false").forEach { second ->
            listOf(true, false).forEach { markFailsFirst ->
                val heat = film("heat").copy(user = UserState(positionMs = 60_000))
                val drive = film("drive").copy(user = UserState(positionMs = 60_000))
                provider.home = listOf(
                    HomeSection(SectionType.CONTINUE, listOf(heat, drive), landscape = true),
                    HomeSection(SectionType.LATEST, listOf(heat), landscape = false),
                )
                provider.fail = { null }
                val read = folders.filterNotNull()
                val vm = HomeViewModel({ provider }, scan, read, settings, scanAll = {}, work = this, events = FakeEvents())
                val collecting = backgroundScope.launch { vm.state.collect {} }
                advanceUntilIdle()
                fun row(type: SectionType) = (vm.content() as HomeContent.Rows).sections.single { it.type == type }.items

                provider.fail = { call ->
                    IOException("NAS away").takeIf { call.startsWith("played") || call.startsWith("favorite") || call == "home" }
                }
                val marking = provider.gate("played heat true")
                val then = provider.gate(second)
                fun latest() = row(SectionType.LATEST).single()
                vm.mark(latest())
                runCurrent()
                if (second.startsWith("favorite")) vm.favorite(latest()) else vm.mark(latest())
                runCurrent()
                assertEquals(listOf("drive"), row(SectionType.CONTINUE).map { it.id })

                (if (markFailsFirst) marking else then).complete(Unit)
                advanceUntilIdle()
                (if (markFailsFirst) then else marking).complete(Unit)
                advanceUntilIdle()
                val case = "$second after the mark, the mark failing first: $markFailsFirst"
                assertEquals(case, listOf(heat, drive), row(SectionType.CONTINUE))
                assertEquals(case, heat, row(SectionType.LATEST).single())
                collecting.cancel()
            }
        }
    }
}
