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
import app.houselights.providers.local.TmdbClient
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import java.text.Collator
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
    val ownLanguage = languageName(app.language, app)
    HlSettingRow(
        title,
        value = when (val audio = tracks.audio) {
            null -> stringResource(R.string.tracks_player)
            TrackPreferences.INTERFACE -> ownLanguage
            else -> languageName(audio, app)
        },
        description = stringResource(if (tracks.audio == null) R.string.audio_language_player_desc else R.string.audio_language_desc),
        onClick = { choosing = true },
    )
    if (choosing) {
        val codes = trackLanguages()
        val options = listOf(
            ChoiceOption(stringResource(R.string.tracks_player), stringResource(R.string.audio_language_player_desc)),
            ChoiceOption(stringResource(R.string.interface_language), ownLanguage),
        ) + codes.map { languageOption(it, app) }
        HlChoiceSheet(
            title,
            options,
            selected = when (val audio = tracks.audio) {
                null -> 0
                TrackPreferences.INTERFACE -> 1
                else -> codes.indexOf(Languages.code(audio)).let { if (it < 0) 0 else it + 2 }
            },
            onSelect = { i -> onChoose(if (i == 0) null else if (i == 1) TrackPreferences.INTERFACE else codes[i - 2]) },
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
    val ownLanguage = languageName(app.language, app)
    HlSettingRow(
        title,
        value = tracks.subtitleLanguage?.let { languageName(it, app) } ?: ownLanguage,
        description = stringResource(
            if (tracks.subtitleLanguage == null) R.string.subtitle_language_interface_desc else R.string.subtitle_language_desc,
        ),
        onClick = { choosing = true },
    )
    if (choosing) {
        val codes = trackLanguages()
        HlChoiceSheet(
            title,
            listOf(ChoiceOption(stringResource(R.string.interface_language), ownLanguage)) + codes.map { languageOption(it, app) },
            selected = tracks.subtitleLanguage?.let { codes.indexOf(Languages.code(it)) + 1 } ?: 0,
            onSelect = { i -> onChoose(codes.getOrNull(i - 1)) },
            onDismiss = { choosing = false },
            description = stringResource(R.string.tracks_for_all),
        )
    }
}

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

/** Languages tracks come in: TMDB's without their regions, Norwegian as files tag it; by their own names. */
@Composable
private fun trackLanguages(): List<String> = remember {
    TmdbClient.LANGUAGES.map { Locale.forLanguageTag(it).language.let { code -> if (code == "nb") "no" else code } }
        .distinct()
        .sortedWith(compareBy(Collator.getInstance(Locale.ROOT)) { languageName(it, Locale.forLanguageTag(it)) })
}

/** [code] under its own name ("Deutsch"), then in the app's when that differs. */
private fun languageOption(code: String, app: Locale): ChoiceOption {
    val own = languageName(code, Locale.forLanguageTag(code))
    return ChoiceOption(own, languageName(code, app).takeUnless { it.equals(own, ignoreCase = true) })
}

private fun languageName(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
