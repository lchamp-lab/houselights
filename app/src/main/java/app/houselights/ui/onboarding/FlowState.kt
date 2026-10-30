package app.houselights.ui.onboarding

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TypedPaths
import app.houselights.providers.local.UsedNas
import app.houselights.providers.local.Volume
import app.houselights.ui.onboarding.FlowKeys.CHOOSE
import app.houselights.ui.onboarding.FlowKeys.DISK
import app.houselights.ui.onboarding.FlowKeys.FIELD_HOST
import app.houselights.ui.onboarding.FlowKeys.FIELD_PASSWORD
import app.houselights.ui.onboarding.FlowKeys.FIELD_PATH
import app.houselights.ui.onboarding.FlowKeys.FIELD_UID
import app.houselights.ui.onboarding.FlowKeys.FIELD_USER
import app.houselights.ui.onboarding.FlowKeys.FOLDER
import app.houselights.ui.onboarding.FlowKeys.GUEST
import app.houselights.ui.onboarding.FlowKeys.NET_FIRST
import app.houselights.ui.onboarding.FlowKeys.TYPE_PATH
import app.houselights.ui.onboarding.FlowKeys.kindKey
import app.houselights.ui.onboarding.FlowKeys.nasKey
import app.houselights.ui.onboarding.FlowKeys.protocolKey
import app.houselights.ui.onboarding.FlowKeys.shareKey
import app.houselights.ui.util.UiError
import java.net.Inet4Address
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Where « Ajouter un dossier » stands ([FolderFlow]): the step shown and where focus goes, what the network answered,
 * and what was chosen and typed, the password aside, with the lists shown, written to [saved] as they change so that
 * a stopped process finds the flow where it was left. Screens read it; only the flow changes it. With [editing], the
 * flow is « Modifier le dossier » on that folder of the player's disks.
 */
open class FlowState(saved: SavedStateHandle, val editing: Long? = null) : ViewModel() {
    protected var stepName by saved.kept(KEPT_STEP, FlowStep.WHERE.name)

    /** The folder [editing] as the library holds it, its kind kept; null for an add, or until it is read. */
    var edited by mutableStateOf<SourceEntity?>(null)
        protected set

    /** The folder [editing] the flow could not open, said in its rows' place; Réessayer reads it again ([rereading]). */
    var lost by mutableStateOf<EditLost?>(null)
        protected set
    var rereading by mutableStateOf(false)
        protected set

    val step: FlowStep get() = FlowStep.entries.find { it.name == stepName } ?: FlowStep.WHERE

    /** Each step or level shown: its list starts at its top. */
    var view by mutableIntStateOf(0)
        protected set

    var aim by mutableStateOf<Aim?>(null)
        protected set

    /** The library's NAS, known before any search; the first step waits for them, so rows never arrive above focus. */
    var ready by mutableStateOf(false)
        protected set
    var used by mutableStateOf<List<UsedNas>>(emptyList())
        protected set

    /** What the ports of the NAS already used answer, by host. */
    val answers = mutableStateMapOf<String, NasAnswer>()

    /** The NAS that announced themselves, in the order they answered. */
    val announced = mutableStateListOf<FoundNas>()

    /** The player's own disks, its storage first, read as the flow opens (« Sur ce lecteur »). */
    val disks = mutableStateListOf<Volume>()

    /** Android's window asking to read the disks was refused for good: only its settings grant it now. */
    protected var disksRefused = false

    var searching by mutableStateOf(false)
        protected set

    protected var host by saved.kept<String?>(KEPT_HOST, null)
    protected var announcedName by saved.kept<String?>(KEPT_NAME, null)

    /** The NAS chosen; null on the first step before any. */
    val nas: FlowNas? get() = host?.let { FlowNas(it, announcedName) }

    /** The NAS was typed (« Saisir une adresse »), not picked from a list. */
    protected var typedNas by saved.kept(KEPT_TYPED_NAS, false)
    protected var protocolName by saved.kept<String?>(KEPT_PROTOCOL, null)
    val protocol: Protocol? get() = Protocol.entries.find { it.name == protocolName }

    /** The NAS answered both protocols and the step asked which one. */
    protected var askedProtocol by saved.kept(KEPT_ASKED_PROTOCOL, false)

    /** The NAS chosen answered; [silent] once it did not. */
    var reached by saved.kept(KEPT_REACHED, false)
        protected set
    var silent by mutableStateOf(false)
        protected set

    var typedHost by saved.kept(KEPT_TYPED_HOST, "")
        protected set
    var typedPath by saved.kept(KEPT_TYPED_PATH, "")
        protected set
    var user by saved.kept(KEPT_USER, "")
        protected set

    /** Never kept across a stopped process, as saved state is written in clear. */
    var password by mutableStateOf("")
        protected set
    var uid by saved.kept(KEPT_UID, "")
        protected set
    var gid by saved.kept(KEPT_GID, "")
        protected set

    var guest by saved.kept(KEPT_GUEST, false)
        protected set

    /** The account is the one a folder of this NAS signs in with, its password reused and never shown. */
    var accountKept by saved.kept(KEPT_ACCOUNT_KEPT, false)
        protected set

    /** The shared folders were listed with the account, or as a guest: the panel says who. */
    var signedIn by saved.kept(KEPT_SIGNED_IN, false)
        protected set

    /** The password of the account kept, read from the library again after a stopped process. */
    protected var keptPassword: Secret? = null

    /** This player's address, which an NFS export must allow; null when unknown. */
    protected var player: Inet4Address? = null

    /**
     * The account the shared folders were listed with, kept while « Changer de compte » types another ([keepAccount]):
     * Back gives it back ([accountBack]). Its password is never saved, so a stopped process forgets it.
     */
    private var listedWith by mutableStateOf<ListedWith?>(null)

    /** Who the shared folders were listed as: the account typed in « Changer de compte » only once it signs in. */
    val listedUser: String get() = listedWith?.user ?: user
    val listedAsGuest: Boolean get() = listedWith?.guest ?: guest

    /** The user and group « Compte NFS » was opened with, which Back gives back for a pair half typed ([identityBack]). */
    private var identityBefore: Pair<String, String>? = null

    protected var sharePaths by saved.kept<List<String>>(KEPT_SHARE_PATHS, arrayListOf())
    protected var shareRemarks by saved.kept<List<String>>(KEPT_SHARE_REMARKS, arrayListOf())
    protected var refusedPaths by saved.kept<List<String>>(KEPT_REFUSED_PATHS, arrayListOf())

    /** The NAS gave no list of its shared folders: « Saisir un chemin » takes their step. */
    var noList by saved.kept(KEPT_NO_LIST, false)
        protected set

    val shares: List<FlowShare>
        get() = sharePaths.mapIndexed { i, path -> FlowShare(path, shareRemarks.getOrElse(i) { "" }, path in refusedPaths) }

    protected var sharePath by saved.kept<String?>(KEPT_SHARE, null)

    /** The shared folder opened. */
    val share: FlowShare? get() = sharePath?.let { opened -> shares.firstOrNull { it.path == opened } ?: FlowShare(opened) }

    /** The shared folder last tried, which « Compte NFS » goes back to. */
    protected var trying by saved.kept<String?>(KEPT_TRYING, null)

    protected var pathNames by saved.kept<List<String>>(KEPT_PATH, arrayListOf())

    /** The folder shown at [FlowStep.BROWSE], then chosen, under [share]'s root. */
    val path: List<String> get() = pathNames

    /**
     * The level shown at [FlowStep.BROWSE]: never in the saved state, which a level of thousands of folders would burst;
     * a stopped process reads it again from the NAS, as [path] says.
     */
    var level by mutableStateOf<FolderLevel?>(null)
        protected set

    /** Levels read since the shared folder opened, so going up shows them at once. */
    protected val levels = mutableMapOf<List<String>, FolderLevel>()

    var pressed by mutableStateOf<Pressed?>(null)
        protected set
    val rowFailures = mutableStateMapOf<String, RowFailure>()

    /** The last failure's sentence in the panel; a typing step's goes under its field ([fieldError]). */
    var message by mutableStateOf<FlowMessage?>(null)
        protected set
    var fieldError by mutableStateOf<FieldError?>(null)
        protected set

    /** What the NAS proposes for a path typed that it does not have: "/volume1/media". */
    var suggestion by mutableStateOf<String?>(null)
        protected set

    /** Writing the folder failed: nothing was saved. */
    var notSaved by mutableStateOf<UiError?>(null)
        protected set

    /** The folder [at] under [opened] as a source the browsers read, signed in as the flow is. */
    protected fun place(opened: FlowShare? = null, at: List<String> = emptyList()): SourceEntity {
        val way = protocol ?: Protocol.SMB
        val smb = way == Protocol.SMB
        val identity = uid.toIntOrNull()?.let { u -> gid.toIntOrNull()?.let { u to it } }.takeIf { !smb }
        return SourceEntity(
            name = at.lastOrNull() ?: opened?.name.orEmpty(),
            kind = SourceKind.MOVIES,
            protocol = way,
            host = host.orEmpty(),
            share = opened?.path?.trim('/').orEmpty(),
            path = at.joinToString("/"),
            username = if (smb && !guest) user.trim() else "",
            password = if (smb && !guest) keptPassword ?: Secret(password) else Secret(""),
            uid = identity?.first,
            gid = identity?.second,
        )
    }

    /** « Changer de compte » opens the account step: the account the shared folders were listed with is kept. */
    protected fun keepAccount() {
        listedWith = ListedWith(user, password, guest, keptPassword, accountKept)
    }

    /** Back from « Changer de compte »: the account kept given back, true; false when there is none (not changing, or lost). */
    protected fun accountBack(): Boolean {
        val kept = listedWith ?: return false
        user = kept.user
        password = kept.password
        guest = kept.guest
        keptPassword = kept.keptPassword
        accountKept = kept.accountKept
        listedWith = null
        return true
    }

    /** Another account signed in, or another NAS: the one kept is no longer given back. */
    protected fun accountChanged() {
        listedWith = null
    }

    protected fun keepIdentity() {
        identityBefore = uid to gid
    }

    /**
     * Back from « Compte NFS »: what was typed is kept when it is both or neither; one alone, which the NAS is never
     * presented ([place]), gives back the pair it was opened with, else root. Another pair clears what refused the last.
     */
    protected fun identityBack() {
        val before = identityBefore ?: ("" to "")
        if (uid.isEmpty() != gid.isEmpty()) {
            uid = before.first
            gid = before.second
        }
        if ((uid to gid) != before) identityChanged()
        identityBefore = null
    }

    /** Another user and group: the shared folders that refused the last may open now. */
    protected fun identityChanged() {
        rowFailures.keys.filter { it.startsWith(FlowKeys.SHARE) }.forEach { rowFailures.remove(it) }
    }

    protected fun listShares(listed: List<FlowShare>) {
        sharePaths = ArrayList(listed.map { it.path })
        shareRemarks = ArrayList(listed.map { it.remark })
        refusedPaths = ArrayList(listed.filter { it.refused }.map { it.path })
    }

    /** [names] as a path typed on this NAS: from the root over NFS ("/volume1/video"), from the share over SMB. */
    protected fun joined(names: List<String>): String = names.joinToString("/").let { if (protocol == Protocol.NFS) "/$it" else it }

    internal fun asking() = Asking(host.orEmpty(), protocol == Protocol.NFS, guest, user.trim(), player?.hostAddress, identity()?.first)

    /** The NFS user and group « Compte NFS » presents, both typed; null for root, as [place] presents it then. */
    protected fun identity(): Pair<String, String>? = uid.takeIf { it.isNotEmpty() && gid.isNotEmpty() }?.let { it to gid }

    /** The account refused: a guest's, or the password. */
    protected fun refusal(): FlowMessage = if (guest) FlowMessage.GuestRefused else FlowMessage.LoginRefused

    protected fun sameShare(a: String, b: String): Boolean {
        val smb = protocol == Protocol.SMB
        val mine = TypedPaths.names(a, protocol)
        val theirs = TypedPaths.names(b, protocol)
        return mine.size == theirs.size && mine.indices.all { mine[it].equals(theirs[it], ignoreCase = smb) }
    }

    /** A row's failure: its two words, and the sentence in the panel; focus stays on it, OK tries again. */
    protected fun row(key: String, failure: FlowFailure, name: String, where: String) {
        rowFailures[key] = failure.words()
        panel(key, asking().refusal(failure, name, where))
    }

    protected fun panel(key: String, sentence: FlowMessage) {
        message = sentence
        aim(key)
    }

    protected fun said(error: FieldError) {
        fieldError = error
        aim(error.field)
    }

    protected fun go(next: FlowStep, focus: String) {
        stepName = next.name
        view++
        aim(focus)
    }

    protected fun aim(key: String) {
        aim = Aim(key, (aim?.serial ?: 0) + 1)
    }

    /** The first row of the step shown, where Back brings focus from deep in its list. */
    fun startKey(): String = when (step) {
        FlowStep.WHERE -> used.firstOrNull()?.let { nasKey(it.host) } ?: NET_FIRST
        FlowStep.ADDRESS -> FIELD_HOST
        FlowStep.PROTOCOLS -> protocolKey(Protocol.SMB)
        FlowStep.ACCOUNT -> FIELD_USER
        FlowStep.SHARES -> shares.firstOrNull()?.let { shareKey(it) } ?: TYPE_PATH
        FlowStep.TYPE_PATH -> FIELD_PATH
        FlowStep.NFS_ACCOUNT -> FIELD_UID
        FlowStep.BROWSE -> CHOOSE
        FlowStep.KIND -> kindKey(SourceKind.MOVIES)
    }

    /** Back on the account step: the guest's row when it signed in as a guest, else the password of a user typed. */
    protected fun accountFocus(): String = when {
        guest -> GUEST
        user.isBlank() -> FIELD_USER
        else -> FIELD_PASSWORD
    }

    // Failures ----------------------------------------------------------------------------------------------------------

    /**
     * Says [failure] of [key]: on its row in two words and in the panel, or under its field in a typing step; a disk of
     * this player's what Android must allow, in its [settings] once only they grant it.
     */
    protected fun refused(key: String, failure: FlowFailure, settings: () -> Boolean) {
        val at = host.orEmpty()
        if (failure == FlowFailure.SILENT) {
            silent = true
            if (used.any { it.host == at }) answers[at] = NasAnswer.Silent
        }
        when (step) {
            FlowStep.ADDRESS -> addressRefused(failure)
            FlowStep.ACCOUNT -> accountRefused(key, failure)
            FlowStep.TYPE_PATH -> pathRefused(failure)
            FlowStep.SHARES -> trying?.let { row(key, failure, FlowShare(it).name, it) } ?: row(key, failure, "", "")
            FlowStep.BROWSE -> if (protocol == Protocol.LOCAL) diskRefused(key, failure, settings()) else folderRefused(key, failure)
            else -> if (key.startsWith(DISK)) diskRefused(key, failure, settings()) else row(key, failure, "", "")
        }
    }

    /**
     * A disk of this player, or a folder on it, that did not open: its row in two words; the panel says what to allow,
     * that the disk is no longer plugged in, or that it failed.
     */
    private fun diskRefused(key: String, failure: FlowFailure, settings: Boolean) {
        rowFailures[key] = failure.words()
        val sentence = when (failure) {
            FlowFailure.READ_REFUSED, FlowFailure.MOUNT_REFUSED -> FlowMessage.DiskRefused(settings)
            FlowFailure.SILENT -> FlowMessage.DiskAbsent(nas ?: FlowNas(key.removePrefix(DISK)))
            else -> FlowMessage.Other
        }
        panel(key, sentence)
    }

    /** An address typed that did not answer, said under it. */
    private fun addressRefused(failure: FlowFailure) =
        said(FieldError(FIELD_HOST, if (failure == FlowFailure.SILENT) FlowMessage.Silent(host.orEmpty()) else FlowMessage.Other))

    /** The account step's failure: the account under its field, a password kept but not read for now under it too, else in the panel. */
    private fun accountRefused(key: String, failure: FlowFailure) = when (failure) {
        FlowFailure.LOGIN_REFUSED -> said(FieldError(if (guest) FIELD_USER else FIELD_PASSWORD, refusal()))
        FlowFailure.PASSWORD_UNREAD -> said(FieldError(FIELD_PASSWORD, FlowMessage.PasswordUnread))
        FlowFailure.SILENT -> panel(key, FlowMessage.Silent(host.orEmpty()))
        else -> panel(key, FlowMessage.Other)
    }

    /** A subfolder that did not open: its row, the sentence naming it from the NAS's root ([joined]). */
    private fun folderRefused(key: String, failure: FlowFailure) {
        val name = key.removePrefix(FOLDER)
        row(key, failure, name, joined(TypedPaths.names(share?.path.orEmpty(), protocol) + path + name))
    }

    /** What refused a path typed, said under its field: the shared folder tried, else the path as typed. */
    private fun pathRefused(failure: FlowFailure) {
        val typed = typedPath.trim()
        val names = TypedPaths.names(typed, protocol)
        val opened = trying ?: joined(names)
        val where = when (failure) {
            FlowFailure.MISSING -> typed
            FlowFailure.READ_REFUSED -> joined(names)
            else -> opened
        }
        val name = if (failure == FlowFailure.MISSING) names.firstOrNull().orEmpty() else FlowShare(opened).name
        said(FieldError(FIELD_PATH, asking().refusal(failure, name, where)))
    }
}

/** An account as the shared folders were listed with it: its user, its password typed or [keptPassword], or a [guest]. */
private class ListedWith(
    val user: String,
    val password: String,
    val guest: Boolean,
    val keptPassword: Secret?,
    val accountKept: Boolean,
)

/** A value of the flow's state, written to [this] saved state as it changes, so a stopped process finds it again. */
private fun <T> SavedStateHandle.kept(key: String, initial: T): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
    private val state: MutableState<T> = mutableStateOf(get<T>(key) ?: initial)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        this@kept[key] = value
    }
}

private const val KEPT_STEP = "flowStep"
private const val KEPT_HOST = "flowHost"
private const val KEPT_NAME = "flowName"
private const val KEPT_TYPED_NAS = "flowTypedNas"
private const val KEPT_PROTOCOL = "flowProtocol"
private const val KEPT_ASKED_PROTOCOL = "flowAskedProtocol"
private const val KEPT_REACHED = "flowReached"
private const val KEPT_TYPED_HOST = "flowTypedHost"
private const val KEPT_TYPED_PATH = "flowTypedPath"
private const val KEPT_USER = "flowUser"
private const val KEPT_UID = "flowUid"
private const val KEPT_GID = "flowGid"
private const val KEPT_GUEST = "flowGuest"
private const val KEPT_ACCOUNT_KEPT = "flowAccountKept"
private const val KEPT_SIGNED_IN = "flowSignedIn"
private const val KEPT_SHARE_PATHS = "flowSharePaths"
private const val KEPT_SHARE_REMARKS = "flowShareRemarks"
private const val KEPT_REFUSED_PATHS = "flowRefusedPaths"
private const val KEPT_NO_LIST = "flowNoList"
private const val KEPT_SHARE = "flowShare"
private const val KEPT_TRYING = "flowTrying"
private const val KEPT_PATH = "flowPath"
