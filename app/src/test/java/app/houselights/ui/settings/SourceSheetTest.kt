package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.core.SourceConfig
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Changer de source" sheet: its rows on the folders with or without a kept server, and on the server; the row that opens it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class SourceSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val salon = SourceConfig.Jellyfin("https://nas:8920", "NAS du salon", "u1", "léa", "token")
    private val chosen = mutableListOf<SourceChoice>()

    private fun show(source: SourceConfig, kept: SourceConfig.Jellyfin? = null, trakt: Boolean = false, folders: Int = 2) {
        compose.setTvContent { SourceSheet(SourceSheetFacts(source, kept, folders, titles = 11, trakt), {}, { chosen += it }) }
        compose.waitForIdle()
    }

    /** The row that says [name], not the same words among the sheet's facts. */
    private fun row(name: String): SemanticsNodeInteraction = compose.onNode(hasText(name) and hasClickAction())

    private fun shows(vararg texts: String) = texts.forEach { compose.onNodeWithText(it).assertExists() }

    /** Focus on the row that says [name], then OK, as the remote does it in the sheet's window. */
    private fun ok(name: String) {
        row(name).requestFocus()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun onTheFoldersTheKeptServerResumesInOneOk() {
        show(SourceConfig.Local, kept = salon)
        shows(
            "Vos dossiers sont conservés", "2 dossiers sur votre NAS", "Jellyfin · léa · sans vous reconnecter",
            "Un autre serveur Jellyfin", "Une autre adresse ou un autre compte", "Houselights s’en déconnecte ici",
        )
        row("NAS du salon").assertIsFocused()
        ok("NAS du salon")
        assertEquals(listOf(SourceChoice.Server(salon, current = false)), chosen)
    }

    @Test
    fun forgettingTheKeptServerIsAskedFirst() {
        show(SourceConfig.Local, kept = salon)
        ok("Oublier «${NB}NAS du salon$NB»")
        shows(
            "Oublier «${NB}NAS du salon$NB»${NB}?",
            "Houselights se déconnectera de ce serveur sur cet appareil. Pour le retrouver, il faudra vous reconnecter.",
        )
        assertEquals(emptyList<SourceChoice>(), chosen)
        ok("Oublier")
        assertEquals(listOf(SourceChoice.Leave(salon, current = false)), chosen)
    }

    /** Leaving the server for the folders asks nothing: nothing is lost, and Trakt picks up again. */
    @Test
    fun onTheServerTheFoldersComeAtOnce() {
        show(salon, trakt = true)
        shows("Le serveur reste connecté si vous changez", "2 dossiers · Trakt reprend", "Jellyfin · léa")
        row("Mes fichiers").assertIsFocused()
        ok("Mes fichiers")
        assertEquals(listOf(SourceChoice.Folders(current = false, traktResumes = true)), chosen)
    }

    /** German counts its folders alone in the nominative, "in" taking the dative only in the library's summary. */
    @Test
    @Config(qualifiers = "de-$TV")
    fun theFoldersAreCountedAloneInEachLanguage() {
        show(salon, trakt = true)
        shows("2 Ordner · Trakt läuft weiter")
    }

    /** While the kept server is asked, the Source row says so and opens nothing: its coming back would undo another choice. */
    @Test
    fun whileTheKeptServerIsAskedTheSourceRowOpensNothing() {
        var resuming by mutableStateOf(true)
        compose.setTvContent {
            val facts = SourceSheetFacts(SourceConfig.Local, salon, folders = 2, titles = 11, trakt = false)
            SourceSetting(facts, failure = null, resuming) { chosen += it }
        }
        row("Source").requestFocus()
        compose.press(Key.DirectionCenter)
        shows("Connexion…")
        compose.onNodeWithText("Changer de source").assertDoesNotExist()

        resuming = false
        compose.press(Key.DirectionCenter)
        shows("Changer de source")
    }

    @Test
    fun signingOutOfTheServerIsAskedFirst() {
        show(salon)
        compose.onNodeWithText("2 dossiers sur votre NAS").assertExists()
        ok("Se déconnecter de «${NB}NAS du salon$NB»")
        shows(
            "Se déconnecter de «${NB}NAS du salon$NB»${NB}?",
            "Houselights oubliera ce serveur sur cet appareil${NB}; vos dossiers prendront sa place. " +
                "Pour le retrouver, il faudra vous reconnecter.",
        )
        ok("Se déconnecter")
        assertEquals(listOf(SourceChoice.Leave(salon, current = true)), chosen)
    }

    /** Signing out of the only library: the server is forgotten at once, and the library chosen again. */
    @Test
    fun signingOutOfTheOnlyLibrarySaysItIsChosenAgain() {
        show(salon, folders = 0)
        ok("Se déconnecter de «${NB}NAS du salon$NB»")
        shows(
            "Houselights oubliera ce serveur sur cet appareil${NB}; vous choisirez de nouveau votre bibliothèque. " +
                "Pour le retrouver, il faudra vous reconnecter.",
        )
        ok("Se déconnecter")
        assertEquals(listOf(SourceChoice.Leave(salon, current = true)), chosen)
    }

    /** Without a kept server, the sheet is as before: the folders, and a server to sign in to. */
    @Test
    fun withoutAKeptServerAServerIsSignedInTo() {
        show(SourceConfig.Local)
        shows("La bibliothèque de votre serveur")
        compose.onNodeWithText("Un autre serveur Jellyfin").assertDoesNotExist()
        ok("Serveur Jellyfin")
        assertEquals(listOf(SourceChoice.SignIn(another = false)), chosen)
    }

    @Test
    fun theRowsFollowTheSourceAndTheKeptServer() {
        assertEquals(
            listOf(SourceChoice.Folders(true, false), SourceChoice.SignIn(another = false)) to null,
            sourceChoices(SourceConfig.Local, kept = null, trakt = true),
        )
        assertEquals(
            listOf(SourceChoice.Folders(true, false), SourceChoice.Server(salon, false), SourceChoice.SignIn(another = true)) to
                SourceChoice.Leave(salon, current = false),
            sourceChoices(SourceConfig.Local, kept = salon, trakt = true),
        )
        assertEquals(
            listOf(SourceChoice.Folders(false, true), SourceChoice.Server(salon, true), SourceChoice.SignIn(another = true)) to
                SourceChoice.Leave(salon, current = true),
            sourceChoices(salon, kept = null, trakt = true),
        )
    }

    private companion object {
        const val NB = ' '
    }
}
