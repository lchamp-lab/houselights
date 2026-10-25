package app.houselights.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Read again, what a screen shows stays: a value refreshing, an error retrying; a failed refresh keeps the value. */
class LoadStateTest {
    @Test
    fun readAgainWhatShowsStays() {
        assertEquals(LoadState.Ready("page", refreshing = true), LoadState.Ready("page").readingAgain())
        assertEquals(LoadState.Failed(UiError.OFFLINE, retrying = true), LoadState.Failed(UiError.OFFLINE).readingAgain())
        assertEquals(LoadState.Loading, LoadState.Loading.readingAgain())
    }

    @Test
    fun whatComesTakesItsPlace() {
        val refreshing = LoadState.Ready("old", refreshing = true)
        val retrying = LoadState.Failed(UiError.OFFLINE, retrying = true)
        assertEquals(LoadState.Ready("new"), refreshing.after(LoadState.Ready("new")))
        assertEquals(LoadState.Ready("new"), retrying.after(LoadState.Ready("new")))
        assertEquals(LoadState.Failed(UiError.FAILED), retrying.after(LoadState.Failed(UiError.FAILED)))
        assertEquals(LoadState.Failed(UiError.OFFLINE), LoadState.Loading.after(LoadState.Failed(UiError.OFFLINE)))
    }

    /** A value made into another is still being read again. */
    @Test
    fun aMappedValueKeepsRefreshing() {
        assertEquals(LoadState.Ready(5, refreshing = true), LoadState.Ready("pages", refreshing = true).map { it.length })
    }

    /** A value shown stays when reading it again fails: the failure is logged, not shown in its place. */
    @Test
    fun aFailedRefreshKeepsTheValue() {
        assertEquals(LoadState.Ready("old"), LoadState.Ready("old", refreshing = true).after(LoadState.Failed(UiError.OFFLINE)))
    }
}
