package app.houselights.servers

import app.houselights.core.Codecs
import app.houselights.core.FailureKind
import app.houselights.core.Languages
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.SortOption
import app.houselights.core.SourceConfig
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.JellyfinProvider
import app.houselights.ui.onboarding.LoginError
import app.houselights.ui.onboarding.loginError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Jellyfin server of tools/test-servers, set up by bootstrap-jellyfin.sh: signing in as the app does (the address
 * typed, HTTPS tried first), its libraries and their kinds, a film's details read from its NFO and file, a show's
 * seasons and episodes, and a token signed out. Jellyfin reads names and NFO alone there, never the internet.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinServerTest {
    private val typed = "${TestServers.host}:8096"
    private val deviceId = "houselights-servers-test"

    @Before
    fun up() = TestServers.assumeUp()

    /** Signs in as the sign-in screen does: the server reached from what was typed, then the account. */
    private suspend fun signIn(): SourceConfig.Jellyfin {
        val (url, info) = JellyfinNetwork.Lan.reach(typed, deviceId)
        val signedIn = JellyfinNetwork.Lan.authenticate(url, deviceId, AuthRequest(TestServers.USER, TestServers.PASSWORD))
        return SourceConfig.Jellyfin(url, info.serverName, signedIn.user.id, signedIn.user.name, signedIn.accessToken)
    }

    private suspend fun library(provider: JellyfinProvider, title: String): Library =
        provider.libraries().single { it.title == title }

    private suspend fun titles(provider: JellyfinProvider, library: Library): List<MediaItem> =
        provider.libraryPage(library, SortOption.TITLE, LibraryFilter(), start = 0, limit = 100, grouped = false).items

    @Test
    fun theAddressTypedIsReachedOverHttpOnceHttpsDoesNotAnswer() = runBlocking {
        val (url, info) = JellyfinNetwork.Lan.reach(typed, deviceId)
        assertEquals("http://$typed", url)
        assertEquals("Jellyfin Server", info.productName)
        assertTrue("Supported: ${info.version}", JellyfinClient.isSupported(info))
        val config = signIn()
        assertEquals(TestServers.USER, config.userName)
    }

    @Test
    fun aWrongPasswordIsRefused() = runBlocking {
        val (url, _) = JellyfinNetwork.Lan.reach(typed, deviceId)
        val failure = suspendRunCatching { JellyfinNetwork.Lan.authenticate(url, deviceId, AuthRequest(TestServers.USER, "wrong")) }
            .exceptionOrNull() ?: throw AssertionError("Signed in with a wrong password")
        assertEquals("$failure", LoginError.Refused, loginError(failure, url))
    }

    @Test
    fun eachLibraryHasItsKind() = runBlocking {
        val provider = JellyfinProvider(signIn(), deviceId)
        val kinds = provider.libraries().associate { it.title to it.kind }
        assertEquals(mapOf("Films" to LibraryKind.MOVIES, "Séries" to LibraryKind.SHOWS, "Vidéos perso" to LibraryKind.MIXED), kinds)
    }

    @Test
    fun aFilmsDetailsComeFromItsNfoAndItsFile() = runBlocking {
        val provider = JellyfinProvider(signIn(), deviceId)
        // Heat's two parts are one film. Jellyfin names the others by their folders, years included, without the internet.
        val films = titles(provider, library(provider, "Films"))
        assertEquals("Films: ${films.map { it.title }}", FILMS, films.size)
        val dune = provider.item(films.single { it.title == "Dune" }.id)
        assertEquals(2021, dune.year)
        assertTrue("Overview: ${dune.overview}", dune.overview.orEmpty().contains("Paul Atreides"))
        assertEquals(listOf("Science-Fiction", "Aventure"), dune.genres)
        assertEquals(listOf("Denis Villeneuve"), dune.directors)
        val media = dune.media
        assertEquals(Codecs.HEVC to 1920, media?.video?.let { it.codec to it.width })
        // Jellyfin says "fra" where the file says "fre"; the app reads both as French.
        assertEquals(listOf("en", "fr"), media?.audio?.map { Languages.code(it.language) })
        assertEquals(listOf("fr"), media?.subtitles?.map { Languages.code(it.language) })
    }

    /** An anime numbered from its first episode has one season, without a number. */
    @Test
    fun showsHaveTheirSeasonsAndEpisodes() = runBlocking {
        val provider = JellyfinProvider(signIn(), deviceId)
        val shows = titles(provider, library(provider, "Séries")).associateBy { it.title }
        assertEquals(setOf("One Piece", "Severance"), shows.keys)
        val expected = listOf(Triple("Severance", 1, listOf(1, 2, 3)), Triple("One Piece", null, listOf(1071, 1072, 1073)))
        for ((title, season, episodes) in expected) {
            val show = shows.getValue(title)
            val only = provider.seasons(show).single()
            assertEquals(title, season, only.seasonNumber)
            assertEquals(title, episodes, provider.episodes(show, only).map { it.episodeNumber })
        }
    }

    @Test
    fun personalVideosAreListedByTheirFolders() = runBlocking {
        val provider = JellyfinProvider(signIn(), deviceId)
        val folders = titles(provider, library(provider, "Vidéos perso")).map { it.title }
        assertEquals(listOf("2019-07 Vacances", "2021-12 Noël"), folders)
    }

    @Test
    fun aTokenSignedOutIsRefused() = runBlocking {
        val config = signIn()
        JellyfinClient.checkToken(config, deviceId)
        JellyfinClient.signOut(config, deviceId)
        val failure = suspendRunCatching { JellyfinClient.checkToken(config, deviceId) }.exceptionOrNull()
        assertEquals("$failure", FailureKind.REFUSED, failure?.failureKind())
    }

    private companion object {
        /** make-media.sh's films in Films. */
        const val FILMS = 6
    }
}
