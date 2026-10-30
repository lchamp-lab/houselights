package app.houselights.ui.settings

import android.icu.util.ULocale
import app.houselights.providers.local.TmdbClient
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.components.ChoiceOption
import java.text.Collator
import java.util.Locale

/**
 * The languages a settings sheet offers, [all] of them, each named by [name] in a locale: their own names read once,
 * the languages sorted by them once, and their options made once for the app's language, so a pane coming back, or a
 * sheet opening again, sorts and names nothing again.
 */
internal class LanguageNames(private val all: List<String>, private val name: (tag: String, locale: Locale) -> String) {
    /** Each language under its own name ("Deutsch"). */
    private val own: Map<String, String> by lazy { all.associateWith { name(it, Locale.forLanguageTag(it)) } }

    /** The languages by their own names, as a reader looks for theirs. */
    val tags: List<String> by lazy {
        val order = Collator.getInstance(Locale.ROOT)
        all.sortedWith(compareBy(order) { own.getValue(it) })
    }

    /** The options last made, and the app's language they were made in. */
    @Volatile
    private var made: Pair<Locale, List<ChoiceOption>>? = null

    /** Each of [tags] under its own name, then in [app]'s, the app's language, when that differs; made once for it. */
    fun options(app: Locale): List<ChoiceOption> = made?.takeIf { it.first == app }?.second ?: tags.map { tag ->
        val itsOwn = own.getValue(tag)
        ChoiceOption(itsOwn, name(tag, app).takeUnless { it.equals(itsOwn, ignoreCase = true) })
    }.also { made = app to it }
}

/** TMDB's languages, a language with several regions under each of them ([metadataLanguageName]). */
internal val metadataLanguages = LanguageNames(TmdbClient.LANGUAGES, ::metadataLanguageName)

/** The app's own languages ([InterfaceLanguage.LANGUAGES]). */
internal val interfaceLanguages = LanguageNames(InterfaceLanguage.LANGUAGES, ::interfaceLanguageName)

/** Languages tracks come in: TMDB's without their regions, Norwegian as files tag it. */
internal val trackLanguages = LanguageNames(
    TmdbClient.LANGUAGES.map { Locale.forLanguageTag(it).language.let { code -> if (code == "nb") "no" else code } }.distinct(),
    ::trackLanguageName,
)

/** The languages TMDB offers in several regions ("fr": "fr-CA" and "fr-FR"), read once. */
private val regional: Set<String> by lazy {
    TmdbClient.LANGUAGES.groupingBy { Locale.forLanguageTag(it).language }.eachCount().filterValues { it > 1 }.keys
}

/** [tag] named in [locale], by default its own ("Deutsch"), with its region where several are offered ("Français (Canada)"). */
internal fun metadataLanguageName(tag: String, locale: Locale = Locale.forLanguageTag(tag)): String {
    val language = Locale.forLanguageTag(tag)
    val name = if (language.language in regional) language.getDisplayName(locale) else language.getDisplayLanguage(locale)
    return name.replaceFirstChar { it.titlecase(locale) }
}

/** [tag]'s language named in [locale], by default its own ("Deutsch"), as one name where it has two scripts ("简体中文"). */
internal fun interfaceLanguageName(tag: String, locale: Locale = Locale.forLanguageTag(tag)): String =
    ULocale.forLanguageTag(tag).getDisplayNameWithDialect(ULocale.forLocale(locale)).replaceFirstChar { it.titlecase(locale) }

/** A track's language [code] named in [locale] ("Deutsch" in its own). */
internal fun trackLanguageName(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
