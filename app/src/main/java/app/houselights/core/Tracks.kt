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

/** Subtitles a title keeps: none, or a language and kind, so that the choice holds for every copy and episode. */
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

        fun parse(stored: String): SubtitleChoice? = if (stored == OFF) Off else {
            val language = stored.substringBefore(':')
            val kind = SubtitleKind.entries.firstOrNull { it.name == stored.substringAfter(':') }
            kind?.takeIf { language.isNotEmpty() }?.let { Track(language, it) }
        }
    }
}

/** A title's own choice of tracks, by meaning rather than number; a null part follows Settings. */
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
 * A file's tracks as asked of the player, from the title's [choice], else [preferences]. [media] lists its tracks in
 * the player's order ([MediaInfo.tracksInOrder]); [language] is the app's ("fr") and [region] the device's ("FR"),
 * which a dub's region is matched against (VFF rather than VFQ).
 */
data class TrackPlan(
    val media: MediaInfo,
    val preferences: TrackPreferences,
    val choice: TrackChoice?,
    val language: String,
    val region: String?,
    /** Where the title's choice is kept ([PlaybackPlan.Launch.titleKey]); null when it is not kept. */
    val titleKey: String?,
) {
    /** The file's own tracks, which the player numbers: a server's files beside it are not among them. */
    private val audioTracks = media.audio.filterNot { it.external }
    private val subtitles = media.subtitles.filterNot { it.external }

    /** The audio language asked for: the title's, else Settings'; null leaves the audio to the player. */
    val audioLanguage: String? =
        known(choice?.audio ?: preferences.audio?.let { if (it == TrackPreferences.INTERFACE) language else it })

    /** The player's number for the audio track asked for (among the file's own); null when none is in [audioLanguage]. */
    val audio: Int? = audioLanguage?.let(::bestAudio)

    val pick = TrackPick(audio, subtitle(expectedAudio()))

    /**
     * The subtitle to ask for once the audio is in [heard] (a language code, null if unknown), in the player's
     * numbering; null leaves it to the player. A title's choice missing from this file means none.
     */
    fun subtitle(heard: String?): Int? {
        when (val chosen = choice?.subtitles) {
            SubtitleChoice.Off -> return NONE
            is SubtitleChoice.Track -> return number(chosen(chosen))
            null -> Unit
        }
        val wanted = known(preferences.subtitleLanguage) ?: language
        return when (preferences.subtitles) {
            SubtitleMode.PLAYER -> null
            SubtitleMode.OFF -> NONE
            SubtitleMode.FORCED -> number(forced(heard, wanted))
            // An audio of unknown language may well be foreign: the player chooses.
            SubtitleMode.FOREIGN -> heard?.let { number((if (it != wanted) full(wanted) else null) ?: forced(it, wanted)) }
            SubtitleMode.ALWAYS -> number(full(wanted) ?: forced(heard, wanted))
        }
    }

    /** The file's subtitle the player numbers [number]; null for none or a number it does not have. */
    fun subtitleTrack(number: Int): SubtitleTrack? = subtitles.getOrNull(number - 1)

    /** What the player's tracks [audio] and [subtitle] (its numbering) mean, each null when unknown or not given. */
    fun choiceOf(audio: Int?, subtitle: Int?) = TrackChoice(
        audio = audio?.let(audioTracks::getOrNull)?.let { known(it.language) },
        subtitles = when (subtitle) {
            null -> null
            NONE -> SubtitleChoice.Off
            else -> subtitleTrack(subtitle)?.let { t -> known(t.language)?.let { SubtitleChoice.Track(it, kindOf(t)) } }
        },
    )

    /** The audio's language as the player should play it: the track asked for, else the file's default, else its first. */
    private fun expectedAudio(): String? =
        known((audio?.let(audioTracks::get) ?: audioTracks.firstOrNull { it.default } ?: audioTracks.firstOrNull())?.language)

    /** The best track in [language], commentaries and audio descriptions aside, a dub of the device's region first. */
    private fun bestAudio(language: String): Int? {
        val tracks = audioTracks.withIndex()
            .filter { (_, t) -> known(t.language) == language && !EXTRA_AUDIO.containsMatchIn(t.title.orEmpty()) }
        val local = tracks.filter { (_, t) -> regional(language, t.language, t.title) }.ifEmpty { tracks }
        val best = Formats.bestTrack(local.map { it.value }) ?: return null
        return local.first { it.value === best }.index
    }

    private fun chosen(chosen: SubtitleChoice.Track): SubtitleTrack? = when (chosen.kind) {
        SubtitleKind.FORCED -> forced(chosen.language, chosen.language)
        SubtitleKind.FULL -> full(chosen.language)
        SubtitleKind.SDH -> inLanguage(chosen.language).firstOrNull { !it.forced && it.hearingImpaired } ?: full(chosen.language)
    }

    /** Forced subtitles in the audio's language, which translate what it leaves in another, else in [wanted]. */
    private fun forced(heard: String?, wanted: String): SubtitleTrack? =
        listOfNotNull(heard, wanted).firstNotNullOfOrNull { language -> inLanguage(language).firstOrNull { it.forced } }

    /** Full subtitles in [language], for the hearing impaired only if there are no others. */
    private fun full(language: String): SubtitleTrack? {
        val full = inLanguage(language).filterNot { it.forced }
        return full.firstOrNull { !it.hearingImpaired } ?: full.firstOrNull()
    }

    /** The file's subtitles in [language], a dub of the device's region first. */
    private fun inLanguage(language: String): List<SubtitleTrack> {
        val all = subtitles.filter { known(it.language) == language }
        return all.sortedByDescending { regional(language, it.language, it.title) }
    }

    private fun regional(language: String, tag: String?, title: String?): Boolean =
        region != null && language == this.language && TrackNames.region(tag, title) == region

    private fun number(track: SubtitleTrack?): Int = track?.let { t -> subtitles.indexOfFirst { it === t } + 1 } ?: NONE

    companion object {
        /** The player's number for no subtitles. */
        const val NONE = 0

        /** A track's language as a two-letter code; null when unknown ("und"). */
        fun known(code: String?): String? = Languages.code(code)?.takeUnless { it == UNDETERMINED }

        fun kindOf(track: SubtitleTrack): SubtitleKind = kindOf(track.forced, track.hearingImpaired)

        fun kindOf(forced: Boolean, hearingImpaired: Boolean): SubtitleKind = when {
            forced -> SubtitleKind.FORCED
            hearingImpaired -> SubtitleKind.SDH
            else -> SubtitleKind.FULL
        }

        private const val UNDETERMINED = "und"

        /** Commentaries and audio descriptions, by their titles in English, French, Spanish, Italian… */
        private val EXTRA_AUDIO = Regex("""comm?ent|descri""", RegexOption.IGNORE_CASE)
    }
}
