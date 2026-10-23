package app.houselights.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import java.util.regex.Pattern

// A viewer's most common journeys, which the baseline profile compiles and the benchmarks measure. Each
// starts from a fresh home, focus on the first card, and navigates by on-screen text.

/** Home freshly started: its first row shown, focus on its first card. */
internal fun MacrobenchmarkScope.startHome() {
    killProcess()
    startActivityAndWait()
    awaitHome()
}

internal fun MacrobenchmarkScope.awaitHome() {
    await(By.textStartsWith(appString("section_latest_movies")), "home's rows")
}

/** Along the first row and back, the hero following focus; then the next row. */
internal fun MacrobenchmarkScope.browseHome() {
    right(6)
    left(6)
    down()
    right(4)
    left(4)
    up()
}

/** Up to the tabs and along them, then the films' wall, browsed. */
internal fun MacrobenchmarkScope.browseLibrary() {
    up(2)
    right(2)
    focusOn(appString("library_movies"), KeyEvent.KEYCODE_DPAD_LEFT)
    ok()
    await(By.textStartsWith(appString("sort")), "the films' wall")
    right(4)
    down()
    left(2)
    down()
    // Back to the bar, then home.
    back(2)
}

/** A film's page: down its rows and along its cast, then back up to its actions and home. */
internal fun MacrobenchmarkScope.browseDetail() {
    ok()
    awaitDetail()
    down(3)
    right(4)
    left(4)
    up(3)
    back()
}

/** A series' page from its wall: its episodes, the overview following focus, then its cast. */
internal fun MacrobenchmarkScope.browseShow() {
    up(2)
    if (!device.hasObject(By.text(appString("library_shows")))) return
    focusOn(appString("library_shows"), KeyEvent.KEYCODE_DPAD_RIGHT)
    ok()
    await(By.textStartsWith(appString("sort")), "the series' wall")
    ok()
    awaitDetail()
    down()
    right(3)
    left(3)
    down()
    right(3)
    back(2)
}

/** The settings: each pane in turn, then the library's rows. */
internal fun MacrobenchmarkScope.browseSettings() {
    openSettings()
    down(3)
    up(3)
    right()
    down(4)
    back(2)
}

/** A search from home's bar: a few letters typed, the results read, then home. None for a source that cannot search. */
internal fun MacrobenchmarkScope.browseSearch() {
    up(2)
    right(4)
    if (!focusSays(appString("search"))) focusOn(appString("search"), KeyEvent.KEYCODE_DPAD_LEFT, max = 3)
    ok()
    await(By.text(appString("search_hint")), "the search")
    // A, M, E: down the keys, then up and along them.
    ok()
    down(2)
    ok()
    up(2)
    right(4)
    ok()
    val found = listOf("search_titles", "search_people", "search_sagas").joinToString("|") { Pattern.quote(appString(it)) }
    val none = Pattern.quote(appString("search_none").substringBefore("%"))
    await(By.text(Pattern.compile("$found|$none.*")), "what the search found")
    back()
}

/** Waits for a detail page: its play button, Lire or Reprendre. */
private fun MacrobenchmarkScope.awaitDetail() {
    val play = Pattern.compile("(" + Pattern.quote(appString("play")) + "|" + Pattern.quote(appString("resume")) + ").*")
    await(By.text(play), "a detail page")
}
