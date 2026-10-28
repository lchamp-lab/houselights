package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.setTvContent
import coil.Coil
import coil.ImageLoader
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.transition.Transition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Components: a picture says once it shows ([ArtworkImage]'s `onShow`), its own decoded or its fallback,
 * and then comes without the loader's fade, its caller bringing it in; every other picture is asked as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ArtworkShownTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun forgetTheLoader() = Coil.reset()

    /** Without `onShow`, a picture is asked as before: its address, or its request at its size, the loader's fade under it. */
    @Test
    fun aPictureIsAskedAsBeforeWithoutOnShown() {
        assertEquals(URL, artworkModel(context, URL, null, fades = true))
        val sized = artworkModel(context, URL, SIZE, fades = true) as ImageRequest
        assertEquals(URL, sized.data)
        assertNull("the loader's own fade", sized.defined.transitionFactory)
    }

    /** A picture its caller brings in itself comes without the loader's fade, at its size when it has one. */
    @Test
    fun aPictureItsCallerBringsInComesWithoutTheLoadersFade() {
        listOf(null, SIZE).forEach { size ->
            val request = artworkModel(context, URL, size, fades = false) as ImageRequest
            assertEquals(URL, request.data)
            assertEquals("at $size", Transition.Factory.NONE, request.transitionFactory)
        }
    }

    /** `onShow` is told once the picture is decoded, never while it loads. */
    @Test
    fun onShowIsToldOnceThePictureIsDecoded() {
        val held = CompletableDeferred<Unit>()
        Coil.setImageLoader(loader(pictureServer { _, _ -> held.await() }))
        var told = 0
        compose.setTvContent { ArtworkImage(URL, "Dune", Modifier.size(SIDE.dp), size = SIZE, onShow = { told++ }) }
        compose.waitForIdle()
        assertEquals("loading", 0, told)
        held.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { told == 1 }
    }

    /** The fallback in the picture's place, missing or failing, `onShow` is told too: what waits for it never waits for nothing. */
    @Test
    fun onShowIsToldOfTheFallback() {
        Coil.setImageLoader(loader(pictureServer { data, _ -> check("broken" !in data.toString()) { "No picture" } }))
        var missing = 0
        var failed = 0
        compose.setTvContent {
            Column {
                ArtworkImage(null, "Dune", Modifier.size(SIDE.dp), onShow = { missing++ })
                ArtworkImage(BROKEN, "Heat", Modifier.size(SIDE.dp), size = SIZE, onShow = { failed++ })
            }
        }
        compose.waitUntil(TIMEOUT_MS) { missing == 1 && failed == 1 }
    }

    private fun loader(server: Fetcher.Factory<Any>) =
        ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(server) }.build()

    private companion object {
        const val URL = "https://img.test/dune?w=224"
        const val BROKEN = "https://img.test/broken?w=224"
        val SIZE = IntSize(224, 336)
        const val SIDE = 40
        const val TIMEOUT_MS = 5_000L
    }
}
