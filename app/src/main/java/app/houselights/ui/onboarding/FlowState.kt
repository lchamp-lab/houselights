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
import app.houselights.ui.onboarding.FlowKeys.CHOOSE
import app.houselights.ui.onboarding.FlowKeys.FIELD_HOST
import app.houselights.ui.onboarding.FlowKeys.FIELD_PASSWORD
import app.houselights.ui.onboarding.FlowKeys.FIELD_PATH
import app.houselights.ui.onboarding.FlowKeys.FIELD_UID
import app.houselights.ui.onboarding.FlowKeys.FIELD_USER
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
 * a stopped process finds the flow where it was left. Screens read it; only the flow changes it.
 */
open class FlowState(saved: SavedStateHandle) : ViewModel() {
    protected var stepName by saved.kept(KEPT_STEP, FlowStep.WHERE.name)

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

    /** « Changer de compte » opened the account step: Back returns to the shared folders. */
    protected var changing by saved.kept(KEPT_CHANGING, false)

    protected var sharePaths by saved.kept(KEPT_SHARE_PATHS, arrayListOf<String>())
    protected var shareRemarks by saved.kept(KEPT_SHARE_REMARKS, arrayListOf<String>())
    protected var refusedPaths by saved.kept(KEPT_REFUSED_PATHS, arrayListOf<String>())

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

    protected var pathNames by saved.kept(KEPT_PATH, arrayListOf<String>())

    /** The folder shown at [FlowStep.BROWSE], then chosen, under [share]'s root. */
    val path: List<String> get() = pathNames

    protected var levelFolders by saved.kept(KEPT_LEVEL_FOLDERS, arrayListOf<String>())
    protected var levelVideos by saved.kept(KEPT_LEVEL_VIDEOS, -1)

    /** The level shown at [FlowStep.BROWSE]. */
    val level: FolderLevel? get() = if (levelVideos < 0) null else FolderLevel(levelFolders, levelVideos)

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

    protected fun listShares(listed: List<FlowShare>) {
        sharePaths = ArrayList(listed.map { it.path })
        shareRemarks = ArrayList(listed.map { it.remark })
        refusedPaths = ArrayList(listed.filter { it.refused }.map { it.path })
    }

    /** [names] as a path typed on this NAS: from the root over NFS ("/volume1/video"), from the share over SMB. */
    protected fun joined(names: List<String>): String = names.joinToString("/").let { if (protocol == Protocol.NFS) "/$it" else it }

    internal fun asking() = Asking(host.orEmpty(), protocol == Protocol.NFS, guest, user.trim(), player?.hostAddress)

    /** The account refused: a guest's, or the password. */
    protected fun refusal(): FlowMessage = if (guest) FlowMessage.GuestRefused else FlowMessage.LoginRefused

    protected fun sameShare(a: String, b: String): Boolean {
        val smb = protocol == Protocol.SMB
        val mine = TypedPaths.names(a, protocol)
        val theirs = TypedPaths.names(b, protocol)
        return mine.size == theirs.size && mine.indices.all { mine[it].equals(theirs[it], ignoreCase = smb) }
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
}

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
private const val KEPT_CHANGING = "flowChanging"
private const val KEPT_SHARE_PATHS = "flowSharePaths"
private const val KEPT_SHARE_REMARKS = "flowShareRemarks"
private const val KEPT_REFUSED_PATHS = "flowRefusedPaths"
private const val KEPT_NO_LIST = "flowNoList"
private const val KEPT_SHARE = "flowShare"
private const val KEPT_TRYING = "flowTrying"
private const val KEPT_PATH = "flowPath"
private const val KEPT_LEVEL_FOLDERS = "flowLevelFolders"
private const val KEPT_LEVEL_VIDEOS = "flowLevelVideos"
