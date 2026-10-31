package app.houselights.ui.design.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.houselights.settings.InterfaceLanguage
import app.houselights.testing.Source
import app.houselights.testing.argumentsFrom
import app.houselights.testing.kotlinSources
import app.houselights.testing.source
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
import java.io.File
import java.util.Locale

/**
 * Every tile's label whole under it, in each of the app's languages, at the default text size and Android's larger ones:
 * every label a tile ([HlIconButton]) is given in the app's code.
 */
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
        val ids = tileLabels()
        val labels = InterfaceLanguage.LANGUAGES.flatMap { tag ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val local = context.createConfigurationContext(config)
            ids.map { id -> "$tag: ${context.resources.getResourceEntryName(id)}" to local.getString(id) }
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

    @Test
    fun everyTileIsRead() {
        val names = tileLabels().map { ApplicationProvider.getApplicationContext<Context>().resources.getResourceEntryName(it) }
        listOf("tech_details", "add_favorite", "remove_favorite", "mark_season_watched", "nav_settings")
            .forEach { assertTrue("$it read as a tile's label: $names", it in names) }
        val stale = chosen.keys.filter { (path, label) -> !labelsIn(source(ui.resolve(path))).contains(label) }
        assertTrue("Labels chosen elsewhere no tile is given any more: $stale", stale.isEmpty())
    }

    private val ui = File("src/main/java/app/houselights/ui")

    /**
     * The strings the app's tiles are given, read from their calls: a label's `R.string` ids, or, for a label chosen
     * before the call, the ids [chosen] names for it.
     */
    private fun tileLabels(): List<Int> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = kotlinSources(ui).flatMap { file ->
            labelsIn(file).flatMap { label ->
                val ids = stringId.findAll(label).map { it.groupValues[1] }.toList()
                ids.ifEmpty { checkNotNull(chosen[file.pathFrom(ui) to label]) { "${file.pathFrom(ui)}: a tile's label unread, $label" } }
            }
        }
        return names.distinct().sorted().map { context.resources.getIdentifier(it, "string", context.packageName) }
    }

    /** The label argument of each tile in [file]: its second, or the one named. */
    private fun labelsIn(file: Source): List<String> = tile.findAll(file.code).map { call ->
        val arguments = file.code.argumentsFrom(call.range.last)
        arguments.firstOrNull { it.startsWith("label =") }?.substringAfter('=')?.trim()
            ?: arguments.filter { !named.containsMatchIn(it) }[1]
    }.toList()

    private val tile = Regex("""(?<![A-Za-z]|fun )HlIconButton\(""")
    private val named = Regex("""^[a-zA-Z]\w*\s*=(?!=)""")
    private val stringId = Regex("""\bR\.string\.(\w+)""")

    /** Labels chosen before their tile's call, by file and label, and the strings they choose among. */
    private val chosen = mapOf(
        ("detail/DetailScreen.kt" to "stringResource(label)") to
            listOf("mark_watched", "mark_unwatched", "mark_season_watched", "mark_season_unwatched"),
    )

    private companion object {
        /** The default text size and Android's larger ones. */
        val SCALES = listOf(1f, 1.15f, 1.3f)
    }
}
