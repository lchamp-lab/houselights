package app.houselights.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Version
import app.houselights.ui.design.components.FactPlate
import app.houselights.ui.design.components.FieldName
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.LetterGrid
import app.houselights.ui.design.components.PlateFact
import app.houselights.ui.design.components.SearchQuery
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.VersionRow
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.assertSetIn
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Type: every name to choose is an `entry` (16), a field's name a `group` a step under what is typed in it,
 * the query and the keyboard's letters a `lead`; an icon beside words is `HlSize.icon`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class TypeRolesTest {
    @get:Rule
    val compose = createComposeRule()

    private val t = HouselightsType

    @Test
    fun aSettingIsNamedInEntryAndSaysWhatItDoesInMeta() {
        compose.setTvContent { HlSettingRow("Dossiers", value = "3 dossiers", description = "Où sont vos films") }
        compose.assertSetIn(t.entry, "Dossiers")
        compose.assertSetIn(t.meta, "Où sont vos films")
        compose.assertSetIn(t.meta, "3 dossiers")
    }

    @Test
    fun aPlaceIsAnEntryAfterAnIconOfTwenty() {
        compose.setTvContent { Box(Modifier.width(256.dp)) { HlNavItem(HlIcons.Folder, "Bibliothèque", selected = false, onFocused = {}) } }
        compose.assertSetIn(t.entry, "Bibliothèque")
        val place = compose.onNode(hasClickAction()).getUnclippedBoundsInRoot()
        val words = compose.onNodeWithText("Bibliothèque", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(HlSpace.s + HlSize.icon + HlSpace.s, words.left - place.left)
    }

    @Test
    fun aChoiceAndAVersionAreEntries() {
        val film = MediaItem("f1", MediaKind.MOVIE, "Dune")
        val version = Version(
            film, edition = null, label = "Version longue", media = null, sizeBytes = null, runtimeMs = null,
            chosen = true, best = true, key = "v1",
        )
        compose.setTvContent {
            Column {
                HlListItem("Français", description = "Audio", onClick = {})
                VersionRow("Version longue", version, onChoose = {})
            }
        }
        compose.assertSetIn(t.entry, "Français")
        compose.assertSetIn(t.entry, "Version longue")
    }

    @Test
    fun aValueInWordsIsAnEntryATechnicalOneASpec() {
        compose.setTvContent {
            Column {
                FactPlate {
                    PlateFact("Fréquence", "Réglée par le lecteur")
                    PlateFact("Définition", "Full HD", technical = true)
                }
                StatusPanel(StatusState.On, "Lu", label = "Adresse", value = "192.168.1.20", name = "Films")
            }
        }
        compose.assertSetIn(t.entry, "Réglée par le lecteur")
        compose.assertSetIn(t.spec, "Full HD")
        compose.assertSetIn(t.entry, "Films")
        compose.assertSetIn(t.spec, "192.168.1.20")
    }

    @Test
    fun aFieldIsNamedAStepUnderWhatIsTypedInIt() {
        // The field reads its name out itself: the name shown is out of the semantics tree, hence FieldName alone.
        compose.setTvContent {
            Column {
                FieldName("Adresse du NAS", "Adresse du NAS")
                HlTextField("Partage", "192.168.1.20", onValueChange = {}, showLabel = false)
            }
        }
        compose.assertSetIn(t.group, "Adresse du NAS")
        compose.assertSetIn(t.body, "192.168.1.20")
    }


    @Test
    fun theQueryAndTheLettersAreLeadsAfterAnIconOfTwenty() {
        compose.setTvContent {
            Column {
                Box(Modifier.width(400.dp)) { SearchQuery("dune", hint = "Un titre, une saga") }
                LetterGrid(onKey = {}, onSpace = {}, onDelete = {})
            }
        }
        compose.assertSetIn(t.lead, "dune")
        compose.assertSetIn(t.lead, "Q")
        val words = compose.onNodeWithText("dune").getUnclippedBoundsInRoot()
        assertEquals(HlSpace.m + HlSize.icon + HlSpace.s, words.left)
    }
}
