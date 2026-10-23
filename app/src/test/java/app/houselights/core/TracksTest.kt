package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tracks asked of the player, as the files of a real library list them (the Zidoo's numbering). */
class TracksTest {
    private fun plan(
        media: MediaInfo,
        preferences: TrackPreferences = TrackPreferences(),
        choice: TrackChoice? = null,
        region: String? = "FR",
    ) = TrackPlan(media, preferences, choice, language = "fr", region = region, titleKey = "local:1")

    private val french = TrackPreferences(audio = TrackPreferences.INTERFACE)

    @Test
    fun settingsLeftToThePlayerAskNothing() {
        assertTrue(plan(BUGONIA).pick.isEmpty)
        assertTrue(plan(COLONY, TrackPreferences(audio = "de")).pick.isEmpty)
    }

    @Test
    fun theAudioIsTheLanguageAskedForInItsBestFormat() {
        assertEquals("the French dub, third", 2, plan(BUGONIA, french).pick.audio)
        assertEquals("TrueHD before Dolby Digital", 0, plan(BUGONIA, TrackPreferences(audio = "eng")).pick.audio)
        assertEquals(1, plan(COLONY, TrackPreferences(audio = "ko")).pick.audio)
    }

    @Test
    fun aDubOfTheDevicesRegionComesFirst() {
        val dubs = media(
            audio = listOf(
                AudioTrack(Codecs.EAC3, 8, "fr-CA", "VFQ", atmos = true),
                AudioTrack(Codecs.AC3, 6, "fre", "VFF"),
                AudioTrack(Codecs.TRUEHD, 8, "eng", atmos = true),
            ),
        )
        assertEquals(1, plan(dubs, french).pick.audio)
        assertEquals(0, plan(dubs, french, region = "CA").pick.audio)
        assertEquals("no region: the best", 0, plan(dubs, french, region = null).pick.audio)
    }

    @Test
    fun commentariesAndDescriptionsAreNeverTheFilm() {
        val tracks = media(
            audio = listOf(
                AudioTrack(Codecs.TRUEHD, 8, "eng", "Director's Commentary"),
                AudioTrack(Codecs.EAC3, 6, "fre", "Audiodescription"),
                AudioTrack(Codecs.AC3, 6, "fre", "VFF"),
                AudioTrack(Codecs.AC3, 6, "eng"),
            ),
        )
        assertEquals(2, plan(tracks, french).pick.audio)
        assertEquals(3, plan(tracks, TrackPreferences(audio = "en")).pick.audio)
    }

    @Test
    fun noTrackInTheLanguageLeavesTheAudioToThePlayer() {
        assertNull(plan(COLONY, TrackPreferences(audio = "de")).audio)
    }

    @Test
    fun subtitlesFollowTheMode() {
        fun subtitle(media: MediaInfo, mode: SubtitleMode, audio: String? = TrackPreferences.INTERFACE) =
            plan(media, TrackPreferences(audio = audio, subtitles = mode)).pick.subtitle
        assertEquals("none", 0, subtitle(COLONY, SubtitleMode.OFF))
        assertEquals("French forced SRT, first", 1, subtitle(COLONY, SubtitleMode.FORCED))
        assertEquals("the film in French: forced only", 1, subtitle(COLONY, SubtitleMode.FOREIGN))
        assertEquals("in Korean: in full", 2, subtitle(COLONY, SubtitleMode.FOREIGN, audio = "ko"))
        assertEquals("full, not the hearing impaired's", 2, subtitle(DISCLOSURE_DAY, SubtitleMode.ALWAYS))
        assertNull(subtitle(COLONY, SubtitleMode.PLAYER))
    }

    /**
     * Bugonia in French: the player alone would show its first track, English forced subtitles. As the scan reads it,
     * from the file, as a server lists it: a file beside the video changes nothing to what is asked at once.
     */
    @Test
    fun noForcedTrackInTheLanguageMeansNone() {
        listOf(BUGONIA.copy(probedWith = PROBED), BUGONIA).forEach { bugonia ->
            assertEquals(0, plan(bugonia, french.copy(subtitles = SubtitleMode.FOREIGN)).pick.subtitle)
            assertEquals(0, plan(bugonia, french.copy(subtitles = SubtitleMode.FORCED)).pick.subtitle)
            assertEquals("in English: French in full", 6, plan(bugonia, TrackPreferences("en", SubtitleMode.FOREIGN)).pick.subtitle)
            val forced = TrackPreferences("en", SubtitleMode.FORCED)
            assertEquals("forced English, which go with the English audio", 1, plan(bugonia, forced).pick.subtitle)
        }
    }

    /**
     * A film in English with forced French, read from the file: forced English, which a file beside it may hold, would
     * come first; the forced French it has are asked meanwhile.
     */
    @Test
    fun theBestSubtitlesTheFileHasAreAskedWhileBetterOnesMayBeBesideIt() {
        val english = media(
            audio = listOf(AudioTrack(Codecs.AC3, 6, "en")),
            subtitles = listOf(SubtitleTrack("fr", format = Codecs.SRT, forced = true), SubtitleTrack("fr", format = Codecs.SRT)),
        ).copy(probedWith = PROBED)
        val p = plan(english, TrackPreferences("en", SubtitleMode.FORCED))
        assertEquals(1 to true, p.pick.subtitle to p.subtitleBeside)
    }

    /**
     * Bugonia read from the file, which says nothing of the files beside it (Bazarr's Bugonia.fr.forced.srt): the forced
     * French may be there, which the check looks for once the player lists them. A server lists what is beside: only
     * German leaves nothing to look for.
     */
    @Test
    fun aFileBesideTheVideoMayHoldTheSubtitlesTheFileLacks() {
        val foreign = french.copy(subtitles = SubtitleMode.FOREIGN)
        val read = BUGONIA.copy(probedWith = PROBED)
        assertTrue(plan(read, foreign).subtitleBeside)
        assertFalse("nothing beside it", plan(BUGONIA, foreign).subtitleBeside)
        assertEquals(listOf(SubtitleChoice.Track("fr", SubtitleKind.FORCED)), plan(read, foreign).wantedSubtitles("fr"))
        assertFalse("its own French in full, with English", plan(read, TrackPreferences("en", SubtitleMode.FOREIGN)).subtitleBeside)
        val listed = BUGONIA.copy(subtitles = BUGONIA.subtitles + SubtitleTrack("fre", format = Codecs.SRT, forced = true, external = true))
        assertEquals("the forced French listed beside it", 0 to true, plan(listed, foreign).let { it.pick.subtitle to it.subtitleBeside })
        val german = BUGONIA.copy(subtitles = BUGONIA.subtitles + SubtitleTrack("ger", format = Codecs.SRT, external = true))
        assertEquals(0 to false, plan(german, foreign).let { it.pick.subtitle to it.subtitleBeside })

        // French in full, which may be beside, comes before the file's forced English, asked meanwhile.
        val english = media(
            audio = listOf(AudioTrack(Codecs.AC3, 6, "en")),
            subtitles = listOf(SubtitleTrack("en", format = Codecs.SRT, forced = true), SubtitleTrack("en", format = Codecs.SRT)),
        )
        val always = TrackPreferences("en", SubtitleMode.ALWAYS)
        assertEquals(1 to true, plan(english.copy(probedWith = PROBED), always).let { it.pick.subtitle to it.subtitleBeside })
        assertEquals(1 to false, plan(english, always).let { it.pick.subtitle to it.subtitleBeside })
        val wanted = listOf(SubtitleKind.FULL to "fr", SubtitleKind.FORCED to "en", SubtitleKind.FORCED to "fr")
        assertEquals(wanted.map { (kind, language) -> SubtitleChoice.Track(language, kind) }, plan(english, always).wantedSubtitles("en"))
    }

    /** A title's choice the file lacks may be a file beside it, before Settings; left to the player, it stays the player's. */
    @Test
    fun aTitlesChoiceMayBeBesideTheVideo() {
        val read = COLONY.copy(probedWith = PROBED)
        val german = TrackChoice(subtitles = SubtitleChoice.Track("de", SubtitleKind.FULL))
        val forced = french.copy(subtitles = SubtitleMode.FORCED)
        val p = plan(read, forced, german)
        assertEquals("Settings' forced French meanwhile", TrackPick(0, 1) to true, p.pick to p.subtitleBeside)
        assertEquals(SubtitleChoice.Track("de", SubtitleKind.FULL), p.wantedSubtitles("fr")?.first())
        assertEquals("the player's", TrackPick(0, null) to false, plan(read, french, german).let { it.pick to it.subtitleBeside })
        assertEquals("nothing beside it: Settings", TrackPick(0, 1), plan(COLONY, forced, german).pick)
    }

    @Test
    fun theSubtitleLanguageIsItsOwnSetting() {
        val english = TrackPreferences(audio = "ko", subtitles = SubtitleMode.ALWAYS, subtitleLanguage = "eng")
        assertEquals(0, plan(COLONY, english).pick.subtitle)
        assertEquals(4, plan(DISCLOSURE_DAY, english).pick.subtitle)
    }

    @Test
    fun withoutTheAudioAskedTheFilesDefaultDecidesForeign() {
        val foreign = TrackPreferences(subtitles = SubtitleMode.FOREIGN)
        assertEquals("French by default: forced", 1, plan(COLONY, foreign).pick.subtitle)
        val unknown = media(audio = listOf(AudioTrack(Codecs.AC3, 6, "und")), subtitles = COLONY.subtitles)
        assertNull("an audio of unknown language may be foreign: the player chooses", plan(unknown, foreign).pick.subtitle)
    }

    /**
     * The audio left to the player, which follows its own setting before the file's default: French there, with an
     * English dub flagged default, makes the French audio heard and its forced subtitles fit, never the full ones.
     * Unset, it looks for the device's language, not the app's.
     */
    @Test
    fun thePlayersOwnAudioSettingComesBeforeTheFilesDefault() {
        val multi = media(
            audio = listOf(AudioTrack(Codecs.TRUEHD, 8, "en", default = true), AudioTrack(Codecs.AC3, 6, "fre")),
            subtitles = listOf(
                SubtitleTrack("en", format = Codecs.SRT, forced = true),
                SubtitleTrack("fre", format = Codecs.SRT, forced = true),
                SubtitleTrack("fre", format = Codecs.SRT),
            ),
        )
        fun subtitle(mode: SubtitleMode, player: String?, device: String = "fr") =
            TrackPlan(multi, TrackPreferences(subtitles = mode), null, "fr", "FR", "local:1", player, device).pick.subtitle
        assertEquals("French forced, the audio French", 2, subtitle(SubtitleMode.FOREIGN, "fr-FR"))
        assertEquals("forced in the audio's language", 2, subtitle(SubtitleMode.FORCED, "fr-FR"))
        assertEquals("set to the file's default: English, French in full", 3, subtitle(SubtitleMode.FOREIGN, TrackPlan.PLAYER_DEFAULT))
        assertEquals("set to the first track", 3, subtitle(SubtitleMode.FOREIGN, TrackPlan.PLAYER_FIRST))
        assertEquals("unset: the device's language", 2, subtitle(SubtitleMode.FOREIGN, null))
        assertEquals("a language the file lacks: the device's", 2, subtitle(SubtitleMode.FOREIGN, "de-DE"))
        assertEquals("unset, the device in English: French in full", 3, subtitle(SubtitleMode.FOREIGN, null, device = "en"))
    }

    /** A choice keeps a dub's region: the VFQ can be chosen where the device prefers the VFF. */
    @Test
    fun aDubsRegionIsPartOfTheChoice() {
        val dubs = media(
            audio = listOf(
                AudioTrack(Codecs.AC3, 6, "fre", "VFF"),
                AudioTrack(Codecs.AC3, 6, "fre", "VFQ"),
                AudioTrack(Codecs.TRUEHD, 8, "eng"),
            ),
            subtitles = listOf(SubtitleTrack("fre", "VFF", Codecs.SRT), SubtitleTrack("fre", "VFQ", Codecs.SRT)),
        )
        val p = plan(dubs, choice = TrackChoice("fr-CA", SubtitleChoice.Track("fr-CA", SubtitleKind.FULL)))
        assertEquals(TrackPick(1, 2), p.pick)
        assertEquals(listOf(0, 1, 2), p.audioOptions)
        assertEquals(listOf(1, 2), p.subtitleOptions)
        assertEquals("fr-CA", p.choiceOf(1, null).audio)
    }

    /** One row a choice can lead to: a commentary or a second track of the same kind is not one. */
    @Test
    fun theChoicesOfAFileAreTheTracksTheyLeadTo() {
        val tracks = media(
            audio = listOf(
                AudioTrack(Codecs.EAC3, 6, "fre", default = true),
                AudioTrack(Codecs.AC3, 2, "fre"),
                AudioTrack(Codecs.AC3, 2, "eng", "Commentary with the director"),
            ),
            subtitles = COLONY.subtitles,
        )
        val p = plan(tracks)
        assertEquals("the best French; no commentary", listOf(0), p.audioOptions)
        assertEquals("SRT forced, SRT full, SDH", listOf(1, 2, 5), p.subtitleOptions)
    }

    @Test
    fun norwegianIsOneLanguageHoweverTagged() {
        val tracks = media(audio = listOf(AudioTrack(Codecs.AC3, 6, "eng"), AudioTrack(Codecs.AC3, 6, "nob")))
        assertEquals(1, plan(tracks, TrackPreferences(audio = "no")).pick.audio)
        assertEquals("no", TrackPlan.known("nn-NO"))
    }

    @Test
    fun aServersExternalAudioIsNotNumbered() {
        val tracks = COLONY.copy(audio = listOf(AudioTrack(Codecs.AC3, 6, "fre", "Commentaire", external = true)) + COLONY.audio)
        assertEquals(TrackPick(1, 2), plan(tracks, TrackPreferences("ko", SubtitleMode.FOREIGN)).pick)
        assertEquals("ko", plan(tracks).choiceOf(1, null).audio)
    }

    @Test
    fun aTitlesChoiceComesBeforeSettings() {
        val vost = TrackChoice(audio = "ko", subtitles = SubtitleChoice.Track("fr", SubtitleKind.FULL))
        assertEquals(TrackPick(1, 2), plan(COLONY, choice = vost).pick)
        val always = french.copy(subtitles = SubtitleMode.ALWAYS)
        assertEquals(TrackPick(0, 0), plan(COLONY, always, TrackChoice(subtitles = SubtitleChoice.Off)).pick)
        val sdh = TrackChoice(subtitles = SubtitleChoice.Track("fr", SubtitleKind.SDH))
        assertEquals(5, plan(COLONY, choice = sdh).pick.subtitle)
        val german = TrackChoice(audio = "de", subtitles = SubtitleChoice.Track("de", SubtitleKind.FULL))
        val elsewhere = plan(COLONY, french.copy(subtitles = SubtitleMode.FORCED), german)
        assertEquals("missing from this episode: Settings", TrackPick(0, 1), elsewhere.pick)
        assertEquals(false to false, elsewhere.audioChosen to elsewhere.subtitleChosen)
    }

    @Test
    fun aServersExternalSubtitlesAreNotNumbered() {
        val external = SubtitleTrack("fre", format = Codecs.SRT, external = true)
        val tracks = COLONY.copy(subtitles = listOf(external) + COLONY.subtitles)
        assertEquals(2, plan(tracks, TrackPreferences("ko", SubtitleMode.FOREIGN)).pick.subtitle)
    }

    @Test
    fun whatThePlayersNumbersMean() {
        val p = plan(DISCLOSURE_DAY)
        // The VFF's region goes with it.
        assertEquals(TrackChoice("en", SubtitleChoice.Track("fr-FR", SubtitleKind.FULL)), p.choiceOf(1, 3))
        assertEquals(TrackChoice(null, SubtitleChoice.Off), p.choiceOf(null, 0))
        assertEquals(TrackChoice(null, SubtitleChoice.Track("fr-FR", SubtitleKind.FORCED)), p.choiceOf(null, 1))
        assertTrue("numbers it does not have", p.choiceOf(7, 9).isEmpty)
    }

    @Test
    fun aChoiceIsStoredAsText() {
        listOf(SubtitleChoice.Off, SubtitleChoice.Track("fr", SubtitleKind.FORCED), SubtitleChoice.Track("ko", SubtitleKind.SDH))
            .forEach { assertEquals(it, SubtitleChoice.parse(it.stored)) }
        assertNull(SubtitleChoice.parse("fr:LOUD"))
        assertNull(SubtitleChoice.parse(":FULL"))
        val vost = TrackChoice("ko", SubtitleChoice.Track("fr", SubtitleKind.FULL))
        assertEquals(TrackChoice("ko", SubtitleChoice.Off), vost.with(TrackChoice(subtitles = SubtitleChoice.Off)))
    }

    private companion object {
        /** A probe's version: tracks read from the file itself. */
        const val PROBED = 5

        fun media(audio: List<AudioTrack>, subtitles: List<SubtitleTrack> = emptyList()) =
            MediaInfo(container = "MKV", audio = audio, subtitles = subtitles, tracksInOrder = true)

        /** As the scan read them on 2026-10-22. */
        val BUGONIA = media(
            audio = listOf(
                AudioTrack(Codecs.TRUEHD, 8, "en"),
                AudioTrack(Codecs.AC3, 6, "en"),
                AudioTrack(Codecs.AC3, 6, "fr-FR", default = true),
            ),
            subtitles = listOf(
                SubtitleTrack("en-US", format = Codecs.SRT, forced = true),
                SubtitleTrack("en-US", format = Codecs.PGS, forced = true),
                SubtitleTrack("en-US", format = Codecs.SRT),
                SubtitleTrack("en-US", format = Codecs.SRT, hearingImpaired = true),
                SubtitleTrack("en-US", format = Codecs.PGS),
                SubtitleTrack("fr-FR", format = Codecs.SRT),
                SubtitleTrack("fr-FR", format = Codecs.PGS),
            ),
        )
        val COLONY = media(
            audio = listOf(AudioTrack(Codecs.EAC3, 6, "fr", default = true), AudioTrack(Codecs.EAC3, 6, "ko")),
            subtitles = listOf(
                SubtitleTrack("fr", format = Codecs.SRT, forced = true, default = true),
                SubtitleTrack("fr", format = Codecs.SRT),
                SubtitleTrack("fr", format = Codecs.PGS, forced = true),
                SubtitleTrack("fr", format = Codecs.PGS),
                SubtitleTrack("fr", format = Codecs.PGS, hearingImpaired = true),
            ),
        )

        /** Formats mixed: the player numbers them in the file's order all the same (checked on the Zidoo). */
        val DISCLOSURE_DAY = media(
            audio = listOf(AudioTrack(Codecs.EAC3, 8, "fr", default = true), AudioTrack(Codecs.TRUEHD, 8, "en")),
            subtitles = listOf(
                SubtitleTrack("fr", "VFF Forced", Codecs.PGS, forced = true, default = true),
                SubtitleTrack("fr", "VFF Full", Codecs.PGS),
                SubtitleTrack("fr", "VFF Full (backup)", Codecs.SRT),
                SubtitleTrack("en", "VO Anglaise SDH", Codecs.PGS, hearingImpaired = true),
            ),
        )
    }
}
