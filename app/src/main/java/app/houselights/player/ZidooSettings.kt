package app.houselights.player

import android.content.ContentResolver
import android.provider.Settings
import app.houselights.core.TrackPlan

/** The Zidoo player's own settings, which it keeps in Settings.System (firmware v6.4.68). */
object ZidooSettings {
    private const val AUDIO = "default_zidoo_audio_language_new"

    /** The older setting, numbered one less, which the player reads when the new one is unset. */
    private const val OLD_AUDIO = "default_zidoo_audio_language"
    private const val UNSET = -1

    /** The player's audio setting ([TrackPlan.playerAudio]); null when unset, or not on a Zidoo. */
    fun audio(resolver: ContentResolver): String? = runCatching {
        audio(Settings.System.getInt(resolver, AUDIO, UNSET), Settings.System.getInt(resolver, OLD_AUDIO, UNSET))
    }.getOrNull()

    /** The setting numbered [index] (0 for automatic), else [old]'s: 1 the file's default track, 2 its first, then a language. */
    internal fun audio(index: Int, old: Int): String? {
        val number = if (index == UNSET && old > 0) old + 1 else index
        return when (number) {
            1 -> TrackPlan.PLAYER_DEFAULT
            2 -> TrackPlan.PLAYER_FIRST
            else -> LANGUAGES.getOrNull(number - FIRST_LANGUAGE)
        }
    }

    private const val FIRST_LANGUAGE = 3

    /**
     * The player's table. It matches a track by two letters, so its "in-ID" and "sw-SE" find Indonesian ("ind") and
     * Swedish ("swe"): they are written here as those languages.
     */
    private val LANGUAGES = listOf(
        "af-ZA", "az-AZ", "id-ID", "ms-MY", "ca-ES", "cs-CZ", "da-DK", "de-DE", "de-LI", "de-GU", "de-CH", "et-EE", "en-AU",
        "en-CA", "en-IN", "en-NZ", "en-SG", "en-GB", "en-US", "es-ES", "es-US", "eu-ES", "fil-PH", "fr-BE", "fr-CA", "fr-FR",
        "fr-CH", "gl-ES", "hr-HR", "zu-ZA", "is-IS", "it-IT", "it-CH", "sw-TZ", "lv-LV", "lt-LT", "hu-HU", "nl-BE", "nl-NL",
        "nb-NO", "uz-UZ", "pl-PL", "pt-BR", "pt-PT", "ro-RO", "rm-CH", "sq-AL", "sk-SK", "sl-SI", "fi-FI", "sv-SE", "vi-VN",
        "tur-TR", "el-GR", "bg-BG", "ky-KG", "kk-KZ", "mk-MK", "mn-MN", "ru-RU", "sr-RS", "uk-UA", "ka-GE", "hy-AM", "iw-IL",
        "ur-PK", "ar-EG", "fa-IR", "am-ET", "ne-NP", "mr-IN", "hi-IN", "bn-BD", "pa-IN", "gu-IN", "ta-IN", "te-IN", "kn-IN",
        "ml-IN", "si-LK", "th-TH", "lo-LA", "my-MM", "km-KH", "ko-KR", "zh-CN", "zh-TW", "zh-HK", "jpn",
    )
}
