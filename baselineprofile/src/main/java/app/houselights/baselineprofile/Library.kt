package app.houselights.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The NAS folders the journeys browse, read from local.properties (baselineprofile.*), never in the sources. */
internal data class LibrarySetup(val host: String, val export: String, val movies: String, val shows: String?) {
    companion object {
        fun fromArguments(): LibrarySetup {
            val args = InstrumentationRegistry.getArguments()
            fun arg(key: String) = args.getString("library.$key")?.takeIf { it.isNotBlank() }
            val host = arg("host")
            val export = arg("export")
            val movies = arg("movies")
            check(host != null && export != null && movies != null) {
                "The app has no library yet: set baselineprofile.host, .export and .movies (and .shows) in " +
                    "local.properties to a NAS folder the device can read over NFS"
            }
            return LibrarySetup(host, export, movies, arg("shows"))
        }
    }
}

/** How long a first scan of the folders may take: files listed, then matched on TMDB. */
private const val SCAN_MS = 300_000L

/** On a fresh install, adds the folders as a viewer would (Mes fichiers, then each) and awaits the scans. */
internal fun MacrobenchmarkScope.ensureLibrary() {
    val welcome = appString("source_local")
    val home = appString("nav_home")
    await(By.text(Pattern.compile(Pattern.quote(welcome) + "|" + Pattern.quote(home))), "Welcome or home")
    if (!shows(welcome)) return
    val setup = LibrarySetup.fromArguments()
    // Mes fichiers has focus on Welcome.
    ok()
    addFolder(setup, setup.movies, shows = false)
    awaitScan("section_latest_movies")
    setup.shows?.let { folder ->
        openSettings()
        right()
        focusOn(appString("add_folder"), KeyEvent.KEYCODE_DPAD_DOWN)
        ok()
        addFolder(setup, folder, shows = true)
        // Another folder leads back to the settings, focus in the pane: Back to its entry, then home.
        backHome()
        awaitScan("section_latest_shows")
    }
}

/** Fills in and submits the folder form: content type, NFS address, folder. */
private fun MacrobenchmarkScope.addFolder(setup: LibrarySetup, folder: String, shows: Boolean) {
    await(By.text(appString("add_folder_desc")), "the add-folder form")
    if (shows) {
        // Focus starts on the content type, films: one to the right selects series.
        right()
        ok()
    }
    // Fields in reading order: address, export, folder.
    val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), SCREEN_MS)
        .sortedWith(compareBy({ it.visibleBounds.top }, { it.visibleBounds.left }))
    check(fields.size >= 3) { "The form has ${fields.size} fields, not the address, export and folder" }
    fields[0].text = setup.host
    fields[1].text = setup.export
    fields[2].text = folder
    focusOn(appString("add"), KeyEvent.KEYCODE_DPAD_DOWN)
    ok()
    // The folder is tested, then added, and the form closes.
    check(device.wait(Until.gone(By.text(appString("add_folder_desc"))), SCAN_MS)) { "The folder $folder was not added" }
}

/** Waits for home to show the row a scan fills, named by [row]. */
private fun MacrobenchmarkScope.awaitScan(row: String) {
    await(By.text(appString(row)), "the first scan to fill the row \"${appString(row)}\"", SCAN_MS)
    device.wait(Until.gone(By.text(appString("home_scanning"))), SCAN_MS)
}

/** Presses Back until home shows its rows: Back first returns to where a screen starts, then leaves it. */
internal fun MacrobenchmarkScope.backHome() {
    val rows = appString("section_latest_movies")
    repeat(BACK_STEPS) {
        if (shows(rows)) return
        back()
    }
    await(By.text(rows), "home after Back")
}

/** The most presses of Back from a screen to home: a pane, its entry, the settings. */
private const val BACK_STEPS = 3

/** From home: up to the bar, along it to the settings button, then OK. */
internal fun MacrobenchmarkScope.openSettings() {
    up(2)
    focusOn(appString("nav_settings"), KeyEvent.KEYCODE_DPAD_RIGHT)
    ok()
    await(By.text(appString("settings_title")), "the settings")
}
