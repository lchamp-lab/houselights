package app.houselights.ui.design.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.fetch.Fetcher
import kotlinx.coroutines.Dispatchers
import org.junit.rules.ExternalResource

/** A loader of [server]'s pictures working on the thread that asks ([Dispatchers.Unconfined]), [options] set as a test needs. */
inline fun <reified T : Any> testLoader(
    server: Fetcher.Factory<T>,
    context: Context = ApplicationProvider.getApplicationContext(),
    options: ImageLoader.Builder.() -> ImageLoader.Builder = { this },
): ImageLoader = ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).options().components { add(server) }.build()

/**
 * The app's loader for a test: [serve] sets one, and it is forgotten after each test whatever the test did, since Coil's
 * loader is the JVM's and a test never counts on what another class left there (CONTRIBUTING).
 */
class CoilRule : ExternalResource() {
    /** Coil's loader, from now on: [testLoader]'s of [server]. */
    inline fun <reified T : Any> serve(server: Fetcher.Factory<T>, options: ImageLoader.Builder.() -> ImageLoader.Builder = { this }) =
        Coil.setImageLoader(testLoader(server, options = options))

    /** Coil's loader, from now on: [loader]. */
    fun serve(loader: ImageLoader) = Coil.setImageLoader(loader)

    override fun after() = Coil.reset()
}
