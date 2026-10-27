package app.houselights.core

/**
 * Subtitles as Settings ask for them. [PLAYER] leaves them to the player, which shows the file's first track when its
 * own setting matches none; [FOREIGN] shows them in full when the audio is in another language, else forced ones only.
 */
enum class SubtitleMode { PLAYER, OFF, FORCED, FOREIGN, ALWAYS }

/**
 * What Settings ask of every title. [audio] is a language code, [INTERFACE] for the app's language, or null to leave
 * the audio to the player; [subtitleLanguage] null means the app's language.
 */
data class TrackPreferences(
    val audio: String? = null,
    val subtitles: SubtitleMode = SubtitleMode.PLAYER,
    val subtitleLanguage: String? = null,
) {
    companion object {
        const val INTERFACE = "interface"
    }
}

/** A subtitle track's kind, as a title's choice keeps it. */
enum class SubtitleKind { FORCED, FULL, SDH }

/** Subtitles a title keeps: none, or a language (with its region, "fr-CA") and kind, so that it holds for every copy and episode. */
sealed interface SubtitleChoice {
    data object Off : SubtitleChoice

    data class Track(val language: String, val kind: SubtitleKind) : SubtitleChoice

    /** "off", or "fr:FORCED". */
    val stored: String
        get() = when (this) {
            Off -> OFF
            is Track -> "$language:$kind"
        }

    companion object {
        private const val OFF = "off"

        fun parse(stored: String): SubtitleChoice? = if (stored == OFF) {
            Off
        } else {
            val language = stored.substringBefore(':')
            val kind = SubtitleKind.entries.firstOrNull { it.name == stored.substringAfter(':') }
            kind?.takeIf { language.isNotEmpty() }?.let { Track(language, it) }
        }
    }
}

/** A title's own choice of tracks, by meaning rather than number: [audio] a language with its region ("fr-CA"); null follows Settings. */
data class TrackChoice(val audio: String? = null, val subtitles: SubtitleChoice? = null) {
    val isEmpty: Boolean get() = audio == null && subtitles == null

    /** This choice, with [changed]'s parts where it has them. */
    fun with(changed: TrackChoice) = TrackChoice(changed.audio ?: audio, changed.subtitles ?: subtitles)
}

/**
 * Tracks to ask the player for, numbered as the Zidoo player numbers them: [audio] from 0 among the audio tracks,
 * [subtitle] from 1 among the file's own subtitles, 0 for none. Null leaves that choice to the player.
 */
data class TrackPick(val audio: Int? = null, val subtitle: Int? = null) {
    val isEmpty: Boolean get() = audio == null && subtitle == null
}

/**
 * A file's tracks as asked of the player, from the title's [choice] where this file has it, else [preferences].
 * [media] lists its tracks in the player's order ([MediaInfo.tracksInOrder]); [language] is the app's ("fr") and
 * [region] the device's ("FR"), which a dub's region is matched against (VFF rather than VFQ).
 */
data class TrackPlan(
    val media: MediaInfo,
    val preferences: TrackPreferences,
    val choice: TrackChoice?,
    val language: String,
    val region: String?,
    /** Where the title's choice is kept ([PlaybackPlan.Launch.titleKey]); null when it is not kept. */
    val titleKey: String?,
    /**
     * The player's own audio setting, which it follows when asked no audio: a language ("fr-FR"), [PLAYER_FIRST] or
     * [PLAYER_DEFAULT]; null when unset, the player then looking for the device's language.
     */
    val playerAudio: String? = null,
    /** The device's language, the player's own, which it looks for when its setting finds no audio. */
    val deviceLanguage: String = language,
) {
    /** The file's own tracks, in the player's order: a server's files beside it are not among them. */
    val audioTracks: List<AudioTrack> = media.audio.filterNot { it.external }
    val subtitleTracks: List<SubtitleTrack> = media.subtitles.filterNot { it.external }

    private val chosenAudio: Int? = choice?.audio?.let(::audioFor)

    /** The player's number for the audio asked for (among the file's own); null leaves the audio to the player. */
    val audio: Int? = chosenAudio ?: preferences.audio
        ?.let { if (it == TrackPreferences.INTERFACE) language else known(it) }
        ?.let { bestAudio(it, null) }

    /** The title's choice decides this file's audio; else Settings do. */
    val audioChosen: Boolean get() = chosenAudio != null

    /** The language of the audio asked for; null when the audio is left to the player. */
    val audioLanguage: String? = audio?.let { known(audioTracks[it].language) }

    private val chosenSubtitle: Int? = when (val chosen = choice?.subtitles) {
        SubtitleChoice.Off -> NONE
        is SubtitleChoice.Track -> numberOf(chosen)
        null -> null
    }

    /** The title's choice decides this file's subtitles; else Settings do. */
    val subtitleChosen: Boolean get() = chosenSubtitle != null

    val pick = TrackPick(audio, subtitle(known(expectedAudio?.language)))

    /** Better subtitles than those asked may be a file beside the video, which the check looks for ([bestBeside]). */
    val subtitleBeside: Boolean = bestBeside(known(expectedAudio?.language)).isNotEmpty()

    /** One audio track for each choice the file offers, as its language and region lead to: no commentary, no twin. */
    val audioOptions: List<Int> get() = audioTracks.mapNotNull { t -> tagOf(t.language, t.title)?.let(::audioFor) }.distinct()

    /** One subtitle (the player's number) for each choice the file offers, as its language, region and kind lead to. */
    val subtitleOptions: List<Int>
        get() = subtitleTracks.mapNotNull { t -> subtitleChoiceOf(t)?.let(::numberOf) }.distinct()

    /**
     * The subtitles wanted once the audio is in [heard] (a language code, null if unknown), best first; none when
     * empty, null leaves them to the player. The title's choice comes first where the file, or a file beside it, may
     * have it; else Settings.
     */
    fun wantedSubtitles(heard: String?): List<SubtitleChoice.Track>? {
        val settings = bySettings(heard)
        return when (val chosen = choice?.subtitles) {
            SubtitleChoice.Off -> emptyList()
            is SubtitleChoice.Track -> when {
                numberOf(chosen) != null -> listOf(chosen)
                settings != null && mayBeBeside(chosen) -> listOf(chosen) + settings
                else -> settings
            }
            null -> settings
        }
    }

    private fun bySettings(heard: String?): List<SubtitleChoice.Track>? {
        val wanted = known(preferences.subtitleLanguage) ?: language
        val full = listOf(SubtitleChoice.Track(wanted, SubtitleKind.FULL))
        // Forced subtitles in the audio's language translate what it leaves in another; else in the language wanted.
        val forced = listOfNotNull(heard, wanted).distinct().map { SubtitleChoice.Track(it, SubtitleKind.FORCED) }
        return when (preferences.subtitles) {
            SubtitleMode.PLAYER -> null
            SubtitleMode.OFF -> emptyList()
            SubtitleMode.FORCED -> forced
            // An audio of unknown language may well be foreign: the player chooses.
            SubtitleMode.FOREIGN -> heard?.let { if (it != wanted) full + forced else forced }
            SubtitleMode.ALWAYS -> full + forced
        }
    }

    /**
     * The subtitle to ask for once the audio is in [heard] (a language code, null if unknown), in the player's
     * numbering: the first wanted the file has, else none. Null leaves it to the player.
     */
    fun subtitle(heard: String?): Int? = wantedSubtitles(heard)?.let { wanted -> wanted.firstNotNullOfOrNull(::numberOf) ?: NONE }

    /** The subtitles wanted with an audio in [heard] before the first the file has, which a file beside it may hold. */
    fun bestBeside(heard: String?): List<SubtitleChoice.Track> =
        wantedSubtitles(heard).orEmpty().takeWhile { numberOf(it) == null }.filter(::mayBeBeside)

    /**
     * Whether a file beside the video may hold [wanted]: any may when the tracks were read from the file itself, which
     * says nothing of the files beside it; a server lists them.
     */
    private fun mayBeBeside(wanted: SubtitleChoice.Track): Boolean = media.readFromFile ||
        media.subtitles.any { it.external && known(it.language) == known(wanted.language) && isOfKind(it.forced, wanted.kind) }

    /** The player's number for the file's own track for [wanted]; null when it has none. */
    private fun numberOf(wanted: SubtitleChoice.Track): Int? = trackFor(wanted)?.let { t -> subtitleTracks.indexOfFirst { it === t } + 1 }

    /** The file's subtitle the player numbers [number]; null for none or a number it does not have. */
    fun subtitleTrack(number: Int): SubtitleTrack? = subtitleTracks.getOrNull(number - 1)

    /** What the player's tracks [audio] and [subtitle] (its numbering) mean, each null when unknown or not given. */
    fun choiceOf(audio: Int?, subtitle: Int?) = TrackChoice(
        audio = audio?.let(audioTracks::getOrNull)?.let { tagOf(it.language, it.title) },
        subtitles = when (subtitle) {
            null -> null
            NONE -> SubtitleChoice.Off
            else -> subtitleTrack(subtitle)?.let(::subtitleChoiceOf)
        },
    )

    /** The audio as the player should play it: the track asked for, else the one it chooses by itself. */
    val expectedAudio: AudioTrack?
        get() = audio?.let(audioTracks::get) ?: playersOwnAudio()

    /**
     * The track the player plays when asked none, as its [playerAudio] setting leads it, else in [deviceLanguage], else
     * its first. Its memory of the file, if any, comes first and is not known here.
     */
    private fun playersOwnAudio(): AudioTrack? {
        val set = when (val setting = playerAudio) {
            null -> null
            PLAYER_FIRST -> audioTracks.firstOrNull()
            PLAYER_DEFAULT -> audioTracks.filter { it.default }.let { all -> all.firstOrNull(::inDeviceLanguage) ?: all.firstOrNull() }
            else -> known(setting)?.let { wanted -> audioTracks.firstOrNull { known(it.language) == wanted } }
        }
        return set ?: audioTracks.firstOrNull(::inDeviceLanguage) ?: audioTracks.firstOrNull()
    }

    private fun inDeviceLanguage(track: AudioTrack) = known(track.language) == known(deviceLanguage)

    /** The track a choice of [tag] ("fr", "fr-CA") leads to. */
    private fun audioFor(tag: String): Int? = known(tag)?.let { bestAudio(it, regionOf(tag)) }

    /**
     * The best track in [language], commentaries and audio descriptions aside; a dub of [wanted] region first, else of
     * the device's for its own language.
     */
    private fun bestAudio(language: String, wanted: String?): Int? {
        val tracks = audioTracks.withIndex()
            .filter { (_, t) -> known(t.language) == language && !EXTRA_AUDIO.containsMatchIn(t.title.orEmpty()) }
        val local = tracks.filter { (_, t) -> inRegion(language, wanted, t.language, t.title) }.ifEmpty { tracks }
        val best = Formats.bestTrack(local.map { it.value }) ?: return null
        return local.first { it.value === best }.index
    }

    private fun trackFor(chosen: SubtitleChoice.Track): SubtitleTrack? {
        val language = known(chosen.language) ?: return null
        val wanted = regionOf(chosen.language)
        return when (chosen.kind) {
            SubtitleKind.FORCED -> inLanguage(language, wanted).firstOrNull { it.forced }
            SubtitleKind.FULL -> full(language, wanted)
            SubtitleKind.SDH -> inLanguage(language, wanted).firstOrNull { !it.forced && it.hearingImpaired } ?: full(language, wanted)
        }
    }

    /** Full subtitles in [language], for the hearing impaired only if there are no others. */
    private fun full(language: String, region: String?): SubtitleTrack? {
        val full = inLanguage(language, region).filterNot { it.forced }
        return full.firstOrNull { !it.hearingImpaired } ?: full.firstOrNull()
    }

    /** The file's subtitles in [language], those of [wanted] region first, else of the device's for its own language. */
    private fun inLanguage(language: String, wanted: String?): List<SubtitleTrack> =
        subtitleTracks.filter { known(it.language) == language }.sortedByDescending { inRegion(language, wanted, it.language, it.title) }

    private fun inRegion(language: String, wanted: String?, tag: String?, title: String?): Boolean {
        val region = wanted ?: region.takeIf { language == this.language } ?: return false
        return TrackNames.region(tag, title) == region
    }

    companion object {
        /** The player's number for no subtitles. */
        const val NONE = 0

        /** [playerAudio] set to the file's first track, or to its default one. */
        const val PLAYER_FIRST = "first"
        const val PLAYER_DEFAULT = "default"

        /** A track's language as a two-letter code, Norwegian's written forms as one; null when unknown ("und"). */
        fun known(code: String?): String? = Languages.code(code)?.let { NORWEGIAN[it] ?: it }?.takeUnless { it == UNDETERMINED }

        /** What choosing [track] keeps: its language and region, and its kind. */
        fun subtitleChoiceOf(track: SubtitleTrack): SubtitleChoice.Track? =
            tagOf(track.language, track.title)?.let { SubtitleChoice.Track(it, kindOf(track)) }

        /** A track's language with its region when known: "fr-CA" for a VFQ, else "fr"; null when unknown. */
        fun tagOf(language: String?, title: String?): String? =
            known(language)?.let { code -> TrackNames.region(language, title)?.let { "$code-$it" } ?: code }

        fun kindOf(track: SubtitleTrack): SubtitleKind = kindOf(track.forced, track.hearingImpaired)

        /** Whether a track, [forced] or not, is of [kind]: hearing-impaired subtitles count as full ones. */
        fun isOfKind(forced: Boolean, kind: SubtitleKind): Boolean = forced == (kind == SubtitleKind.FORCED)

        fun kindOf(forced: Boolean, hearingImpaired: Boolean): SubtitleKind = when {
            forced -> SubtitleKind.FORCED
            hearingImpaired -> SubtitleKind.SDH
            else -> SubtitleKind.FULL
        }

        /** The region of a tag ("fr-CA": "CA"); null without one. */
        private fun regionOf(tag: String): String? = tag.substringAfter('-', "").uppercase().takeIf { it.length == 2 }

        private const val UNDETERMINED = "und"
        private val NORWEGIAN = mapOf("nb" to "no", "nn" to "no")

        /** Commentaries and audio descriptions, by their titles in English, French, Spanish, Italian… */
        private val EXTRA_AUDIO = Regex("""comm?ent|descri""", RegexOption.IGNORE_CASE)
    }
}
