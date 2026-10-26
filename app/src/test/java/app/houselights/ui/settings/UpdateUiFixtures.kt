package app.houselights.ui.settings

import app.houselights.update.UpdateControls
import app.houselights.update.UpdateOffer
import app.houselights.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 0.4.1, as GitHub offers it to the installed 0.4.0: 6.4 MB, three notes in English and French. */
internal val OFFER = UpdateOffer(
    tag = "v0.4.1",
    versionName = "0.4.1",
    versionCode = 1010,
    prerelease = false,
    size = 6_444_633,
    sha256 = "e".repeat(64),
    url = "https://github.com/lchamp-lab/houselights/releases/download/v0.4.1/Houselights-0.4.1.apk",
    notes = mapOf(
        "en" to "- Forced subtitles are chosen again.\n- A scan cut by standby resumes.\n- Sagas follow release order.",
        "fr" to "- Les sous-titres forcés sont de nouveau choisis.\n- Une analyse coupée par la veille reprend.\n" +
            "- Les sagas suivent l’ordre de sortie.",
    ),
)

/** The updater of the project's release, 0.4.0 installed, checked [checkedAt]; [offer] found. */
internal fun updates(offer: UpdateOffer? = OFFER, checkedAt: Long? = System.currentTimeMillis()) =
    UpdateState(on = true, installed = "0.4.0", checkedAt = checkedAt, offer = offer)

/** The updater as the screens drive it: what they ask, in order; each changes nothing unless [onAsk] does. */
internal class FakeControls(val state: MutableStateFlow<UpdateState>) : UpdateControls {
    val asked = mutableListOf<String>()

    /** What an ask does to the state, as the updater would. */
    var onAsk: (String) -> Unit = {}

    val ui get() = UpdateUi(state, this)

    private fun ask(what: String) {
        asked += what
        onAsk(what)
    }

    override fun checkNow() = ask("checkNow")

    override fun opened() {
        asked += "opened"
    }

    override fun closed() {
        asked += "closed"
    }

    override fun update() = ask("update")

    override fun retry() = ask("retry")

    override fun cancel() = ask("cancel")

    override fun allow() = ask("allow")

    override fun reopen() = ask("reopen")

    override fun shownOnHome(versionCode: Long) {
        asked += "shownOnHome $versionCode"
        state.update { it.copy(seenOnHome = versionCode) }
    }

    override fun updatedSaid() {
        asked += "updatedSaid"
        state.update { it.copy(updated = it.updated?.copy(said = true)) }
    }

    /** What was asked, the sheet opening and closing left out. */
    val actions get() = asked.filter { it != "opened" && it != "closed" }
}
