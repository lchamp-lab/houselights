package app.houselights.ui.onboarding

import androidx.compose.foundation.background
import androidx.annotation.StringRes
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.tryRequestFocus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.PosterWall
import app.houselights.ui.design.components.PosterWallScrim
import app.houselights.ui.design.components.Lockup
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.TaskTitle
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.toPx

/** First launch: trending films behind the lockup, then the source choice; [problem] is the last choice's failure. */
@Composable
fun WelcomeScreen(onLocal: () -> Unit, onJellyfin: () -> Unit, problem: UiError? = null) {
    val app = appContainer()
    val lostSource = app.settings.state.collectAsStateWithLifecycle().value.lostSource
    val vm: WelcomeViewModel = viewModel(factory = WelcomeViewModel.factory(app, HlSize.welcomePoster.toPx()))
    val posters by vm.posters.collectAsStateWithLifecycle()
    // Returning from a choice retries a wall that TMDB left empty.
    LaunchedEffect(Unit) { vm.show() }
    Welcome(posters, onLocal, onJellyfin, lostSource, problem)
}

/** The welcome screen as drawn: [posters] on the wall, the source choice focused; [lostSource] and [problem] noted. */
@Composable
internal fun Welcome(posters: List<String>, onLocal: () -> Unit, onJellyfin: () -> Unit, lostSource: Boolean, problem: UiError?) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
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
                    style = Hl.type.title,
                    color = Hl.colors.contentSecondary,
                    modifier = Modifier.widthIn(max = HlLayout.welcomeTagline),
                )
                Column(Modifier.weight(1f).padding(top = HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    // The saved connection could not be read back: say so rather than leave it unexplained.
                    if (lostSource) AttentionNote(stringResource(R.string.welcome_lost_secrets))
                    problem?.let { AttentionNote(stringResource(welcomeProblem(it))) }
                }
            }
            Spacer(Modifier.width(HlSpace.xl))
            // A focused choice's frame stays inside the safe area.
            Column(Modifier.padding(end = HlFocus.reach).width(HlLayout.welcomeChoices)) {
                TaskTitle(stringResource(R.string.welcome_title))
                Spacer(Modifier.height(HlSpace.m))
                // Raised on glass over the wall; both reserve the same description lines, so they have the same height.
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    HlListItem(
                        stringResource(R.string.source_local),
                        stringResource(R.string.source_local_desc),
                        onLocal,
                        Modifier.focusRequester(focus),
                        icon = HlIcons.Folder,
                        badges = listOf("NFS", "SMB", "TMDB"),
                        descriptionLines = CHOICE_LINES,
                        level = PanelLevel.Raised,
                    )
                    HlListItem(
                        stringResource(R.string.source_jellyfin),
                        stringResource(R.string.source_jellyfin_desc),
                        onJellyfin,
                        icon = HlIcons.Server,
                        badges = listOf("HTTPS", "HTTP"),
                        descriptionLines = CHOICE_LINES,
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

/** Description lines each welcome choice reserves: as many as the longest needs. */
private const val CHOICE_LINES = 3
