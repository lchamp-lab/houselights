package app.houselights.settings

import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The app speaks the language chosen in Settings from the moment it is saved, and at every launch after. */
@RunWith(RobolectricTestRunner::class)
class InterfaceLanguageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val before = LocaleList.getDefault()

    @After
    fun restore() = LocaleList.setDefault(before)

    @Test
    fun aChosenLanguageIsSpokenAndKept() = runTest {
        val language = InterfaceLanguage(context)
        language.choose("fr")
        assertEquals("Langue de l’appareil", language.resources.getString(R.string.device_language))
        assertEquals("Langue de l’appareil", language.wrap(context).getString(R.string.device_language))
        assertEquals("fr", Locale.getDefault().language)
        assertEquals("fr", InterfaceLanguage(context).chosen.value)
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun theDeviceLanguageIsFollowedAgain() = runTest {
        val language = InterfaceLanguage(context)
        language.choose("fr")
        language.choose(null)
        assertEquals("Device language", language.resources.getString(R.string.device_language))
        assertSame(context, language.wrap(context))
        assertEquals(Locale.US, Locale.getDefault())
        assertNull(InterfaceLanguage(context).chosen.value)
    }

    /** Each language offered is shipped (the build keeps only the interface's) and has its own words. */
    @Test
    fun everyLanguageOfferedIsSpoken() {
        val language = InterfaceLanguage(context)
        val english = context.getString(R.string.interface_language)
        InterfaceLanguage.LANGUAGES.filter { it != "en" }.forEach { tag ->
            assertNotEquals(tag, english, language.wrap(context, tag).getString(R.string.interface_language))
        }
    }

    @Test
    fun aLanguageTheAppNoLongerHasFollowsTheDevice() {
        context.getSharedPreferences("interface", Context.MODE_PRIVATE).edit().putString("language", "xx").commit()
        assertNull(InterfaceLanguage(context).chosen.value)
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun aLanguageTheDeviceSpeaksKeepsItsRegion() = runTest {
        InterfaceLanguage(context).choose("fr")
        assertEquals(Locale.CANADA_FRENCH, Locale.getDefault())
    }

    @Test
    @Config(qualifiers = "ru-rRU")
    fun aDeviceLanguageTheAppLacksSpeaksEnglish() {
        assertEquals("en", InterfaceLanguage.deviceLanguage())
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun aDeviceLanguageTheAppHasIsSpoken() {
        assertEquals("fr", InterfaceLanguage.deviceLanguage())
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun chineseIsSpokenInTheScriptOfTheDevicesRegion() {
        assertEquals("zh-Hant", InterfaceLanguage.deviceLanguage())
    }
}
