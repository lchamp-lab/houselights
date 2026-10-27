package app.houselights.core

/**
 * A file's technical sheet: ordered sections, unknown facts left out. Case words come verbatim ([TechValue.Word]);
 * measures keep their type so the screen formats them in its language.
 */
data class TechSheet(val sections: List<TechSection>) {
    companion object {
        /** The sheet of [media]: picture, sound, subtitles and file ([fileName] when known). */
        fun of(media: MediaInfo, fileName: String? = null): TechSheet = TechSheet(
            listOfNotNull(picture(media.video), sound(media.audio), subtitles(media.subtitles), file(media, fileName)),
        )

        /** The picture rows that are known; the Dolby Vision profile and base layer go under the ranges. */
        private fun picture(video: VideoInfo?): TechSection? {
            val v = video?.takeIf { it.codec != Codecs.UNKNOWN || it.width != null } ?: return null
            val size = if (v.width != null && v.height != null) TechValue.Dimensions(v.width, v.height) else null
            val codec = listOfNotNull(v.codec.takeIf { it != Codecs.UNKNOWN }?.let(TechValue::Word), v.bitDepth?.let(TechValue::BitDepth))
            val rows = listOfNotNull(
                v.definition?.let { fact(TechLabel.DEFINITION, listOfNotNull(TechValue.Word(it.label), size)) },
                v.aspectRatio?.let { fact(TechLabel.ASPECT_RATIO, listOf(TechValue.Ratio(it))) },
                fact(
                    TechLabel.DYNAMIC_RANGE,
                    Formats.ranges(v).map(TechValue::Word).ifEmpty { listOf(TechValue.StandardRange) },
                    detail = dolbyVision(v),
                ),
                codec.takeIf { it.isNotEmpty() }?.let { fact(TechLabel.CODEC, it) },
                v.frameRate?.let { fact(TechLabel.FRAME_RATE, listOf(TechValue.FrameRate(it))) },
                v.bitrateKbps?.let { fact(TechLabel.BITRATE, listOf(TechValue.Megabits(it.toLong()))) },
            )
            return TechSection(TechTopic.PICTURE, rows)
        }

        /** Detail under Dolby Vision: its profile ("8.1") and its base layer's range. */
        private fun dolbyVision(v: VideoInfo): List<TechValue> {
            if (HdrFormat.DOLBY_VISION !in v.hdr) return emptyList()
            val compatibility = v.dolbyVisionCompatibility
            val profile = v.dolbyVisionProfile?.let { p ->
                // Profile 8 is qualified by its compatibility id: 8.1, 8.2, 8.4.
                if (p == DV_PROFILE_8 && compatibility in DV_SUBPROFILES) "$p.$compatibility" else "$p"
            }
            val base = when (compatibility) {
                DV_BASE_HDR10, DV_BASE_PQ -> BaseRange.HDR10
                DV_BASE_SDR -> BaseRange.SDR
                DV_BASE_HLG -> BaseRange.HLG
                DV_BASE_NONE -> null
                else -> when {
                    HdrFormat.HDR10 in v.hdr || v.dolbyVisionProfile == DV_PROFILE_7 -> BaseRange.HDR10
                    HdrFormat.HLG in v.hdr -> BaseRange.HLG
                    else -> null
                }
            }
            return listOfNotNull(profile?.let(TechValue::DolbyVisionProfile), base?.let(TechValue::BaseLayer))
        }

        /** Audio tracks, the one the player starts with (default, else first) first and in use. */
        private fun sound(audio: List<AudioTrack>): TechSection? {
            if (audio.isEmpty()) return null
            val ordered = audio.sortedByDescending { it.default }
            val regions = regions(ordered.map { it.language to it.title })
            val rows = ordered.mapIndexed { i, t ->
                TechRow.Track(
                    language = t.language,
                    region = regions[i],
                    inUse = i == 0,
                    value = listOfNotNull(TechValue.Word(Formats.soundName(t)), t.layout?.let(TechValue::Channels)),
                    detail = listOfNotNull(t.bitrateKbps?.let(TechValue::Kilobits)),
                    note = TrackNames.note(t.title, t.language),
                )
            }
            return TechSection(TechTopic.SOUND, rows, tracks = audio.size)
        }

        /** The subtitles, the default first and marked in use (none when there is no default): format, forced, SDH. */
        private fun subtitles(subtitles: List<SubtitleTrack>): TechSection? {
            if (subtitles.isEmpty()) return null
            val ordered = subtitles.sortedByDescending { it.default }
            val regions = regions(ordered.map { it.language to it.title })
            val rows = ordered.mapIndexed { i, t ->
                TechRow.Track(
                    language = t.language,
                    region = regions[i],
                    inUse = i == 0 && t.default,
                    value = listOfNotNull(
                        t.format.takeIf { it != Codecs.UNKNOWN }?.let(TechValue::Word),
                        TechValue.Forced.takeIf { t.forced },
                        TechValue.HearingImpaired.takeIf { t.hearingImpaired },
                    ),
                )
            }
            return TechSection(TechTopic.SUBTITLES, rows, tracks = subtitles.size)
        }

        /** The file: its container, size, average bitrate, duration, and its name when known. */
        private fun file(media: MediaInfo, fileName: String?): TechSection? {
            val rows = listOfNotNull(
                media.container?.let { fact(TechLabel.CONTAINER, listOf(TechValue.Word(it))) },
                media.sizeBytes?.let { fact(TechLabel.SIZE, listOf(TechValue.Bytes(it))) },
                media.bitrateKbps?.let { fact(TechLabel.AVERAGE_BITRATE, listOf(TechValue.Megabits(it))) },
                media.durationMs?.let { fact(TechLabel.DURATION, listOf(TechValue.Duration(it))) },
                fileName?.takeIf { it.isNotBlank() }?.let { fact(TechLabel.NAME, listOf(TechValue.Word(it))) },
            )
            return rows.takeIf { it.isNotEmpty() }?.let { TechSection(TechTopic.FILE, it) }
        }

        /** Each track's region, only where one language comes from several regions (France and Québec French). */
        private fun regions(tracks: List<Pair<String?, String?>>): List<String?> {
            val languages = tracks.map { (code, _) -> Languages.code(code) }
            val regions = tracks.map { (code, title) -> TrackNames.region(code, title) }
            return tracks.indices.map { i ->
                val alike = tracks.indices.filter { languages[i] != null && languages[it] == languages[i] }
                regions[i].takeIf { alike.size > 1 && alike.map(regions::get).distinct().size > 1 }
            }
        }

        private fun fact(label: TechLabel, value: List<TechValue>, detail: List<TechValue> = emptyList()) =
            TechRow.Fact(label, value, detail)

        private const val DV_PROFILE_7 = 7
        private const val DV_PROFILE_8 = 8

        /** Dolby Vision compatibility ids, the base layer's range: none, HDR10, SDR, HLG, PQ. */
        private const val DV_BASE_NONE = 0
        private const val DV_BASE_HDR10 = 1
        private const val DV_BASE_SDR = 2
        private const val DV_BASE_HLG = 4
        private const val DV_BASE_PQ = 6
        private val DV_SUBPROFILES = setOf(DV_BASE_HDR10, DV_BASE_SDR, DV_BASE_HLG)
    }
}

enum class TechTopic { PICTURE, SOUND, SUBTITLES, FILE }

/** A sheet section: its [topic], its [rows], and for sound and subtitles the number of [tracks]. */
data class TechSection(val topic: TechTopic, val rows: List<TechRow>, val tracks: Int? = null)

enum class TechLabel {
    DEFINITION, ASPECT_RATIO, DYNAMIC_RANGE, CODEC, FRAME_RATE, BITRATE,
    CONTAINER, SIZE, AVERAGE_BITRATE, DURATION, NAME,
}

sealed interface TechRow {
    /** A fact: its [label], its [value], and what qualifies it ([detail]). */
    data class Fact(val label: TechLabel, val value: List<TechValue>, val detail: List<TechValue> = emptyList()) : TechRow

    /** A track: [language] as written, [region] when ambiguous, [inUse] for the one the player starts with. */
    data class Track(
        val language: String?,
        val region: String?,
        val inUse: Boolean,
        val value: List<TechValue>,
        val detail: List<TechValue> = emptyList(),
        val note: String? = null,
    ) : TechRow
}

/** The range a Dolby Vision base layer shows on a display without Dolby Vision. */
enum class BaseRange { HDR10, HLG, SDR }

/** A sheet value, formatted by the screen. */
sealed interface TechValue {
    /** A word a case prints as it is: Ultra HD, HEVC, Dolby Vision, PGS, MKV, a file name. */
    data class Word(val text: String) : TechValue

    /** A track's channels as a case counts them: 7.1, 5.1, 2.0. */
    data class Channels(val layout: String) : TechValue
    data class Dimensions(val width: Int, val height: Int) : TechValue
    data class Ratio(val value: Float) : TechValue
    data class BitDepth(val bits: Int) : TechValue
    data class FrameRate(val fps: Float) : TechValue
    data class Megabits(val kbps: Long) : TechValue
    data class Kilobits(val kbps: Int) : TechValue
    data class Bytes(val bytes: Long) : TechValue
    data class Duration(val ms: Long) : TechValue
    data class DolbyVisionProfile(val name: String) : TechValue
    data class BaseLayer(val range: BaseRange) : TechValue

    /** No high dynamic range: SDR. */
    data object StandardRange : TechValue
    data object Forced : TechValue
    data object HearingImpaired : TechValue
}
