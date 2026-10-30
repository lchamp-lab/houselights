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

    /** A show's page as it opens: its logo and its facts over its picture, Play on its next episode in focus. */
    @Test
    fun show() {
        camera.title(camera.library.idOf(ReadmeTitles.SHOW_PAGE))
        camera.assertFocusOn("Play")
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

    /**
     * « To watch », opened by home's door of the latest titles, focus along them to [ReadmeTitles.WATCH_FOCUS], whose
     * overview its two lines hold to a word.
     */
    @Test
    fun toWatch() {
        camera.journey()
        camera.toNextUp()
        camera.press(Key.DirectionRight)
        camera.assertFocusOn("New")
        camera.press(Key.DirectionCenter)
        camera.pressUntil(Key.DirectionRight, ReadmeTitles.WATCH_FOCUS)
        camera.capture("watch")
    }

    /** Settings › Playback: the audio and subtitles the Zidoo player is asked for every title, Subtitles in focus. */
    @Test
    fun playback() {
        camera.chooseTracks(ReadmeTitles.TRACKS)
        camera.settings()
        camera.pressUntil(Key.DirectionDown, "Playback")
        camera.settle()
        camera.press(Key.DirectionRight)
        camera.pressUntil(Key.DirectionDown, "Subtitles")
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
 * The README's animation, filmed on a paused clock a frame every 32 ms (`frames/NNNN.png`): the lights come up on home,
 * focus goes to the latest titles, « To watch » opens and focus runs across them, its backdrop and glow following, OK
 * opens a film's page, and Play dims the room, as before the player starts. Each step is cued (`frames/cues.txt`),
 * where compose.py cuts it into its loop.
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
        camera.cue("lit")
        camera.roll(BEAT)
        step(Key.DirectionRight, HOME)
        // « To watch », on the latest films.
        camera.cue("watch")
        step(Key.DirectionCenter, OPEN)
        repeat(2) { step(Key.DirectionRight, STEP) }
        step(Key.DirectionRight, HOLD)
        // The film's page, Play in focus, then Play: the room goes dark.
        camera.cue("page")
        step(Key.DirectionCenter, PAGE)
        camera.cue("play")
        step(Key.DirectionCenter, DIM)
    }

    /** [key] pressed, then [seconds] filmed. */
    private fun step(key: Key, seconds: Double) {
        camera.press(key)
        camera.roll(seconds)
    }

    private companion object {
        const val LIGHTS_UP = 1.0
        const val BEAT = 0.6
        const val HOME = 0.8

        /** « To watch » opens: its hero settles on the title in focus, which compose.py cuts to, then stays. */
        const val OPEN = 1.3
        const val STEP = 0.7
        const val HOLD = 1.0

        /** Long enough to read the film's picture and sound. */
        const val PAGE = 2.2
        const val DIM = 1.25
    }
}
