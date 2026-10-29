package app.houselights.ui.edit

import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.providers.local.LocalEditor
import app.houselights.providers.local.ScanBench
import app.houselights.providers.local.TmdbClient
import app.houselights.providers.local.TmdbImage
import app.houselights.providers.local.TmdbImageSet
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
import java.util.Locale

/**
 * DESIGN.md, Layout: the contact sheet's sections, in their order. The head, untitled: Automatique, the one in use and
 * what the title's files give; then a section a language as TMDB's are ranked, one language a section, the one in use
 * never again in its own; a saga's films last. Backdrops start with those without text; a section holds 30 pictures
 * at most, as the library's editor offers them.
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkSectionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("sections"))
    }

    @After
    fun close() = bench.close()

    private fun online(value: String, language: String?) = ArtworkOption(value, null, ArtworkOrigin.ONLINE, language)

    private val automatic = online("auto", "fr")
    private val nfo = ArtworkOption("https://nas/poster.jpg", null, ArtworkOrigin.NFO)
    private val french = (1..8).map { online("fr-$it", "fr") }
    private val english = (1..3).map { online("en-$it", "en") }
    private val none = (1..2).map { online("none-$it", null) }

    private fun choices(options: List<ArtworkOption>, selected: ArtworkOption? = automatic) =
        ArtworkChoices(options, automatic, selected, onlineAvailable = true)

    @Test
    fun theHeadComesFirstThenALanguageASectionInTheirRanking() {
        val sections = choiceSections(choices(listOf(automatic, nfo) + french + english + none))
        assertEquals(
            listOf(SectionName.Head, SectionName.Language("fr"), SectionName.Language("en"), SectionName.Language(null)),
            sections.map { it.name },
        )
        assertEquals("Automatique and what the title's files give, untitled", listOf(automatic, nfo), sections[0].options)
        assertEquals(french, sections[1].options)
        assertEquals(english, sections[2].options)
        assertEquals(none, sections[3].options)
    }

    @Test
    fun theOneInUseIsAtTheHeadBesideAutomatiqueNeverAgainInItsLanguage() {
        val chosen = french[3]
        val sections = choiceSections(choices(listOf(automatic, nfo) + french + english, selected = chosen))
        assertEquals("beside Automatique, before the files'", listOf(automatic, chosen, nfo), sections[0].options)
        assertEquals("one place, checked once", french - chosen, sections[1].options)
        assertEquals(1, sections.sumOf { section -> section.options.count { it == chosen } })
    }

    @Test
    fun aLanguageIsOneSectionWhereverItsPicturesCome() {
        val mixed = listOf(automatic, french[0], english[0], french[1], none[0], english[1], french[2])
        val sections = choiceSections(choices(mixed))
        assertEquals(listOf(null, "fr", "en", null), sections.map { (it.name as? SectionName.Language)?.language })
        assertEquals(listOf(french[0], french[1], french[2]), sections[1].options)
        assertEquals(listOf(english[0], english[1]), sections[2].options)
    }

    @Test
    fun aSagasFilmsComeLastOneAFilm() {
        val films = listOf("Dune" to 2021, "Dune : Deuxième partie" to 2024).map { (title, year) ->
            ArtworkOption("film-$year", null, ArtworkOrigin.FILM, film = title, filmYear = year)
        }
        val sections = choiceSections(choices(listOf(automatic) + english + films))
        assertEquals(SectionName.Films, sections.last().name)
        assertEquals(films, sections.last().options)
    }

    @Test
    fun eachSectionStartsALineOfItsOwn() {
        val sections = choiceSections(choices(listOf(automatic) + french + english))
        val lines = sheetLines(sections, POSTERS)
        assertEquals("the head, two lines of French, one of English", 4, lines.size)
        assertEquals(listOf(listOf(automatic), french.take(POSTERS), french.drop(POSTERS), english), lines.map { it.options })
        assertEquals(listOf(0, 1, 1, 2), lines.map { it.section })
        assertEquals(listOf(true, false, true, true), lines.map { it.last })
    }

    /** A film's backdrops: without text first, then in the viewer's language and English, Automatique having none. */
    @Test
    fun backdropsStartWithThoseWithoutText() = runTest {
        val film = heat(TmdbImageSet(backdrops = pictures("en") + pictures("fr") + pictures(null)))
        val sections = choiceSections(editor().artwork(film, ArtworkKind.BACKDROP))
        assertEquals(listOf(null, "fr", "en"), sections.map { (it.name as SectionName.Language).language })
    }

    /** No language takes more than 30 pictures of the 80 offered, so every one keeps its section; the best are kept. */
    @Test
    fun aSectionHoldsThirtyPicturesAtMost() = runTest {
        val film = heat(TmdbImageSet(posters = pictures("fr") + pictures("en") + pictures(null)))
        val sections = choiceSections(editor().artwork(film, ArtworkKind.POSTER))
        assertEquals(SectionName.Head, sections.first().name)
        assertEquals(listOf(1, PER_LANGUAGE, PER_LANGUAGE, 19), sections.map { it.options.size })
        assertTrue(sections.all { it.options.size <= PER_LANGUAGE })
        assertEquals("the best rated", "/fr-${PICTURES - 1}.jpg", sections[1].options.first().value)
    }

    /** Heat in the library, identified, TMDB listing [images] of it. */
    private suspend fun heat(images: TmdbImageSet): MediaItem {
        bench.tmdb.movie(HEAT, "Heat", 1995)
        bench.tmdb.imageSets[HEAT] = images
        val films = bench.folder("Films")
        bench.nas.put(HEAT_FILE)
        bench.scan()
        val row = bench.item(films, HEAT_FILE)
        return MediaItem(row.id.toString(), MediaKind.MOVIE, row.title)
    }

    /** The library's editor, asking TMDB in French. */
    private fun editor() = LocalEditor(bench.dao, bench.scanner) { TmdbClient("key", transport = bench.tmdb, locale = Locale.FRANCE) }

    /** [PICTURES] of TMDB's pictures in [language], rated from 0 up as they come. */
    private fun pictures(language: String?) =
        (0 until PICTURES).map { TmdbImage("/${language ?: "none"}-$it.jpg", language, voteAverage = it / 10.0) }

    private companion object {
        const val POSTERS = 6
        const val PICTURES = 40
        const val PER_LANGUAGE = 30
        const val HEAT = 949
        const val HEAT_FILE = "Heat (1995)/Heat.mkv"
    }
}
