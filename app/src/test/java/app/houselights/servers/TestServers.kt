package app.houselights.servers

import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.RemoteBrowsers
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.address
import org.junit.Assume.assumeTrue

/**
 * The test servers of tools/test-servers (its README): a Samba NAS, an unfs3 NAS and a Jellyfin server, serving what
 * make-media.sh writes. Their tests run only when HOUSELIGHTS_TEST_SERVERS=1 says the servers are up, as CI's « servers »
 * job does; scripts/check.sh and the « check » job skip them.
 */
internal object TestServers {
    /** Where they listen: this machine, unless HOUSELIGHTS_TEST_SERVERS_HOST names another. */
    val host: String = System.getenv("HOUSELIGHTS_TEST_SERVERS_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"

    /** The NAS's read-only account, and Jellyfin's: test-only. */
    const val USER = "houselights"
    const val PASSWORD = "houselights-test"

    /** Skips the calling test unless the servers are up. */
    fun assumeUp() = assumeTrue(
        "The test servers are not up (HOUSELIGHTS_TEST_SERVERS=1, tools/test-servers/README.md)",
        System.getenv("HOUSELIGHTS_TEST_SERVERS") == "1",
    )

    /** What the folder settings would say of [source]'s test, which must fail; the failure itself in the message. */
    suspend fun RemoteBrowsers.refusal(source: SourceEntity): Pair<ScanErrorKind, String> {
        val failure = suspendRunCatching { of(source).test(source) }.exceptionOrNull()
            ?: throw AssertionError("${source.address()} passed its test")
        return ScanErrors.classify(failure).kind to failure.toString()
    }
}

/** What make-media.sh writes in the shared folder « video » (/volume1/video), less what a NAS hides. */
internal object Samples {
    const val DUNE = "Films/Dune (2021)/Dune (2021).mkv"
    const val DUNE_NFO = "Films/Dune (2021)/Dune (2021).nfo"

    /** What the app counts at the root of « video », its folders and its videos: Films and Séries; #recycle and @eaDir left out. */
    val ROOT = 2 to 0

    /** What the app counts in Films: six films, five in folders, one loose; .DS_Store left out. */
    val FILMS = 5 to 1

    val videos = setOf(
        "Films/Alien (1979)/Alien (1979).mp4",
        "Films/Blade Runner (1982) {tmdb-78}/Blade Runner (1982) {tmdb-78}.mkv",
        DUNE,
        "Films/Heat (1995)/Heat (1995) CD1.avi",
        "Films/Heat (1995)/Heat (1995) CD2.avi",
        "Films/Le Grand Tournoi des escargots (2019)/Le Grand Tournoi des escargots (2019).mkv",
        "Films/Wonder Woman 1984.mkv",
        "Séries/One Piece (1999)/One Piece - 1071.mkv",
        "Séries/One Piece (1999)/One Piece - 1072.mkv",
        "Séries/One Piece (1999)/One Piece - 1073.mkv",
        "Séries/Severance (2022)/Season 01/Severance - S01E01.mkv",
        "Séries/Severance (2022)/Season 01/Severance - S01E02.mkv",
        "Séries/Severance (2022)/Season 01/Severance - S01E03.mkv",
    )

    /** Dune's NFO and artwork; the thumbnails of @eaDir are not among them. */
    val sidecars = setOf(DUNE_NFO, "Films/Dune (2021)/fanart.jpg", "Films/Dune (2021)/poster.jpg")
}

/** A level's folders and videos, counted apart, as a folder's test says them: « 5 dossiers et 1 vidéo ». */
internal fun FolderLevel.counted(): Pair<Int, Int> = folders.size to videos
