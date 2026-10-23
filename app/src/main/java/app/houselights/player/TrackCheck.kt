package app.houselights.player

import app.houselights.core.SubtitleChoice
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan

/** A track to switch the player to: its number in the player's list ([PlayerTrack.index]). */
data class TrackFix(val kind: TrackKind, val index: Int)

/**
 * Checks, once the film plays, that the player plays what [TrackPlan] asked: its numbering could differ from the
 * file's (a server's external subtitles), and the audio it chose by itself decides the subtitles of some modes.
 */
object TrackCheck {
    /** At most one fix per kind, so that what the player [plays] matches [plan]; tracks matched by language and kind. */
    fun fixes(plan: TrackPlan, plays: PlayerTracks): List<TrackFix> = buildList {
        var heard = TrackPlan.known(plays.audio.firstOrNull { it.index == plays.audioIndex }?.language)
        val audio = plan.audioLanguage?.takeIf { plan.audio != null && it != heard }
            ?.let { wanted -> plays.audio.firstOrNull { TrackPlan.known(it.language) == wanted } }
        if (audio != null) {
            add(TrackFix(TrackKind.AUDIO, audio.index))
            heard = TrackPlan.known(audio.language)
        }
        val wanted = plan.subtitle(heard) ?: return@buildList
        val target = if (wanted == TrackPlan.NONE) wanted else playerNumber(wanted, plan.subtitleTrack(wanted), plays)
        if (target != null && target != plays.subtitleIndex) add(TrackFix(TrackKind.SUBTITLE, target))
    }

    /** What the player's tracks [audio] and [subtitle], by its own numbers in [plays], mean; each null when unknown. */
    fun choiceOf(plays: PlayerTracks, audio: Int?, subtitle: Int?) = TrackChoice(
        audio = audio?.let { i -> plays.audio.firstOrNull { it.index == i } }?.let { TrackPlan.known(it.language) },
        subtitles = when (subtitle) {
            null -> null
            TrackPlan.NONE -> SubtitleChoice.Off
            else -> plays.subtitles.firstOrNull { it.index == subtitle }?.let { t ->
                TrackPlan.known(t.language)?.let { SubtitleChoice.Track(it, TrackPlan.kindOf(t.forced, t.hearingImpaired)) }
            }
        },
    )

    /**
     * The player's number for the file's subtitle [track], numbered [number]: that number if it matches, else the first
     * that does. Only a title tells the player's hearing-impaired tracks, so a match without it is the last resort.
     */
    private fun playerNumber(number: Int, track: SubtitleTrack?, plays: PlayerTracks): Int? {
        track ?: return null
        val language = TrackPlan.known(track.language)
        val same = plays.subtitles.filter { TrackPlan.known(it.language) == language && it.forced == track.forced }
        val match = same.firstOrNull { it.index == number && it.hearingImpaired == track.hearingImpaired }
            ?: same.firstOrNull { it.hearingImpaired == track.hearingImpaired }
            ?: same.firstOrNull { it.index == number }
            ?: same.firstOrNull()
        return match?.index
    }
}
