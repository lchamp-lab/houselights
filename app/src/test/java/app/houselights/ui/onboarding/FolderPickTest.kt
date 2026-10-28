package app.houselights.ui.onboarding

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.Text
import app.houselights.PICKED_SHARE
import app.houselights.R
import app.houselights.Route
import app.houselights.leaveSharePick
import app.houselights.openSharePick
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.sharePicked
import app.houselights.testing.folderNames
import app.houselights.testing.mountFailure
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.DS920
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PLAYER
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.setTvContent
import com.emc.ecs.nfsclient.mount.MountStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Choisir un dossier partagé » (#59): « Modifier le dossier »'s form opens the guided flow's step 4 over itself, on the
 * folders its test just read from the NAS (FakeFlowNetwork's DS920), the shared folders alone, titled as the form is, its
 * panel « En ligne »; OK on one hands it back to the form, which tests it, the subfolder kept; Back returns to the form
 * as it was, on its line. The route there and back is the app's ([openSharePick], [sharePicked], [leaveSharePick]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FolderPickTest : FlowHarness() {
    /** « Films », once on the old QNAP at DS920's address, under /share/Multimedia: the form's folder. */
    private val qnap = films.copy(id = FORM_FOLDER, share = "share/Multimedia", uid = null, gid = null)

    /** What the form's test read from DS920. */
    private val listed get() = network.exports.orEmpty()

    private fun pick(uid: String = "", gid: String = "") = SharePick(FORM_FOLDER, DS920, listed, uid, gid)

    @Test
    fun theStepShowsTheSharedFoldersAloneAsTheFormIsTitled() {
        flow(qnap, pick = pick("1026", "100"))

        compose.onNodeWithText(text(R.string.edit_folder)).assertExists()
        compose.onNodeWithText(text(R.string.flow_share_desc)).assertExists()
        listed.forEach { compose.onNodeWithText(it.path).assertExists() }
        compose.onNode(hasText("photo") and hasText(text(R.string.not_allowed))).assertExists()
        compose.onNode(hasText("video") and hasText(text(R.string.not_allowed))).assertDoesNotExist()
        // The form behind has the path and the NFS account already.
        compose.onNodeWithText(text(R.string.type_path)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.nfs_identity_title)).assertDoesNotExist()
        // The NAS answered with its list as the mount failed: online, not reached.
        compose.onNodeWithText(text(R.string.online)).assertExists()
        compose.onNodeWithText(text(R.string.connection_ok)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_movies), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.nfs_identity, "1026", "100"), substring = true).assertExists()
        focusIsOn("video")

        // Named as it announces itself.
        network.announce(ds920)
        until { compose.onAllNodesWithText("DS920").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Focus on the shared folder the folder had when last saved, when the NAS lists it. */
    @Test
    fun focusGoesToTheSharedFolderTheFolderWasSavedIn() {
        flow(qnap.copy(share = "volume1/musique"), pick = pick())
        focusIsOn("musique")
    }

    /** The library's other folders on the NAS say how each shared folder stands to them; the folder edited never does. */
    @Test
    fun theLibrarysOtherFoldersSayWhereTheyAreTheFolderEditedApart() {
        val series = films.copy(id = FORM_FOLDER + 1, name = "Séries", kind = SourceKind.SHOWS, path = "Séries")
        flow(qnap.copy(share = "volume1/musique"), series, pick = pick())

        compose.onNode(hasText("video") and hasText(text(R.string.share_holds_one, "Séries"), substring = true)).assertExists()
        compose.onNode(hasText("musique") and hasText(text(R.string.share_holds_one, "Films"), substring = true)).assertDoesNotExist()
    }

    /** OK on a shared folder, « Non autorisé » or not, chooses it for the form, opening nothing; once. */
    @Test
    fun okOnASharedFolderChoosesItOnce() {
        val flow = flow(qnap, pick = pick())
        val asked = network.asked.size
        down()
        focusIsOn("photo")

        ok()
        assertEquals("/volume1/photo", picked)
        assertEquals("nothing asked of the NAS", asked, network.asked.size)
        assertEquals(FlowStep.SHARES, flow.step)
        down()
        ok()
        assertEquals("a second OK as the step goes chooses nothing more", "/volume1/photo", flow.picked)
    }

    @Test
    fun backReturnsToTheFormWithNothingChosen() {
        flow(qnap, pick = pick())
        back()
        assertTrue(left)
        assertNull(picked)
    }

    /** A process stopped on the step finds it again, its shared folders and their marks. */
    @Test
    fun theStepOutlivesAStoppedProcess() {
        val saved = SavedStateHandle()
        val before = flow(qnap, saved = saved, pick = pick())
        val after = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath(), pick = pick())
        until { after.ready }
        assertEquals(FlowStep.SHARES, after.step)
        assertEquals(before.shares, after.shares)
        assertTrue(after.picking)
    }

    /**
     * The route: Tester on the form, the NAS lacking /share/Multimedia; « Choisir un dossier partagé », focused, opens
     * the step over the form; OK on « video » returns to the form, « /volume1/video » in « Chemin de montage », « Films »
     * kept, tested at once from Tester, which has focus.
     */
    @Test
    fun theFormsLineOpensTheStepAndTheFolderChosenIsTested() {
        val form = app()
        failedOnTheForm(form)

        ok()
        untilFocusOn("video")
        assertTrue(onTheStep())
        compose.onNodeWithText(text(R.string.edit_folder)).assertExists()
        ok()
        until { form.check is FolderCheck.Reachable }
        assertFalse(onTheStep())
        assertEquals("/volume1/video" to "Films", form.form.share to form.form.path)
        assertFalse(form.away)
        focusIsOn(text(R.string.test_connection))
        assertEquals("nothing saved", "share/Multimedia", runBlocking { db.dao().source(FORM_FOLDER) }?.share)
    }

    /** Back from the step: the form as it was, its message and its line, focus on the line. */
    @Test
    fun backFromTheStepReturnsToTheFormOnItsLine() {
        val form = app()
        failedOnTheForm(form)
        ok()
        untilFocusOn("video")

        back()
        untilFocusOn(text(R.string.pick_share))
        assertFalse(onTheStep())
        assertFalse(form.away)
        assertEquals(FolderTrouble.ExportMissing("/share/Multimedia"), (form.check as FolderCheck.Failed).trouble)
    }

    private lateinit var nav: NavHostController

    private fun onTheStep(): Boolean =
        nav.currentBackStackEntry?.takeIf { it.destination.hasRoute<Route.AddFolder>() }?.toRoute<Route.AddFolder>()?.pick == true

    /** Tester on the form, the NAS lacking its path: the failure said, focus on « Choisir un dossier partagé ». */
    private fun failedOnTheForm(form: AddFolderViewModel) {
        until { form.ready }
        compose.onNode(hasText(text(R.string.test_connection)) and hasClickAction()).requestFocus()
        ok()
        until { form.shared != null }
        untilFocusOn(text(R.string.pick_share))
    }

    /** Waits until focus is on what says [words]. */
    private fun untilFocusOn(words: String) = until {
        compose.onAllNodes(isFocused() and hasText(words, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** The probe of DS920 over NFS: what it shares opens, anything else refused, as Linux's mountd refuses a path not exported. */
    private val synology: suspend (SourceEntity) -> FolderLevel = { folder ->
        if (listed.none { it.path.trim('/') == folder.share }) throw mountFailure(MountStatus.MNT3ERR_ACCES)
        FolderLevel(folderNames(412), videos = 3)
    }

    /** « Modifier le dossier » on [qnap] in Réglages, as the app routes it; its form's model. */
    private fun app(): AddFolderViewModel {
        stock(qnap)
        var form: AddFolderViewModel? = null
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(
                nav,
                startDestination = Route.Settings,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                composable<Route.Settings> { Text("Settings") }
                composable<Route.AddFolder> { entry ->
                    val route = entry.toRoute<Route.AddFolder>()
                    val pick = route.sharePick
                    if (pick != null) {
                        val flow: FolderFlow = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    FolderFlow(network, library, scope, useFolders = { false }, createSavedStateHandle(), pick = pick)
                                }
                            },
                        )
                        AddFolderFlow(flow, onLeave = { nav.leaveSharePick() }, onPick = nav::sharePicked)
                    } else {
                        val vm: AddFolderViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    AddFolderViewModel(
                                        library,
                                        FORM_FOLDER,
                                        scope,
                                        synology,
                                        createSavedStateHandle(),
                                        playerAddress = { PLAYER },
                                        exports = { network.exports },
                                    )
                                }
                            },
                        )
                        form = vm
                        val picked by entry.savedStateHandle.getStateFlow<String?>(PICKED_SHARE, null).collectAsStateWithLifecycle()
                        val picking = SharePicking(picked, nav::openSharePick) { entry.savedStateHandle[PICKED_SHARE] = null }
                        AddFolderForm(vm, route.focus, picking = picking) {}
                    }
                }
            }
        }
        compose.runOnIdle { nav.navigate(Route.AddFolder(FORM_FOLDER, FolderField.PATH)) }
        until { form != null }
        return checkNotNull(form)
    }

    private companion object {
        const val FORM_FOLDER = 7L
    }
}
