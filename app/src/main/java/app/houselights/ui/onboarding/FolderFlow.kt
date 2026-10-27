package app.houselights.ui.onboarding

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.DiskAccess
import app.houselights.providers.local.ExportAccess
import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.FolderSave
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Standing
import app.houselights.providers.local.TypedPath
import app.houselights.providers.local.TypedPaths
import app.houselights.providers.local.UsedNas
import app.houselights.providers.local.Volume
import app.houselights.providers.local.exportAccess
import app.houselights.providers.local.nfsIdentity
import app.houselights.providers.local.samePlace
import app.houselights.providers.local.smbAccess
import app.houselights.providers.local.standing
import app.houselights.ui.onboarding.FlowKeys.CHANGE_ACCOUNT
import app.houselights.ui.onboarding.FlowKeys.CHOOSE
import app.houselights.ui.onboarding.FlowKeys.CONTINUE
import app.houselights.ui.onboarding.FlowKeys.FIELD_GID
import app.houselights.ui.onboarding.FlowKeys.FIELD_HOST
import app.houselights.ui.onboarding.FlowKeys.FIELD_PASSWORD
import app.houselights.ui.onboarding.FlowKeys.FIELD_PATH
import app.houselights.ui.onboarding.FlowKeys.FIELD_UID
import app.houselights.ui.onboarding.FlowKeys.FIELD_USER
import app.houselights.ui.onboarding.FlowKeys.GUEST
import app.houselights.ui.onboarding.FlowKeys.NAS
import app.houselights.ui.onboarding.FlowKeys.NFS_ACCOUNT
import app.houselights.ui.onboarding.FlowKeys.SIGN_IN
import app.houselights.ui.onboarding.FlowKeys.SUGGESTION
import app.houselights.ui.onboarding.FlowKeys.TYPE_ADDRESS
import app.houselights.ui.onboarding.FlowKeys.TYPE_PATH
import app.houselights.ui.onboarding.FlowKeys.diskKey
import app.houselights.ui.onboarding.FlowKeys.folderKey
import app.houselights.ui.onboarding.FlowKeys.kindKey
import app.houselights.ui.onboarding.FlowKeys.nasKey
import app.houselights.ui.onboarding.FlowKeys.protocolKey
import app.houselights.ui.onboarding.FlowKeys.shareKey
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * « Ajouter un dossier » as a guided flow (#83): the NAS, its protocol and account, its shared folder, the folder, its
 * content, each picked from a list, typing only as the list's last row. Waiting happens on the row pressed ([pressed]):
 * listing is the test, and the next step shows once its list has come, focus placed once ([aim]). What was chosen and
 * typed, the password aside, and the lists shown are kept in [saved] across a stopped process.
 */
class FolderFlow(
    private val network: FlowNetwork,
    private val library: LocalLibrary,
    /** The app's scope: a folder chosen is saved and scanned even if Back leaves the flow meanwhile. */
    private val background: CoroutineScope,
    /** Makes the folders the source; true when it changed (it was a server, or nothing yet). */
    private val useFolders: suspend () -> Boolean,
    saved: SavedStateHandle = SavedStateHandle(),
) : FlowState(saved) {
    constructor(app: AppContainer, context: Context, saved: SavedStateHandle) : this(
        network = AppFlowNetwork(app, context),
        library = app.library,
        background = app.scope,
        useFolders = {
            // The app sees the folder first: until then, the folders are no library and give way to Welcome (shownSource).
            app.hasFolders.first { it == true }
            val switched = app.settings.state.value.source != SourceConfig.Local
            if (switched) app.sources.switchTo(SourceConfig.Local)
            switched
        },
        saved = saved,
    )

    /** The library's folders on the NAS chosen, the latest first: how each row stands to them, what is reused. */
    private var folders: List<SourceEntity> = emptyList()
    private var kinds: Set<SourceKind> = emptySet()
    private val probes = mutableMapOf<String, Deferred<Set<Protocol>>>()
    private var job: Job? = null
    private var discovery: Job? = null

    init {
        viewModelScope.launch {
            used = suspendRunCatching { library.usedNas() }
                .onFailure { Log.w(TAG, "Reading the NAS used failed", it) }
                .getOrDefault(emptyList())
            kinds = suspendRunCatching { library.kinds() }.getOrDefault(emptySet())
            player = withContext(Dispatchers.IO) { suspendRunCatching { network.player() }.getOrNull() }
            val mounted = withContext(Dispatchers.IO) { suspendRunCatching { network.disks() } }
            disks += mounted.onFailure { Log.w(TAG, "Reading this player's disks failed", it) }.getOrDefault(emptyList())
            if (step != FlowStep.WHERE) restore()
            ready = true
            used.forEach { probe(it.host) }
            search()
            if (step == FlowStep.WHERE) aim(startKey())
        }
    }

    /** Back after a stopped process: the step left, but the account step again when it needs a password typed. */
    private suspend fun restore() {
        val at = host ?: return go(FlowStep.WHERE, startKey())
        folders = suspendRunCatching { library.foldersOn(at) }.getOrDefault(emptyList())
        if (protocol == Protocol.SMB && accountKept) keptPassword = folders.smbAccess()?.password
        val typedAccount = protocol == Protocol.SMB && !guest && keptPassword == null
        if (typedAccount && step in AFTER_ACCOUNT) {
            accountKept = false
            signedIn = false
            return go(FlowStep.ACCOUNT, if (user.isBlank()) FIELD_USER else FIELD_PASSWORD)
        }
        if (step == FlowStep.BROWSE || step == FlowStep.KIND) levels[path] = level ?: return go(FlowStep.WHERE, startKey())
        view++
        aim(entryKey())
    }

    // Step 1 · where the videos are --------------------------------------------------------------------------------

    /** The NAS announced that the library does not use: those it uses show among « Déjà utilisés », by their name. */
    val others: List<FoundNas> get() = announced.filter { found -> used.none { matches(it, found) } }

    /** The name [nas] announces on the network, when it does. */
    fun nameOf(nas: UsedNas): String? = announced.firstOrNull { matches(nas, it) }?.name?.takeIf { it.isNotBlank() }

    fun searchAgain() {
        if (!searching) search()
    }

    private fun search() {
        searching = true
        discovery?.cancel()
        discovery = viewModelScope.launch {
            try {
                suspendRunCatching { network.discover().collect { found(it) } }.onFailure { Log.w(TAG, "Finding NAS failed", it) }
            } finally {
                searching = false
            }
        }
    }

    private fun found(nas: FoundNas) {
        val i = announced.indexOfFirst { it.address == nas.address }
        if (i < 0) announced += nas else announced[i] = nas
    }

    /** Asks which protocols [at] answers; a NAS already used says « Ne répond pas » on its row when none does. */
    private fun probe(at: String): Deferred<Set<Protocol>> {
        val asked = viewModelScope.async { suspendRunCatching { network.protocols(at) }.getOrDefault(emptySet()) }
        probes[at] = asked
        if (used.any { it.host == at }) {
            answers[at] = NasAnswer.Checking
            viewModelScope.launch {
                val protocols = asked.await()
                answers[at] = if (protocols.isEmpty()) NasAnswer.Silent else NasAnswer.Answers(protocols)
            }
        }
        return asked
    }

    /** The protocols [at] answers, as its probe said, else as it says asked again: OK on a silent NAS tries again. */
    private suspend fun answering(at: String): Set<Protocol> {
        probes[at]?.await()?.takeIf { it.isNotEmpty() }?.let { return it }
        return probe(at).await()
    }

    fun chooseUsed(nas: UsedNas) {
        val name = nameOf(nas)
        press(nasKey(nas.host), R.string.signing_in) { connect(FlowNas(nas.host, name), typed = null, viaUsed = true) }
    }

    fun chooseFound(found: FoundNas) = press(nasKey(found.address), R.string.signing_in) {
        connect(FlowNas(found.address, found.name.takeIf { it.isNotBlank() }), typed = null, viaUsed = false, known = found.protocols)
    }

    fun typeAddress() = go(FlowStep.ADDRESS, FIELD_HOST)

    /**
     * OK on a disk of this player ([Volume]), [name]d as its row names it: opened at its root, as a shared folder is,
     * once the app may read it; else [ask] asks Android ([DiskAccess]), what was under way stopped as OK on another row
     * stops it, the row saying « Non autorisé » and the panel what to allow until it does, then the disk opens; after
     * its settings, the disk is chosen again.
     */
    fun chooseDisk(disk: Volume, name: String, ask: DiskAsk) {
        val key = diskKey(disk.id)
        val access = network.diskAccess(disksRefused)
        if (access == DiskAccess.GRANTED) return openDisk(disk, name)
        job?.cancel()
        pressed = null
        silent = false
        rowFailures[key] = RowFailure.NOT_ALLOWED
        message = FlowMessage.DiskRefused(settings = access == DiskAccess.IN_SETTINGS)
        ask.ask(access) { refusedForGood ->
            disksRefused = refusedForGood
            val now = network.diskAccess(refusedForGood)
            // Android answering once the flow has gone on: the disk is chosen again from its row.
            if (step != FlowStep.WHERE) return@ask
            if (now == DiskAccess.GRANTED) return@ask openDisk(disk, name)
            message = FlowMessage.DiskRefused(settings = now == DiskAccess.IN_SETTINGS)
            aim(key)
        }
    }

    /** The disk's root, its folders read as a NAS's shared folder's are, the disk [name]d as its row; Back returns to it. */
    private fun openDisk(disk: Volume, name: String) = press(diskKey(disk.id), R.string.opening) {
        begin(FlowNas(disk.id, name), typedAddress = false)
        protocolName = Protocol.LOCAL.name
        reached = true
        val root = FlowShare("")
        sharePath = root.path
        show(emptyList(), network.level(place(root), ""))
    }

    /**
     * What is typed in the field [field] ([FIELD_HOST], [FIELD_PATH]…), a user or group in whole numbers only
     * ([FolderForm.isId]); its message goes, and so does what the NAS proposed for a path.
     */
    fun type(field: String, typed: String) {
        when (field) {
            FIELD_HOST -> typedHost = typed
            FIELD_PATH -> {
                typedPath = typed
                suggestion = null
            }
            FIELD_USER -> user = typed
            FIELD_PASSWORD -> password = typed
            FIELD_UID -> if (FolderForm.isId(typed)) uid = typed else return
            FIELD_GID -> if (FolderForm.isId(typed)) gid = typed else return
        }
        if (fieldError?.field == field) fieldError = null
    }

    /** « Continuer »: the address typed, or a whole path ("smb://nas/video", "\\NAS\video"), its rest kept for later. */
    fun continueAddress() {
        val typed = TypedPaths.read(typedHost)
        val at = typed?.host ?: typed?.names?.firstOrNull()
        if (typed == null || at == null) return said(FieldError(FIELD_HOST, FlowMessage.Missing(R.string.error_host_missing)))
        val rest = if (typed.host != null) typed.names else typed.names.drop(1)
        if (rest.isNotEmpty()) typedPath = rest.joinToString("/").let { if (typed.protocol == Protocol.NFS) "/$it" else it }
        press(CONTINUE, R.string.signing_in) { connect(FlowNas(at), typed, viaUsed = false) }
    }

    /**
     * Opens the NAS [chosen]: a protocol typed with it, else the one its folders use when picked among them, else the
     * one that answers; both answering, the step asks, SMB first.
     */
    private suspend fun connect(chosen: FlowNas, typed: TypedPath?, viaUsed: Boolean, known: Set<Protocol>? = null) {
        begin(chosen, typedAddress = typed != null)
        val protocols = known?.takeIf { it.isNotEmpty() } ?: answering(chosen.host)
        if (protocols.isEmpty()) throw FlowRefusal(FlowFailure.SILENT)
        reached = true
        val wanted = typed?.protocol?.takeIf { it in protocols }
        val kept = folders.firstOrNull()?.protocol?.takeIf { viaUsed && it in protocols }
        val only = protocols.singleOrNull()
        when {
            wanted != null -> open(wanted)
            kept != null -> open(kept)
            only != null -> open(only)
            else -> {
                askedProtocol = true
                go(FlowStep.PROTOCOLS, protocolKey(Protocol.SMB))
            }
        }
    }

    /** Starts again from the NAS [chosen]: what was chosen after it goes, what was typed for another NAS too. */
    private suspend fun begin(chosen: FlowNas, typedAddress: Boolean) {
        if (host?.trim()?.equals(chosen.host.trim(), ignoreCase = true) != true) {
            user = ""
            password = ""
        }
        host = chosen.host
        announcedName = chosen.name
        typedNas = typedAddress
        protocolName = null
        askedProtocol = false
        reached = false
        silent = false
        guest = false
        accountKept = false
        signedIn = false
        accountChanged()
        keptPassword = null
        uid = ""
        gid = ""
        noList = false
        trying = null
        listShares(emptyList())
        sharePath = null
        levels.clear()
        rowFailures.keys.filter { !it.startsWith(NAS) }.forEach { rowFailures.remove(it) }
        folders = suspendRunCatching { library.foldersOn(chosen.host) }.getOrDefault(emptyList())
    }

    // Steps 2 and 3 · protocol, account -------------------------------------------------------------------------------

    fun chooseProtocol(chosen: Protocol) = press(protocolKey(chosen), R.string.signing_in) { open(chosen) }

    /**
     * Opens [chosen] on the NAS: NFS lists its exports, carrying over its folders' user and group; SMB signs in as its
     * folders do, else asks for an account. A list the NAS does not give is typed instead.
     */
    private suspend fun open(chosen: Protocol) {
        val at = host ?: return
        protocolName = chosen.name
        if (chosen == Protocol.NFS) {
            folders.nfsIdentity()?.let { (carriedUid, carriedGid) ->
                uid = carriedUid.toString()
                gid = carriedGid.toString()
            }
            val exports = suspendRunCatching { network.exports(at) }.onFailure { Log.w(TAG, "Listing the exports of $at failed", it) }
            exports.onSuccess { list -> showShares(list.map { FlowShare(it.path, refused = refuses(it)) }) }
            exports.onFailure { typeInstead() }
            return
        }
        val kept = folders.smbAccess() ?: return go(FlowStep.ACCOUNT, FIELD_USER)
        user = kept.username
        guest = kept.username.isBlank()
        keptPassword = kept.password
        val listed = suspendRunCatching { network.shares(place()) }
        val failure = listed.exceptionOrNull()?.flowFailure()
        when {
            failure == null -> {
                accountKept = true
                signedIn = true
                showShares(listed.getOrThrow().map { FlowShare(it.name, it.remark) })
            }
            failure == FlowFailure.LOGIN_REFUSED -> {
                // The account kept no longer opens the NAS: the account step, its user filled.
                keptPassword = null
                go(FlowStep.ACCOUNT, if (guest) FIELD_USER else FIELD_PASSWORD)
                fieldError = FieldError(if (guest) FIELD_USER else FIELD_PASSWORD, refusal())
            }
            failure == FlowFailure.SILENT -> throw listed.exceptionOrNull() ?: FlowRefusal(failure)
            else -> {
                accountKept = true
                typeInstead()
            }
        }
    }

    /** Whether [export]'s clients leave this player out, as far as they can be read ([exportAccess]). */
    private fun refuses(export: ExportedFolder): Boolean = player?.let { exportAccess(export.groups, it) == ExportAccess.REFUSED } == true

    /** « Se connecter », or « Sans compte (invité) » ([asGuest], or a user left empty): listing the shares is the test. */
    fun signIn(asGuest: Boolean = false) = press(if (asGuest) GUEST else SIGN_IN, R.string.signing_in) {
        guest = asGuest || user.isBlank()
        keptPassword = null
        val listed = suspendRunCatching { network.shares(place()) }
        when (listed.exceptionOrNull()?.flowFailure()) {
            null -> {
                accountKept = false
                signedIn = true
                accountChanged()
                showShares(listed.getOrThrow().map { FlowShare(it.name, it.remark) })
            }
            FlowFailure.LOGIN_REFUSED, FlowFailure.SILENT -> throw listed.exceptionOrNull() ?: FlowRefusal(FlowFailure.OTHER)
            // Signed in, but no list: its shared folder is typed.
            else -> {
                signedIn = true
                accountChanged()
                typeInstead()
            }
        }
    }

    /** « Changer de compte »: the account step, the account the shared folders were listed with kept until another signs in. */
    fun changeAccount() {
        keepAccount()
        go(FlowStep.ACCOUNT, FIELD_USER)
    }

    // Step 4 · the shared folder --------------------------------------------------------------------------------------

    private fun showShares(listed: List<FlowShare>) {
        noList = false
        listShares(listed)
        go(FlowStep.SHARES, sharesFocus())
    }

    /** The NAS lists no shared folder: one is typed, its step saying why. */
    private fun typeInstead() {
        noList = true
        listShares(emptyList())
        go(FlowStep.TYPE_PATH, FIELD_PATH)
    }

    /** Focus on the shares: the share of the last folder added from this NAS, else the first one still open to add. */
    private fun sharesFocus(): String {
        val listed = shares
        val open = listed.filter { standingOf(it) !is Standing.Added }
        val last = folders.firstOrNull { it.protocol == protocol }
        val focused = last?.let { folder -> open.firstOrNull { sameShare(it.path, folder.share) } }
            ?: open.firstOrNull()
            ?: listed.firstOrNull()
        return focused?.let { shareKey(it) } ?: TYPE_PATH
    }

    fun openShare(opened: FlowShare) = press(shareKey(opened), R.string.opening) {
        trying = opened.path
        val root = network.level(place(opened), "")
        sharePath = opened.path
        levels.clear()
        show(emptyList(), root)
    }

    /** How the folder [at] under [opened] stands to the library's folders on this NAS: said on its row ([standing]). */
    fun standingOf(opened: FlowShare?, at: List<String> = emptyList()): Standing =
        opened?.let { place(it, at).standing(folders) } ?: Standing.Apart

    fun openNfsAccount() {
        keepIdentity()
        go(FlowStep.NFS_ACCOUNT, FIELD_UID)
    }

    /** « Continuer » on « Compte NFS »: both or neither, then back where it was opened, on the folder last tried. */
    fun continueIdentity() {
        if (uid.isEmpty() != gid.isEmpty()) {
            return said(
                if (uid.isEmpty()) {
                    FieldError(FIELD_UID, FlowMessage.Missing(R.string.error_uid_missing))
                } else {
                    FieldError(FIELD_GID, FlowMessage.Missing(R.string.error_gid_missing))
                },
            )
        }
        identityChanged()
        message = null
        val tried = trying?.let { path -> shares.firstOrNull { it.path == path } }
        if (noList) go(FlowStep.TYPE_PATH, FIELD_PATH) else go(FlowStep.SHARES, tried?.let { shareKey(it) } ?: NFS_ACCOUNT)
    }

    fun typePath() = go(FlowStep.TYPE_PATH, FIELD_PATH)

    /**
     * « Continuer » on a path typed whole: the app splits it into its shared folder and the folder inside ([TypedPaths]),
     * then opens step 5 at that folder. What the NAS does not have is said under the field, with what it proposes.
     */
    fun continuePath() {
        val way = protocol ?: return
        val names = TypedPaths.read(typedPath, nas = host, protocol = way)?.names.orEmpty()
        if (names.isEmpty()) {
            val missing = if (way == Protocol.NFS) R.string.error_full_path_missing_nfs else R.string.error_full_path_missing_smb
            return said(FieldError(FIELD_PATH, FlowMessage.Missing(missing)))
        }
        press(CONTINUE, R.string.signing_in) { openTyped(names, way) }
    }

    /**
     * Opens [names]: over SMB its first name is the share, listed or not (one hidden, "video$", is never listed), as
     * the NAS spells it when listed; over NFS the longest export listed starts it, else the longest start that mounts.
     */
    private suspend fun openTyped(names: List<String>, way: Protocol) {
        val known = shares.map { it.path }.takeIf { !noList && way == Protocol.NFS && it.isNotEmpty() }
        val located = TypedPaths.locate(names, way, known) { export -> network.level(place(FlowShare(export)), "") }
        if (located == null) return shareMissing(names, way)
        val opened = shares.firstOrNull { sameShare(it.path, located.share) }
            ?: FlowShare(if (way == Protocol.NFS) "/" + located.share else located.share)
        trying = opened.path
        val at = TypedPaths.names(located.path, way)
        val read = suspendRunCatching { network.level(place(opened), at.joinToString("/")) }
        val failure = read.exceptionOrNull() ?: return openAt(opened, at, read.getOrThrow())
        if (failure.flowFailure() != FlowFailure.MISSING) throw failure
        folderMissing(opened, at, failure)
    }

    private fun openAt(opened: FlowShare, at: List<String>, read: FolderLevel) {
        sharePath = opened.path
        levels.clear()
        show(at, read, focusOn = CHOOSE)
    }

    /** No shared folder the NAS lists starts [names]: said under the field, with the one it may have meant. */
    private fun shareMissing(names: List<String>, way: Protocol) {
        if (way == Protocol.NFS) {
            val meant = TypedPaths.meant(names, shares.map { it.path }).firstOrNull()
            val proposed = meant?.let { joined(TypedPaths.names(it.share) + TypedPaths.names(it.path)) }
            return pathFailed(FlowMessage.NoExport(typedPath.trim()), proposed)
        }
        val near = TypedPaths.closest(names.first(), shares.map { it.path })
        pathFailed(FlowMessage.NoShare(host.orEmpty(), names.first()), near?.let { joined(listOf(it) + names.drop(1)) })
    }

    /**
     * The folder [at] under [opened] is not there: which of its names is missing, read a level at a time from the shared
     * folder's root, with a folder of that level within one letter of it ([TypedPaths.closest]); [failure] when none is.
     */
    private suspend fun folderMissing(opened: FlowShare, at: List<String>, failure: Throwable) {
        val smb = protocol == Protocol.SMB
        val root = TypedPaths.names(opened.path, protocol)
        val first = suspendRunCatching { network.level(place(opened), "") }
        if (first.exceptionOrNull()?.flowFailure() == FlowFailure.MISSING) return shareMissing(root + at, protocol ?: Protocol.SMB)
        var read = first.getOrThrow()
        for (i in at.indices) {
            val name = at[i]
            if (read.folders.none { it == name || smb && it.equals(name, ignoreCase = true) }) {
                val near = TypedPaths.closest(name, read.folders)
                val where = joined(root + at.take(i))
                return pathFailed(FlowMessage.NoFolder(where, smb, name), near?.let { joined(root + at.take(i) + it + at.drop(i + 1)) })
            }
            if (i < at.lastIndex) read = network.level(place(opened), at.take(i + 1).joinToString("/"))
        }
        throw failure
    }

    private fun pathFailed(said: FlowMessage, proposed: String?) {
        suggestion = proposed
        fieldError = FieldError(FIELD_PATH, said)
        aim(if (proposed != null) SUGGESTION else FIELD_PATH)
    }

    /** « Utiliser /volume1/media »: the field filled with what the NAS proposes, and continued. */
    fun useSuggestion() {
        typedPath = suggestion ?: return
        continuePath()
    }

    // Step 5 · browse to the folder -----------------------------------------------------------------------------------

    fun openFolder(name: String) {
        val opened = share ?: return
        press(folderKey(name), R.string.opening) {
            val at = path + name
            show(at, network.level(place(opened), at.joinToString("/")))
        }
    }

    /** Shows the level [read] at [at], focus on [focusOn], else as [browseFocus] says. */
    private fun show(at: List<String>, read: FolderLevel, focusOn: String? = null) {
        levels[at] = read
        pathNames = ArrayList(at)
        levelFolders = ArrayList(read.folders)
        levelVideos = read.videos
        go(FlowStep.BROWSE, focusOn ?: browseFocus(at, read))
    }

    /**
     * Focus in a level: at a shared folder's root, the first subfolder open to choose named for a kind the library
     * lacks, else the first open; deeper, « Choisir ce dossier »; a level holding both kinds, its first subfolder.
     */
    private fun browseFocus(at: List<String>, read: FolderLevel): String {
        val open = read.folders.filter { standingOf(share, at + it) !is Standing.Added }
        if (at.isNotEmpty() && !holdsBoth(read.folders)) return CHOOSE
        val lacking = SourceKind.entries.filter { it !in kinds }
        val named = open.firstOrNull { name -> kindNamed(name)?.let { it in lacking } == true }.takeIf { at.isEmpty() }
        return (named ?: open.firstOrNull())?.let { folderKey(it) } ?: CHOOSE
    }

    fun chooseFolder() = go(FlowStep.KIND, kindKey(suggestedKind()))

    /** The folder's name, as the library will call it: its last segment; a disk's root, the disk's. */
    fun folderName(): String = path.lastOrNull() ?: if (protocol == Protocol.LOCAL) announcedName.orEmpty() else share?.name.orEmpty()

    /** The kind its name suggests, else the kind the library lacks (films alone: shows), else films. */
    private fun suggestedKind(): SourceKind =
        kindNamed(folderName()) ?: if (kinds == setOf(SourceKind.MOVIES)) SourceKind.SHOWS else SourceKind.MOVIES

    // Step 6 · what it holds ------------------------------------------------------------------------------------------

    /** OK on a kind adds the folder, its row saying « Ajout… »; [onDone] once it is in the library. */
    fun add(kind: SourceKind, onDone: (FlowDone) -> Unit) {
        val opened = share ?: return
        val key = kindKey(kind)
        press(key, R.string.adding) {
            val folder = place(opened, path).copy(name = folderName(), kind = kind)
            val outcome = suspendRunCatching { saveInBackground(folder) }
            outcome.onFailure { e ->
                Log.w(TAG, "The folder at ${folder.host} was not saved", e)
                notSaved = e.toWriteError()
                aim(key)
            }
            when (val added = outcome.getOrNull()) {
                is Added.Done -> onDone(FlowDone(added.switched, added.id))
                is Added.Overlaps -> {
                    val name = added.existing.name.replaceFirstChar { it.uppercase() }
                    message = FlowMessage.Overlaps(name, folder.standing(listOf(added.existing)))
                    aim(key)
                }
                null -> {}
            }
        }
    }

    private sealed interface Added {
        data class Done(val switched: Boolean, val id: Long?) : Added

        data class Overlaps(val existing: SourceEntity) : Added
    }

    /** Saves [folder] in the app's scope, so Back cannot half-save it; failures are logged even with no flow left. */
    private suspend fun saveInBackground(folder: SourceEntity): Added = background.async {
        suspendRunCatching { save(folder) }.onFailure { Log.w(TAG, "The folder at ${folder.host} was not saved", it) }
    }.await().getOrThrow()

    /**
     * Adds [folder], checked once more against the library (a folder added meanwhile), and makes the folders the source.
     * One added earlier when switching the source failed is this one again: only the switch is left.
     */
    private suspend fun save(folder: SourceEntity): Added = when (val result = library.addFolder(folder)) {
        FolderSave.Saved -> Added.Done(useFolders(), library.overlapping(folder)?.id)
        is FolderSave.Overlaps -> if (result.existing.samePlace(folder) && useFolders()) {
            Added.Done(switched = true, id = result.existing.id)
        } else {
            Added.Overlaps(result.existing)
        }
        FolderSave.Gone -> Added.Overlaps(folder)
    }

    // Back, focus and failures ----------------------------------------------------------------------------------------

    /**
     * Back: up one level, else the step before, its choice kept and focused; from a typing step, the row that opened it;
     * from the first step, [leave]. It cancels what is under way, but never an add.
     */
    fun back(leave: () -> Unit) {
        if (pressed?.words == R.string.adding) return
        job?.cancel()
        pressed = null
        fieldError = null
        suggestion = null
        message = null
        notSaved = null
        val at = host.orEmpty()
        when (step) {
            FlowStep.WHERE -> leave()
            FlowStep.ADDRESS -> go(FlowStep.WHERE, TYPE_ADDRESS)
            FlowStep.PROTOCOLS -> nasStep(at)
            FlowStep.ACCOUNT -> beforeAccount(at)
            FlowStep.SHARES -> beforeShares(at)
            FlowStep.TYPE_PATH -> if (noList) beforeShares(at) else go(FlowStep.SHARES, TYPE_PATH)
            FlowStep.NFS_ACCOUNT -> {
                identityBack()
                go(if (noList) FlowStep.TYPE_PATH else FlowStep.SHARES, NFS_ACCOUNT)
            }
            FlowStep.BROWSE -> up()
            FlowStep.KIND -> go(FlowStep.BROWSE, CHOOSE)
        }
    }

    /**
     * Back from the account step: the shared folders it was opened from to change the account, with the account they
     * were listed as ([accountBack]); else, or that account lost with a stopped process, the step before.
     */
    private fun beforeAccount(at: String) = when {
        accountBack() -> go(FlowStep.SHARES, CHANGE_ACCOUNT)
        askedProtocol -> go(FlowStep.PROTOCOLS, protocolKey(Protocol.SMB))
        else -> nasStep(at)
    }

    /** The step the NAS was chosen in: its row, or the address typed. */
    private fun nasStep(at: String) = if (typedNas) go(FlowStep.ADDRESS, FIELD_HOST) else go(FlowStep.WHERE, nasKey(at))

    private fun beforeShares(at: String) = when {
        protocol == Protocol.SMB && !accountKept -> go(FlowStep.ACCOUNT, accountFocus())
        askedProtocol -> go(FlowStep.PROTOCOLS, protocolKey(protocol ?: Protocol.SMB))
        else -> nasStep(at)
    }

    /** Up one level, focus on the folder left; from the shared folder's root, its step. */
    private fun up() {
        val now = path
        val opened = share
        if (now.isEmpty() || opened == null) return beforeBrowse(opened)
        val above = now.dropLast(1)
        val left = folderKey(now.last())
        levels[above]?.let { return show(above, it, focusOn = left) }
        // A path typed, or a stopped process: the level above is read first.
        job = viewModelScope.launch {
            val read = suspendRunCatching { network.level(place(opened), above.joinToString("/")) }
            read.onSuccess { show(above, it, focusOn = left) }
            read.onFailure {
                Log.w(TAG, "Reading the level above failed", it)
                beforeBrowse(opened)
            }
        }
    }

    /** Back from the root of [opened]: the shared folders, the path typed, or a disk's row. */
    private fun beforeBrowse(opened: FlowShare?) = when {
        protocol == Protocol.LOCAL -> go(FlowStep.WHERE, diskKey(host.orEmpty()))
        noList -> go(FlowStep.TYPE_PATH, FIELD_PATH)
        else -> go(FlowStep.SHARES, opened?.let { shareKey(it) } ?: TYPE_PATH)
    }

    /** Where focus starts on the step shown, as arriving there does. */
    private fun entryKey(): String = when (step) {
        FlowStep.ACCOUNT -> if (user.isBlank()) FIELD_USER else FIELD_PASSWORD
        FlowStep.SHARES -> sharesFocus()
        FlowStep.BROWSE -> level?.let { browseFocus(path, it) } ?: CHOOSE
        FlowStep.KIND -> kindKey(suggestedKind())
        else -> startKey()
    }

    /**
     * Runs [work] for the row or button [key], which says [words] meanwhile; OK on it again does nothing, OK on another
     * cancels it; a failure is said where it belongs ([failed]). Nothing interrupts an add.
     */
    private fun press(key: String, @StringRes words: Int, work: suspend () -> Unit) {
        val now = pressed
        if (now?.key == key || now?.words == R.string.adding) return
        job?.cancel()
        val mine = Pressed(key, words)
        pressed = mine
        message = null
        fieldError = null
        suggestion = null
        notSaved = null
        silent = false
        rowFailures.remove(key)
        job = viewModelScope.launch {
            try {
                suspendRunCatching { work() }.onFailure { e ->
                    Log.w(TAG, "$key failed", e)
                    failed(key, e)
                }
            } finally {
                if (pressed === mine) pressed = null
            }
        }
    }

    /** Says [e] of [key] where it belongs ([refused]), a disk's sentence naming Android's settings once only they grant it. */
    private fun failed(key: String, e: Throwable) =
        refused(key, e.flowFailure()) { network.diskAccess(disksRefused) == DiskAccess.IN_SETTINGS }

    companion object {
        /** The steps that read the NAS with the account: after a stopped process, they need its password again. */
        private val AFTER_ACCOUNT = setOf(FlowStep.SHARES, FlowStep.TYPE_PATH, FlowStep.BROWSE, FlowStep.KIND)

        fun factory(app: AppContainer, context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { FolderFlow(app, context.applicationContext, createSavedStateHandle()) }
        }
    }
}

private const val TAG = "AddFolderFlow"
