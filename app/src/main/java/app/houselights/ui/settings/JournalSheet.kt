package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.journal.JournalAccess
import app.houselights.journal.JournalOpener
import app.houselights.journal.JournalServer
import app.houselights.journal.LocalJournal
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.QrCode
import app.houselights.ui.design.components.ReadingPlate
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatDuration
import java.util.Locale

/** Sending the app's log, for a report: OK opens a sheet whose QR code opens the log on a phone ([JournalSheet]). */
@Composable
internal fun JournalRow(modifier: Modifier = Modifier, journal: JournalOpener = rememberJournal()) {
    var open by remember { mutableStateOf(false) }
    HlSettingRow(
        stringResource(R.string.journal),
        modifier,
        description = stringResource(R.string.journal_desc),
        onClick = { open = true },
    )
    if (open) JournalSheet(journal, onDismiss = { open = false })
}

@Composable
private fun rememberJournal(): JournalOpener {
    val context = LocalContext.current.applicationContext
    val container = appContainer()
    return remember(context, container) { LocalJournal(context, container) }
}

/**
 * The journal's page while the sheet is open, as Soutenir Houselights shows its page: what the code opens, the QR code
 * and the address, then what the log keeps and when the page closes. A log holding almost nothing says to come back
 * right after the problem in place of what the code opens; without a home network, the sheet says so in place of the
 * code. The page closes with the sheet, and the sheet with the page once its time is up. Nothing in it takes focus
 * but, taller than the screen (the largest text), its notes on a plate, which Down rings and reads the sheet through from.
 */
@Composable
internal fun JournalSheet(journal: JournalOpener, onDismiss: () -> Unit) {
    var access by remember { mutableStateOf<JournalAccess?>(null) }
    val description = when (val a = access) {
        null -> null
        JournalAccess.Offline -> stringResource(R.string.journal_offline)
        is JournalAccess.Open -> stringResource(if (a.few) R.string.journal_few else R.string.journal_scan)
    }
    val scroll = rememberScrollState()
    HlSideSheet(stringResource(R.string.journal), onDismiss, description = description, scroll = scroll) { sheet ->
        LaunchedEffect(journal) {
            journal.serve { access = it }
            if (access is JournalAccess.Open) sheet.dismiss()
        }
        (access as? JournalAccess.Open)?.let { open ->
            // In capitals, the address takes the QR code's most compact mode; the page answers in either case.
            QrCode(open.address.uppercase(Locale.ROOT), open.address, small = true)
            // A plate only when the remote must read through: at the default text size the notes lie on the glass.
            if (scroll.maxValue > 0) {
                ReadingPlate(Modifier.fillMaxWidth().readsThrough(scroll)) {
                    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) { Notes() }
                }
            } else {
                Notes()
            }
        }
    }
}

/** What the log keeps, and when the page closes. */
@Composable
private fun Notes() {
    val c = Hl.colors
    Text(stringResource(R.string.journal_masked), style = Hl.type.meta, color = c.contentSecondary)
    Text(
        stringResource(R.string.journal_closes, formatDuration(JournalServer.LIFETIME_MS)),
        style = Hl.type.meta,
        color = c.contentSecondary,
    )
}
