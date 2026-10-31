package app.houselights.providers.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * The library's writes: bulk ones stay under Android 9's 999 values per statement (counted, as Robolectric's SQLite
 * takes more), a relinked file keeps its probe only at the same size, and an insert never replaces a row; reads of one
 * column give what the whole rows gave.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var provider: LocalProvider
    private val widest = AtomicInteger()
    private var source = 0L

    @Before
    fun open() = runBlocking {
        db = testDatabase { setQueryCallback({ _, args -> widest.accumulateAndGet(args.size, ::maxOf) }, Runnable::run) }
        dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor)
        source = dao.insertSource(nfsFolder("Séries", SourceKind.SHOWS, path = "series"))
    }

    @After
    fun close() = db.close()

    private fun row(kind: ItemKind, title: String, parentId: Long? = null, seriesId: Long? = null, collectionId: Long? = null) =
        ItemEntity(
            kind = kind, sourceId = source, title = title, sortTitle = title, parentId = parentId, seriesId = seriesId,
            collectionId = collectionId, filePath = "$title.mkv".takeIf { kind == ItemKind.MOVIE || kind == ItemKind.EPISODE },
            addedAt = 0,
        )

    private suspend fun films(count: Int, collectionId: Long? = null): List<Long> = db.withTransaction {
        (0 until count).map { dao.insert(row(ItemKind.MOVIE, "Film $it", collectionId = collectionId)) }
    }

    private fun assertFitsAndroid9() = assertTrue("${widest.get()} values in one statement", widest.get() <= ANDROID_9_LIMIT)

    /**
     * A film matched anew keeps its choice of tracks under its new key ([tracksKey]), never over the
     * choice that key holds already; the old key stays while another copy is still matched to it.
     */
    @Test
    fun aFilmMatchedAnewKeepsItsChoiceOfTracks() = runTest {
        val choices = db.trackChoices()
        val heat = dao.insert(row(ItemKind.MOVIE, "Heat"))
        choices.save(TrackChoiceEntity("local:$heat", "eng", null, at = 5))
        dao.saveScanned(checkNotNull(dao.item(heat)).copy(tmdbId = 949))
        assertEquals("eng", choices.choice("local:film:949")?.audio)
        assertNull(choices.choice("local:$heat"))

        val copy = dao.insert(row(ItemKind.MOVIE, "Heat 2160p").copy(tmdbId = 949))
        choices.save(TrackChoiceEntity("local:film:1", "fre", null, at = 6))
        dao.saveScanned(checkNotNull(dao.item(heat)).copy(tmdbId = 1))
        assertEquals("its own choice stays", "fre", choices.choice("local:film:1")?.audio)
        assertEquals("still the copy's", "eng", choices.choice("local:film:949")?.audio)
        dao.saveScanned(checkNotNull(dao.item(copy)).copy(tmdbId = 1))
        assertNull("no copy keys it any more", choices.choice("local:film:949"))
    }

    /** A renamed file keeps its probe; one of another size is probed again, its length with it. */
    @Test
    fun aFileReplacedByOneOfAnotherSizeIsReadAgain() = runTest {
        val info = MediaInfo(container = "MKV", durationMs = 7_094_000, probedWith = MediaProbe.VERSION)
        val read = row(ItemKind.MOVIE, "Dune").copy(fileSize = 42).withMedia(info)
        val renamed = dao.insert(read)
        val replaced = dao.insert(read.copy(filePath = "Arrival.mkv"))
        dao.relink(renamed, "Dune (2021).mkv", 42)
        dao.relink(replaced, "Arrival (2016).mkv", 43)

        val kept = checkNotNull(dao.item(renamed))
        assertEquals(Triple(read.mediaInfo, MediaProbe.VERSION, 7_094_000L), Triple(kept.mediaInfo, kept.probeVersion, kept.fileDurationMs))
        val again = checkNotNull(dao.item(replaced))
        assertEquals(Triple(null, 0, null), Triple(again.mediaInfo, again.probeVersion, again.fileDurationMs))
    }

    @Test
    fun removesMoreItemsThanAStatementTakes() = runTest {
        val ids = films(1_500)
        widest.set(0)
        dao.deleteItems(ids)
        assertEquals(0, dao.filesOf(source).size)
        assertFitsAndroid9()
    }

    /** Films TMDB matched, each looked for among the rows that stay ([LibraryDao.staying]) before it is kept. */
    @Test
    fun keepsMoreItemsThanAStatementTakes() = runTest {
        val ids = films(1_500)
        db.openHelper.writableDatabase.execSQL("UPDATE items SET favorite = 1, tmdbId = 1000 + id")
        widest.set(0)
        dao.settleMissing(emptyList(), emptyList(), emptyList(), removed = ids, parts = emptyList(), now = 1)
        assertEquals(0, dao.filesOf(source).size)
        val kept = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kept_states").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        assertEquals(1_500, kept)
        assertFitsAndroid9()
    }

    private fun entry(title: String, tmdbId: Int? = null, played: Boolean = true, favorite: Boolean = false) = KeptStateEntity(
        title, ItemKind.MOVIE, title, tmdbId = tmdbId, showTmdbId = null, sourceId = source, folder = "", seasonNumber = null,
        episodeNumber = null, lastEpisodeNumber = null, played = played, positionMs = 0, durationMs = null, lastPlayedAt = 10,
        favorite = favorite, userTmdbId = null, userPosterPath = null, userBackdropPath = null, userLogoPath = null, addedAt = 0,
        audio = null, subtitles = null, tracksAt = null,
    )

    /** Past their cap, entries go in the order they were kept, one kept again counting as new. */
    @Test
    fun keptStatesStopAtTheirCap() = runTest {
        listOf("a", "b", "c", "a").forEach { dao.keep(listOf(entry(it))) }
        dao.trimKept(max = 2)
        assertEquals(listOf("a", "c"), listOf("a", "b", "c").filter { dao.keptState(it) != null })
    }

    /** A title leaving again replaces what its earlier removal kept, never merged into it: an unmark is never undone. */
    @Test
    fun aLaterRemovalReplacesWhatAnEarlierOneKept() = runTest {
        val title = KeptKeys.film(DUNE)
        suspend fun leaves(favorite: Boolean): KeptStateEntity? {
            dao.keep(listOf(entry(title, DUNE, played = true, favorite = true)))
            val dune = dao.insert(row(ItemKind.MOVIE, "Dune (2021)").copy(tmdbId = DUNE, favorite = favorite))
            dao.settleMissing(emptyList(), emptyList(), emptyList(), removed = listOf(dune), parts = emptyList(), now = 1)
            return dao.keptState(title)
        }
        assertNull("unwatched and no longer favourite: nothing older comes back", leaves(favorite = false))
        val kept = checkNotNull(leaves(favorite = true))
        assertEquals("unwatched, favourite still", false to true, kept.played to kept.favorite)
    }

    /** Dune's main row, its artwork chosen, and two copies grouped under it, the oldest first. */
    private suspend fun dunes(): Triple<Long, Long, Long> {
        val (main, older, newer) = listOf("Dune (2021)", "Dune (2021) 2160p", "Dune (2021) Remux").map { title ->
            dao.insert(row(ItemKind.MOVIE, title).copy(tmdbId = DUNE))
        }
        dao.groupVersions()
        dao.setUserPoster(main, "/chosen-poster.jpg")
        dao.setUserBackdrop(main, "/chosen-backdrop.jpg")
        dao.setUserLogo(main, "/chosen-logo.png")
        return Triple(main, older, newer)
    }

    private suspend fun assertHolds(main: Long, choice: Long?) {
        val held = checkNotNull(dao.item(main))
        assertNull("the main row", held.versionOf)
        assertEquals(
            listOf("/chosen-poster.jpg", "/chosen-backdrop.jpg", "/chosen-logo.png", choice),
            listOf(held.userPosterPath, held.userBackdropPath, held.userLogoPath, held.versionChoice),
        )
    }

    /** A film's main row whose file went, its oldest copy holds the film with what was chosen on it, the copy to play too. */
    @Test
    fun aMainRowWhoseFileWentHandsItsChoicesToTheCopyLeft() = runTest {
        val (main, older, newer) = dunes()
        dao.setVersionChoice(main, newer)
        dao.settleMissing(emptyList(), emptyList(), emptyList(), removed = listOf(main), parts = emptyList(), now = 1)
        assertHolds(older, choice = newer)
        assertEquals(older, dao.item(newer)?.versionOf)
    }

    /**
     * Held rows removed together, the copy left holds the film with its artwork; the copy chosen gone with them, it
     * plays the copy Lire picks ([VersionRank.played]).
     */
    @Test
    fun heldRowsRemovedHandTheFilmToTheCopyLeft() = runTest {
        val (main, older, newer) = dunes()
        dao.setVersionChoice(main, older)
        dao.setMissingSince(listOf(main, older), 1)
        assertEquals(2, dao.removeHeld(listOf(main, older)))
        assertHolds(newer, choice = null)
    }

    /** A show left without episodes is kept by title, unless its folder is gone: removing a folder forgets its titles. */
    @Test
    fun anEmptyShowIsKeptUnlessItsFolderIsGone() = runTest {
        val kept = dao.insert(row(ItemKind.SERIES, "Severance").copy(matchKey = "severance", favorite = true))
        val gone = dao.insert(row(ItemKind.SERIES, "Andor").copy(matchKey = "andor", favorite = true, sourceId = source + 1))
        dao.deleteEmptyShows()
        assertEquals(null to null, dao.item(kept) to dao.item(gone))
        assertEquals(listOf(true), dao.keptNamed(ItemKind.SERIES, "severance").map { it.favorite })
        assertEquals(emptyList<KeptStateEntity>(), dao.keptNamed(ItemKind.SERIES, "andor"))
    }

    /**
     * To identify: a film or show TMDB found nothing for, or hesitated on, without a TMDB id; never an episode, a title
     * matched or still to look up, a file held missing, nor a title the user left as it is. A file held missing keeps why
     * it is to identify, Modifier still opening Identifier on its page, out of the list until it is back.
     */
    @Test
    fun theTitlesToIdentifyLeaveOutEpisodesMissingFilesAndThoseLeftAsTheyAre() = runTest {
        fun film(title: String, state: MatchState?, addedAt: Long) = row(ItemKind.MOVIE, title).copy(matchState = state, addedAt = addedAt)
        val notFound = dao.insert(film("Inconnu", MatchState.NOT_FOUND, addedAt = 1))
        val unsure = dao.insert(film("Dune", MatchState.UNSURE, addedAt = 2))
        val show = dao.insert(row(ItemKind.SERIES, "Severance").copy(matchState = MatchState.NOT_FOUND, addedAt = 3))
        val held = dao.insert(film("Parti", MatchState.NOT_FOUND, addedAt = 7).copy(missingSince = 7))
        val others = listOf(
            film("Heat", MatchState.MATCHED, addedAt = 4).copy(tmdbId = 949),
            film("Pas encore", null, addedAt = 5),
            film("Nommé par son NFO", MatchState.NOT_FOUND, addedAt = 6).copy(tmdbId = 1),
            film("Vacances", MatchState.NOT_FOUND, addedAt = 8).copy(userNoMatch = true),
            row(ItemKind.EPISODE, "Pilote").copy(matchState = MatchState.NOT_FOUND, addedAt = 9),
        ).map { dao.insert(it) }

        assertEquals("the latest first", listOf(show, unsure, notFound), dao.toIdentify().map { it.id })
        assertEquals(3, dao.observeToIdentifyCount().first())
        // What a title's page says of it is what the list selects, a file held missing aside.
        val doubts = dao.rows(listOf(notFound, unsure, show, held) + others).associate { it.id to it.doubt() }.filterValues { it != null }
        val listed = mapOf(notFound to MatchDoubt.NOT_FOUND, unsure to MatchDoubt.UNSURE, show to MatchDoubt.NOT_FOUND)
        assertEquals(listed + (held to MatchDoubt.NOT_FOUND), doubts)
    }

    /**
     * A show to identify whose every episode is held, which its page says is held ([LocalMapper]), waits outside the
     * list, its count and the queue, as a film held does, until one of them is back; one with an episode there, or none
     * yet, is listed.
     */
    @Test
    fun aShowEveryEpisodeOfWhichIsHeldWaitsOutOfTheList() = runTest {
        fun title(kind: ItemKind, name: String, addedAt: Long) = row(kind, name).copy(matchState = MatchState.NOT_FOUND, addedAt = addedAt)
        val film = dao.insert(title(ItemKind.MOVIE, "Inconnu", addedAt = 1))
        val gone = dao.insert(title(ItemKind.SERIES, "Severance", addedAt = 2))
        val half = dao.insert(title(ItemKind.SERIES, "Kaamelott", addedAt = 3))
        val bare = dao.insert(title(ItemKind.SERIES, "Sans épisode", addedAt = 4))
        val episodes = listOf(gone, gone, half, half).mapIndexed { i, show -> dao.insert(row(ItemKind.EPISODE, "E$i", seriesId = show)) }
        dao.setMissingSince(episodes.take(3), 7)

        assertEquals(listOf(bare, half, film), dao.toIdentify().map { it.id })
        assertEquals(3, dao.observeToIdentifyCount().first())
        assertEquals("the queue passes it", film, dao.nextToIdentify(addedAt = 3, id = half))

        dao.setMissingSince(listOf(episodes[1]), null)
        assertEquals("one episode back", listOf(bare, half, gone, film), dao.toIdentify().map { it.id })
        assertEquals(gone, dao.nextToIdentify(addedAt = 3, id = half))
    }

    /** A title left as it is stays so whatever a scan writes ([LibraryDao.saveScanned]). */
    @Test
    fun aTitleLeftAsItIsKeepsItThroughAScan() = runTest {
        val home = dao.insert(row(ItemKind.MOVIE, "Vacances 2019").copy(userNoMatch = true))
        dao.saveScanned(checkNotNull(dao.item(home)).copy(userNoMatch = false, matchState = MatchState.NOT_FOUND))
        assertTrue(checkNotNull(dao.item(home)).userNoMatch)
    }

    /** A show left as it is, its episodes taken over by another show row (two season packs merging), leaves that one so. */
    @Test
    fun aShowLeftAsItIsHandsItOver() = runTest {
        val left = dao.insert(row(ItemKind.SERIES, "Vacances S01").copy(matchState = MatchState.NOT_FOUND, userNoMatch = true))
        val taking = dao.insert(row(ItemKind.SERIES, "Vacances").copy(matchState = MatchState.NOT_FOUND))
        dao.handOver(left, taking)
        assertTrue(checkNotNull(dao.item(taking)).userNoMatch)
        dao.deleteItems(listOf(left))
        assertEquals("not to identify", emptyList<ItemEntity>(), dao.toIdentify())
    }

    /** Two season packs merging into one show: the tracks chosen for the show left go on, unless the other has its own. */
    @Test
    fun aShowTakenOverHandsItsChoiceOfTracksOver() = runTest {
        val choices = db.trackChoices()
        val left = dao.insert(row(ItemKind.SERIES, "Vacances S01"))
        val taking = dao.insert(row(ItemKind.SERIES, "Vacances"))
        choices.save(TrackChoiceEntity("local:$left", "eng", "fre", at = 5))
        dao.handOver(left, taking)
        dao.deleteItems(listOf(left))
        dao.deleteOrphanTrackChoices()
        assertEquals("eng" to "fre", choices.choice("local:$taking")?.let { it.audio to it.subtitles })

        val other = dao.insert(row(ItemKind.SERIES, "Vacances S02"))
        choices.save(TrackChoiceEntity("local:$other", "fre", null, at = 6))
        dao.handOver(other, taking)
        assertEquals("its own stays", "eng", choices.choice("local:$taking")?.audio)
    }

    @Test
    fun holdsMoreItemsThanAStatementTakes() = runTest {
        val ids = films(1_500)
        widest.set(0)
        dao.setMissingSince(ids, 42)
        assertEquals(setOf(42L), dao.filesOf(source).map { it.missingSince }.toSet())
        dao.setMissingSince(ids, null)
        assertEquals(setOf(null), dao.filesOf(source).map { it.missingSince }.toSet())
        assertFitsAndroid9()
    }

    @Test
    fun marksAVeryLongShowWatched() = runTest {
        // One Piece: more than a thousand episodes.
        val (series, seasons) = db.withTransaction {
            val series = dao.insert(row(ItemKind.SERIES, "One Piece"))
            val seasons = (1..12).map { n ->
                val season = dao.insert(row(ItemKind.SEASON, "Season $n", parentId = series, seriesId = series))
                repeat(100) { dao.insert(row(ItemKind.EPISODE, "S${n}E$it", parentId = season, seriesId = series)) }
                season
            }
            series to seasons
        }
        widest.set(0)
        provider.setPlayed(MediaItem(id = series.toString(), kind = MediaKind.SERIES, title = "One Piece"), played = true)
        assertEquals(1_200, dao.episodesOfSeries(series).count { it.played })
        assertFitsAndroid9()

        provider.setPlayed(MediaItem(id = seasons.first().toString(), kind = MediaKind.SEASON, title = ""), played = false)
        assertEquals(1_100, dao.episodesOfSeries(series).count { it.played })
        assertEquals(listOf(false), dao.childRows(seasons.first()).map { it.played }.distinct())
    }

    @Test
    fun marksAVeryLargeCollectionWatched() = runTest {
        val collection = dao.insertCollection(CollectionEntity(matchKey = "name:all", name = "All", sortTitle = "all"))
        films(1_200, collectionId = collection)
        val other = dao.insert(row(ItemKind.MOVIE, "Elsewhere"))
        widest.set(0)
        provider.setPlayed(MediaItem(id = LocalId.ofCollection(collection), kind = MediaKind.COLLECTION, title = "All"), played = true)
        assertEquals(1_200, dao.collectionFilms(collection).count { it.played })
        assertEquals(false, dao.item(other)?.played)
        assertFitsAndroid9()
    }

    /**
     * Home's first scan shows the films and shows first found, then the latest, first found first: a film's copies
     * once, never a season or an episode, a title without a poster too, the poster its user chose first; found in the
     * order the scan made them, a show's new episode not moving it on, a title given back from a kept state with its
     * older date the latest found; it counts them so.
     */
    @Test
    fun theFirstTitlesAreTheFirstFoundThenTheLatest() = runTest {
        val show = added("Severance", at = 1, kind = ItemKind.SERIES, poster = "/severance.jpg")
        val season = dao.insert(row(ItemKind.SEASON, "Saison 1", parentId = show, seriesId = show).copy(addedAt = 2))
        dao.insert(row(ItemKind.EPISODE, "Good News About Hell", parentId = season, seriesId = show).copy(addedAt = 3))
        val dune = added("Dune", at = 4)
        dao.insert(row(ItemKind.MOVIE, "Dune 2160p").copy(addedAt = 5, versionOf = dune, posterPath = "/dune.jpg"))
        val heat = dao.insert(row(ItemKind.MOVIE, "Heat").copy(addedAt = 6, posterPath = "/heat.jpg", userPosterPath = "/mine.jpg"))
        val later = (7..12).map { added("Film $it", at = it.toLong()) }
        dao.insert(row(ItemKind.EPISODE, "Half Loop", parentId = season, seriesId = show).copy(addedAt = LATER))
        dao.setAddedAt(show, LATER)
        val givenBack = added("Given back", at = 0)

        assertEquals(
            listOf(
                FirstTitleRow(show, "Severance", null, "/severance.jpg"),
                FirstTitleRow(dune, "Dune", null, null),
                FirstTitleRow(heat, "Heat", null, "/mine.jpg"),
                FirstTitleRow(givenBack, "Given back", null, null),
            ),
            dao.observeFirstTitles(3).first(),
        )
        assertEquals(
            "the latest once among the first",
            listOf(show, dune, heat) + later + givenBack,
            dao.observeFirstTitles(20).first().map { it.id },
        )
        assertEquals("the titles found, read as the wall says them", 10, dao.observeFirstTitleCount().first())
    }

    private suspend fun added(title: String, at: Long, kind: ItemKind = ItemKind.MOVIE, poster: String? = null): Long =
        dao.insert(row(kind, title).copy(addedAt = at, posterPath = poster))

    /**
     * A show's first episode path is the first of its episodes in their order that has a file, the earliest row of two
     * at one place, as reading every episode found it; another show's never, a show without episodes none.
     */
    @Test
    fun aShowsFirstEpisodePathIsTheFirstFileInItsEpisodesOrder() = runTest {
        val show = dao.insert(row(ItemKind.SERIES, "Severance"))
        val other = dao.insert(row(ItemKind.SERIES, "Arcane"))
        val empty = dao.insert(row(ItemKind.SERIES, "Pluribus"))
        suspend fun episode(series: Long, title: String, season: Int, number: Int, file: Boolean = true) = dao.insert(
            row(ItemKind.EPISODE, title, seriesId = series)
                .copy(seasonNumber = season, episodeNumber = number, filePath = "$title.mkv".takeIf { file }),
        )
        episode(show, "S02E01", season = 2, number = 1)
        episode(show, "S01E02", season = 1, number = 2)
        episode(show, "S01E01 without a file", season = 1, number = 1, file = false)
        episode(show, "S01E01", season = 1, number = 1)
        episode(show, "S01E01 again", season = 1, number = 1)
        episode(other, "S00E00", season = 0, number = 0)

        val read = dao.episodesOfSeries(show).firstNotNullOfOrNull { it.filePath }
        assertEquals("S01E01.mkv", dao.firstEpisodePath(show))
        assertEquals("as every episode read", read, dao.firstEpisodePath(show))
        assertEquals(
            dao.episodesOfSeries(show).map { it.filePath }.sortedBy { it.orEmpty() },
            dao.episodePaths(show).sortedBy { it.orEmpty() },
        )
        assertNull(dao.firstEpisodePath(empty))
        assertEquals(emptyList<String?>(), dao.episodePaths(empty))
    }

    /**
     * A special comes before season 1, an episode without a season or a number before those with one, as reading every
     * episode found them; a season's row with a file of the show is never one of its episodes.
     */
    @Test
    fun aShowsFirstEpisodePathTakesSpecialsAndUnnumberedFirstAndOnlyEpisodes() = runTest {
        suspend fun show(title: String) = dao.insert(row(ItemKind.SERIES, title))
        suspend fun episode(series: Long, title: String, season: Int?, number: Int?) = dao.insert(
            row(ItemKind.EPISODE, title, seriesId = series).copy(seasonNumber = season, episodeNumber = number),
        )
        suspend fun assertFirst(expected: String, series: Long) {
            assertEquals(expected, dao.firstEpisodePath(series))
            val read = dao.episodesOfSeries(series).firstNotNullOfOrNull { it.filePath }
            assertEquals("as every episode read", read, dao.firstEpisodePath(series))
        }
        val special = show("Doctor Who")
        episode(special, "S01E01", season = 1, number = 1)
        episode(special, "S00E01", season = 0, number = 1)
        assertFirst("S00E01.mkv", special)

        val unseasoned = show("Arcane")
        episode(unseasoned, "S01E01", season = 1, number = 1)
        episode(unseasoned, "E05", season = null, number = 5)
        assertFirst("E05.mkv", unseasoned)
        episode(unseasoned, "Unnumbered", season = null, number = null)
        assertFirst("Unnumbered.mkv", unseasoned)

        val unnumbered = show("Shogun")
        episode(unnumbered, "S01E01", season = 1, number = 1)
        episode(unnumbered, "S01", season = 1, number = null)
        assertFirst("S01.mkv", unnumbered)

        val seasoned = show("Severance")
        val season = row(ItemKind.SEASON, "Saison 0", parentId = seasoned, seriesId = seasoned)
        dao.insert(season.copy(seasonNumber = 0, filePath = "season.mkv"))
        episode(seasoned, "S01E01", season = 1, number = 1)
        assertFirst("S01E01.mkv", seasoned)
        assertEquals(listOf<String?>("S01E01.mkv"), dao.episodePaths(seasoned))
    }

    /** A show's own seasons are read without its groups, as all its orders gave them; none for a show with only groups. */
    @Test
    fun aShowsAiredOrderIsReadAlone() = runTest {
        fun order(show: Int, id: String, rank: Int = 0) =
            EpisodeOrderEntity(show, id, type = 0, name = "", episodeCount = 12, rank = rank, places = "[]", groupsAt = 7)
        dao.saveEpisodeOrders(
            listOf(
                order(SEVERANCE, "dvd", rank = 1),
                order(SEVERANCE, EpisodeOrderEntity.AIRED),
                order(ARCANE, EpisodeOrderEntity.AIRED),
                order(PLURIBUS, "absolute", rank = 1),
            ),
        )

        assertEquals(order(SEVERANCE, EpisodeOrderEntity.AIRED), dao.aired(SEVERANCE))
        assertEquals(dao.episodeOrders(SEVERANCE).firstOrNull { it.orderId == EpisodeOrderEntity.AIRED }, dao.aired(SEVERANCE))
        assertNull("only groups", dao.aired(PLURIBUS))
        assertNull("no order", dao.aired(SHOGUN))
    }

    @Test
    fun aRowInsertedAgainFailsRatherThanReplacingIt() = runTest {
        val id = films(1).single()
        dao.savePlayback(id, positionMs = 600_000, durationMs = null, played = false, lastPlayedAt = 1)
        val snapshot = checkNotNull(dao.item(id)).copy(positionMs = 0, lastPlayedAt = null)

        val failure = runCatching { dao.insert(snapshot) }.exceptionOrNull()

        assertTrue("$failure", failure is SQLiteConstraintException)
        assertEquals(600_000L, dao.item(id)?.positionMs)
    }

    private companion object {
        const val ANDROID_9_LIMIT = 999

        /** When a show's latest episode was found, after every title. */
        const val LATER = 99L
        const val DUNE = 438631
        const val ARCANE = 94605
        const val SEVERANCE = 95396
        const val PLURIBUS = 225171
        const val SHOGUN = 126308
    }
}
