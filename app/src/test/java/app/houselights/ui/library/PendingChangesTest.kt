package app.houselights.ui.library

import app.houselights.core.MediaItem
import app.houselights.core.WatchStatus
import app.houselights.ui.testing.film
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a poster's changes show while they save and once one is not saved, whichever screen made them: the title as the
 * source has it, the other change still saving or saved showing meanwhile. Each screen's tests hold its own wiring
 * (one change undone and said, a read begun before a change); these hold the matrix, for all of them.
 */
class PendingChangesTest {
    private var pending = PendingChanges()
    private val samourai = film("samourai", "Le Samouraï")

    /** The title as its screen shows it, each change laid over it as asked and each one undone as the screen does. */
    private var shown: MediaItem = samourai

    /** A screen anew: nothing held, the title as the source has it. */
    private fun fresh() {
        pending = PendingChanges()
        shown = samourai
    }

    private fun mark(played: Boolean = !shown.user.played): Held =
        pending.hold(shown, shown.markedAs(played), Facet.MARK) { keepsMarked(it, played) }.also { shown = it.item }

    private fun favorite(favorite: Boolean = !shown.user.favorite): Held =
        pending.hold(shown, shown.favoredAs(favorite), Facet.FAVORITE) { keepsFavorite(it, favorite) }.also { shown = it.item }

    private fun fails(change: Held) {
        shown = pending.dropped(change).over(shown)
    }

    /** Marked then made a favourite, neither saved: the one still saving shows meanwhile, then the title as it was. */
    @Test
    fun aMarkAndAFavouriteNotSavedLeaveTheTitleAsItWasWhicheverFailsFirst() {
        for (markFailsFirst in listOf(true, false)) {
            fresh()
            val marked = mark()
            val favoured = favorite()
            assertEquals(samourai.markedAs(true).favoredAs(true), shown)
            fails(if (markFailsFirst) marked else favoured)
            val other = if (markFailsFirst) samourai.favoredAs(true) else samourai.markedAs(true)
            assertEquals("the mark failing first: $markFailsFirst", other, shown)
            fails(if (markFailsFirst) favoured else marked)
            assertEquals("the mark failing first: $markFailsFirst", samourai, shown)
        }
    }

    /** Marked then unmarked, neither saved: the last asked shows while it saves, then the title as it was. */
    @Test
    fun aMarkAndItsOppositeNotSavedLeaveTheTitleAsItWasWhicheverFailsFirst() {
        for (firstFailsFirst in listOf(true, false)) {
            fresh()
            val marked = mark(played = true)
            val unmarked = mark(played = false)
            fails(if (firstFailsFirst) marked else unmarked)
            assertEquals("the first failing first: $firstFailsFirst", !firstFailsFirst, shown.user.played)
            fails(if (firstFailsFirst) unmarked else marked)
            assertEquals("the first failing first: $firstFailsFirst", samourai, shown)
        }
    }

    /** A mark not saved leaves a favourite asked after it showing, saved before or after the mark fails. */
    @Test
    fun aMarkNotSavedLeavesAFavouriteSavedShowing() {
        for (markFailsFirst in listOf(true, false)) {
            fresh()
            val marked = mark()
            val favoured = favorite()
            if (markFailsFirst) {
                fails(marked)
                pending.saved(favoured)
            } else {
                pending.saved(favoured)
                fails(marked)
            }
            assertEquals("the mark failing first: $markFailsFirst", samourai.favoredAs(true), shown)
        }
    }

    /**
     * A read begun before a change was saved has it laid over its answer, which may say the title as it was; one begun
     * after says it, so nothing is laid over it; a change not saved lies over no read.
     */
    @Test
    fun aChangeLiesOverTheReadsBegunBeforeItsSaveAlone() {
        val before = pending.reading()
        val marked = mark()
        assertEquals(listOf(samourai.markedAs(true)), pending.over(before).map { it.over(samourai) })
        pending.saved(marked)
        assertEquals("begun before the save", listOf(samourai.markedAs(true)), pending.over(before).map { it.over(samourai) })
        assertEquals("begun after it", emptyList<Laid>(), pending.over(pending.reading()))

        fails(favorite())
        assertTrue("not saved", pending.over(before).none { it.item.user.favorite })
    }

    /** Over a read, a title's last mark and its last favourite are laid together, the read's other figures kept. */
    @Test
    fun aTitlesLastMarkAndLastFavouriteAreLaidOverARead() {
        val read = pending.reading()
        mark(played = true)
        mark(played = false)
        favorite(favorite = true)
        val answer = samourai.copy(title = "Le Samouraï (1967)")
        assertEquals(listOf(answer.markedAs(false).favoredAs(true)), pending.over(read).map { it.over(answer) })
    }

    /** A title marked watched leaves an Unwatched wall but stays on Favoris; a favourite taken out leaves Favoris alone. */
    @Test
    fun aWallKeepsATitleChangedAsItsFilterSays() {
        shown = samourai.favoredAs(true)
        mark(played = true)
        val laid = pending.over(pending.reading()).single()
        assertEquals(false, laid.stays(WatchStatus.UNWATCHED))
        assertEquals(true, laid.stays(WatchStatus.FAVORITES))
        favorite(favorite = false)
        val both = pending.over(pending.reading()).single()
        assertEquals(false, both.stays(WatchStatus.FAVORITES))
        assertEquals(true, both.stays(WatchStatus.ALL))
    }
}
