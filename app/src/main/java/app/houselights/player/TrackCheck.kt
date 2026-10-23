package app.houselights.player

import app.houselights.core.SubtitleChoice
import app.houselights.core.SubtitleKind
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan

/** A track to switch the player to: its number in the player's list ([PlayerTrack.index]). */
data class TrackFix(val kind: TrackKind, val index: Int)

/**
 * Checks, once the film plays, that the player plays what [TrackPlan] asked: its numbering could differ from the
 * file's (a server's external subtitles, a file replaced since the scan), the audio it chose by itself decides the
 * subtitles of some modes, and only its list tells the subtitle files beside the video. A kind it no longer plays as
 * asked was changed by the user, and is left as it is.
 */
object TrackCheck {
    /**
     * At most one fix per kind still played as asked ([TrackPlan.pick]), so that what the player [plays] matches [plan],
     * tracks matched by language and kind; subtitles wanted before those asked, once listed beside the video, replace
     * them. Without its audio list, the audio is taken to be the one asked; one left to the player is only guessed, and
     * subtitles that would follow it are not fixed until the list tells it.
     */
    fun fixes(plan: TrackPlan, plays: PlayerTracks): List<TrackFix> = buildList {
        val index = plays.audioIndex
        var heard = TrackPlan.known(if (index == null) plan.expectedAudio?.language else plays.audioAt(index)?.language)
        audioFix(plan, plays)?.let { audio ->
            add(TrackFix(TrackKind.AUDIO, audio.index))
            heard = TrackPlan.known(audio.language)
        }
        val asked = plan.pick.subtitle?.takeIf { it == plays.subtitleIndex } ?: return@buildList
        if (index == null && followsPlayersAudio(plan)) return@buildList
        val target = subtitleFor(plan, heard, plays)
        if (target != null && target != asked) add(TrackFix(TrackKind.SUBTITLE, target))
    }

    /** The subtitles asked depend on which of the file's audio tracks the player chose by itself. */
    private fun followsPlayersAudio(plan: TrackPlan): Boolean = plan.pick.audio == null &&
        plan.audioTracks.map { TrackPlan.known(it.language) }.distinct()
            .map { heard -> plan.subtitle(heard) to plan.bestBeside(heard) }
            .distinct().size > 1

    /**
     * The player's number for the subtitles wanted with [heard]: the first it lists beside the video of those wanted
     * before the file's own ([TrackPlan.bestBeside]), else the file's, found in its list. Null leaves them as they are.
     */
    private fun subtitleFor(plan: TrackPlan, heard: String?, plays: PlayerTracks): Int? {
        plan.bestBeside(heard).firstNotNullOfOrNull { beside(it, plays) }?.let { return it }
        val wanted = plan.subtitle(heard) ?: return null
        return if (wanted == TrackPlan.NONE) wanted else playerNumber(wanted, plan.subtitleTrack(wanted), plays)
    }

    /** The player's number for [wanted], which the file lacks: a file beside the video it lists of that language and kind. */
    private fun beside(wanted: SubtitleChoice.Track, plays: PlayerTracks): Int? {
        val language = TrackPlan.known(wanted.language)
        val same = plays.subtitles.filter {
            it.index != TrackPlan.NONE && TrackPlan.known(it.language) == language && TrackPlan.isOfKind(it.forced, wanted.kind)
        }
        return (same.firstOrNull { it.hearingImpaired == (wanted.kind == SubtitleKind.SDH) } ?: same.firstOrNull())?.index
    }

    /**
     * The audio to switch to when the player plays the number asked in another language than the file's: the track of
     * the file's language at the same rank among them; none when the player lists another count of them.
     */
    private fun audioFix(plan: TrackPlan, plays: PlayerTracks): PlayerTrack? {
        val asked = plan.pick.audio?.takeIf { it == plays.audioIndex } ?: return null
        val wanted = plan.audioLanguage ?: return null
        if (TrackPlan.known(plays.audioAt(asked)?.language) == wanted) return null
        val inFile = plan.audioTracks.filter { TrackPlan.known(it.language) == wanted }
        val listed = plays.audio.filter { TrackPlan.known(it.language) == wanted }
        return listed.getOrNull(inFile.indexOfFirst { it === plan.audioTracks[asked] }).takeIf { listed.size == inFile.size }
    }

    /**
     * What the player's tracks [audio] and [subtitle], by its own numbers, mean: read in [plays] where it gave the
     * list, else in the file's numbering when [byFile]; each null when unknown. The player's audio, in the language of
     * the file's track of that number, keeps that track's region ("fr-CA").
     */
    fun choiceOf(plan: TrackPlan, plays: PlayerTracks?, audio: Int?, subtitle: Int?, byFile: Boolean): TrackChoice {
        val file = if (byFile) plan.choiceOf(audio, subtitle) else TrackChoice()
        return TrackChoice(
            audio = if (plays?.audioIndex == null) file.audio else audio?.let { audioOf(plan, plays, it) },
            subtitles = if (plays?.subtitleIndex == null) file.subtitles else subtitle?.let { subtitlesOf(plays, it) },
        )
    }

    private fun audioOf(plan: TrackPlan, plays: PlayerTracks, index: Int): String? {
        val language = TrackPlan.known(plays.audioAt(index)?.language) ?: return null
        val same = plan.audioTracks.getOrNull(index)?.takeIf { TrackPlan.known(it.language) == language }
        return same?.let { TrackPlan.tagOf(it.language, it.title) } ?: language
    }

    private fun subtitlesOf(plays: PlayerTracks, index: Int): SubtitleChoice? =
        if (index == TrackPlan.NONE) SubtitleChoice.Off else plays.subtitles.firstOrNull { it.index == index }?.let { t ->
            TrackPlan.known(t.language)?.let { SubtitleChoice.Track(it, TrackPlan.kindOf(t.forced, t.hearingImpaired)) }
        }

    private fun PlayerTracks.audioAt(index: Int): PlayerTrack? = audio.firstOrNull { it.index == index }

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
