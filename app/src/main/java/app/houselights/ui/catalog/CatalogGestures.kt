package app.houselights.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.BackHint
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.DefinitionList
import app.houselights.ui.design.components.DefinitionRow
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.InfoBlock
import app.houselights.ui.design.components.Intertitle
import app.houselights.ui.design.components.PosterStrip
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.components.SpecTable
import app.houselights.ui.design.components.SpecTableRow
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.UiError
import app.houselights.ui.util.formatDimensions

/**
 * The three gestures the small surfaces borrow from film materials, first side by side, then each
 * at the width the screens give it: the back of a case for what reads (a state, a measure, a
 * format), the intertitle for every message, the counter for what moves on.
 */
internal fun LazyListScope.catalogGestures() {
    item { Group("Gestures: the back of a case, the intertitle, the counter") { SideBySide() } }
    // On a screen, HlEmptyState: the same intertitle, its action focused as it appears.
    item { Group("Intertitle: neutral, attention, Back as the way out, no action") { Intertitles() } }
    item { Group("Error state: what fixes each error, on the spot or where it is fixed") { ErrorStates() } }
    item { Group("Counter: home, a folder's card, counting, a row") { Counters() } }
    item { Group("Back of a case: Affichage, À propos") { Jackets() } }
    item { Group("Empty frames and poster strips", still = true) { Frames() } }
}

@Composable
private fun SideBySide() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Gesture("back of a case: blocks to read, the dot on what is in service", Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                InfoBlock("Sortie actuelle") { CurrentOutput() }
                InfoBlock("Modes disponibles") {
                    SpecTable(
                        listOf(
                            SpecTableRow("Ultra HD", listOf(formatDimensions(3840, 2160)), inUse = true),
                            SpecTableRow("Full HD", listOf(formatDimensions(1920, 1080))),
                        ),
                        columns = emptyList(),
                    )
                }
            }
        }
        Gesture("intertitle: a short rule, ember for attention, one action", Modifier.weight(1f)) {
            Intertitle(
                "Bibliothèque injoignable",
                message = "192.168.1.20 ne répond pas. Vérifiez que le NAS est allumé.",
                attention = true,
            ) { HlButton("Réessayer", {}, emphasis = Emphasis.Primary) }
        }
        Gesture("counter: the ember line, the count, the file", Modifier.weight(1f)) {
            ScanProgress(3, 9, label = "Analyse de vos fichiers", current = "Bugonia (2025).mkv")
        }
    }
}

@Composable
private fun Intertitles() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Gesture("neutral: what to do, and a quieter way", Modifier.weight(1f)) {
            Intertitle("Aucun film pour l’instant", message = "Ajoutez le dossier qui les contient.") {
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    HlButton("Ajouter un dossier", {}, emphasis = Emphasis.Primary)
                    HlButton("Voir les dossiers", {})
                }
            }
        }
        Gesture("attention: the action that fixes it", Modifier.weight(1f)) {
            Intertitle(
                "TMDB refuse cette clé",
                message = "TMDB n’accepte pas la clé enregistrée dans les réglages. Vérifiez-la, ou remplacez-la.",
                attention = true,
            ) { HlButton("Modifier la clé", {}, emphasis = Emphasis.Primary) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Gesture("nothing fixes it: Back is the way out", Modifier.weight(1f)) {
            Intertitle("Ce titre n’est plus là", message = "Ce titre n’est plus dans la bibliothèque.", attention = true) { BackHint() }
        }
        Gesture("no action: the search field above is the way", Modifier.weight(1f)) {
            Intertitle("Aucun résultat", message = "Essayez un autre titre, sans l’année, ou un identifiant IMDb (tt…).")
        }
    }
}

/**
 * HlErrorState, one for each action an error offers, and none: the words and the action come with
 * the error. Here the actions that lead elsewhere lead nowhere.
 */
@Composable
private fun ErrorStates() {
    CompositionLocalProvider(LocalErrorFix provides {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
            Gesture("try again, on the spot", Modifier.weight(1f)) { HlErrorState(UiError.OFFLINE, onRetry = {}) }
            Gesture("trying again: it stays, its button at work", Modifier.weight(1f)) {
                HlErrorState(UiError.OFFLINE, onRetry = {}, retrying = true)
            }
            Gesture("nothing fixes it: Back is the way out", Modifier.weight(1f)) { HlErrorState(UiError.GONE, onRetry = {}) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
            Gesture("sign in again", Modifier.weight(1f)) { HlErrorState(UiError.DENIED, onRetry = {}) }
            Gesture("add a TMDB key, in the settings", Modifier.weight(1f)) { HlErrorState(UiError.NO_TMDB_KEY, onRetry = {}) }
            Gesture("change the key TMDB refuses", Modifier.weight(1f)) { HlErrorState(UiError.TMDB_KEY_REFUSED, onRetry = {}) }
        }
    }
}

@Composable
private fun Counters() {
    Gesture("first scan, on home: what is read, a line a reading column long, the file") {
        ScanProgress(41, 120, label = "Analyse de vos fichiers", current = "Mobland.S01E04.2160p.mkv", lineWidth = HlLayout.readableNarrow)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Gesture("a folder's card: one line, the file after the count", Modifier.width(HlLayout.readableNarrow)) {
            ScanProgress(3, 9, current = "Disclosure Day (2026).mkv")
        }
        Gesture("counting the files: a sliver, no count yet") { ScanProgress(0, 0) }
    }
    Gesture("the row that starts a scan: the same count", Modifier.width(HlLayout.readableNarrow)) {
        HlSettingRow("Analyser maintenant", value = "3$NBSP/${NBSP}9", valueLit = true, onClick = {}, chevron = false)
    }
}

@Composable
private fun Jackets() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Gesture("Affichage, on a TV that lists Ultra HD: the row that acts comes last", Modifier.width(HlLayout.readableNarrow)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                Text("Affichage", style = Hl.type.subject, color = Hl.colors.content)
                InfoBlock("Sortie actuelle") { CurrentOutput() }
                InfoBlock("Rendu de l’interface") { Text(formatDimensions(1920, 1080), style = Hl.type.meta, color = Hl.colors.content) }
                InfoBlock("Modes disponibles") {
                    SpecTable(
                        listOf(
                            SpecTableRow(
                                "Ultra HD",
                                listOf(formatDimensions(3840, 2160), rates("23,976", "24", "50", "59,94", "60")),
                                inUse = true,
                            ),
                            SpecTableRow("Full HD", listOf(formatDimensions(1920, 1080), rates("24", "50", "60"))),
                            SpecTableRow("HD", listOf(formatDimensions(1280, 720), rates("50", "60"))),
                        ),
                        columns = listOf(HlLayout.specFormat),
                    )
                }
                HlSettingRow("Résolution de l’interface", value = "Ultra HD 60${NBSP}Hz", onClick = {})
            }
        }
        Gesture("Affichage on the Zidoo: the player sets its output") {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                InfoBlock("Sortie actuelle") {
                    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        Text("Réglée par le lecteur", style = Hl.type.meta, color = Hl.colors.content)
                        Text(
                            "Le lecteur met l’interface à l’échelle de sa sortie. Résolution, fréquence d’images et HDR se " +
                                "choisissent dans Réglages du lecteur > Affichage.",
                            style = Hl.type.caption,
                            color = Hl.colors.contentSecondary,
                        )
                    }
                }
                InfoBlock("Rendu de l’interface") { Text(formatDimensions(1920, 1080), style = Hl.type.meta, color = Hl.colors.content) }
            }
        }
    }
    Gesture("À propos: states with their light, then the credits", Modifier.width(HlLayout.readableNarrow)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
            InfoBlock("État") {
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                    DefinitionRow("Lecteur Zidoo") { HlStatus(StatusState.Done, "Détecté") }
                    DefinitionRow("API de contrôle") { HlStatus(StatusState.Pending, "Vérification…") }
                }
            }
            InfoBlock("Générique") {
                DefinitionList(listOf("Données des films" to "TMDB", "Police" to "Archivo, SIL Open Font License"))
            }
        }
    }
}

@Composable
private fun Frames() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Gesture("a folder's card: one poster that fails, five free places", Modifier.width(HlLayout.readableNarrow)) {
            PosterStrip(listOf(BROKEN), slots = 6)
        }
        Gesture("a sheet's header: three places") {
            PosterStrip(listOf(BROKEN), slots = 3, slotWidth = HlSize.rowPreviewPoster, gap = HlSpace.xxs, shape = HlShapes.artwork)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Gesture("a folder with no poster yet: its name, like a one-sheet", Modifier.width(HlLayout.readableNarrow)) {
            PosterStrip(emptyList(), slots = 6, name = "Concerts")
        }
        Gesture("loading cards: empty frames, at a poster and a thumbnail") {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Bottom) {
                CardSkeleton(HlSize.poster, POSTER)
                CardSkeleton(HlSize.posterThumb, POSTER)
            }
        }
    }
}

/** "Ultra HD 60 Hz" in the technical voice, its frame size beside it, as Affichage sets it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentOutput() {
    // Short of room, the frame size goes under, whole: a number never breaks.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
        Text(
            "Ultra HD 60${NBSP}Hz",
            style = Hl.type.spec,
            color = Hl.colors.content,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            formatDimensions(3840, 2160),
            style = Hl.type.meta,
            color = Hl.colors.contentSecondary,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** One gesture or one state of it, named underneath. */
@Composable
private fun Gesture(caption: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        content()
        Spacer(Modifier.height(HlSpace.s))
        Text(caption, style = Hl.type.caption, color = Hl.colors.contentTertiary)
    }
}

private fun rates(vararg hz: String) = hz.joinToString(FACT_GAP) + "${NBSP}Hz"

private const val POSTER = 2f / 3f

/** A poster that fails to load. */
private val BROKEN = ImageRef("catalog-gestures-broken") { "file:///nonexistent/gesture-poster.jpg" }
