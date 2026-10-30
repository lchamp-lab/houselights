package app.houselights.providers.local

import app.houselights.testing.mountFailure
import app.houselights.testing.smbStatus
import app.houselights.ui.settings.heldByFolder
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.net.ConnectException

/**
 * Rows of files a pass did not list: kept whole for a NAS off, an empty listing or a folder out of sight; removed on
 * the pass that misses them, what the user did kept by title; a sudden drop, as when a disk of the NAS's pool is
 * offline, held whatever the time until the files are back or the user answers, keeping them or removing them; so too
 * a folder gone from a NAS that answers, whole.
 */
@RunWith(RobolectricTestRunner::class)
class ScanMissingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val log = StatementLog()
    private lateinit var artwork: File
    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        artwork = temp.newFolder("artwork")
        bench = ScanBench(artwork, tmdbKey = false, onQuery = log)
        films = bench.folder("Films")
    }

    @After
    fun close() = bench.close()

    private suspend fun filePaths() = bench.paths(films)

    private fun film(n: Int) = "Film $n (2020).mkv"

    private suspend fun filmFolder(count: Int) {
        repeat(count) { nas.put(film(it)) }
        bench.scan()
    }

    /** The rows held in [source], as its sheet lists them. */
    private suspend fun heldIds(source: SourceEntity = films) = dao.heldIn(source.id)

    private suspend fun folder() = checkNotNull(dao.source(films.id))

    private suspend fun missingSince(paths: List<String>) =
        dao.filesOf(films.id).filter { it.filePath in paths }.map { it.missingSince }.toSet()

    private suspend fun idOf(path: String) = dao.filesOf(films.id).single { it.filePath == path }.id

    /**
     * A pass that only adds a film leaves the cleanup, which reads every reference and every image cached, to the next one
     * that removes a title, or to the next day: an image no title holds stays until then. The first pass since the app
     * started that changes the library cleans up.
     */
    @Test
    fun aPassCleansUpOnceItRemovesATitleOrADayLater() = runTest {
        val unused = File(artwork, "${films.id}/unused.jpg")
        fun leaveAnImage() = unused.apply { checkNotNull(parentFile).mkdirs() }.writeBytes(ByteArray(8))
        leaveAnImage()
        filmFolder(2)
        assertFalse("the first pass cleans up", unused.exists())

        leaveAnImage()
        nas.put(film(2))
        bench.scan()
        assertEquals(3, filePaths().size)
        assertTrue("a pass that only adds a film leaves it", unused.exists())
        nas.remove(film(0))
        bench.scan()
        assertEquals(2, filePaths().size)
        assertFalse("a pass that removes one cleans up", unused.exists())

        leaveAnImage()
        nas.put(film(3))
        bench.scan()
        assertTrue(unused.exists())
        bench.later(LibraryScanner.CLEANUP_EVERY_MS)
        nas.put(film(4))
        bench.scan()
        assertFalse("a pass a day later cleans up", unused.exists())
    }

    /**
     * The first pass since the app started, the pass of a cold start, cleans up only once it changes the library: one
     * that finds the library as it was leaves « Identifier », « Garder » or « Retirer » no cleanup to wait for.
     */
    @Test
    fun theFirstPassCleansUpOnlyOnceItChangesTheLibrary() = runTest {
        val unused = File(artwork, "${films.id}/unused.jpg")
        unused.apply { checkNotNull(parentFile).mkdirs() }.writeBytes(ByteArray(8))
        // A folder that lists nothing and held nothing: the pass finds no file, and changes nothing.
        bench.scan()
        assertNull(bench.error(films))
        assertTrue(filePaths().isEmpty())
        assertTrue("a first pass that changes nothing leaves it", unused.exists())

        nas.put(film(0))
        bench.scan()
        assertEquals(1, filePaths().size)
        assertFalse("the first pass that adds a film cleans up", unused.exists())
    }

    @Test
    fun anUnreachableNasRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        dao.setFavorite(dao.filesOf(films.id).single().id, true)

        nas.listFailure = ConnectException("Connection refused")
        bench.scan()

        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(films))
        assertTrue(dao.filesOf(films.id).single().favorite)
    }

    /** What home's note and the folder's sheet say of [films]: its titles held, and whether the user kept them. */
    private suspend fun homeNote() = heldByFolder(dao.observeHeld().first(), listOf(folder().toFolder())).singleOrNull()

    /** The folder deleted or moved on the NAS, which answers: NFS3ERR_NOENT in the export, as nfs-client throws it. */
    private fun folderGone() {
        nas.listFailure = FileNotFoundException("lookup /volume1/media/houselights-test/films")
    }

    /**
     * The folder gone from a NAS that answers: the first pass asks nothing; the second holds its titles as a sudden drop
     * holds them, however few, none removed, asked about once; kept, nothing asks again pass after pass; the folder back
     * where it was, they are as they were. The other folder is left as it is.
     */
    @Test
    fun aFolderGoneFromANasThatAnswersHoldsItsTitles() = runTest {
        val series = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        filmFolder(3)
        val gone = (0 until 3).map(::film)
        val favourite = idOf(film(0))
        dao.setFavorite(favourite, true)

        folderGone()
        bench.scan()
        assertEquals(ScanErrorKind.NOT_FOUND, bench.error(films))
        assertNull("asked nothing yet", homeNote())
        bench.later(PASS)
        val told = bench.changesDuring { bench.scan() }
        assertEquals("nothing removed", gone.sorted(), filePaths())
        assertEquals(setOf(START + PASS), missingSince(gone))
        assertEquals(3, homeNote()?.rows?.size)
        assertFalse("asked about", checkNotNull(homeNote()).kept)
        assertTrue("screens mark them", told > 0)
        assertEquals("the other folder as it was", emptyList<Long>(), heldIds(series))

        bench.scanner.keepHeld(films.id, heldIds())
        bench.later(DAY)
        bench.scan()
        bench.later(30 * DAY)
        bench.scan()
        assertTrue("not asked again", checkNotNull(homeNote()).kept)
        assertEquals("no clock decides", gone.sorted(), filePaths())
        assertEquals("held since it went", setOf(START + PASS), missingSince(gone))

        nas.listFailure = null
        bench.scan()
        assertNull(bench.error(films))
        assertNull("nothing held", homeNote())
        assertEquals(setOf(null), missingSince(gone))
        assertTrue(checkNotNull(dao.item(favourite)).favorite)
    }

    /**
     * Gone at one pass only, as a folder renamed and named back, or between two passes that list it or find the NAS
     * out of reach: nothing is held, nothing asked.
     */
    @Test
    fun aFolderGoneAtOnePassOnlyHoldsNothing() = runTest {
        filmFolder(3)
        listOf<Throwable?>(null, ConnectException("Connection refused")).forEach { between ->
            folderGone()
            bench.scan()
            nas.listFailure = between
            bench.scan()
            folderGone()
            bench.scan()
            assertNull("$between", homeNote())
            nas.listFailure = null
            bench.scan()
        }
        assertEquals((0 until 3).map(::film).sorted(), filePaths())
    }

    /** Over SMB, a folder gone from a share that answers (OBJECT_PATH_NOT_FOUND) is held as over NFS. */
    @Test
    fun aFolderGoneFromAnSmbShareHoldsItsTitles() = runTest {
        val smb = bench.folder("Films 4K", on = nas, stored = nfsFolder("Films 4K").copy(protocol = Protocol.SMB))
        nas.put("Dune (2021).mkv")
        bench.scan()

        nas.listFailure = smbStatus(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)
        bench.scan()
        bench.scan()
        assertEquals(ScanErrorKind.NOT_FOUND, bench.error(smb))
        assertEquals(1, heldIds(smb).size)
        assertEquals(listOf("Dune (2021).mkv"), bench.paths(smb))
    }

    /**
     * Kept, the folder back with a file fewer: the others are back, the one missing goes as on any pass, never waiting
     * for a file that left with the folder's move; back with many fewer, they are a sudden drop, asked about anew, still
     * held since the folder went.
     */
    @Test
    fun aFolderBackWithFewerFilesSettlesThemAsAnyPass() = runTest {
        filmFolder(22)
        folderGone()
        bench.scan()
        bench.scan()
        bench.scanner.keepHeld(films.id, heldIds())
        bench.later(DAY)

        nas.listFailure = null
        nas.remove(film(0))
        bench.scan()
        assertEquals((1 until 22).map(::film).sorted(), filePaths())
        assertNull("nothing held", homeNote())

        folderGone()
        bench.scan()
        bench.scan()
        bench.scanner.keepHeld(films.id, heldIds())
        bench.later(DAY)
        val offline = (1 until 13).map(::film)
        nas.listFailure = null
        offline.forEach(nas::remove)
        bench.scan()
        assertEquals(21, filePaths().size)
        assertEquals(offline.sorted(), dao.observeHeld().first().mapNotNull { it.filePath }.sorted())
        assertFalse("asked about anew", checkNotNull(homeNote()).kept)
        assertEquals("held since the folder went", setOf(START + DAY), missingSince(offline))
    }

    /** Back with a subfolder it cannot read: what lies under it is out of sight, shown unmarked as before, not held. */
    @Test
    fun aFolderBackWithASubfolderItCannotReadHoldsNothingUnderIt() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Private/Arrival (2016)/Arrival (2016).mkv")
        bench.scan()
        folderGone()
        bench.scan()
        bench.scan()
        assertEquals(2, heldIds().size)

        nas.listFailure = null
        nas.unreadable = listOf("Private")
        bench.scan()
        assertEquals(2, filePaths().size)
        assertEquals(emptyList<Long>(), heldIds())
        assertNull(homeNote())
    }

    /**
     * What does not say the folder left holds nothing and asks nothing, pass after pass: a NAS out of reach or spinning
     * its disks up, a share or an export missing or refused (a NAS waking up may answer so), an account or a guest
     * refused, a folder refused.
     */
    @Test
    fun aNasThatDoesNotSayTheFolderLeftHoldsNothing() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        listOf(
            ConnectException("Connection refused"),
            NfsException(NfsStatus.NFS3ERR_JUKEBOX, "lookup error code: NfsStatus:10008"),
            mountFailure(MountStatus.MNT3ERR_NOENT),
            mountFailure(MountStatus.MNT3ERR_ACCES),
            smbStatus(NtStatus.STATUS_BAD_NETWORK_NAME),
            smbStatus(NtStatus.STATUS_LOGON_FAILURE),
            SMB2GuestSigningRequiredException(),
            NfsException(NfsStatus.NFS3ERR_ACCES, "lookup error code: NfsStatus:13"),
        ).forEach { failure ->
            nas.listFailure = failure
            bench.scan()
            bench.scan()
            assertEquals("$failure", emptyList<Long>(), heldIds())
            assertEquals("$failure", listOf("Dune (2021).mkv"), filePaths())
        }
        assertNull(homeNote())
    }

    @Test
    fun anEmptyListingRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).first { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = bench.now)
        dao.setFavorite(dune.id, true)

        // The share answers, but its disk is not mounted.
        nas.clear()
        bench.scan()

        assertEquals(ScanErrorKind.EMPTY, bench.error(films))
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
        val kept = checkNotNull(dao.item(dune.id))
        assertEquals(600_000L, kept.positionMs)
        assertTrue(kept.favorite)
        assertEquals(null, kept.missingSince)
    }

    /** lost+found, another user's folder: what the library holds under it is out of sight, not gone. */
    @Test
    fun aFolderThatCannotBeReadRemovesNothingUnderIt() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Private/Arrival (2016)/Arrival (2016).mkv")
        bench.scan()

        nas.unreadable = listOf("Private")
        nas.put("Blade Runner (1982)/Blade Runner (1982).mkv")
        bench.scan()
        bench.later(DAY)
        bench.scan()

        assertEquals(null, bench.error(films))
        assertEquals(
            listOf(
                "Blade Runner (1982)/Blade Runner (1982).mkv",
                "Dune (2021)/Dune (2021).mkv",
                "Private/Arrival (2016)/Arrival (2016).mkv",
            ),
            filePaths(),
        )
        assertEquals("not even held", null, dao.filesOf(films.id).single { it.title == "Arrival" }.missingSince)
    }

    @Test
    fun aFolderNeverFilledMayListEmpty() = runTest {
        bench.scan()
        assertEquals(null, bench.error(films))
    }

    /** Twelve of twenty-two at once: nothing leaves, then or a month later; they are held from that pass, and asked about. */
    @Test
    fun aSuddenDropRemovesNothingWhateverTheTime() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        offline.forEach(nas::remove)
        bench.scan()
        assertEquals(22, filePaths().size)
        assertEquals(setOf(START), missingSince(offline))
        assertEquals(null, bench.error(films))
        assertFalse("asked about", folder().heldKept)

        bench.later(30 * DAY)
        bench.scan()
        assertEquals("no clock decides", 22, filePaths().size)
        assertEquals("held since they went", setOf(START), missingSince(offline))
        assertEquals(offline.sorted(), dao.observeHeld().first().mapNotNull { it.filePath }.sorted())
    }

    /** Ten of forty, a quarter: no drop, they go on the pass that misses them. */
    @Test
    fun fewFilesGoneGoOnTheNextPass() = runTest {
        filmFolder(40)
        (0 until 10).map(::film).forEach(nas::remove)
        bench.scan()
        assertEquals((10 until 40).map(::film).sorted(), filePaths())
    }

    /** A film deleted goes on the pass that misses it, the app's own after a restart included; what the user did is kept. */
    @Test
    fun aFileDeletedIsRemovedOnTheNextPass() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        dao.setFavorite(dao.filesOf(films.id).single { it.title == "Dune" }.id, true)

        nas.remove("Dune (2021).mkv")
        bench.autoScan()
        assertEquals(listOf("Heat (1995).mkv"), filePaths())
        assertEquals(listOf("film:name:${films.id}::dune|2021"), bench.keptTitles())
    }

    /** The files back end the hold without a word: every row as it was, nothing held, nothing to answer. */
    @Test
    fun filesBackEndTheHold() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        val favourite = idOf(film(0))
        dao.setFavorite(favourite, true)
        offline.forEach(nas::remove)
        bench.scan()

        offline.forEach { nas.put(it) }
        val told = bench.changesDuring { bench.scan() }
        assertEquals(setOf(null), missingSince(offline))
        assertEquals(22, filePaths().size)
        assertTrue(checkNotNull(dao.item(favourite)).favorite)
        assertEquals(emptyList<HeldRow>(), dao.observeHeld().first())
        assertTrue("screens no longer mark them", told > 0)
    }

    /** Back but one, deleted meanwhile: that one stays held, its answer awaited, however long. */
    @Test
    fun aTitleStillMissingOnceTheOthersAreBackWaitsForTheAnswer() = runTest {
        filmFolder(40)
        val offline = (0 until 21).map(::film)
        offline.forEach(nas::remove)
        bench.scan()

        offline.drop(1).forEach { nas.put(it) }
        bench.later(30 * DAY)
        bench.scan()
        assertEquals(40, filePaths().size)
        assertEquals(listOf(film(0)), dao.observeHeld().first().map { it.filePath })
        assertFalse(folder().heldKept)
    }

    /** « Garder »: they stay held and nothing asks again, until another drop, which asks about them all. */
    @Test
    fun keptTheyWaitForTheirFilesUntilAnotherDrop() = runTest {
        filmFolder(40)
        (0 until 12).map(::film).forEach(nas::remove)
        bench.scan()
        bench.scanner.keepHeld(films.id, heldIds())
        assertTrue(folder().heldKept)

        bench.later(DAY)
        bench.scan()
        assertTrue("still kept", folder().heldKept)
        assertEquals(40, filePaths().size)

        (12 until 24).map(::film).forEach(nas::remove)
        bench.scan()
        assertFalse("asked again", folder().heldKept)
        assertEquals(24, heldIds().size)
    }

    /** Kept as shown: a drop held after the sheet was read is still asked about. */
    @Test
    fun keepingKeepsWhatWasShownOnly() = runTest {
        filmFolder(40)
        (0 until 12).map(::film).forEach(nas::remove)
        bench.scan()
        val shown = heldIds()
        (12 until 24).map(::film).forEach(nas::remove)
        bench.scan()

        bench.scanner.keepHeld(films.id, shown)
        assertFalse(folder().heldKept)
        bench.scanner.keepHeld(films.id, heldIds())
        assertTrue(folder().heldKept)
    }

    /** « Retirer »: they leave at once, nothing on the NAS touched, what the user did kept by title for when they return. */
    @Test
    fun removedTheyLeaveKeepingWhatTheUserDid() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        dao.setFavorite(idOf(film(0)), true)
        offline.forEach(nas::remove)
        bench.scan()

        val told = bench.changesDuring { bench.scanner.removeHeld(heldIds()) }
        assertEquals((12 until 22).map(::film).sorted(), filePaths())
        assertTrue("screens told", told > 0)
        assertEquals("only what the user did: ${bench.keptTitles()}", 1, bench.keptTitles().size)
        assertEquals(emptyList<HeldRow>(), dao.observeHeld().first())

        // Back, it is the film it was.
        nas.put(film(0))
        bench.scan()
        assertTrue(dao.filesOf(films.id).single { it.filePath == film(0) }.favorite)
    }

    /** One title held removed from its page: it alone leaves; a title there is never removed so, and stays. */
    @Test
    fun aTitleHeldRemovedAloneLeavesTheOthersHeld() = runTest {
        filmFolder(22)
        (0 until 12).map(::film).forEach(nas::remove)
        bench.scan()

        assertNull("nothing of it left", bench.scanner.removeHeldTitle(idOf(film(0))))
        assertEquals(21, filePaths().size)
        assertEquals(11, heldIds().size)
        val there = idOf(film(20))
        assertEquals(there, bench.scanner.removeHeldTitle(there))
        assertEquals(21, filePaths().size)
    }

    /** Dune in two copies, each grouped under the first as TMDB would: its main row [DUNE], and [DUNE_UHD]. */
    private suspend fun twoDunes(): Pair<Long, Long> {
        filmFolder(22)
        nas.put(DUNE)
        nas.put(DUNE_UHD)
        bench.scan()
        listOf(DUNE, DUNE_UHD).forEach { dao.update(bench.item(films, it).copy(tmdbId = DUNE_TMDB)) }
        dao.groupVersions()
        val main = idOf(DUNE)
        val uhd = idOf(DUNE_UHD)
        assertEquals(main, dao.item(uhd)?.versionOf)
        return main to uhd
    }

    private suspend fun dunes() = dao.filesOf(films.id).filter { it.filePath.orEmpty().startsWith("Dune") }.map { it.id }

    /**
     * A film in two copies, its main row's disk offline: removed from its page, the copy held alone leaves, the film
     * staying under the copy left, which holds it now.
     */
    @Test
    fun aFilmRemovedFromItsPageTakesItsCopiesHeldOnly() = runTest {
        val (main, uhd) = twoDunes()
        (listOf(DUNE) + (0 until 11).map(::film)).forEach(nas::remove)
        bench.scan()
        assertEquals(12, heldIds().size)

        assertEquals("the film stays, under the copy left", uhd, bench.scanner.removeHeldTitle(main))
        assertEquals(listOf(uhd), dunes())
        assertNull(dao.item(uhd)?.versionOf)
        assertEquals(11, heldIds().size)
    }

    /** Both copies held: removed from the page, the film leaves whole. */
    @Test
    fun aFilmWhoseCopiesAreAllHeldLeavesWhole() = runTest {
        val (main, _) = twoDunes()
        (listOf(DUNE, DUNE_UHD) + (0 until 10).map(::film)).forEach(nas::remove)
        bench.scan()
        assertEquals(12, heldIds().size)

        assertNull(bench.scanner.removeHeldTitle(main))
        assertEquals(emptyList<Long>(), dunes())
        assertEquals(10, heldIds().size)
    }

    /** Back meanwhile: an answer given for them removes nothing. */
    @Test
    fun removingTitlesBackRemovesNothing() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        offline.forEach(nas::remove)
        bench.scan()
        val shown = heldIds()
        offline.forEach { nas.put(it) }
        bench.scan()

        bench.scanner.removeHeld(shown)
        assertEquals(22, filePaths().size)
    }

    /**
     * From the show's page: an episode held removed alone, the show stays, its others held; then the show held removed,
     * it goes with its episodes held, what the user did to it kept, the other show staying.
     */
    @Test
    fun aShowHeldRemovedFromItsPageGoesWithItsEpisodes() = runTest {
        val series = bench.folder("Séries", SourceKind.SHOWS)
        val gone = (1..12).map { "MobLand/Season 1/MobLand - S01E${"%02d".format(it)}.mkv" }
        gone.forEach { bench.showsNas.put(it) }
        (1..10).forEach { bench.showsNas.put("Severance/Season 1/Severance - S01E${"%02d".format(it)}.mkv") }
        bench.scan()
        val mobLand = bench.series("MobLand").id
        dao.setFavorite(mobLand, true)
        gone.forEach { bench.showsNas.remove(it) }
        bench.scan()
        assertEquals(12, heldIds(series).size)

        assertEquals("its show holds it now", mobLand, bench.scanner.removeHeldTitle(bench.item(series, gone.first()).id))
        assertEquals(11, heldIds(series).size)
        assertEquals(11, bench.paths(series).count { it.startsWith("MobLand/") })

        assertNull("nothing of it left", bench.scanner.removeHeldTitle(mobLand))
        assertTrue(bench.paths(series).none { it.startsWith("MobLand/") })
        assertEquals(10, bench.paths(series).size)
        assertNull(dao.seriesByKey(FilenameParser.showKey("MobLand", null)))
        assertTrue(bench.keptTitles().toString(), bench.keptTitles().any { it.startsWith("show:name:") })
    }

    /** A show's episodes held, then removed: the show goes with them, what the user did to it kept. */
    @Test
    fun aShowWhoseEpisodesHeldAreRemovedGoesWithThem() = runTest {
        val series = bench.folder("Séries", SourceKind.SHOWS)
        val gone = (1..12).map { "MobLand/Season 1/MobLand - S01E${"%02d".format(it)}.mkv" }
        gone.forEach { bench.showsNas.put(it) }
        (1..10).forEach { bench.showsNas.put("Severance/Season 1/Severance - S01E${"%02d".format(it)}.mkv") }
        bench.scan()
        dao.setFavorite(bench.series("MobLand").id, true)

        gone.forEach { bench.showsNas.remove(it) }
        bench.scan()
        assertEquals(12, heldIds(series).size)

        bench.scanner.removeHeld(heldIds(series))
        assertTrue(bench.paths(series).none { it.startsWith("MobLand/") })
        assertEquals(10, bench.paths(series).size)
        assertNull(dao.seriesByKey(FilenameParser.showKey("MobLand", null)))
        assertTrue(bench.keptTitles().toString(), bench.keptTitles().any { it.startsWith("show:name:") })
    }

    @Test
    fun moreFilesGoneThanAStatementTakes() = runTest {
        filmFolder(1_201)
        (0 until 1_200).map(::film).forEach(nas::remove)
        log.reset()
        bench.scan()
        assertEquals(null, bench.error(films))
        assertEquals("held", 1_201, filePaths().size)

        bench.scanner.keepHeld(films.id, heldIds())
        bench.scanner.removeHeld(heldIds())
        assertEquals(listOf(film(1_200)), filePaths())
        log.assertNoStatementTooWideForAndroid9()
    }

    private companion object {
        const val DUNE = "Dune (2021).mkv"
        const val DUNE_UHD = "Dune (2021) 2160p.mkv"
        const val DUNE_TMDB = 438631
        const val START = ScanBench.START
        const val DAY = 24 * 60 * 60_000L

        /** Between two passes while the app is on screen. */
        const val PASS = 10 * 60_000L
    }
}
