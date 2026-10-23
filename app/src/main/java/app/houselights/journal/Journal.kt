package app.houselights.journal

import android.content.Context
import android.util.Log
import app.houselights.AppContainer
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.player.DeviceIdentity
import app.houselights.player.ZidooStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the journal's page can be opened, as Settings > About shows it. */
sealed interface JournalAccess {
    /** At [address]; [few], when the journal holds almost nothing yet. */
    data class Open(val address: String, val few: Boolean) : JournalAccess

    /** No home network to serve it on. */
    data object Offline : JournalAccess
}

/** Opens the journal's page for a phone. */
fun interface JournalOpener {
    /** Tells where the page is ([opened]), then serves it; returns once it has closed on its own, or at once when offline. */
    suspend fun serve(opened: (JournalAccess) -> Unit)
}

/** The app's journal on the home network ([JournalServer]), its lines read again at each request ([AppLog]). */
class LocalJournal(private val context: Context, private val container: AppContainer, private val log: AppLog = AppLog()) : JournalOpener {
    override suspend fun serve(opened: (JournalAccess) -> Unit) = coroutineScope {
        val host = withContext(Dispatchers.IO) { JournalServer.privateIpv4() } ?: return@coroutineScope opened(JournalAccess.Offline)
        // The player's firmware may take its API's time to answer: the page waits for it, not the sheet.
        val facts = async { facts() }
        val words = words()
        val content = object : JournalContent {
            override suspend fun page(download: String, nonce: String) =
                JournalPage.html(head(facts.await()), log.read(), words, download, nonce)

            override suspend fun text() = JournalPage.text(head(facts.await()), log.read())
        }
        val few = log.read().size < AppLog.FEW_LINES
        try {
            JournalServer(host, content).serve { opened(JournalAccess.Open(it, few)) }
        } catch (e: IOException) {
            Log.w(TAG, "The journal's page could not open", e)
            opened(JournalAccess.Offline)
        }
    }

    /** The app, the device and its firmware when the player says it, the source and its protocols, Trakt. */
    private suspend fun facts(): List<String> {
        val device = DeviceIdentity.current()
        val firmware = if (device.namesZidoo) ZidooStatus.firmware() else null
        val settings = container.settings.state.value
        val source = when (settings.source) {
            SourceConfig.Local -> {
                val folders = suspendRunCatching { container.library.folders.first() }.getOrDefault(emptyList())
                val protocols = folders.map { it.protocol.name }.distinct().sorted()
                context.getString(R.string.source_local) + protocols.joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
            }
            is SourceConfig.Jellyfin -> JELLYFIN
            null -> null
        }
        val trakt = context.getString(if (settings.trakt != null) R.string.journal_trakt_on else R.string.journal_trakt_off)
        return listOfNotNull("Houselights ${BuildConfig.VERSION_NAME}", device.name + firmware?.let { " ($it)" }.orEmpty(), source, trakt)
    }

    private fun head(facts: List<String>): JournalHead {
        val locale = context.resources.configuration.locales[0]
        return JournalHead(facts, DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(LocalDateTime.now()))
    }

    private fun words() = JournalWords(
        language = context.resources.configuration.locales[0].toLanguageTag(),
        title = context.getString(R.string.journal_page_title),
        text = context.getString(R.string.journal_page_text),
        copy = context.getString(R.string.journal_copy),
        copied = context.getString(R.string.journal_copied),
        download = context.getString(R.string.journal_download),
        empty = context.getString(R.string.journal_empty),
    )

    private companion object {
        const val TAG = "Journal"
        const val JELLYFIN = "Jellyfin"
    }
}
