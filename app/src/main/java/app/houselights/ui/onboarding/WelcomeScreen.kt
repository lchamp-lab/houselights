package app.houselights.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.settings.AppSettings
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.Lockup
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.PosterWall
import app.houselights.ui.design.components.PosterWallScrim
import app.houselights.ui.design.components.TaskTitle
import app.houselights.ui.design.components.rememberDescriptionLines
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.toPx

/**
 * First launch: trending films behind the lockup, then the source choice; [problem] is the last choice's failure.
 * A saved connection the Keystore did not open is read again on Réessayer, and once read leads home ([onReadBack]).
 */
@Composable
fun WelcomeScreen(
    onLocal: () -> Unit,
    onJellyfin: () -> Unit,
    problem: UiError? = null,
    onReadBack: () -> Unit = {},
    vm: WelcomeViewModel = viewModel(factory = WelcomeViewModel.factory(appContainer(), HlSize.welcomePoster.toPx())),
) {
    val app = appContainer()
    val s = app.settings.state.collectAsStateWithLifecycle().value
    val reading by app.settings.reading.collectAsStateWithLifecycle()
    val posters by vm.posters.collectAsStateWithLifecycle()
    // Returning from a choice retries a wall that TMDB left empty.
    LaunchedEffect(Unit) { vm.show() }
    val choosing = rememberReadBack(s, reading, onReadBack)
    Welcome(
        posters, choosing(onLocal), choosing(onJellyfin), s.lostSource, problem,
        settingsReset = s.settingsReset,
        readAgain = app.settings::readAgain.takeIf { s.unreadSource }, reading = reading,
        onJellyfinFocus = app.jellyfin::lookAhead,
    )
}

/**
 * The welcome screen as drawn: [posters] on the wall, the source choice focused; [lostSource], [problem] and
 * [settingsReset] noted.
 * With [readAgain], the saved connection was not opened for now: its note's Réessayer runs it, focused, busy while [reading].
 * [onJellyfinFocus] runs as « Serveur Jellyfin » takes focus, so its sign-in opens on the servers already found.
 */
@Composable
internal fun Welcome(
    posters: List<String>,
    onLocal: () -> Unit,
    onJellyfin: () -> Unit,
    lostSource: Boolean,
    problem: UiError?,
    settingsReset: Boolean = false,
    readAgain: (() -> Unit)? = null,
    reading: Boolean = false,
    onJellyfinFocus: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (readAgain != null) retry else focus).tryRequestFocus() }
    val black = Hl.colors.background

    Box(Modifier.fillMaxSize().background(black)) {
        PosterWall(posters)
        PosterWallScrim()
        Row(
            Modifier.fillMaxSize().padding(horizontal = HlSpace.safeH, vertical = HlSpace.safeV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // The lockup and tagline in the middle, what went wrong under them: nothing moves as a note comes.
                Spacer(Modifier.weight(1f))
                Lockup(HlSize.wordmarkHero)
                Spacer(Modifier.height(HlSpace.l))
                Text(
                    stringResource(R.string.welcome_tagline),
                    style = Hl.type.lead,
                    color = Hl.colors.contentSecondary,
                    modifier = Modifier.widthIn(max = HlLayout.welcomeTagline),
                )
                Column(Modifier.weight(1f).padding(top = HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    // The saved connection, or the whole settings file, could not be read back: say so rather than
                    // leave it unexplained.
                    if (lostSource) AttentionNote(stringResource(R.string.welcome_lost_secrets))
                    if (settingsReset) AttentionNote(stringResource(R.string.welcome_settings_reset))
                    // Kept, but not opened for now: said so, with a way to read it again.
                    readAgain?.let {
                        PartFailed(
                            UiError.SECRETS_UNREAD,
                            reading,
                            it,
                            Modifier.widthIn(max = HlLayout.welcomeTagline),
                            actionModifier = Modifier.focusRequester(retry),
                        )
                    }
                    problem?.let { AttentionNote(stringResource(welcomeProblem(it))) }
                }
            }
            Spacer(Modifier.width(HlSpace.xl))
            // A focused choice's frame stays inside the safe area.
            Column(Modifier.padding(end = HlFocus.reach).width(HlLayout.welcomeChoices)) {
                TaskTitle(stringResource(R.string.welcome_title))
                Spacer(Modifier.height(HlSpace.m))
                // Raised on glass over the wall, a line of words each and no mark: both reserve the lines the longer
                // takes at the viewer's text size, so they have one height and no band is left empty.
                val local = stringResource(R.string.source_local_desc)
                val jellyfin = stringResource(R.string.welcome_jellyfin_desc)
                val lines = rememberDescriptionLines(listOf(local, jellyfin), HlLayout.welcomeChoices)
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    HlListItem(
                        stringResource(R.string.source_local),
                        local,
                        onLocal,
                        Modifier.focusRequester(focus),
                        icon = HlIcons.Folder,
                        descriptionLines = lines,
                        level = PanelLevel.Raised,
                    )
                    HlListItem(
                        stringResource(R.string.source_jellyfin),
                        jellyfin,
                        onJellyfin,
                        // Its servers looked for during the gesture before its sign-in.
                        Modifier.onFocusChanged { if (it.hasFocus) onJellyfinFocus() },
                        icon = HlIcons.Server,
                        descriptionLines = lines,
                        level = PanelLevel.Raised,
                    )
                }
            }
        }
    }
}

/** The message for [problem] on Welcome and the first folder form, never pointing to Settings (unreachable there). */
@StringRes
internal fun welcomeProblem(problem: UiError): Int = when (problem) {
    UiError.NOT_SAVED -> R.string.error_not_saved_player
    else -> problem.message
}

/**
 * Leads home ([onReadBack]) once a saved connection the Keystore did not open ([AppSettings.unreadSource]) is read at
 * last, by Réessayer or on coming back to the app, as at a launch that read it. A choice wrapped by the function
 * returned replaces that connection, and leads where it leads; a read asked after it ([reading]), the choice having
 * failed, leads home again.
 */
@Composable
internal fun rememberReadBack(s: AppSettings, reading: Boolean, onReadBack: () -> Unit): (() -> Unit) -> () -> Unit {
    var awaiting by remember { mutableStateOf(false) }
    val readBack by rememberUpdatedState(onReadBack)
    LaunchedEffect(s.unreadSource, reading) { if (s.unreadSource) awaiting = true }
    LaunchedEffect(awaiting, s.source) { if (awaiting && s.source != null) readBack() }
    return { choice ->
        {
            awaiting = false
            choice()
        }
    }
}
