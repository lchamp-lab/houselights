package app.houselights.journal

import android.content.Context
import android.util.Log
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.player.DeviceIdentity
import app.houselights.player.ZidooStatus
import app.houselights.providers.local.Protocol
import app.houselights.settings.AppSettings
import app.houselights.settings.SecretSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the journal's page can be opened, as Settings > About shows it. */
sealed interface JournalAccess {
    /** At [address]; [few], when the journal holds almost nothing yet. */
    data class Open(val address: String, val few: Boolean) : JournalAccess

    /** No home network to serve it on. */
    data object Offline : JournalAccess

    /** The page could not listen on the player. */
    data object Failed : JournalAccess
}

/** Opens the journal's page for a phone. */
fun interface JournalOpener {
    /** Tells where the page is ([opened]), then serves it; returns once it has closed on its own, at once when it cannot open. */
    suspend fun serve(opened: (JournalAccess) -> Unit)
}

/**
 * The app's journal on the home network ([JournalServer]) at [host]'s address, its lines read again at each request
 * ([AppLog]); its head says the source from [settings], with [protocols], the folders', and Trakt.
 */
class LocalJournal(
    private val context: Context,
    private val settings: () -> AppSettings,
    private val protocols: suspend () -> List<Protocol>,
    private val log: AppLog = AppLog(),
    private val host: () -> InetAddress? = { HomeAddress.find(context) },
) : JournalOpener {
    override suspend fun serve(opened: (JournalAccess) -> Unit) = coroutineScope {
        val address = withContext(Dispatchers.IO) { host() } ?: return@coroutineScope opened(JournalAccess.Offline)
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
            JournalServer(address, content).serve { opened(JournalAccess.Open(it, few)) }
        } catch (e: IOException) {
            Log.w(TAG, "The journal's page could not open", e)
            facts.cancel()
            opened(JournalAccess.Failed)
        }
    }

    /** The app, the device and its firmware when the player says it, the source and its protocols, Trakt. */
    private suspend fun facts(): List<String> {
        val device = DeviceIdentity.current()
        val firmware = if (device.namesZidoo) ZidooStatus.firmware() else null
        val settings = settings()
        val source = when (settings.source) {
            SourceConfig.Local -> {
                val protocols = suspendRunCatching { protocols() }.getOrDefault(emptyList()).map { it.name }.distinct().sorted()
                context.getString(R.string.source_local) + protocols.joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
            }
            is SourceConfig.Jellyfin -> JELLYFIN
            null -> null
        }
        // A session kept that the Keystore did not open at this launch is no session gone.
        val trakt = context.getString(
            when {
                settings.trakt != null -> R.string.journal_trakt_on
                SecretSetting.TRAKT in settings.unreadSecrets -> R.string.journal_trakt_unread
                else -> R.string.journal_trakt_off
            },
        )
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
