package app.houselights.readme

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The README's screenshots, made by the app itself: its real screens over a library of recent films and shows that the
 * app's own scanner read from TMDB, in English, at 1920 × 1080 with focus drawn. They run only when HOUSELIGHTS_README
 * names where they go, with a TMDB key (CONTRIBUTING, README's images); `tools/readme-images/compose.py` then frames
 * them. Each saves `stills/<name>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = README_TV, sdk = [README_SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ReadmeShots {
    @get:Rule
    val camera = ReadmeCamera()

    /** Home, the case resuming the film in progress beside the next episode and the latest titles. */
    @Test
    fun home() {
        camera.home()
        camera.report()
        camera.capture("home")
    }

    /** A film's page, Play in focus, its picture and sound on the case back. */
    @Test
    fun film() {
        camera.title(camera.library.idOf(ReadmeTitles.FILM_PAGE))
        camera.capture("film")
    }

    /** A show's page on its episodes, the next one in focus. */
    @Test
    fun show() {
        val library = camera.library
        camera.title(library.idOf(ReadmeTitles.SHOW_PAGE))
        // Down to the episodes of the season of the next one, then along them to it.
        camera.pressUntil(Key.DirectionDown, library.episodes(ReadmeTitles.SHOW_PAGE))
        camera.pressUntil(Key.DirectionRight, library.nextEpisode(ReadmeTitles.SHOW_PAGE))
        camera.capture("show")
    }

    /** A saga's page: the films owned among its films, resumed where the one in progress stopped. */
    @Test
    fun saga() {
        camera.title(camera.library.sagaOf(ReadmeTitles.SAGA_OF))
        camera.capture("saga")
    }

    /** The Movies wall, a saga among the films, lit by the poster in focus. */
    @Test
    fun movies() {
        camera.movies()
        camera.capture("library")
    }

    /** « To watch », opened by home's door of the latest titles, on the latest film. */
    @Test
    fun toWatch() {
        camera.journey()
        camera.toNextUp()
        camera.press(Key.DirectionRight)
        camera.assertFocusOn("New")
        camera.press(Key.DirectionCenter)
        camera.capture("watch")
    }

    /** Settings › Display: the interface's language and its text size, the latter in focus. */
    @Test
    fun display() {
        camera.settings()
        camera.pressUntil(Key.DirectionDown, "Display")
        camera.settle()
        camera.press(Key.DirectionRight)
        camera.pressUntil(Key.DirectionDown, "Text size")
        camera.capture("settings")
    }

    /** Home as a new version is found: the update sheet over it. */
    @Test
    fun update() {
        camera.homeWithAnUpdate()
        // Down to Later and back: Update in focus, as on the TV.
        camera.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        camera.pressInSheet(KeyEvent.KEYCODE_DPAD_UP)
        camera.assertFocusOn("Update")
        camera.capture("update")
    }

    // The guided way to add a folder (feat/040-add-folder-flow) is shot here once it is in release/0.4.0: its first
    // step, the NAS found on the network, as `camera.show { AddFolderScreen(...) }` sets it.
}

/** « To identify » and Identify's suggestions, over the library with a few files the scan could not name alone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = README_TV, sdk = [README_SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ReadmeIdentifyShots {
    @get:Rule
    val camera = ReadmeCamera(
        extra = {
            ReadmeTitles.TO_IDENTIFY.forEach { name ->
                putFilm(name, ReadmeFiles.film(Master.FHD_DTS, UNNAMED_MINUTES), ReadmeFiles.size(Master.FHD_DTS, UNNAMED_MINUTES))
            }
        },
    )

    /** The titles to identify, the latest first, each with why, what it may be and where it lies. */
    @Test
    fun toIdentify() {
        stamp()
        camera.toIdentify()
        camera.capture("to-identify")
    }

    /** Identify on the first of them: its suggestions at once, the best in focus. */
    @Test
    fun identify() {
        stamp()
        camera.identify(camera.firstToIdentify())
        camera.settle(SEARCH_ROUNDS)
        camera.capture("identify")
    }

    /** The files to identify joined the library in their order, the first last, minutes apart. */
    private fun stamp() = ReadmeTitles.TO_IDENTIFY.forEachIndexed { i, name -> camera.library.addedAgo(name, (i + 1) * STAMP_DAYS) }

    private companion object {
        const val UNNAMED_MINUTES = 118
        const val STAMP_DAYS = 0.01

        /** TMDB's search and its posters, on the network. */
        const val SEARCH_ROUNDS = 120
    }
}

/**
 * The README's animation, filmed on a paused clock a frame every 32 ms (`frames/NNNN.jpg`): the lights come up on home,
 * focus goes to the latest titles, « To watch » opens and focus runs across them, its backdrop and glow following, OK
 * opens a film's page, and Play dims the room, as before the player starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = README_TV, sdk = [README_SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ReadmeTour {
    @get:Rule
    val camera = ReadmeCamera()

    @Test
    fun fromHomeToTheFilm() {
        camera.journey()
        // Before the reel: focus on the next episode's door, beside the latest titles', and the room dark.
        camera.toNextUp()
        camera.lightsDown()
        camera.reel()
        camera.lightsUp()
        camera.roll(LIGHTS_UP)
        camera.roll(BEAT)
        step(Key.DirectionRight, STEP)
        // « To watch », on the latest films.
        step(Key.DirectionCenter, OPEN)
        repeat(2) { step(Key.DirectionRight, STEP) }
        step(Key.DirectionRight, OPEN)
        // The film's page, Play in focus, then Play: the room goes dark.
        step(Key.DirectionCenter, PAGE)
        step(Key.DirectionCenter, DIM)
    }

    /** [key] pressed, then [seconds] filmed. */
    private fun step(key: Key, seconds: Double) {
        camera.press(key)
        camera.roll(seconds)
    }

    private companion object {
        const val LIGHTS_UP = 1.0
        const val BEAT = 0.4
        const val STEP = 0.7
        const val OPEN = 1.0
        const val PAGE = 1.3
        const val DIM = 1.25
    }
}
