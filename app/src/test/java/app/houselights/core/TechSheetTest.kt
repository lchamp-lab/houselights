package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a technical sheet sets, section by section: only what is known, in the order it is read. */
class TechSheetTest {
    private fun section(sheet: TechSheet, topic: TechTopic) = sheet.sections.single { it.topic == topic }

    private fun facts(sheet: TechSheet, topic: TechTopic) = section(sheet, topic).rows.map { it as TechRow.Fact }

    private fun tracks(sheet: TechSheet, topic: TechTopic) = section(sheet, topic).rows.map { it as TechRow.Track }

    private fun range(video: VideoInfo) =
        facts(TechSheet.of(MediaInfo(video = video)), TechTopic.PICTURE).single { it.label == TechLabel.DYNAMIC_RANGE }

    private fun dolbyVision(profile: Int?, compatibility: Int?, vararg also: HdrFormat) = VideoInfo(
        Codecs.HEVC,
        hdr = listOf(HdrFormat.DOLBY_VISION) + also,
        dolbyVisionProfile = profile,
        dolbyVisionCompatibility = compatibility,
    )

    private fun profile(name: String) = TechValue.DolbyVisionProfile(name)

    private fun base(range: BaseRange) = TechValue.BaseLayer(range)

    @Test
    fun dolbyVisionSaysItsProfileAndTheBaseItPlaysAsElsewhere() {
        assertEquals(listOf(profile("8.1"), base(BaseRange.HDR10)), range(dolbyVision(8, 1, HdrFormat.HDR10)).detail)
        assertEquals(listOf(profile("8.4"), base(BaseRange.HLG)), range(dolbyVision(8, 4, HdrFormat.HLG)).detail)
        assertEquals(listOf(profile("8.2"), base(BaseRange.SDR)), range(dolbyVision(8, 2)).detail)
        // Profile 5 has no base to fall back on; profile 7 is a Blu-ray's, on HDR10.
        assertEquals(listOf(profile("5")), range(dolbyVision(5, 0)).detail)
        assertEquals(listOf(profile("7"), base(BaseRange.HDR10)), range(dolbyVision(7, 6)).detail)
        assertEquals(listOf(profile("7"), base(BaseRange.HDR10)), range(dolbyVision(7, null)).detail)
        // An unknown compatibility reads the base from the ranges found in the stream.
        assertEquals(listOf(profile("8"), base(BaseRange.HDR10)), range(dolbyVision(8, null, HdrFormat.HDR10)).detail)
        assertEquals(listOf(base(BaseRange.HLG)), range(dolbyVision(null, null, HdrFormat.HLG)).detail)
    }

    @Test
    fun rangesGoByTheirNamesOnTheCase() {
        val both = range(dolbyVision(8, 1, HdrFormat.HDR10_PLUS, HdrFormat.HDR10))
        assertEquals(listOf(TechValue.Word("Dolby Vision"), TechValue.Word("HDR10+")), both.value)
        val hdr = range(VideoInfo(Codecs.HEVC, hdr = listOf(HdrFormat.HDR10_PLUS, HdrFormat.HDR10)))
        assertEquals(listOf(TechValue.Word("HDR10+"), TechValue.Word("HDR10")), hdr.value)
        assertEquals(emptyList<TechValue>(), hdr.detail)
        val sdr = range(VideoInfo(Codecs.AVC, 1920, 1080))
        assertEquals(listOf(TechValue.StandardRange), sdr.value)
    }

    @Test
    fun thePictureIsReadInOrderAndOnlyWhatIsKnown() {
        val full = VideoInfo(
            Codecs.HEVC, 3840, 1608, 10, listOf(HdrFormat.HDR10),
            frameRate = 24000f / 1001f, bitrateKbps = 6_500,
        )
        val rows = facts(TechSheet.of(MediaInfo(video = full)), TechTopic.PICTURE)
        assertEquals(
            listOf(
                TechLabel.DEFINITION, TechLabel.ASPECT_RATIO, TechLabel.DYNAMIC_RANGE,
                TechLabel.CODEC, TechLabel.FRAME_RATE, TechLabel.BITRATE,
            ),
            rows.map { it.label },
        )
        assertEquals(listOf(TechValue.Word("Ultra HD"), TechValue.Dimensions(3840, 1608)), rows[0].value)
        assertEquals(listOf(TechValue.Word("HEVC"), TechValue.BitDepth(10)), rows[3].value)
        assertEquals(listOf(TechValue.Megabits(6_500)), rows[5].value)

        val lean = facts(TechSheet.of(MediaInfo(video = VideoInfo(Codecs.AVC))), TechTopic.PICTURE)
        assertEquals(listOf(TechLabel.DYNAMIC_RANGE, TechLabel.CODEC), lean.map { it.label })
        assertEquals(listOf(TechValue.Word("AVC")), lean[1].value)
    }

    @Test
    fun anUnknownPictureHasNoSection() {
        val sheet = TechSheet.of(MediaInfo(container = "ISO", video = VideoInfo(Codecs.UNKNOWN)))
        assertEquals(listOf(TechTopic.FILE), sheet.sections.map { it.topic })
        assertEquals(emptyList<TechSection>(), TechSheet.of(MediaInfo()).sections)
    }

    @Test
    fun theDefaultTrackComesFirstAndIsInUse() {
        val sheet = TechSheet.of(
            MediaInfo(
                audio = listOf(
                    AudioTrack(Codecs.TRUEHD, 8, "eng", atmos = true, bitrateKbps = 2_857),
                    AudioTrack(Codecs.EAC3, 6, "fre", atmos = true, default = true, bitrateKbps = 768),
                    AudioTrack(Codecs.AC3, 6, "eng", bitrateKbps = 448),
                ),
                subtitles = listOf(
                    SubtitleTrack("eng", format = Codecs.PGS),
                    SubtitleTrack("fre", format = Codecs.SRT, forced = true, default = true),
                ),
            ),
        )
        val sound = tracks(sheet, TechTopic.SOUND)
        assertEquals(listOf("fre", "eng", "eng"), sound.map { it.language })
        assertEquals(listOf(true, false, false), sound.map { it.inUse })
        assertEquals(listOf(TechValue.Word("Dolby Digital Plus Atmos"), TechValue.Word("5.1")), sound[0].value)
        assertEquals(listOf(TechValue.Kilobits(768)), sound[0].detail)
        // Tracks that are not the default keep the file's order.
        assertEquals(listOf(TechValue.Word("Dolby TrueHD Atmos"), TechValue.Word("7.1")), sound[1].value)
        val subtitles = tracks(sheet, TechTopic.SUBTITLES)
        assertEquals(listOf("fre", "eng"), subtitles.map { it.language })
        assertEquals(listOf(true, false), subtitles.map { it.inUse })
    }

    @Test
    fun withoutADefaultThePlayerStartsTheFirstSoundAndNoSubtitles() {
        val sheet = TechSheet.of(
            MediaInfo(
                audio = listOf(AudioTrack(Codecs.DTS, 6, "eng"), AudioTrack(Codecs.AC3, 2, "fre")),
                subtitles = listOf(SubtitleTrack("eng", format = Codecs.SRT), SubtitleTrack("fre", format = Codecs.SRT)),
            ),
        )
        assertEquals(listOf(true, false), tracks(sheet, TechTopic.SOUND).map { it.inUse })
        assertEquals(listOf(false, false), tracks(sheet, TechTopic.SUBTITLES).map { it.inUse })
    }

    @Test
    fun aRegionIsSaidOnlyWhereTwoTracksShareALanguage() {
        fun regions(vararg tracks: Pair<String?, String?>) = tracks(
            TechSheet.of(MediaInfo(audio = tracks.map { (language, title) -> AudioTrack(Codecs.AC3, 6, language, title) })),
            TechTopic.SOUND,
        ).map { it.region }
        assertEquals(listOf("FR", "CA"), regions("fr-FR" to null, "fr-CA" to null))
        assertEquals(listOf("FR", "CA"), regions("fr-FR" to null, "fre" to "VFQ"))
        // One French track with a region is unambiguous: its language is enough.
        assertEquals(listOf(null, null), regions("fr-FR" to null, "en-US" to null))
        // Two French tracks, neither with a region: nothing tells them apart.
        assertEquals(listOf(null, null), regions("fre" to null, "fra" to null))
        // A region on one track still tells it from a track without one.
        assertEquals(listOf("CA", null), regions("fr-CA" to null, "fre" to null))
        assertEquals(listOf(null, null), regions(null to null, null to null))
        // Welsh by its bibliographic code is Welsh still: the screen names both "Gallois".
        assertEquals(listOf("GB", null), regions("cy-GB" to null, "wel" to null))
    }

    @Test
    fun aTitleIsANoteOnlyWhenItSaysMore() {
        val sound = tracks(
            TechSheet.of(
                MediaInfo(
                    audio = listOf(
                        AudioTrack(Codecs.AC3, 6, "eng", "Compatibility Track"),
                        AudioTrack(Codecs.AC3, 6, "fre", "VFF  DD 5.1  384 kbps"),
                    ),
                ),
            ),
            TechTopic.SOUND,
        )
        assertEquals(listOf("Compatibility Track", null), sound.map { it.note })
    }

    @Test
    fun subtitlesSayTheirFormatThenWhetherForcedOrForTheDeaf() {
        val sheet = TechSheet.of(
            MediaInfo(
                subtitles = listOf(
                    SubtitleTrack("fre", format = Codecs.SRT, forced = true),
                    SubtitleTrack("fre", format = Codecs.PGS, hearingImpaired = true),
                    SubtitleTrack(format = Codecs.UNKNOWN),
                ),
            ),
        )
        val rows = tracks(sheet, TechTopic.SUBTITLES)
        assertEquals(listOf(TechValue.Word("SRT"), TechValue.Forced), rows[0].value)
        assertEquals(listOf(TechValue.Word("PGS"), TechValue.HearingImpaired), rows[1].value)
        assertEquals(emptyList<TechValue>(), rows[2].value)
        assertNull(rows[2].language)
        assertEquals(3, section(sheet, TechTopic.SUBTITLES).tracks)
    }

    @Test
    fun sectionsAreReadInOrderAndCountTheirTracks() {
        val sheet = TechSheet.of(
            MediaInfo(
                container = "MKV",
                video = VideoInfo(Codecs.HEVC, 1920, 1080),
                audio = listOf(AudioTrack(Codecs.AAC, 2, "fre")),
                subtitles = listOf(SubtitleTrack("fre", format = Codecs.SRT), SubtitleTrack("eng", format = Codecs.SRT)),
            ),
        )
        assertEquals(listOf(TechTopic.PICTURE, TechTopic.SOUND, TechTopic.SUBTITLES, TechTopic.FILE), sheet.sections.map { it.topic })
        assertEquals(listOf(null, 1, 2, null), sheet.sections.map { it.tracks })
    }

    @Test
    fun theFileSaysWhatIsKnownOfIt() {
        val media = MediaInfo(container = "MKV", sizeBytes = 9_310_000_000, durationMs = 7_080_000)
        val local = facts(TechSheet.of(media, "Bugonia.mkv"), TechTopic.FILE)
        assertEquals(
            listOf(TechLabel.CONTAINER, TechLabel.SIZE, TechLabel.AVERAGE_BITRATE, TechLabel.DURATION, TechLabel.NAME),
            local.map { it.label },
        )
        assertEquals(listOf(TechValue.Word("Bugonia.mkv")), local.last().value)
        // From a server: no file name.
        val served = facts(TechSheet.of(media), TechTopic.FILE)
        assertFalse(served.any { it.label == TechLabel.NAME })
        assertTrue(facts(TechSheet.of(media, " "), TechTopic.FILE).none { it.label == TechLabel.NAME })
    }

    @Test
    fun aSparseFileKeepsOnlyWhatItKnows() {
        val sheet = TechSheet.of(
            MediaInfo(
                container = "ISO",
                audio = listOf(AudioTrack(Codecs.DTS, 6)),
                subtitles = listOf(SubtitleTrack(format = Codecs.VOBSUB)),
            ),
        )
        assertEquals(listOf(TechTopic.SOUND, TechTopic.SUBTITLES, TechTopic.FILE), sheet.sections.map { it.topic })
        val sound = tracks(sheet, TechTopic.SOUND).single()
        assertNull(sound.language)
        assertNull(sound.region)
        assertEquals(emptyList<TechValue>(), sound.detail)
        assertEquals(listOf(TechLabel.CONTAINER), facts(sheet, TechTopic.FILE).map { it.label })
    }
}
