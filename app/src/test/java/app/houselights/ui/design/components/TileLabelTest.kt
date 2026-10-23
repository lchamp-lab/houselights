package app.houselights.ui.design.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.TV
import app.houselights.ui.util.screenWidthPx
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Every tile's label and hint whole under it, in each of the app's languages, at the default text size and Android's larger ones. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so words measure as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileLabelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyTileLabelIsWholeAtEveryTextSize() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val labels = InterfaceLanguage.LANGUAGES.flatMap { tag ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val local = context.createConfigurationContext(config)
            TILE_LABELS.map { "$tag: ${local.getString(it)}" to local.getString(it) }
        }
        val cut = mutableListOf<String>()
        compose.setContent {
            Column {
                SCALES.forEach { scale ->
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        HouselightsTheme {
                            val measurer = rememberTextMeasurer()
                            val style = Hl.type.meta
                            val screen = screenWidthPx()
                            // The plate's padding takes its share of the label's width.
                            val room = with(LocalDensity.current) { tileLabelWidth(screen) - 2 * HlControl.labelPaddingH.roundToPx() }
                            labels.forEach { (name, label) ->
                                val width = measurer.measure(label, style, softWrap = false, maxLines = 1).size.width
                                if (width > room) cut += "at $scale, $name: $width > $room"
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("Cut short:\n" + cut.joinToString("\n"), cut.isEmpty())
    }

    private companion object {
        /** The default text size and Android's larger ones. */
        val SCALES = listOf(1f, 1.15f, 1.3f)

        /** What the app's tiles say, and their hints, bound alike. */
        val TILE_LABELS = listOf(
            R.string.mark_watched, R.string.mark_unwatched, R.string.mark_season_watched, R.string.mark_season_unwatched,
            R.string.menu_series_watched, R.string.menu_series_unwatched,
            R.string.add_favorite, R.string.remove_favorite, R.string.from_start, R.string.versions, R.string.tech_details,
            R.string.nfo_title, R.string.edit, R.string.add, R.string.remove, R.string.search, R.string.nav_settings,
        )
    }
}
