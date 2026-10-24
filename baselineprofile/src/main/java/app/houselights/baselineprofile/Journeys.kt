package app.houselights.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

// A viewer's most common journeys, which the baseline profile compiles and the benchmarks measure. Each
// starts from a fresh home, focus on its case, and reaches a door by where it lies on screen (focusTo): a
// library of films alone, its door across the whole row, or of films and shows, takes the same journeys.

/** Home freshly started: its grid shown, focus on its case. */
internal fun MacrobenchmarkScope.startHome() {
    killProcess()
    startActivityAndWait()
    awaitHome()
}

/** Home's grid shown and the launch screen lifted: until then it takes no key. */
internal fun MacrobenchmarkScope.awaitHome() {
    await(By.textStartsWith(appString("home_news")), "home's grid")
    device.wait(Until.gone(By.res(Pattern.compile(".*:id/splashscreen_icon_view"))), SCREEN_MS)
}

/** Home's doors in reading order: what is watched, the libraries, the tools. */
private val HOME_DOORS = listOf(
    "section_next_up",
    "section_continue",
    "home_news",
    "library_movies",
    "library_shows",
    "home_libraries",
    "library_collections",
    "search",
    "nav_settings",
)

/** Each door home shows in turn, then left along the last row to the case. */
internal fun MacrobenchmarkScope.browseHome() {
    HOME_DOORS.map { appString(it) }.filter { device.hasObject(By.text(it)) }.forEach { focusTo(it) }
    left(3)
}

/** The films' wall from its door: along and down its posters, then back to the first and home. */
internal fun MacrobenchmarkScope.browseLibrary() {
    openWall("library_movies")
    right(4)
    down()
    left(2)
    down()
    back(2)
}

/** The sagas' wall from its door, where the source has some. */
internal fun MacrobenchmarkScope.browseSagas() {
    if (!device.hasObject(By.text(appString("library_collections")))) return
    openWall("library_collections")
    right(3)
    down()
    back(2)
}

/** A film's page from « À regarder »: down its rows and along its cast, then back up to its actions and home. */
internal fun MacrobenchmarkScope.browseDetail() {
    focusTo(appString("home_news"))
    ok()
    await(By.text(appString("watch_title")), "the page to watch")
    ok()
    awaitDetail()
    down(3)
    right(4)
    left(4)
    up(3)
    back(2)
}

/** A series' page from its wall: its episodes, the overview following focus, then its cast. */
internal fun MacrobenchmarkScope.browseShow() {
    if (!device.hasObject(By.text(appString("library_shows")))) return
    openWall("library_shows")
    ok()
    awaitDetail()
    down()
    right(3)
    left(3)
    down()
    right(3)
    back(2)
}

/** The settings: down their places, each pane shown in turn, then into the library's rows. */
internal fun MacrobenchmarkScope.browseSettings() {
    openSettings()
    down(SETTINGS_PLACES)
    up(SETTINGS_PLACES)
    right()
    down(4)
    back(2)
}

/** The settings' places past the first: Numbers, Playback, Trakt, Display, Storage, About, where shown. */
private const val SETTINGS_PLACES = 6

/** A search from home's door: a few letters typed, the results read, then home. None for a source that cannot search. */
internal fun MacrobenchmarkScope.browseSearch() {
    if (!device.hasObject(By.text(appString("search")))) return
    focusTo(appString("search"))
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

/** A wall from home's [door], named by its string, once its menus show. */
private fun MacrobenchmarkScope.openWall(door: String) {
    focusTo(appString(door))
    ok()
    await(By.textStartsWith(appString("sort")), "the wall \"${appString(door)}\"")
}

/** Waits for a detail page: its play button, Lire or Reprendre. */
private fun MacrobenchmarkScope.awaitDetail() {
    val play = Pattern.compile("(" + Pattern.quote(appString("play")) + "|" + Pattern.quote(appString("resume")) + ").*")
    await(By.text(play), "a detail page")
}
