package app.houselights.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.LocaleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * The language the app speaks: the device's, or one of [LANGUAGES] chosen in Settings. It applies before the first
 * screen, so it is read at once from preferences of its own rather than from [SettingsStore].
 */
class InterfaceLanguage(private val base: Context) {
    private val prefs = base.getSharedPreferences("interface", Context.MODE_PRIVATE)
    private val _chosen = MutableStateFlow(prefs.getString(KEY, null)?.takeIf { it in LANGUAGES })

    /** The language chosen in Settings, one of [LANGUAGES]; null follows the device. */
    val chosen: StateFlow<String?> = _chosen

    /** The application's resources, in the app's language. */
    @Volatile
    var resources: Resources = wrap(base).resources
        private set

    /** The configuration that makes resources speak [tag], its locale alone; null when the app follows the device. */
    fun overrides(tag: String? = chosen.value): Configuration? = tag?.let { Configuration().apply { setLocales(LocaleList(locale(it))) } }

    /** [context] speaking [tag]; [context] itself when the app follows the device. */
    fun wrap(context: Context, tag: String? = chosen.value): Context = overrides(tag)?.let(context::createConfigurationContext) ?: context

    /** Makes the app's language the default one, which formats read; the system resets it on configuration changes. */
    fun applyDefault() {
        LocaleList.setDefault(chosen.value?.let { LocaleList(locale(it)) } ?: deviceLocales())
    }

    /** Saves [tag] (null for the device's language) and speaks it; activities make their screens again. */
    suspend fun choose(tag: String?) {
        withContext(Dispatchers.IO) {
            val saved = prefs.edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.commit()
            if (!saved) throw IOException("The interface language could not be saved")
        }
        resources = wrap(base, tag).resources
        _chosen.value = tag
        applyDefault()
    }

    companion object {
        private const val KEY = "language"

        /** Languages the app has text in, as BCP 47 tags. */
        val LANGUAGES = listOf("de", "en", "es", "fr", "it", "ja", "ko", "nl", "pl", "pt", "zh-Hans", "zh-Hant")

        /** The device's languages, in its order, whatever the app speaks. */
        fun deviceLocales(): LocaleList = Resources.getSystem().configuration.locales

        /** The language the app speaks by the device's: the first of the device's it has text in, else English. */
        fun deviceLanguage(): String = deviceLocales().all().map(::tagOf).firstOrNull { it in LANGUAGES } ?: "en"

        /** [tag] with the device's region where the device lists that language ("fr-CA", "zh-TW"), else alone. */
        private fun locale(tag: String): Locale = deviceLocales().all().firstOrNull { tagOf(it) == tag } ?: Locale.forLanguageTag(tag)

        /** [locale]'s tag among [LANGUAGES]: its language, with its script for Chinese ("zh-TW" is "zh-Hant"). */
        private fun tagOf(locale: Locale): String =
            if (locale.language != "zh") locale.language else "zh-" + ULocale.addLikelySubtags(ULocale.forLocale(locale)).script

        private fun LocaleList.all(): List<Locale> = List(size(), ::get)
    }
}
