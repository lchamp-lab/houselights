package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.AudioTrack
import app.houselights.core.Formats
import app.houselights.core.SubtitleChoice
import app.houselights.core.SubtitleKind
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackChoice
import app.houselights.core.TrackNames
import app.houselights.core.TrackPlan
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.languageName
import java.util.Locale

/**
 * A title's audio and subtitles, side by side: "As in Settings" heads each column, then the file's tracks in its order,
 * the one Lire plays checked. Choosing keeps the sheet open, for the other column; [show] words it for a whole show.
 */
@Composable
fun TracksSheet(name: String, plan: TrackPlan, show: Boolean, onChoose: (TrackChoice) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val choice = plan.choice ?: TrackChoice()
    HlSideSheet(
        stringResource(R.string.tracks),
        onDismiss = onDismiss,
        facts = listOf(name, stringResource(if (show) R.string.tracks_for_show else R.string.tracks_for_film)),
        initialFocus = first,
        wide = true,
    ) { _ ->
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { AudioColumn(plan, choice, onChoose, first) }
            Column(Modifier.weight(1f)) { SubtitleColumn(plan, choice, onChoose) }
        }
        Text(
            stringResource(if (show) R.string.tracks_note_show else R.string.tracks_note_film),
            style = Hl.type.meta,
            color = Hl.colors.contentSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AudioColumn(plan: TrackPlan, choice: TrackChoice, onChoose: (TrackChoice) -> Unit, first: FocusRequester) {
    // What Settings alone make of this file.
    val settings = plan.copy(choice = choice.copy(audio = null))
    HlSettingGroup(stringResource(R.string.tracks_audio)) {
        TrackRow(
            stringResource(R.string.tracks_settings),
            settings.audio?.let { audioName(plan.audioTracks[it]) } ?: stringResource(R.string.tracks_player_choice),
            chosen = !plan.audioChosen,
            onClick = { onChoose(choice.copy(audio = null)) },
            modifier = Modifier.focusRequester(first),
        )
        // A choice keeps a language and region: one row for each track they lead to.
        plan.audioOptions.forEach { i ->
            val track = plan.audioTracks[i]
            TrackRow(
                audioName(track),
                audioFormat(track),
                chosen = plan.audioChosen && plan.audio == i,
                onClick = { onChoose(choice.copy(audio = TrackPlan.tagOf(track.language, track.title))) },
            )
        }
    }
}

@Composable
private fun SubtitleColumn(plan: TrackPlan, choice: TrackChoice, onChoose: (TrackChoice) -> Unit) {
    val settings = plan.copy(choice = choice.copy(subtitles = null)).subtitleShown
    HlSettingGroup(stringResource(R.string.subtitles)) {
        TrackRow(
            stringResource(R.string.tracks_settings),
            when (settings) {
                null -> stringResource(R.string.tracks_player_choice)
                TrackPlan.NONE -> stringResource(R.string.tracks_none)
                else -> plan.subtitleTrack(settings)?.let { subtitleName(it) }
            },
            chosen = !plan.subtitleChosen,
            onClick = { onChoose(choice.copy(subtitles = null)) },
        )
        TrackRow(
            stringResource(R.string.tracks_none),
            null,
            chosen = plan.subtitleChosen && plan.pick.subtitle == TrackPlan.NONE,
            onClick = { onChoose(choice.copy(subtitles = SubtitleChoice.Off)) },
        )
        plan.subtitleOptions.forEach { number ->
            val track = checkNotNull(plan.subtitleTrack(number))
            TrackRow(
                subtitleName(track),
                subtitleFormat(track),
                chosen = plan.subtitleChosen && plan.pick.subtitle == number,
                onClick = { onChoose(choice.copy(subtitles = TrackPlan.subtitleChoiceOf(track))) },
            )
        }
    }
}

@Composable
private fun TrackRow(name: String, description: String?, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HlSettingRow(
        name,
        modifier.semantics { selected = chosen },
        description = description,
        onClick = onClick,
        trailing = if (chosen) ({ ChoiceCheck() }) else null,
    )
}

/** "Anglais", "Français (Canada)". */
@Composable
private fun audioName(track: AudioTrack): String = trackLanguage(track.language, TrackNames.region(track.language, track.title))

/** "Dolby TrueHD Atmos 7.1", then what its title says more ("Commentaire audio"). */
private fun audioFormat(track: AudioTrack): String =
    listOfNotNull(listOfNotNull(Formats.soundName(track), track.layout).joinToString(" "), TrackNames.note(track.title, track.language))
        .joinToString(", ")

/** "Français", "Français, forcés", "Anglais, SDH". */
@Composable
private fun subtitleName(track: SubtitleTrack): String {
    val language = trackLanguage(track.language, TrackNames.region(track.language, track.title))
    return when (TrackPlan.kindOf(track)) {
        SubtitleKind.FORCED -> stringResource(R.string.tracks_forced, language)
        SubtitleKind.SDH -> stringResource(R.string.tracks_sdh, language)
        SubtitleKind.FULL -> language
    }
}

private fun subtitleFormat(track: SubtitleTrack): String =
    listOfNotNull(track.format, TrackNames.subtitleNote(track.title, track.language)).joinToString(", ")

/**
 * What Lire plays, for an action's second line: "Français, sous-titres forcés"; only what is asked of the player
 * ([TrackPlan.subtitleShown]), and [R.string.tracks_player] when nothing is.
 */
@Composable
fun tracksHint(plan: TrackPlan): String {
    // The language alone: the line is short.
    val audio = plan.audio?.let { languageName(plan.audioTracks[it].language) }
    val subtitles = when (val number = plan.subtitleShown) {
        null -> null
        TrackPlan.NONE -> stringResource(R.string.tracks_hint_none)
        else -> plan.subtitleTrack(number)?.let { track ->
            if (track.forced) {
                stringResource(R.string.tracks_hint_forced)
            } else {
                // "sous-titres en français": the language as a sentence says it.
                val language = Locale.forLanguageTag(TrackPlan.known(track.language).orEmpty()).getDisplayLanguage(Locale.getDefault())
                stringResource(R.string.tracks_hint_full, language)
            }
        }
    }
    val line = when {
        audio != null && subtitles != null -> stringResource(R.string.tracks_hint_pair, audio, subtitles)
        else -> audio ?: subtitles ?: return stringResource(R.string.tracks_player)
    }
    return line.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
