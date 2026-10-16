package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/** Whole scans of a folder on a NAS held in memory, without TMDB, on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
class LibraryScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var scanner: LibraryScanner
    private val nas = FakeRemoteBrowser()
    private val showsNas = FakeRemoteBrowser()

    /** Another NAS, at another address ([OTHER_HOST]). */
    private val otherNas = FakeRemoteBrowser()
    private val passes = SupervisorJob()
    private var now = START

    /** Since the device started: a second in. */
    private var elapsed = 1_000L

    /** A film playing, the app on screen. */
    private val playing = MutableStateFlow(false)
    private val started = MutableStateFlow(true)

    /** The most values one statement bound. */
    private val widest = AtomicInteger()

    /** Transactions opened, those opened inside another not counted. */
    private val transactions = AtomicInteger()
    private val depth = AtomicInteger()

    /** Statements run while [recording]. */
    private val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var recording = false
    private lateinit var films: SourceEntity

    @Before
    fun open() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .setQueryCallback({ sql, args ->
                widest.accumulateAndGet(args.size, ::maxOf)
                if (recording) statements += sql
                when {
                    sql.startsWith("BEGIN") -> if (depth.getAndIncrement() == 0) transactions.incrementAndGet()
                    sql.startsWith("END") -> depth.decrementAndGet()
                }
            }, Runnable::run)
            .build()
        dao = db.dao()
        scanner = LibraryScanner(
            dao = dao,
            tmdb = { null },
            scope = CoroutineScope(passes),
            artwork = ArtworkCache(temp.newFolder("artwork")),
            browsers = {
                when {
                    it.host == OTHER_HOST -> otherNas
                    it.kind == SourceKind.SHOWS -> showsNas
                    else -> nas
                }
            },
            closeIdle = {},
            clock = { now },
            elapsed = { elapsed },
            playing = playing,
            started = started,
        )
        films = folder("Films", SourceKind.MOVIES)
    }

    @After
    fun close() = db.close()

    private suspend fun folder(name: String, kind: SourceKind, host: String = "nas"): SourceEntity {
        val id = dao.insertSource(
            SourceEntity(
                name = name, kind = kind, protocol = Protocol.NFS, host = host, share = "media", path = name.lowercase(),
                username = "", password = Secret(""),
            )
        )
        return checkNotNull(dao.source(id))
    }

    /** One whole pass, as the app asks for it, waited for. */
    private suspend fun scan(refreshMetadata: Boolean = false) {
        scanner.scanAll(refreshMetadata)
        passes.children.toList().forEach { it.join() }
    }

    private suspend fun filePaths(source: SourceEntity = films) = dao.filesOf(source.id).mapNotNull { it.filePath }.sorted()

    private fun error(source: SourceEntity = films) = scanner.state.value.errors[source.id]?.kind

    private fun film(n: Int) = "Film $n (2020).mkv"

    /** A folder of [count] films, scanned. */
    private suspend fun filmFolder(count: Int) {
        repeat(count) { nas.put(film(it)) }
        scan()
    }

    @Test
    fun indexesFilmsAndShowsThenReadsNothingAgain() = runTest {
        val shows = folder("Séries", SourceKind.SHOWS)
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put("Severance/Season 1/Severance - S01E02.mkv")
        scan()

        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021)/Dune (2021).mkv"), filePaths())
        assertEquals(listOf("Arrival", "Dune"), dao.filesOf(films.id).map { it.title }.sorted())
        val episodes = dao.filesOf(shows.id).sortedBy { it.episodeNumber }
        assertEquals(listOf(1, 2), episodes.map { it.episodeNumber })
        assertEquals("Severance", dao.item(episodes.first().seriesId!!)?.title)
        assertEquals(4, nas.reads.size + showsNas.reads.size)

        nas.reads.clear()
        showsNas.reads.clear()
        scan()
        assertEquals("a pass with nothing new reads no file", 0, nas.reads.size + showsNas.reads.size)
    }

    /**
     * A pass that finds nothing new reads what tells files apart, never their details nor what each file
     * holds: it runs every ten minutes, on thousands of files.
     */
    @Test
    fun aPassFindingNothingNewReadsNeitherDetailsNorWhatFilesHold() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        scan()
        recording = true
        scan()
        recording = false
        val items = Regex("""\bitems\b""")
        val reads = statements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) && items.containsMatchIn(it) }
        assertTrue("the files known are read", reads.isNotEmpty())
        assertEquals(emptyList<String>(), reads.filter { "mediaInfo" in it || "overview" in it })
    }

    /** A scan unless one ended less than ten minutes ago, as the app asks when it comes back, waited for. */
    private suspend fun scanIfStale() {
        scanner.scanIfStale(TEN_MINUTES)
        passes.children.toList().forEach { it.join() }
    }

    @Test
    fun theFirstScanAskedRunsEvenMomentsAfterTheDeviceStarted() = runTest {
        nas.put("Dune (2021).mkv")
        scanIfStale()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aScanEndedMomentsAgoIsNotRunAgain() = runTest {
        nas.put("Dune (2021).mkv")
        scan()
        nas.put("Arrival (2016).mkv")
        elapsed += TEN_MINUTES - 1
        scanIfStale()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())

        elapsed += 1
        scanIfStale()
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
    }

    @Test
    fun theDateSetBackDoesNotHoldScans() = runTest {
        nas.put("Dune (2021).mkv")
        scan()
        nas.put("Arrival (2016).mkv")
        // The date goes back three hours; eleven minutes pass.
        now -= 3 * HOUR
        elapsed += TEN_MINUTES + 60_000
        scanIfStale()
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
    }

    /** The app looks again every ten minutes: a pass that lasts longer (a first scan) is not followed by another. */
    @Test
    fun aScanUnderWayIsNotFollowedByAnother() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { listed++ }
        // The pass is under way, waiting for the film that plays.
        playing.value = true
        scanner.scanAll()
        playing.subscriptionCount.first { it > 0 }
        elapsed += TEN_MINUTES
        scanner.scanIfStale(TEN_MINUTES)

        playing.value = false
        passes.children.toList().forEach { it.join() }
        assertEquals("one pass", 1, listed)
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aScanWaitsWhileTheAppIsAway() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { listed++ }
        // Home pressed: a film may play from the NAS in another app.
        started.value = false
        scanner.scanAll()
        started.subscriptionCount.first { it > 0 }
        assertEquals("not even the folder is listed", 0, listed)

        started.value = true
        passes.children.toList().forEach { it.join() }
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aScanWaitsForTheFilmPlayingBeforeListingAFolder() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { listed++ }
        playing.value = true
        scanner.scanAll()
        playing.subscriptionCount.first { it > 0 }
        assertEquals(0, listed)

        playing.value = false
        passes.children.toList().forEach { it.join() }
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aPassCutShortLetsTheNextOneStart() = runTest {
        nas.put("Dune (2021).mkv")
        val listing = CompletableDeferred<Unit>()
        nas.onList = {
            listing.complete(Unit)
            awaitCancellation()
        }
        scanner.scanAll()
        listing.await()
        passes.children.toList().forEach { it.cancelAndJoin() }

        nas.onList = null
        scan()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun anUnreachableNasRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        scan()
        dao.setFavorite(dao.filesOf(films.id).single().id, true)

        nas.listFailure = ConnectException("Connection refused")
        scan()

        assertEquals(ScanErrorKind.UNREACHABLE, error())
        assertTrue(dao.filesOf(films.id).single().favorite)
    }

    @Test
    fun aNasThatStopsAnsweringEndsThePassAfterThreeFiles() = runTest {
        nas.put("Dune (2021).mkv")
        scan()

        repeat(5) { nas.put("New $it (2024).mkv") }
        nas.readFailure = SocketTimeoutException("timeout")
        nas.reads.clear()
        scan()

        assertEquals(ScanErrorKind.UNREACHABLE, error())
        assertEquals("gives up after three files, each with long timeouts", 3, nas.reads.size)
        assertTrue("Dune (2021).mkv" in filePaths())

        // Files it gave up on are read at the next pass.
        nas.readFailure = null
        nas.reads.clear()
        scan()
        assertEquals(5, nas.reads.size)
        assertEquals(null, error())
    }

    @Test
    fun twoFoldersOfOneNameKeepTheirOwnState() = runTest {
        // "Films" on two NAS: the second one is off.
        val other = folder("Films", SourceKind.MOVIES, host = OTHER_HOST)
        nas.put("Dune (2021).mkv")
        otherNas.listFailure = ConnectException("Connection refused")
        val read = mutableListOf<Long?>()
        nas.onList = { read += scanner.state.value.sourceId }
        scan()

        assertEquals(listOf<Long?>(films.id), read)
        assertEquals(ScanErrorKind.UNREACHABLE, error(other))
        assertEquals("the folder read is not said off", null, error(films))
    }

    @Test
    fun anEmptyListingRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        scan()
        val dune = dao.filesOf(films.id).first { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = now)
        dao.setFavorite(dune.id, true)

        // The share answers, but its disk is not mounted.
        nas.clear()
        scan()

        assertEquals(ScanErrorKind.EMPTY, error())
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
        val kept = checkNotNull(dao.item(dune.id))
        assertEquals(600_000L, kept.positionMs)
        assertTrue(kept.favorite)
        assertEquals(null, kept.missingSince)
    }

    /** lost+found, a folder of another user: what the library holds under it is out of sight, not gone. */
    @Test
    fun aFolderThatCannotBeReadRemovesNothingUnderIt() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Private/Arrival (2016)/Arrival (2016).mkv")
        scan()

        nas.unreadable = listOf("Private")
        nas.put("Blade Runner (1982)/Blade Runner (1982).mkv")
        scan()
        now += MissingFiles.HOLD_MS
        scan()

        assertEquals(null, error())
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
        scan()
        assertEquals(null, error())
    }

    @Test
    fun manyFilesGoneAtOnceStayADay() = runTest {
        filmFolder(40)
        // More than 20: a disk of the pool is offline.
        val offline = (0 until 21).map(::film)
        offline.forEach(nas::remove)
        scan()
        assertEquals(40, filePaths().size)
        assertEquals(setOf(START), dao.filesOf(films.id).filter { it.filePath in offline }.map { it.missingSince }.toSet())
        assertEquals(null, error())

        now += 23 * HOUR
        scan()
        assertEquals("held for a day", 40, filePaths().size)

        now += 2 * HOUR
        scan()
        assertEquals("still missing a day later", (21 until 40).map(::film).sorted(), filePaths())
    }

    @Test
    fun aDiskOfALargePoolOfflineIsHeldToo() = runTest {
        filmFolder(1_000)
        // One disk of seven: far less than a quarter of the folder.
        val offline = (0 until 150).map(::film)
        offline.forEach(nas::remove)
        scan()
        assertEquals(1_000, filePaths().size)
        assertEquals(setOf(START), dao.filesOf(films.id).filter { it.filePath in offline }.map { it.missingSince }.toSet())
    }

    @Test
    fun aFileBackIsNoLongerHeld() = runTest {
        filmFolder(40)
        (0 until 21).map(::film).forEach(nas::remove)
        scan()

        nas.put(film(0))
        now += 25 * HOUR
        scan()

        val back = dao.filesOf(films.id).single { it.filePath == film(0) }
        assertEquals(null, back.missingSince)
        assertEquals(20, filePaths().size)
    }

    @Test
    fun fewFilesGoneGoAnHourLater() = runTest {
        filmFolder(40)
        (0 until 20).map(::film).forEach(nas::remove)
        scan()
        assertEquals("held until a later pass", 40, filePaths().size)

        now += HOUR - 1
        scan()
        assertEquals("held for an hour", 40, filePaths().size)

        now += 1
        scan()
        assertEquals((20 until 40).map(::film).sorted(), filePaths())
    }

    @Test
    fun aFilmImportedInTwoStepsKeepsItsRow() = runTest {
        nas.put("Dune (2021) Bluray-1080p.mkv")
        nas.put("Heat (1995).mkv")
        scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setPlayed(dune.id, played = true, now = now)

        // Radarr sends the old file to its recycle bin, then copies the new one from another volume.
        nas.remove("Dune (2021) Bluray-1080p.mkv")
        scan()
        now += 2 * HOUR
        nas.put("Dune (2021) Remux-2160p.mkv")
        scan()

        val upgraded = dao.filesOf(films.id).single { it.title == "Dune" }
        assertEquals(dune.id, upgraded.id)
        assertEquals("Dune (2021) Remux-2160p.mkv", upgraded.filePath)
        assertEquals(null, upgraded.missingSince)
        assertTrue(upgraded.played)
    }

    @Test
    fun moreFilesGoneThanAStatementTakes() = runTest {
        filmFolder(1_201)
        (0 until 1_200).map(::film).forEach(nas::remove)
        widest.set(0)
        scan()
        assertEquals(null, error())
        assertEquals("held", 1_201, filePaths().size)

        now += 25 * HOUR
        scan()
        assertEquals(listOf(film(1_200)), filePaths())
        assertTrue("${widest.get()} values in one statement", widest.get() <= ANDROID_9_LIMIT)
    }

    @Test
    fun filmsSortedIntoFoldersKeepTheirRows() = runTest {
        filmFolder(1_200)
        val before = dao.filesOf(films.id).associate { it.filePath to it.id }
        // Sorted into a folder per letter: every film leaves the path and the folder it had.
        val moved = (0 until 1_200).associate { film(it) to "${film(it).first()}/${film(it)}" }
        moved.forEach { (from, to) -> nas.rename(from, to) }
        widest.set(0)
        scan()

        assertEquals(moved.values.sorted(), filePaths())
        assertEquals(before.mapKeys { (path, _) -> moved[path] }, dao.filesOf(films.id).associate { it.filePath to it.id })
        assertTrue("${widest.get()} values in one statement", widest.get() <= ANDROID_9_LIMIT)
    }

    @Test
    fun aFilmFolderRenamedKeepsItsRow() = runTest {
        nas.put("Dune 2021/Dune.mkv")
        nas.put("Heat (1995)/Heat (1995).mkv")
        scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setFavorite(dune.id, true)

        // Radarr's "Rename Movie Folder", and its files.
        nas.rename("Dune 2021/Dune.mkv", "Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        scan()

        val renamed = dao.filesOf(films.id).single { it.title == "Dune" }
        assertEquals(dune.id, renamed.id)
        assertEquals("Dune (2021)/Dune (2021) Bluray-1080p.mkv", renamed.filePath)
        assertTrue(renamed.favorite)
    }

    @Test
    fun twoCopiesOfAFilmMovedAreNotTold() = runTest {
        nas.put("Dune (2021) Bluray-1080p.mkv")
        nas.put("Dune (2021) Remux-2160p.mkv")
        scan()
        val before = dao.filesOf(films.id).map { it.id }.toSet()

        nas.rename("Dune (2021) Bluray-1080p.mkv", "Dune/Dune (2021) Bluray-1080p.mkv")
        nas.rename("Dune (2021) Remux-2160p.mkv", "Dune/Dune (2021) Remux-2160p.mkv")
        scan()

        val moved = dao.filesOf(films.id).filter { it.missingSince == null }
        assertEquals(2, moved.size)
        assertTrue("which is which cannot be told", moved.none { it.id in before })
    }

    @Test
    fun aFilmUpgradedByRadarrKeepsWhatTheUserDid() = runTest {
        nas.put("Dune (2021)/Dune (2021) Bluray-1080p.mkv", size = 30_000_000_000)
        nas.put("Arrival (2016)/Arrival (2016) Bluray-1080p.mkv")
        scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = now)
        dao.setFavorite(dune.id, true)
        dao.setUserTmdbId(dune.id, 438631)
        dao.setUserPoster(dune.id, "/chosen.jpg")

        nas.remove("Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        nas.put("Dune (2021)/Dune (2021) Remux-2160p.mkv", size = 80_000_000_000)
        nas.reads.clear()
        scan()

        val upgraded = dao.filesOf(films.id).single { it.title == "Dune" }
        assertEquals(dune.id, upgraded.id)
        assertEquals("Dune (2021)/Dune (2021) Remux-2160p.mkv", upgraded.filePath)
        assertEquals(80_000_000_000L, upgraded.fileSize)
        assertEquals(dune.addedAt, upgraded.addedAt)
        assertEquals(600_000L, upgraded.positionMs)
        assertEquals(now, upgraded.lastPlayedAt)
        assertTrue(upgraded.favorite)
        assertEquals(438631, upgraded.userTmdbId)
        assertEquals("/chosen.jpg", upgraded.userPosterPath)
        assertEquals("the new file is read", listOf(upgraded.filePath), nas.reads)
        assertEquals(80_000_000_000L, MediaProbe.decode(upgraded.mediaInfo)?.sizeBytes)
    }

    @Test
    fun filmsUpgradedSideBySideKeepTheirRows() = runTest {
        nas.put("Dune (2021) Bluray-1080p.mkv")
        nas.put("Arrival (2016) WEBDL-1080p.mkv")
        nas.put("Heat (1995).mkv")
        scan()
        val before = dao.filesOf(films.id).associate { it.title to it.id }

        nas.rename("Dune (2021) Bluray-1080p.mkv", "Dune (2021) Remux-2160p.mkv")
        nas.rename("Arrival (2016) WEBDL-1080p.mkv", "Arrival (2016) Bluray-2160p.mkv")
        scan()

        val after = dao.filesOf(films.id)
        assertEquals(before, after.associate { it.title to it.id })
        assertEquals(listOf("Arrival (2016) Bluray-2160p.mkv", "Dune (2021) Remux-2160p.mkv", "Heat (1995).mkv"), filePaths())
    }

    @Test
    fun anotherFilmBesideIsNotARename() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setFavorite(dune.id, true)

        nas.remove("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        scan()

        val arrival = dao.filesOf(films.id).single { it.title == "Arrival" }
        assertTrue(arrival.id != dune.id)
        assertEquals(false, arrival.favorite)
        assertEquals("Dune waits on its own", START, dao.item(dune.id)?.missingSince)
    }

    @Test
    fun anotherFilmAloneInAFolderNotNamedLikeAFilmIsNotARename() = runTest {
        nas.put("À voir/Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setFavorite(dune.id, true)
        dao.setPlayed(dune.id, played = true, now = now)

        nas.remove("À voir/Dune (2021).mkv")
        nas.put("À voir/Arrival (2016).mkv")
        scan()

        val arrival = dao.filesOf(films.id).single { it.filePath == "À voir/Arrival (2016).mkv" }
        assertTrue(arrival.id != dune.id)
        assertEquals("Arrival", arrival.title)
        assertEquals(false, arrival.favorite)
        assertEquals(false, arrival.played)
    }

    @Test
    fun anEpisodeUpgradedBySonarrStaysWatched() = runTest {
        val shows = folder("Séries", SourceKind.SHOWS)
        showsNas.put("Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv")
        showsNas.put("Severance/Season 1/Severance - S01E02 - Half Loop WEBDL-1080p.mkv")
        scan()
        val pilot = dao.filesOf(shows.id).single { it.episodeNumber == 1 }
        dao.setPlayed(pilot.id, played = true, now = now)

        showsNas.rename(
            "Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv",
            "Severance/Season 1/Severance - S01E01 - Good News About Hell Bluray-2160p.mkv",
        )
        scan()

        val upgraded = dao.filesOf(shows.id).single { it.episodeNumber == 1 }
        assertEquals(pilot.id, upgraded.id)
        assertTrue(upgraded.played)
        assertEquals(pilot.addedAt, upgraded.addedAt)
        assertEquals(2, dao.episodesOfSeries(checkNotNull(pilot.seriesId)).size)
    }

    @Test
    fun aFileRenamedAloneIsNotReadAgain() = runTest {
        nas.put("Dune (2021) Remux-2160p.mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        scan()
        val dune = dao.filesOf(films.id).single()

        nas.rename("Dune (2021) Remux-2160p.mkv", "Dune (2021) {imdb-tt1160419} Remux-2160p.mkv")
        nas.reads.clear()
        val changes = AtomicInteger()
        val watch = launch(Dispatchers.Unconfined) { scanner.changed.collect { changes.incrementAndGet() } }
        scan()
        watch.cancel()

        val renamed = dao.filesOf(films.id).single()
        assertEquals(dune.id, renamed.id)
        assertEquals("Dune (2021) {imdb-tt1160419} Remux-2160p.mkv", renamed.filePath)
        assertEquals("the same size, the same file", emptyList<String>(), nas.reads)
        assertEquals(dune.mediaInfo, renamed.mediaInfo)
        assertEquals(REMUX_BADGES, renamed.badges)
        assertTrue("screens learn the new name", changes.get() > 0)
    }

    @Test
    fun filesRenamedByTheThousandAreWrittenAtOnce() = runTest {
        filmFolder(1_200)
        // Sonarr's or Radarr's "Rename files", the whole library at once.
        (0 until 1_200).forEach { nas.rename(film(it), "Film $it (2020) Bluray-1080p.mkv") }
        transactions.set(0)
        scan()

        assertEquals((0 until 1_200).map { "Film $it (2020) Bluray-1080p.mkv" }.sorted(), filePaths())
        assertTrue("${transactions.get()} transactions", transactions.get() <= A_FEW_TRANSACTIONS)
    }

    @Test
    fun aFileReplacedUnderTheSameNameIsReadAgain() = runTest {
        nas.put("Dune (2021).mkv", size = 30_000_000_000)
        scan()
        assertEquals(emptyList<String>(), dao.filesOf(films.id).single().badges)

        // A remux in its place, under the same name; or the rest of a file read while it was copied.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        nas.reads.clear()
        scan()

        val dune = dao.filesOf(films.id).single()
        assertEquals(listOf("Dune (2021).mkv"), nas.reads)
        assertEquals(80_000_000_000L, dune.fileSize)
        assertEquals(80_000_000_000L, MediaProbe.decode(dune.mediaInfo)?.sizeBytes)
        assertEquals(REMUX_BADGES, dune.badges)

        nas.reads.clear()
        scan()
        assertEquals("read once", emptyList<String>(), nas.reads)
    }

    @Test
    fun anEpisodeReplacedUnderTheSameNameIsReadAgain() = runTest {
        val shows = folder("Séries", SourceKind.SHOWS)
        val pilot = "Severance/Season 1/Severance - S01E01.mkv"
        showsNas.put(pilot, size = 2_000_000_000)
        scan()

        showsNas.put(pilot, size = 9_000_000_000, bytes = MkvProbeTest().sampleFile())
        showsNas.reads.clear()
        scan()

        val episode = dao.filesOf(shows.id).single()
        assertEquals(listOf(pilot), showsNas.reads)
        assertEquals(9_000_000_000L, episode.fileSize)
        assertEquals(REMUX_BADGES, episode.badges)

        showsNas.reads.clear()
        scan()
        assertEquals("read once", emptyList<String>(), showsNas.reads)
    }

    private suspend fun shareWithPassword(): SourceEntity = checkNotNull(
        dao.source(
            dao.insertSource(
                SourceEntity(
                    name = "Partage", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "nas", share = "media",
                    path = "films", username = "zidoo", password = Secret("s3cret"),
                )
            )
        )
    )

    private fun sealedPassword(source: SourceEntity): String =
        db.openHelper.readableDatabase.query("SELECT password FROM sources WHERE id = ${source.id}").use {
            check(it.moveToFirst())
            it.getString(0)
        }

    @Test
    fun theEndOfAScanWritesItsTimesAlone() = runTest {
        val share = shareWithPassword()
        val sealed = sealedPassword(share)
        nas.put("Dune (2021).mkv")
        scan()
        assertEquals(START to START, checkNotNull(dao.source(share.id)).let { it.lastScanAt to it.firstScanAt })

        now += HOUR
        scan()
        val scanned = checkNotNull(dao.source(share.id))
        assertEquals(START + HOUR to START, scanned.lastScanAt to scanned.firstScanAt)
        assertEquals("the password is not sealed again", sealed, sealedPassword(share))
        assertEquals("s3cret", scanned.password.value)
    }

    @Test
    fun aFolderEditedDuringAScanKeepsTheEdit() = runTest {
        val share = shareWithPassword()
        nas.put("Dune (2021).mkv")
        // The NAS moved to another address; the user says so while a scan lists the folder.
        nas.onList = { dao.updateSource(checkNotNull(dao.source(share.id)).copy(host = "192.168.1.20")) }
        scan()
        val edited = checkNotNull(dao.source(share.id))
        assertEquals("192.168.1.20", edited.host)
        assertEquals(START, edited.lastScanAt)
    }

    private companion object {
        val REMUX_BADGES = listOf("4K", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1")
        const val ANDROID_9_LIMIT = 999
        const val A_FEW_TRANSACTIONS = 20
        const val START = 1_800_000_000_000L
        const val HOUR = 60 * 60_000L
        const val OTHER_HOST = "192.168.1.30"
        const val TEN_MINUTES = 10 * 60_000L
    }
}
