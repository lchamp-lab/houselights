package app.houselights.ui.onboarding

import androidx.compose.foundation.background
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
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
import app.houselights.ui.design.components.Wordmark
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.toPx

/**
 * First launch: a lobby of this week's films behind the wordmark, then where yours are. [problem]
 * says why the choice made last could not be saved.
 */
@Composable
fun WelcomeScreen(onLocal: () -> Unit, onJellyfin: () -> Unit, problem: UiError? = null) {
    val app = appContainer()
    val lostSource = app.settings.state.collectAsStateWithLifecycle().value.lostSource
    val focus = remember { FocusRequester() }
    val vm: WelcomeViewModel = viewModel(factory = WelcomeViewModel.factory(app, HlSize.posterWall.toPx()))
    val posters by vm.posters.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        focus.tryRequestFocus()
        // Back from a choice: a wall TMDB left empty is asked for again.
        vm.show()
    }
    val black = Hl.colors.background

    Box(Modifier.fillMaxSize().background(black)) {
        PosterWall(posters)
        PosterWallScrim()
        Row(
            Modifier.fillMaxSize().padding(horizontal = HlSpace.safeH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Wordmark(style = Hl.type.wordmarkHero)
                Spacer(Modifier.height(HlSpace.m))
                Text(
                    stringResource(R.string.welcome_tagline),
                    style = Hl.type.title,
                    color = Hl.colors.contentSecondary,
                    modifier = Modifier.widthIn(max = HlLayout.welcomeTagline),
                )
            }
            Spacer(Modifier.width(HlSpace.xl))
            Column(Modifier.width(HlLayout.welcomeChoices), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                Text(stringResource(R.string.welcome_title), style = Hl.type.lead, color = Hl.colors.content)
                // Back here because the connection kept could not be read again: said, not left to guess.
                if (lostSource) AttentionNote(stringResource(R.string.welcome_lost_secrets))
                problem?.let { AttentionNote(stringResource(welcomeProblem(it))) }
                Spacer(Modifier.height(HlSpace.xxs))
                // The same height side by side; under each, how it reaches your films.
                HlListItem(
                    stringResource(R.string.source_local),
                    stringResource(R.string.source_local_desc),
                    onLocal,
                    Modifier.focusRequester(focus),
                    icon = HlIcons.Folder,
                    badges = listOf("NFS", "SMB", "TMDB"),
                    descriptionLines = CHOICE_LINES,
                )
                HlListItem(
                    stringResource(R.string.source_jellyfin),
                    stringResource(R.string.source_jellyfin_desc),
                    onJellyfin,
                    icon = HlIcons.Server,
                    badges = listOf("HTTPS", "HTTP"),
                    descriptionLines = CHOICE_LINES,
                )
            }
        }
    }
}

/**
 * What the welcome screen says of [problem], the failure of the choice made last, and the form of the
 * first folder it leads to: never to look in the settings, which neither leads to.
 */
@StringRes
internal fun welcomeProblem(problem: UiError): Int = when (problem) {
    UiError.NOT_SAVED -> R.string.error_not_saved_player
    else -> problem.message
}

/** Lines of description each welcome choice keeps room for: the longest one's. */
private const val CHOICE_LINES = 3
