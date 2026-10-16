package app.houselights.ui.onboarding

import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.SecretsUnavailableException
import app.houselights.ui.util.toWriteError
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException
import java.util.Locale

/** Choosing the folders failed on the welcome screen: what it says never sends to the settings, which it does not lead to. */
@RunWith(RobolectricTestRunner::class)
class WelcomeProblemTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun Context.inLocale(locale: Locale): Context =
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })

    @Test
    fun aFailureToChooseTheFoldersNeverSendsToTheSettings() {
        // What choosing the folders meets: the storage full or failing, the Keystore, anything else.
        val failures = listOf(
            IOException("No space left on device"),
            SQLiteFullException("database or disk is full"),
            SecretsUnavailableException(ProviderException("Keystore operation failed")),
            IllegalStateException("anything"),
        )
        for (failure in failures) {
            val problem = welcomeProblem(failure.toWriteError())
            val english = context.inLocale(Locale.ENGLISH).getString(problem)
            val french = context.inLocale(Locale.FRENCH).getString(problem)
            assertFalse(english, english.contains("Settings", ignoreCase = true))
            assertFalse(french, french.contains("réglages", ignoreCase = true))
        }
    }
}
