package app.houselights.update

import android.content.Intent
import android.content.pm.PackageInstaller
import app.houselights.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** When the updater asks GitHub, what it says after an update, and how it follows an install Android holds. */
@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.fixture() = UpdaterFixture(this, folder.root)

    @Test
    fun theDailyCheckWaitsForPlaybackAndAScanToEnd() = runTest {
        val f = fixture()
        f.playing.value = true
        f.start()
        wait(MINUTE)
        assertEquals("Not while the player plays", 0, f.source.lists)
        f.playing.value = false
        f.scanning.value = true
        runCurrent()
        assertEquals("Not while a scan runs", 0, f.source.lists)
        f.scanning.value = false
        runCurrent()
        assertEquals(1, f.source.lists)
    }

    @Test
    fun theFirstCheckWaitsTwentySecondsAfterTheLaunch() = runTest {
        val f = fixture()
        f.start()
        wait(19_000)
        assertEquals(0, f.source.lists)
        wait(1_000)
        assertEquals(1, f.source.lists)
    }

    @Test
    fun itChecksOnceADayAcrossRestarts() = runTest {
        val f = fixture()
        f.start()
        wait(21_000)
        assertEquals(1, f.source.lists)
        wait(23 * HOUR)
        assertEquals(1, f.source.lists)
        f.start()
        wait(21_000)
        assertEquals("A restart asks nothing before the day is out", 1, f.source.lists)
        wait(HOUR)
        assertEquals(2, f.source.lists)
    }

    @Test
    fun aCheckFromTheFutureCountsAsDue() = runTest {
        val f = fixture()
        // The player's clock was set ahead, then back.
        f.store.answered(START + 3 * DAY)
        f.start()
        wait(21_000)
        assertEquals(1, f.source.lists)
    }

    @Test
    fun turnedOffItAsksGithubNothingButOnDemand() = runTest {
        val f = fixture()
        f.settings.value = AppSettings(loaded = true, updateCheck = false)
        f.source.publish("v0.4.1", 1010)
        f.start()
        wait(2 * DAY)
        assertEquals(0, f.source.lists)
        f.updater.checkNow()
        runCurrent()
        assertEquals(1, f.source.lists)
        assertEquals("v0.4.1", f.updater.state.value.offer?.tag)
        assertFalse("Home never opens its sheet with the check off", f.updater.state.value.offeredOnHome)
    }

    @Test
    fun twoAsksInAMinuteReachGithubOnce() = runTest {
        val f = fixture()
        f.settings.value = AppSettings(loaded = true, updateCheck = false)
        f.start()
        f.updater.checkNow()
        runCurrent()
        f.updater.checkNow()
        runCurrent()
        assertEquals(1, f.source.lists)
        wait(61_000)
        f.updater.checkNow()
        runCurrent()
        assertEquals(2, f.source.lists)
    }

    /** Opening the sheet asks GitHub with the check on, nothing offered and an answer an hour old; never otherwise. */
    @Test
    fun openingTheSheetAsksOnlyWhenTheAnswerIsOld() = runTest {
        val f = fixture()
        f.start()
        wait(21_000)
        assertEquals(1, f.source.lists)
        f.updater.opened()
        runCurrent()
        assertEquals("An answer under an hour old stands", 1, f.source.lists)
        f.updater.closed()
        f.source.publish("v0.4.1", 1010)
        wait(HOUR)
        f.updater.opened()
        runCurrent()
        assertEquals(2, f.source.lists)
        assertEquals("v0.4.1", f.updater.state.value.offer?.tag)
        f.updater.closed()
        wait(2 * HOUR)
        f.updater.opened()
        runCurrent()
        assertEquals("A version offered keeps its action live", 2, f.source.lists)
        f.updater.closed()
        val g = UpdaterFixture(this, folder.newFolder())
        g.settings.value = AppSettings(loaded = true, updateCheck = false)
        g.start()
        g.updater.opened()
        runCurrent()
        assertEquals("With the check off, only on demand", 0, g.source.lists)
    }

    @Test
    fun aCheckThatFailedCanBeAskedAgainAtOnce() = runTest {
        val f = fixture()
        f.settings.value = AppSettings(loaded = true, updateCheck = false)
        f.source.failure = IOException("Network is unreachable")
        f.start()
        f.updater.checkNow()
        runCurrent()
        assertEquals(UpdateFailure.OFFLINE, f.updater.state.value.failure)
        f.source.failure = null
        f.updater.retry()
        runCurrent()
        assertEquals(2, f.source.lists)
        assertNull(f.updater.state.value.failure)
    }

    /** A check asked failed, then the daily one finds a version: home offers it as found, nothing failed. */
    @Test
    fun aCheckThatFailedIsBehindTheNextAnswer() = runTest {
        val f = fixture()
        f.source.failure = IOException("Network is unreachable")
        f.start()
        f.updater.checkNow()
        runCurrent()
        assertEquals(UpdateFailure.OFFLINE, f.updater.state.value.failure)
        f.source.failure = null
        f.source.publish("v0.4.1", 1010)
        wait(21_000)
        assertEquals("v0.4.1", f.updater.state.value.offer?.tag)
        assertNull("GitHub answered", f.updater.state.value.failure)
        assertTrue(f.updater.state.value.offeredOnHome)
    }

    /** An update's failure stays while its version is offered, and goes with it once a newer one is found. */
    @Test
    fun anUpdatesFailureGoesWithItsVersion() = runTest {
        val f = fixture()
        f.source.publish("v0.4.1", 1010)
        f.start()
        wait(21_000)
        f.downloads.failure = UpdateFailure.DOWNLOAD_CUT
        f.updater.opened()
        f.updater.update()
        runCurrent()
        f.updater.closed()
        assertEquals(UpdateFailure.DOWNLOAD_CUT, f.updater.state.value.failure)
        wait(DAY)
        assertEquals(2, f.source.lists)
        assertEquals("The same version: « Réessayer » still says it", UpdateFailure.DOWNLOAD_CUT, f.updater.state.value.failure)
        assertFalse("Acted on: home does not offer it", f.updater.state.value.offeredOnHome)
        f.source.publish("v0.4.2", 1020)
        wait(DAY)
        assertEquals("v0.4.2", f.updater.state.value.offer?.tag)
        assertNull(f.updater.state.value.failure)
        assertTrue(f.updater.state.value.offeredOnHome)
    }

    @Test
    fun anAutomaticFailureIsOnlyLogged() = runTest {
        val f = fixture()
        f.source.failure = IOException("Network is unreachable")
        f.start()
        wait(21_000)
        assertEquals(1, f.source.lists)
        assertNull("The daily check never shows a failure", f.updater.state.value.failure)
        assertEquals(UpdateStep.Idle, f.updater.state.value.step)
        wait(HOUR)
        assertEquals("Retried an hour later", 2, f.source.lists)
        wait(HOUR)
        assertEquals(3, f.source.lists)
        wait(21 * HOUR)
        assertEquals("Three times a day at most", 3, f.source.lists)
        wait(HOUR)
        assertEquals("A day after the first", 4, f.source.lists)
    }

    /** GitHub listed its releases, but the newest one's manifest did not come: no check, asked again an hour later. */
    @Test
    fun aManifestThatDoesNotComeIsAskedAgainAnHourLater() = runTest {
        val f = fixture()
        f.source.releases = listOf(release("v0.4.1"))
        f.start()
        wait(21_000)
        assertEquals(1, f.source.lists)
        assertNull("not checked", f.updater.state.value.checkedAt)
        wait(HOUR)
        assertEquals("Retried an hour later", 2, f.source.lists)
        assertEquals("the list kept for GitHub's 304", "\"e1\"", f.source.etags.last())
    }

    @Test
    fun aCheckWaitsWhileGithubAsksToWait() = runTest {
        val f = fixture()
        f.settings.value = AppSettings(loaded = true, updateCheck = false)
        f.source.failure = GithubStatusException(403, START + 20 * MINUTE)
        f.start()
        f.updater.checkNow()
        runCurrent()
        assertEquals(UpdateFailure.BUSY, f.updater.state.value.failure)
        assertEquals(START + 20 * MINUTE, f.updater.state.value.waitUntil)
        f.updater.retry()
        runCurrent()
        assertEquals("Nothing is asked while GitHub asks to wait", 1, f.source.lists)
        f.source.failure = null
        wait(21 * MINUTE)
        f.updater.retry()
        runCurrent()
        assertEquals(2, f.source.lists)
    }

    /** GitHub's wait kept from a clock that ran weeks ahead, set right since: an hour at most, then the checks go on. */
    @Test
    fun aWaitKeptFromAClockAheadHoldsAnHourAtMost() = runTest {
        val f = fixture()
        f.store.waitUntil(START + 30 * DAY)
        f.start()
        assertEquals(START + HOUR, f.updater.state.value.waitUntil)
        wait(HOUR + MINUTE)
        assertEquals(1, f.source.lists)
    }

    @Test
    fun aBetaInstalledIsOfferedBetasUntilChosenOtherwise() = runTest {
        val f = fixture()
        f.app.name = "0.4.0-beta.1"
        f.source.publish("v0.4.0", 1000)
        f.source.publish("v0.4.1-beta.1", 1010)
        f.start()
        wait(21_000)
        assertEquals("v0.4.1-beta.1", f.updater.state.value.offer?.tag)
        f.settings.value = AppSettings(loaded = true, updatePrereleases = false)
        runCurrent()
        assertEquals("Asked again for the stable releases", 2, f.source.lists)
        assertEquals("v0.4.0", f.updater.state.value.offer?.tag)
    }

    /** What was kept is offered in the channel the settings choose once read, never in the one before them. */
    @Test
    fun theOfferKeptWaitsForTheChannelTheSettingsChoose() = runTest {
        suspend fun UpdaterFixture.keptABeta() {
            store.answered(START, listOf(release("v0.4.1-beta.2")), "\"e\"")
            store.manifest("v0.4.1-beta.2", manifestJson("v0.4.1-beta.2", 1008))
            settings.value = AppSettings()
        }
        val f = fixture()
        f.app.name = "0.4.1-beta.1"
        f.app.code = 1005
        f.keptABeta()
        f.start()
        f.settings.value = AppSettings(loaded = true, updateCheck = false, updatePrereleases = false)
        runCurrent()
        assertNull("No beta once the stable releases are chosen", f.updater.state.value.offer)

        val g = UpdaterFixture(this, folder.newFolder())
        g.keptABeta()
        g.start()
        g.settings.value = AppSettings(loaded = true, updateCheck = false, updatePrereleases = true)
        runCurrent()
        assertEquals("The betas chosen, a final installed", "v0.4.1-beta.2", g.updater.state.value.offer?.tag)
    }

    /** A newer release this version cannot read is passed over at the next start too: the version found stays offered. */
    @Test
    fun aRestartOffersWhatWasFoundPastAReleaseItCannotRead() = runTest {
        val f = fixture()
        f.source.publish("v0.4.1", 1010)
        f.source.publish("v0.5.0", 1100)
        f.source.manifests = f.source.manifests + (downloads("v0.5.0") + "update.json" to manifestJson("v0.5.0", 1100, schema = 2))
        f.start()
        wait(21_000)
        assertEquals("v0.4.1", f.updater.state.value.offer?.tag)
        f.start()
        assertEquals("Offered again from what was kept", "v0.4.1", f.updater.state.value.offer?.tag)
        assertEquals(1, f.source.lists)
    }

    /** With the check off, the betas chosen keep the version found offered: nothing asks GitHub until the user does. */
    @Test
    fun anotherChannelWithTheCheckOffKeepsTheVersionFound() = runTest {
        val f = fixture()
        f.settings.value = AppSettings(loaded = true, updateCheck = false)
        f.source.publish("v0.4.1", 1010)
        f.source.publish("v0.5.0-beta.1", 1050)
        f.start()
        f.updater.checkNow()
        runCurrent()
        assertEquals("v0.4.1", f.updater.state.value.offer?.tag)
        f.settings.value = AppSettings(loaded = true, updateCheck = false, updatePrereleases = true)
        runCurrent()
        assertEquals("Nothing asked with the check off", 1, f.source.lists)
        assertEquals("v0.4.1", f.updater.state.value.offer?.tag)
    }

    @Test
    fun theVersionOfferedOpensOnHomeOncePerVersion() = runTest {
        val f = fixture()
        f.source.publish("v0.4.1", 1010)
        f.start()
        wait(21_000)
        assertTrue(f.updater.state.value.offeredOnHome)
        f.updater.shownOnHome(1010)
        assertFalse(f.updater.state.value.offeredOnHome)
        runCurrent()
        f.start()
        runCurrent()
        assertEquals("Offered again from what was kept", "v0.4.1", f.updater.state.value.offer?.tag)
        assertFalse("« Plus tard » holds until a newer version", f.updater.state.value.offeredOnHome)
        f.source.publish("v0.4.2", 1020)
        wait(DAY)
        assertTrue(f.updater.state.value.offeredOnHome)
    }

    @Test
    fun closedBeforeVerifiedItStopsAtReady() = runTest {
        val f = fixture()
        f.offered()
        f.downloads.gate = CompletableDeferred()
        f.updater.update()
        runCurrent()
        assertTrue(f.updater.state.value.step is UpdateStep.Downloading)
        f.updater.closed()
        f.downloads.gate?.complete(Unit)
        runCurrent()
        assertEquals(UpdateStep.Ready, f.updater.state.value.step)
        assertTrue("Android's window never opens by itself", f.installer.installed.isEmpty())
        f.updater.opened()
        f.updater.update()
        runCurrent()
        assertEquals(1, f.installer.installed.size)
        assertEquals("« Installer » hands over the file verified", 1, f.downloads.fetched)
    }

    @Test
    fun leavingTheScreenCancelsTheDownloadNotTheInstall() = runTest {
        val f = fixture()
        f.offered()
        f.downloads.gate = CompletableDeferred()
        f.updater.update()
        runCurrent()
        f.onScreen.value = false
        runCurrent()
        assertEquals(1, f.downloads.cancelled)
        assertEquals(UpdateStep.Idle, f.updater.state.value.step)
        f.onScreen.value = true
        f.downloads.gate = null
        f.updater.update()
        runCurrent()
        assertEquals(UpdateStep.Installing(), f.updater.state.value.step)
        f.onScreen.value = false
        wait(HOUR)
        assertEquals(UpdateStep.Installing(), f.updater.state.value.step)
        assertEquals("Only the start's", 1, f.installer.abandons)
    }

    @Test
    fun aSecondUpdateWhileOneRunsDoesNothing() = runTest {
        val f = fixture()
        f.offered()
        f.downloads.gate = CompletableDeferred()
        f.updater.update()
        f.updater.update()
        runCurrent()
        assertEquals(1, f.downloads.fetched)
    }

    @Test
    fun cancellingTheDownloadComesBackToTheOffer() = runTest {
        val f = fixture()
        f.offered()
        f.downloads.gate = CompletableDeferred()
        f.updater.update()
        runCurrent()
        f.updater.cancel()
        runCurrent()
        assertEquals(1, f.downloads.cancelled)
        assertEquals(UpdateStep.Idle, f.updater.state.value.step)
        assertEquals("v0.4.1", f.updater.state.value.offer?.tag)
        assertNull(f.updater.state.value.failure)
    }

    /** A file not the project's is deleted, never handed to Android nor kept: the next try downloads the release again. */
    @Test
    fun aFileNotTheProjectsIsNeverHandedToAndroid() = runTest {
        val f = fixture()
        val offer = f.offered()
        f.archive.facts = ArchiveFacts(f.app.packageName, offer.versionCode, offer.versionName, listOf(OTHER_KEY))
        f.updater.update()
        runCurrent()
        assertEquals(UpdateFailure.NOT_GENUINE, f.updater.state.value.failure)
        assertTrue(f.installer.installed.isEmpty())
        assertEquals("the file deleted", emptyList<String>(), folder.root.resolve("updates").list().orEmpty().toList())

        f.genuine(offer)
        f.updater.update()
        runCurrent()
        assertEquals("downloaded again", 2, f.downloads.fetched)
        assertEquals(1, f.installer.installed.size)
    }

    /**
     * « Installer » again while Android still holds the first session abandons it for a new one; Android's answer for the
     * one abandoned, which may come after, changes nothing: the new session's install stands.
     */
    @Test
    fun aSessionAbandonedForANewOneIsNoLongerFollowed() = runTest {
        val f = fixture()
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.onSessionStatus(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        f.updater.update()
        runCurrent()
        assertEquals("the first session abandoned, after the start's", 2, f.installer.abandons)
        assertEquals(setOf(2), f.installer.held)

        f.updater.onSessionStatus(1, PackageInstaller.STATUS_FAILURE_ABORTED, null, null)
        f.updater.onSessionStatus(1, PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_ABORTED", null)
        runCurrent()
        assertEquals(UpdateStep.Installing(), f.updater.state.value.step)
        assertNull(f.updater.state.value.failure)
        assertEquals(Pending(1010, "0.4.1", START), f.store.read().pending)
        f.updater.onSessionStatus(2, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        assertEquals("the new session's confirmation", 2, f.installer.confirmed.size)
    }

    /** Android refusing the session it was handed says why, the device full or not, and leaves nothing pending. */
    @Test
    fun aSessionAndroidRefusesSaysWhyAndLeavesNothingPending() = runTest {
        val refusals = mapOf(
            IOException("write failed: ENOSPC (No space left on device)") to UpdateFailure.STORAGE,
            SecurityException("Session 1 is not owned by the app") to UpdateFailure.INSTALL_FAILED,
        )
        refusals.forEach { (thrown, failure) ->
            val f = UpdaterFixture(this, folder.newFolder())
            f.installer.failure = thrown
            f.offered()
            f.updater.update()
            runCurrent()
            assertEquals("$thrown", failure, f.updater.state.value.failure)
            assertEquals("$thrown", UpdateStep.Idle, f.updater.state.value.step)
            assertNull("Not said as « not installed » at the next start", f.store.read().pending)
        }
    }

    /** Android finding the file invalid drops it, so « Réessayer » downloads it again; after another failure it is reused. */
    @Test
    fun onlyAnInvalidFileIsDownloadedAgain() = runTest {
        val downloads = mapOf(PackageInstaller.STATUS_FAILURE_INVALID to 2, PackageInstaller.STATUS_FAILURE to 1)
        downloads.forEach { (status, fetched) ->
            val f = UpdaterFixture(this, folder.newFolder())
            f.offered()
            f.updater.update()
            runCurrent()
            f.updater.onSessionStatus(1, status, "INSTALL_FAILED_x", null)
            runCurrent()
            f.updater.retry()
            runCurrent()
            assertEquals("Status $status", fetched, f.downloads.fetched)
            assertEquals("Status $status: handed to Android again", 2, f.installer.installed.size)
        }
    }

    @Test
    fun thePendingInstallIsSavedBeforeTheCommit() = runTest {
        val f = fixture()
        f.offered()
        f.updater.update()
        runCurrent()
        assertEquals(Pending(1010, "0.4.1", START), f.installer.pendingAtCommit)
    }

    @Test
    fun pendingUserActionOpensAndroidsConfirmationOnlyOnScreen() = runTest {
        val f = fixture()
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.onSessionStatus(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        assertEquals(1, f.installer.confirmed.size)
        assertEquals(UpdateStep.Installing(), f.updater.state.value.step)

        val g = UpdaterFixture(this, folder.newFolder())
        g.offered()
        g.updater.update()
        runCurrent()
        g.onScreen.value = false
        runCurrent()
        g.updater.onSessionStatus(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        assertTrue("Never over another app", g.installer.confirmed.isEmpty())
        assertEquals(UpdateStep.Installing(reopen = true), g.updater.state.value.step)
        g.onScreen.value = true
        g.updater.reopen()
        assertEquals(1, g.installer.confirmed.size)
    }

    @Test
    fun confirmedThenSlowInstallStaysInstalling() = runTest {
        val f = fixture()
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.onSessionStatus(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        // Android's window, over the app, is confirmed: the app resumes while Android installs.
        f.updater.returned()
        wait(6 * HOUR)
        assertEquals(UpdateStep.Installing(), f.updater.state.value.step)
        assertEquals("No timer abandons the session", 1, f.installer.abandons)
        assertTrue(f.installer.holds(1))
    }

    @Test
    fun androidsWindowLeftWithHomeOpensAgain() = runTest {
        val f = fixture()
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.onSessionStatus(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        f.onScreen.value = false
        runCurrent()
        f.onScreen.value = true
        f.updater.returned()
        assertEquals(UpdateStep.Installing(reopen = true), f.updater.state.value.step)
        f.updater.reopen()
        assertEquals(2, f.installer.confirmed.size)
        assertEquals(UpdateStep.Installing(), f.updater.state.value.step)
    }

    @Test
    fun eachInstallStatusSaysWhatToDo() = runTest {
        val expected = mapOf(
            PackageInstaller.STATUS_FAILURE_BLOCKED to UpdateFailure.BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT to UpdateFailure.CONFLICT,
            PackageInstaller.STATUS_FAILURE_STORAGE to UpdateFailure.STORAGE,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to UpdateFailure.INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID to UpdateFailure.INSTALL_FAILED,
            PackageInstaller.STATUS_FAILURE to UpdateFailure.INSTALL_FAILED,
        )
        expected.forEach { (status, failure) ->
            val f = UpdaterFixture(this, folder.newFolder())
            f.offered()
            f.updater.update()
            runCurrent()
            f.updater.onSessionStatus(1, status, "INSTALL_FAILED_x", null)
            runCurrent()
            assertEquals("Status $status", failure, f.updater.state.value.failure)
            assertNull("Nothing pending after status $status", f.store.read().pending)
        }
    }

    @Test
    fun aCancelledInstallComesBackToInstallerWithNothingPending() = runTest {
        val f = fixture()
        f.offered()
        f.updater.update()
        runCurrent()
        f.updater.onSessionStatus(1, PackageInstaller.STATUS_FAILURE_ABORTED, null, null)
        runCurrent()
        assertEquals(UpdateStep.Ready, f.updater.state.value.step)
        assertNull(f.updater.state.value.failure)
        assertNull("Not said as « not installed » at the next start", f.store.read().pending)
    }

    @Test
    fun leftoverSessionsAndDownloadsGoAtStart() = runTest {
        val f = fixture()
        val left = folder.root.resolve("updates").apply { mkdirs() }.resolve("Houselights-0.4.1.apk.part").apply { writeText("x") }
        f.start()
        assertEquals(1, f.installer.abandons)
        assertFalse(left.exists())
    }

    @Test
    fun afterTheUpdateTheNewVersionSaysSoOnce() = runTest {
        val f = fixture()
        f.store.pending(Pending(1010, "0.4.1", START))
        f.app.code = 1010
        f.app.name = "0.4.1"
        f.start()
        assertEquals(Updated("0.4.1", installed = true, at = START), f.updater.state.value.updated)
        f.updater.updatedSaid()
        runCurrent()
        f.start()
        assertEquals(true, f.updater.state.value.updated?.said)
        assertNull(f.store.read().pending)
        wait(DAY)
        f.start()
        assertNull("Said in About for a day", f.updater.state.value.updated)
    }

    @Test
    fun anUpdateThatDidNotHappenIsSaid() = runTest {
        val f = fixture()
        f.store.pending(Pending(1010, "0.4.1", START))
        f.start()
        assertEquals(Updated("0.4.1", installed = false, at = START), f.updater.state.value.updated)
    }

    /** As Android starts the app once replaced: the start settles what was pending while the receiver asks. */
    @Test
    fun theUpdatedAppReopensOnlyAfterItsOwnRecentUpdate() = runTest {
        val f = fixture()
        f.start()
        assertFalse("Not replaced by its own update", f.updater.replaced())
        f.store.pending(Pending(1010, "0.4.1", START))
        f.app.code = 1010
        f.start()
        assertNull("Settled by the start", f.store.read().pending)
        assertTrue(f.updater.replaced())

        val g = UpdaterFixture(this, folder.newFolder())
        g.store.pending(Pending(1010, "0.4.1", START - 16 * MINUTE))
        g.app.code = 1010
        g.start()
        assertFalse("Not after a quarter of an hour", g.updater.replaced())
    }

    @Test
    fun aBuildThatDoesNotUpdateItselfStaysInert() = runTest {
        val f = UpdaterFixture(this, folder.root)
        f.source.publish("v0.4.1", 1010)
        val updater = Updater(
            backgroundScope,
            f.settings,
            f.store,
            FakeApp(available = false),
            UpdateParts(f.source, ReleaseChoice(::downloads, PINNED, 28), f.downloads, UpdateVerifier(f.archive, PINNED), f.installer),
            UpdateMoments(f.onScreen, f.playing, f.scanning, launched = {}),
            clock = f.clock,
        )
        updater.run()
        updater.checkNow()
        wait(2 * DAY)
        assertFalse(updater.state.value.on)
        assertEquals(0, f.source.lists)
        assertEquals(0, f.installer.abandons)
    }
}
