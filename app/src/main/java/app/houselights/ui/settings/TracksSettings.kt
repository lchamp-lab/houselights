package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.Languages
import app.houselights.core.SubtitleMode
import app.houselights.core.TrackPreferences
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import java.util.Locale

/** The audio and subtitles asked of the player for every title, each chosen in a side sheet; a title may keep its own. */
@Composable
internal fun TracksGroup(tracks: TrackPreferences) {
    val vm = settingsViewModel()
    val failure = rowFailure(vm.errorOf(SettingAction.TRACKS))
    val app = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        HlSettingGroup(stringResource(R.string.tracks)) {
            AudioRow(tracks, app) { vm.setTracks(tracks.copy(audio = it)) }
            SubtitlesRow(tracks, app) { vm.setTracks(tracks.copy(subtitles = it)) }
            SubtitleLanguageRow(tracks, app) { vm.setTracks(tracks.copy(subtitleLanguage = it)) }
            RowFailureNote(failure)
        }
        Text(stringResource(R.string.tracks_per_title), style = Hl.type.meta, color = Hl.colors.contentSecondary)
    }
}

@Composable
private fun AudioRow(tracks: TrackPreferences, app: Locale, onChoose: (String?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.audio_language)
    val ownLanguage = trackLanguageName(app.language, app)
    HlSettingRow(
        title,
        value = when (val audio = tracks.audio) {
            null -> stringResource(R.string.tracks_player)
            TrackPreferences.INTERFACE -> ownLanguage
            else -> trackLanguageName(audio, app)
        },
        description = stringResource(if (tracks.audio == null) R.string.audio_language_player_desc else R.string.audio_language_desc),
        onClick = { choosing = true },
    )
    if (choosing) {
        val codes = trackCodes(tracks.audio, trackLanguages.tags)
        val options = listOf(
            ChoiceOption(stringResource(R.string.tracks_player), stringResource(R.string.audio_language_player_desc)),
            ChoiceOption(stringResource(R.string.interface_language), ownLanguage),
        ) + trackOptions(codes, app)
        HlChoiceSheet(
            title,
            options,
            selected = audioOption(tracks.audio, codes),
            onSelect = { onChoose(audioOf(it, codes)) },
            onDismiss = { choosing = false },
            description = stringResource(R.string.tracks_for_all),
        )
    }
}

@Composable
private fun SubtitlesRow(tracks: TrackPreferences, app: Locale, onChoose: (SubtitleMode) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.subtitles)
    // "en français": the language as a sentence says it, not as a title.
    val wanted = Locale.forLanguageTag(tracks.subtitleLanguage ?: app.language).getDisplayLanguage(app)
    HlSettingRow(
        title,
        value = stringResource(modeName(tracks.subtitles)),
        description = modeDescription(tracks.subtitles, wanted),
        onClick = { choosing = true },
    )
    if (choosing) {
        HlChoiceSheet(
            title,
            SubtitleMode.entries.map { ChoiceOption(stringResource(modeName(it)), modeDescription(it, wanted)) },
            selected = tracks.subtitles.ordinal,
            onSelect = { onChoose(SubtitleMode.entries[it]) },
            onDismiss = { choosing = false },
            description = stringResource(R.string.tracks_for_all),
        )
    }
}

@Composable
private fun SubtitleLanguageRow(tracks: TrackPreferences, app: Locale, onChoose: (String?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.subtitle_language)
    val ownLanguage = trackLanguageName(app.language, app)
    HlSettingRow(
        title,
        value = tracks.subtitleLanguage?.let { trackLanguageName(it, app) } ?: ownLanguage,
        description = stringResource(
            if (tracks.subtitleLanguage == null) R.string.subtitle_language_interface_desc else R.string.subtitle_language_desc,
        ),
        onClick = { choosing = true },
    )
    if (choosing) {
        val codes = trackCodes(tracks.subtitleLanguage, trackLanguages.tags)
        HlChoiceSheet(
            title,
            listOf(ChoiceOption(stringResource(R.string.interface_language), ownLanguage)) + trackOptions(codes, app),
            selected = subtitleLanguageOption(tracks.subtitleLanguage, codes),
            onSelect = { onChoose(subtitleLanguageOf(it, codes)) },
            onDismiss = { choosing = false },
            description = stringResource(R.string.tracks_for_all),
        )
    }
}

/**
 * The languages a sheet lists: [tags], after [saved] when it is a language none of them is (one left by another
 * version), so that the sheet shows chosen the language the row names.
 */
internal fun trackCodes(saved: String?, tags: List<String>): List<String> =
    if (saved == null || saved == TrackPreferences.INTERFACE || Languages.code(saved) in tags) tags else listOf(saved) + tags

/** The options of [codes], as [trackCodes] lists them: the saved language they add named as the others are. */
private fun trackOptions(codes: List<String>, app: Locale): List<ChoiceOption> =
    codes.dropLast(trackLanguages.tags.size).map { code ->
        val itsOwn = trackLanguageName(code, Locale.forLanguageTag(code))
        ChoiceOption(itsOwn, trackLanguageName(code, app).takeUnless { it.equals(itsOwn, ignoreCase = true) })
    } + trackLanguages.options(app)

/**
 * The audio sheet's option for [audio]: the player's choice (null) first, then the app's language
 * ([TrackPreferences.INTERFACE]), then each of [codes]; a language not among them shows the player's choice.
 */
internal fun audioOption(audio: String?, codes: List<String>): Int = when (audio) {
    null -> PLAYER_OPTION
    TrackPreferences.INTERFACE -> INTERFACE_OPTION
    else -> codes.indexOf(Languages.code(audio)).let { if (it < 0) PLAYER_OPTION else it + AUDIO_LANGUAGES }
}

/** The audio asked for by the audio sheet's [option], in [audioOption]'s order. */
internal fun audioOf(option: Int, codes: List<String>): String? = when (option) {
    PLAYER_OPTION -> null
    INTERFACE_OPTION -> TrackPreferences.INTERFACE
    else -> codes[option - AUDIO_LANGUAGES]
}

/**
 * The subtitles' language sheet's option for [language]: the app's language (null) first, then each of [codes]; a
 * language not among them shows the app's.
 */
internal fun subtitleLanguageOption(language: String?, codes: List<String>): Int =
    language?.let { codes.indexOf(Languages.code(it)) + SUBTITLE_LANGUAGES } ?: 0

/** The subtitles' language asked for by that sheet's [option], in [subtitleLanguageOption]'s order. */
internal fun subtitleLanguageOf(option: Int, codes: List<String>): String? = codes.getOrNull(option - SUBTITLE_LANGUAGES)

/** The audio sheet's first two options, before its languages. */
private const val PLAYER_OPTION = 0
private const val INTERFACE_OPTION = 1

/** Where the languages begin in the audio sheet, after the player's choice and the app's language. */
private const val AUDIO_LANGUAGES = 2

/** Where the languages begin in the subtitles' language sheet, after the app's language. */
private const val SUBTITLE_LANGUAGES = 1

private fun modeName(mode: SubtitleMode): Int = when (mode) {
    SubtitleMode.PLAYER -> R.string.tracks_player
    SubtitleMode.OFF -> R.string.subtitles_off
    SubtitleMode.FORCED -> R.string.subtitles_forced
    SubtitleMode.FOREIGN -> R.string.subtitles_auto
    SubtitleMode.ALWAYS -> R.string.subtitles_always
}

@Composable
private fun modeDescription(mode: SubtitleMode, wanted: String): String = when (mode) {
    SubtitleMode.PLAYER -> stringResource(R.string.tracks_player_desc)
    SubtitleMode.OFF -> stringResource(R.string.subtitles_off_desc)
    SubtitleMode.FORCED -> stringResource(R.string.subtitles_forced_desc)
    SubtitleMode.FOREIGN -> stringResource(R.string.subtitles_auto_desc, wanted)
    SubtitleMode.ALWAYS -> stringResource(R.string.subtitles_always_desc, wanted)
}
