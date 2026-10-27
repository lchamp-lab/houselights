package app.houselights.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.houselights.core.SourceConfig
import app.houselights.settings.AppSettings
import app.houselights.settings.SecretSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The welcome screen shown for a connection the Keystore did not open: home once it is read, unless a choice was made. */
@RunWith(RobolectricTestRunner::class)
class WelcomeReadBackTest {
    @get:Rule
    val compose = createComposeRule()

    private val server = SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "token")
    private val unread = AppSettings(loaded = true, unreadSecrets = setOf(SecretSetting.SOURCE))

    @Test
    fun aConnectionReadAtLastLeadsHome() {
        var settings by mutableStateOf(unread)
        var homes = 0
        compose.setContent { rememberReadBack(settings, reading = false) { homes++ } }
        compose.waitForIdle()
        assertEquals(0, homes)
        settings = AppSettings(loaded = true, source = server)
        compose.waitForIdle()
        assertEquals(1, homes)
    }

    /** "Mes fichiers" chosen meanwhile replaces the connection: its own way home goes alone. */
    @Test
    fun aChoiceMadeMeanwhileLeadsWhereItLeads() {
        var settings by mutableStateOf(unread)
        var homes = 0
        var chosen = false
        lateinit var choosing: (() -> Unit) -> () -> Unit
        compose.setContent { choosing = rememberReadBack(settings, reading = false) { homes++ } }
        compose.runOnIdle { choosing { chosen = true }() }
        settings = AppSettings(loaded = true, source = SourceConfig.Local, unreadSecrets = setOf(SecretSetting.KEPT_SERVER))
        compose.waitForIdle()
        assertTrue(chosen)
        assertEquals(0, homes)
    }

    /** A choice that failed, the screen still there: Réessayer, or coming back to the app, leads home once it reads. */
    @Test
    fun aReadAskedAfterAChoiceThatFailedLeadsHome() {
        var settings by mutableStateOf(unread)
        var reading by mutableStateOf(false)
        var homes = 0
        lateinit var choosing: (() -> Unit) -> () -> Unit
        compose.setContent { choosing = rememberReadBack(settings, reading) { homes++ } }
        compose.runOnIdle { choosing {}() }
        reading = true
        compose.waitForIdle()
        settings = AppSettings(loaded = true, source = server)
        reading = false
        compose.waitForIdle()
        assertEquals(1, homes)
    }

    @Test
    fun aSourceSetWhenNoneWasUnreadIsNoReadBack() {
        var settings by mutableStateOf(AppSettings(loaded = true))
        var homes = 0
        compose.setContent { rememberReadBack(settings, reading = false) { homes++ } }
        compose.waitForIdle()
        settings = AppSettings(loaded = true, source = SourceConfig.Local)
        compose.waitForIdle()
        assertEquals(0, homes)
    }
}
