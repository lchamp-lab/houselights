# Houselights design system

Houselights is named after the lights of a cinema auditorium, which dim before the film starts.
The interface follows that idea: the room is dark and quiet, and the light comes from the films.
Everything lives in `app/src/main/java/app/houselights/ui/design`. **Screens never hard-code a colour,
a size, an opacity or a font**: they use the tokens and components below, and a test checks it on
every build (see *Guard*).

## Principles

1. **One light source.** The room is pure black (`#000000`). Colour comes from the artwork: the
   *ambient light* (`LocalAmbient`) is picked from the focused backdrop and spills onto the room. When
   nothing is selected, the room takes the colours of the posters on the wall (`CollectionLight`).
   Ember, the only fixed accent, marks **states**: in use, your choice, needs attention. It is a
   light, not an ink: a gradient from bright orange to raspberry wherever it has room to show.
2. **Ten-foot first.** Nothing below 12 sp, no content below 14 sp, every text at least 4.5:1 on the
   surface it sits on (see *Contrast*). Content stays inside the overscan-safe area (`HlSpace.safeH` /
   `safeV`). Every focusable element shows an unmistakable focus state. Every screen is laid out on
   the TV canvas, 960 × 540 dp (`HlLayout.CANVAS_WIDTH`): `HouselightsTheme` sets the density from
   the screen's real width, not from what the device declares (the Zidoo declares 240 dpi, which
   would draw everything a quarter smaller); the system's text scale stays the viewer's.
3. **Calm motion.** Motion answers the user (focus, opening, playing, choosing: the pill of a track
   slides to the option chosen). Two orchestrated moments only:
   the *house lights* going down before playback, and the *poster wall* drifting on the welcome screen.
4. **Words do one job.** Sentence case, no all-caps labels, no punctuation between facts (layout space
   separates them). In French, the interface uses *vous*. The one exception to capitals is the billing
   block, set like the credits at the foot of a poster.

## Identity: the typography of film materials

Nothing is added for decoration. Houselights is recognised by its type and its vocabulary, applied
with discipline everywhere: one family, **Archivo**, whose widths are the voices of the interface.

| Voice | Width | What it says | Styles |
|---|---|---|---|
| The poster | condensed (70) | titles, names in the billing block, large figures, what a panel is about | `display`, `headline`, `figure`, `subject`, `billing`, `wordmark` |
| Reading | normal (100) | overviews, descriptions, names in lists, values | `title`, `lead`, `body`, `entry`, `meta`, `caption`, `detail` |
| Controls | wide (112) | the words of buttons and of the options of a track | `label` |
| Technical | expanded (125), tabular figures | formats and specifications: short, set apart | `spec`, `badge` |

Each width declares only the weights its styles use, so the system never fakes a bold
(`TypeScaleTest`). **One family is the rule; a second face is justified only by what the text is,
never to decorate:** raw text read as written, whose columns must line up, uses the system's
monospaced face (`code`, the NFO). Scripts Archivo does not cover fall back to the system font.

The technical voice speaks the vocabulary of a Blu-ray case: formats by their names (`Ultra HD`,
`Full HD`, `Dolby Vision`, `HDR10`, `TrueHD Atmos`), aspect ratios written `2,39:1`, the frame size as
`3840 × 1608`. Two moments carry the identity further:

- **House lights.** Playing a title runs one cue (`HouseLightCue`, `DIM`): the room fades first (text,
  buttons, rows: `Modifier.houseLit`), the scrims lift so the picture shows whole, then black and the
  player. The player starts when the room is dark, not after a fixed time: the cue runs while the
  title is being prepared. Coming back runs the cue backwards. While the lights go down the remote
  waits: a second OK does nothing, Back brings the lights up again and nothing plays.
- **Billing block.** On a detail page, director and leading cast read as a sentence, "a film by NAME
  with NAME, NAME and NAME": names in condensed capitals, small words and commas receding. On one line:
  it keeps as many leading names as fit, and never breaks inside a name.

**The wordmark** is type and nothing else: "Houselights" in the condensed SemiBold, in the ember
light across its letters (`emberLight`), with no halo, glow or shadow; the light in the room comes
from the films. It is the one use of ember that is not a state. It appears twice: large on the welcome screen, where the drifting wall
of posters is the bold element, and at the size of a subject in About. The settings do not sign
their column with it.

## Gestures: the small surfaces

The large surfaces carry the identity (the detail page, the billing block, the technical sheet).
The small ones (a settings pane, a message, a scan going on) borrow **one** gesture from film
materials, never two, and invent nothing. The catalogue shows the three side by side.

- **The back of a case (A): what reads.** A state, a measure, a format is an `InfoBlock`: a
  hairline, its name in `caption`, then what it says. Formats speak the technical voice ("Ultra HD
  60 Hz"); sizes, counts and frame sizes stay in `meta`, tabular, as on the technical sheet. Pairs
  are a `DefinitionList` (a state with its light goes in a `DefinitionRow`); several of a kind are a
  `SpecTable`, the ember dot on the one in service. A block is flush with the title above it:
  only what acts is a row, in its well. Affichage, Stockage's legend, À propos, a Jellyfin server.
- **The intertitle (B): every message.** As in a silent film, and like the one-sheet of a title
  without artwork: a short rule, one line in the voice of the poster (`subject`), one line to read
  (`body`, secondary, `readableNarrow`), at most one action and a quieter one. A ember rule says
  attention: a failure, a question that cannot be undone. It takes the place of what the screen
  would show, from the top of that place, on the screen's margin: never centred, never a snackbar,
  a Toast or a dialog. `HlEmptyState` for a screen, `SheetConfirm` in a sheet.
- **The counter (C): what moves on.** The ember line, the count at its end in tabular figures
  ("3 / 9", one string, `scan_progress`), the file being read in a quiet `detail` line
  (`ScanProgress`). A duration reads "2 h 02", time left "Il reste 20 min"; a timecode only names a
  frame of the film, never a button.

## Tokens

### Colour (`Color.kt`)

| Token | Value | Role |
|---|---|---|
| `background` | `#000000` | the room; masks over artwork; before Compose draws, the window and the launcher icon's ground (`@color/room`) |
| `surface` / `surfaceRaised` | `#121110` / `#1D1B19` | quiet panels / dialogs, menus, focused fields |
| `outline` | `#3A3632` | hairlines, badge borders |
| `content` | `#F2F0EB` | projector white: primary text |
| `contentSecondary` | `#A39E96` | secondary text; 4.96:1 at worst, in a primary button |
| `contentTertiary` | `#8C867E` | the quietest text: 4.76:1 on `surfaceRaised`, its lowest permitted surface |
| `contentDecorative` | `#4A4642` | never text: empty frames, inactive marks |
| `ember` | `#FF7A4D` | states only, a closed list (below); the wordmark is the one exception. Solid, for what is too small for a gradient: a dot, a check, a word |
| `emberFrom`, `emberTo` | `#FF9F1C` → `#FF2E63` | the ember light (`emberLight()`): the same states where they have room, a gradient from start to end across what they fill. The wordmark, a progress line and the counter, the rule of an intertitle that calls for attention, the edge of a field being typed in, the edge and disc of what is lit |
| `focus` | `#F2F0EB` | projector white again: the fill and outline of focus |
| `onFocus` | `#000000` | a focused control's name and icon |
| `onFocusSecondary` | `#5C5B59` | the line under that name (a summary, a runtime): black at 62 % on `focus`, 5.96:1 |

`HlAlpha` names every opacity, by role: `QUIET_SURFACE`, `CONTROL_FILL` / `CONTROL_FILL_STRONG`,
`WELL`, `BORDER`, `DIVIDER`, `SCRIM` / `SCRIM_SIDE`, `PLATE`, `TRACK`, `FOCUS_QUIET`, `LIT_DISC`,
`ARTWORK_EDGE`. `PLATE` is the plate under whatever sits on artwork: a card's marks
and progress, and the controls laid over a backdrop. The veils laid over artwork are recipes, in
`HlScrim` (see *Veils*).

**Ember is a closed list.** It says three things and nothing else. *In use*: a title's progress,
the track playing, the setup in use, a field being typed in. *Your choice*: the check of the current
choice, a value or a match you chose, an active filter. *Needs attention*: an error and its light,
the action a question asks about. The wordmark is the one exception. It never marks focus (projector
white), where you are (white, like the current option of a track), a fact (a score is grey) or a
series of data (a meter's parts are white and grey). A screen never names it: it says the state and
the component lights it (`HlStatus`, `AttentionNote`, `lit`, `valueLit`, `detailLit`); the guard
holds screens to it.

**Your choice, written one way.** One glyph, `HlIcons.Check`, two inks: white says done (watched),
ember says your choice. Over artwork both sit on the same dark plate (`SelectedMark` is the
watched mark in ember); in a list, a sheet or a menu the check stands alone. In words, a choice
takes the light of a state (`HlStatus` On: "Votre choix", "Actuel"); how something was done is a
plain `meta` word without light ("Automatique"). A `Badge` is what a case would print, formats, age
ratings and protocols: never a state.

**Contrast.** Text reaches 4.5:1 and icons and marks 3:1 on the surface they really sit on, each
translucent layer composed over what lies under it. `ContrastTest` lists every pair the system
draws and checks it on every build: a new pair goes there before a component uses it. The states of
a control are named colours, never an opacity laid on the current one: the line under a name is
`contentTertiary` at rest, `contentSecondary` when chosen, `onFocusSecondary` with focus. Two pairs
are forbidden: `contentTertiary` on a `WELL` row inside a raised panel (≈ 4.09:1) and on
`CONTROL_FILL_STRONG` (≈ 3.66:1). There, the quiet line stays `contentSecondary`, as in setting
rows and buttons. A line that names a state (`detailLit`: an active filter) is `ember` at rest
and `onFocusSecondary` with focus. A **disabled** control sits in a `WELL` with `contentTertiary`
words, on the room only: inside a raised sheet that pair is the forbidden one, and a sheet leaves
out an action that cannot be taken.

**On artwork.** A translucent white fill is made for the dark room: over a light image it vanishes.
What sits on an image takes a dark plate instead (`PLATE`, matte, never blurred), so its contrast no
longer depends on the picture: a card's marks and progress line, and the controls that pass
`onArtwork` (the home bar's track and settings button). On that plate the options of a track at rest
are `content`: `contentSecondary` is forbidden there (≈ 3.47:1 over a white image). Focus does not
change.

### Veils (`Scrim.kt`)

`HlScrim` holds the recipes of what is laid over artwork, never loose numbers: the **left veil**
behind the text column, the **top veil** under the top bar, the **bottom fade** to black, the
**ambient spill** below a backdrop, the **collection light**, and the **welcome wall** (its veil;
nothing lights the wordmark, the posters are the light of that screen). The left veil follows the
text column in dp, `safeH` + `readableNarrow`, then fades out over `HlLayout.veilFade`: never a
share of a screen assumed 960 dp wide.

**No picture, no light.** Without artwork, `Backdrop(null)` is the black room: no warm glow stands
in for a picture. The ambient light of a title without a backdrop, or whose backdrop fails to load,
is transparent (`LocalAmbient` too, by default): it spills nothing and lights no card. Where a wall
of posters shows with nothing selected, the posters light the room (`CollectionLight`).

Under the text column the left veil keeps a **floor**, read from the small copy of the backdrop the
ambient light is picked from (`ArtworkSample`): the opacity the column's quietest text
(`contentTertiary`: genres, the billing block's small words) needs for 4.5:1, through the other
veils and under the spill, wherever the veil falls short. A dark image keeps its veil; a white one
gets 0.875, more where the spill adds its light (the overview alone would need 0.79, but quieter
words sit above and below it). A highlight smaller than a twentieth of the column does not count
(`FLOOR_QUANTILE`). The picture is never cropped or letterboxed for it, and nothing lights it.

### Type (`Type.kt`)

Eight sizes with clear steps: **12 · 14 · 16 · 20 · 24 · 32 · 44 · 64 sp**, plus 80 for the wordmark on
the welcome screen. Line heights sit on a 2 sp grid. Numbers use tabular figures (`tnum`) in every technical
style and wherever a figure changes under the eye (`figure`, `meta`, `label`, `caption`, `detail`),
so durations, sizes and counts line up and do not jitter. A text without a style is `body`
(`HouselightsTheme` provides it, and maps tv-material's roles onto the scale): never tv-material's
own face.

| Style | Size / line | Role (one each) |
|---|---|---|
| `display` | 64 / 72 | title of a detail page without logo |
| `headline` | 44 / 48 | screen titles, home hero title without logo |
| `figure` | 32 / 36 | a figure that states something (storage used) |
| `subject` | 32 / 36 | what a panel, a sheet or a settings pane is about ("Modifier", "Fiche technique", "Affichage"); the line of an intertitle |
| `cardTitle` | 20 / 24 | a title set on a card without artwork, like a plain one-sheet |
| `title` | 24 / 30 | taglines, an episode's name in its heading |
| `lead` | 20 / 26 | row headers, names of cards and results, episode names |
| `spec` | 20 / 26 | headline of a technical sheet |
| `billing` / `billingJoin` | 18 and 14 / 24 | the billing block, capitals with case-sensitive forms |
| `body` | 16 / 24 | overviews, descriptions |
| `entry` | 16 / 20 | a name to read in a list: a setting, a menu option, a navigation entry, a title in a line |
| `label` | 16 / 20 | the words of buttons and of the options of a track |
| `meta` | 16 / 20 | facts and values: year, runtime, score, sizes; names under artwork |
| `caption` | 14 / 20 | secondary lines: hints, roles, paths, group titles |
| `detail` | 14 / 18 | the line under a name in a row, an entry or a choice; tighter than `caption`, so two lines read as one block |
| `code` | 14 / 22 | raw text read as written (NFO XML), in the system's monospaced face |
| `badge` | 12 / 16 | format and rating badges |
| `wordmark` / `wordmarkHero` | 32 / 36 and 80 / 84 | the Houselights wordmark: the size of a subject in About, large over the welcome wall |

Archivo covers Latin (with Vietnamese); Greek, Cyrillic, Thai or Korean names fall back to the system
font. No other font is shipped; line heights leave room for the fallback, and for Vietnamese: in
the condensed bold of `display` and `cardTitle`, a stacked mark (ổ, ế) rises to 0.92 em and a
descender falls to 0.20 em, so their lines are 1.125 and 1.2 em apart. A capital with a stacked
mark (Ổ, 1.05 em) right under a descender can still touch it; the catalogue's stress shows both.

A frame that holds text is never a fixed height in dp, which the viewer's text scale would
overflow: it is a minimum (`heightIn(min = …)`), or, where it must not move with what it shows (the
home hero, an episode's overview), `textRoom(height)`, a height given at the default text size
that grows with the text.

### Space and size (`Dimens.kt`)

| Token | Content |
|---|---|
| `HlSpace` | 4 dp grid: `xxs` 4, `xs` 8, `s` 12, `m` 16, `l` 24, `xl` 32, `xxl` 48; `xxs` between a name and the line under it; `fact` 20 between facts; `safeH` 48 / `safeV` 28; `fadeEdge` 24, how far what scrolls fades at an edge |
| `HlInset` | inner padding of containers, by what they hold: `tight` 8 (menus, results with a thumbnail), `compact` 12 (dense rows), `card` 24 (panels, cards), `sheet` 32 (sheets) |
| `HlShapes` | radius follows hierarchy: `badge` 3, `artwork` 4, `field` 6, `row` 8 (rows that can be chosen), `panel` 12, `sheet` 16 (sheets and menus), `control` pill, `avatar` circle. A shape set close inside another keeps their corners parallel: **outer radius = inner radius + gap** (a menu: 8 + 8 = 16; a result: 4 + 8 = 12) |
| `HlSize` | artwork (poster 112, landscape 220, episode 264, avatar 76 in a column of 120, thumbnails), `control` 48 for every button, icons 20, discs 52, marks 20, dots 8, setting rows 64, previews in a row (48 tall, a slot of 84), meter 10, the short rules of a loading title (48, 24), the short rule heading an intertitle (`intertitleRule`, 24 by 2) |
| `HlLayout` | measures of layout parts: reading widths (640, 520), the left veil's fade past them (240), side columns (280, 340), pane top 72, the reading sheet (864) and the side sheet (520), title art, hero, info line, track table columns, the label of a round button (160 wide at most, 36 of room under its row) |
| `HlControl` | inner padding of buttons and track options, badges; the gap between an icon and its text (8) |
| `HlFocus` | focus scale by family (artwork 1.10, cards 1.06, pills and discs 1.04, panels 1.02, rows 1), `reach(size, scale)`: how far a focused element reaches past its ends, the room its container keeps; `ring` 5, how far a focus ring stands outside; siblings stepping back (dim 0.3, colour kept 0.3, in `RECEDE_STEPS` 3), the glow of focused artwork, 20 dp, `edge` 28 (`safeV`): how far a list keeps focus from its ends |

### Motion, icons, scrolling

| Token | File | Content |
|---|---|---|
| `HlMotion`, `awaitMotion` | `Motion.kt` | `FAST` 150, `STANDARD` 250, `SLOW` 600, `DIM` 1000 ms; `SETTLE` 220 ms before a backdrop changes; `SKELETON_DELAY` 400 ms before a placeholder shows; `WALL_DRIFT`; `ENTER_SCALE`, where a reading sheet and a menu grow from. What follows a motion waits for its end (`awaitMotion`, or the animated value itself), never a fixed `delay`: with animations off, everything settles without moving and nothing waits |
| `LocalHouseLights`, `HouseLightCue` | `Auditorium.kt` | how far the house lights are down (read while drawing), and the order of the cue |
| `HlIcons` | `Icons.kt` | every icon of the app, drawn on one line (24 dp grid, 1.75 stroke, round caps and joins): places (Folder, Server, Display, Storage), actions (Add, Edit, Refresh, Restart, Test, Close, Settings), marks (Check, Star, Attention), the remote's Back. Play is the one glyph taken from Material, filled, as on a remote; a state that must read as on is the same drawing filled (a favourite: `Heart`, `HeartFilled`). No other Material icon (a test forbids it); the catalogue shows every glyph at 24 and 20 dp, on the room and in a focused control |
| `rememberListScroll`, `ListScroll`, `RowScroll` | `Scroll.kt` | lists scroll only as much as needed (`EdgeScroll`), never against the edge of the screen: a list keeps focus `HlFocus.edge` from its top and bottom, so the last row stops inside the safe area; a list that starts under a header or a hero (`ListScroll(top)`) brings its rows back to where its first one rests; rows keep focus 80 dp from the sides, so the first card always scrolls home |
| `scrollEdges(state, color)` | `Scroll.kt` | what scrolls fades into its surface over `HlSpace.fadeEdge`, at the top and the bottom, on a side only while there is more that way: a line cut at the edge reads as more to come. A gradient of the surface's own colour over the content, no layer, so only on an opaque surface: the room in `HlTwoPane`, the raised sheet of `HlSideSheet` and of menus, the panel or the sheet of `HlScrollArea`. Never on rows of posters, whose ends stay open |
| `revealOnFocus()`, `keepVerticalFocus()` | `Scroll.kt` | a group comes fully into view when focus enters it; up and down never leave a column |

## Components

Names say what a component is. `Hl` marks the building blocks that stand for a platform control or
container: buttons, fields, panels, dialogs, lists, menus, settings rows (`HlButton`, `HlModal`,
`HlSettingRow`). Parts of those blocks and the pieces made for this app are named for what they show,
without the prefix (`MenuOption`, `MeterLegend`, `PosterCard`, `BillingBlock`, `TechSpecs`).

| Component | Use |
|---|---|
| `Backdrop` (`Hero`, `Full`, `Quiet`) | full-screen artwork under the `HlScrim` veils, with a floor under the text column on a light image, plus the ambient light; its veils lift when the lights go down |
| `rememberAmbientColor`, `CollectionLight` | the light a picture spills into the room; the colours of a wall when nothing is selected. The light is a state, provided as a function (`LocalAmbient provides { ambient.value }`) and read while drawing, like the house lights: as it changes over `SLOW`, only the backdrop's spill and the focused card's glow follow it, nothing recomposes the screen |
| `PosterCard`, `LandscapeCard`, `PersonCard`, `ArtworkSurface`, `ArtworkImage` | artwork with the shared focus treatment and a faint edge at rest (see *Cards*) |
| `MediaRow` | horizontal row with header; focus coming back into it lands on its last card; dims the other cards when one is focused |
| `HeroInfo`, `TitleArt`, `EpisodeHeading`, `MetaLine`, `BillingBlock` | what a title is: clear logo or condensed title, facts, credits |
| `TitleLine` | the line naming the title under focus over a wall: its name, facts and formats on one line of fixed height. Short of room, the formats go first, from the last, then the name ends in an ellipsis |
| `Badge`, `BadgeRow`, `FactRow`, `Score` | what a case would print: formats, age ratings, protocols, never a state; wrapping, or on one line (`oneLine`) where the last ones go; facts on one line, spaced, never punctuated; a score, the line star and its figure, quiet: a fact, it never lights |
| `HlButton` (`Primary`, `Secondary`), `HlIconButton` | pill and round actions, all 48 dp tall; the icon button shows its label under it when focused. The label takes no room (a row with something below keeps `HlLayout.iconLabelRoom` for it), always sits on a dark plate, a pill padded 12 × 4 (`HlControl.labelPadding*`) 8 below the button, so it reads over any picture; it is centred under the button, even at the edge of the safe area; only where it would come closer than 24 to the side of the screen (`HlLayout.iconLabelEdge`) does it align with the button's edge, hides while a sheet is open, and is not read out: the icon carries the name. A toggle names what OK does ("Marquer comme vu", "Retirer des favoris") and shows where things stand with its icon, ember at rest when `lit` (a favourite's heart fills). Over an image (`onArtwork`) the button rests on a plate. A pill's label stops at `readableNarrow` and its detail at one line, with an ellipsis; `detailLit` lights the detail of a state (an active filter) instead of borrowing the primary emphasis. Not `enabled`, a control keeps focus, outlined, and ignores OK. `busy`, a pill is at work: the quiet dot of a pending state where its icon was, the words the screen gives (`busyLabel`: "Connexion…", "Vérification…"), its width kept, OK ignored, read out as in progress; nothing spins |
| `HlSegmented` | the one way to show where you are or what is chosen among a few: home navigation, seasons (`scrollable`), NFO views, form choices. In a track, **white means current**: one white pill sits under the current option and slides to the option chosen (`STANDARD`, emphasized). The option under focus but not chosen is a grey pill; focus on the current option draws a ring around the pill, travelling with it. Focus entering a track, from any side, lands on the current option, never on the nearest one; to accessibility services the options are tabs, the current one selected. A long track scrolls as one piece, pill included. Over an image (`onArtwork`), the track is a plate and its options are white. There is no standalone tab |
| `HlPanel` (`Quiet`, `Raised`, `lit`), `HlPanelButton` | the one panel recipe, and its focusable form (not `enabled`: a faint well, what it draws in `LocalContentColor` goes tertiary) |
| `HlIconDisc`, `HlDivider`, `HlStatus`, `AttentionNote`, `HlMeter`, `MeterLegend` | an icon in a disc (lit when what it stands for is on, never for focus), a hairline, a state with its light (ember when on or calling for attention) and why, under it; what needs attention beside what it concerns (a folder that could not be read), the light by its first line and the words in ember, as under a field; a segmented gauge, its parts inked by rank (`MeterInk`: white, then the two greys of reading), a gap apart, never ember |
| `HlListItem`, `HlResultItem`, `PosterThumb` | choices in a list (icon in line with the name, white with focus, what the choice brings in badges under a hairline, a chevron on focus); a search result with its poster, the one in use edged in ember and saying so with a light ("Actuel"), which says "Association…" in its place while it is being chosen: the results stay, and so does focus |
| `HlNavItem`, `HlSettingGroup`, `HlSettingRow` | settings: navigation entries with what they are set to, the current one on a faint fill, white as the current option of a track; rows with their value, leading somewhere or stating it; a row not `enabled` stays on the room (see *Contrast*) A pane is titled in the voice of the poster, like a sheet; what a pane only states (an output, a server, a status) is a block flush with that title, not a row (see *Gestures*) |
| `HlSideSheet`, `SheetScope`, `RowPreview`, `SheetConfirm` | acting on what a screen shows, beside it: a panel against the right edge (see *Sheets*), where it stands in its header (`status`); rows can lead with a preview of the image they change; an action that cannot be undone asks first, in the sheet |
| `HlChoiceSheet` | the choices of a setting, in a side sheet: the current one checked in ember and focused, choosing applies and closes, Back changes nothing |
| `HlMenuButton`, `MenuSection`, `MenuOption` | a button opening a sectioned menu anchored to its end edge (sort, filters): below it when the room down to the safe area holds the menu, else above; never past the safe area, a longer menu scrolls inside, its options fading at an edge with more. It moves like a sheet, growing from the button's corner and leaving the same way; a sort applies once the menu has gone, so the wall reorders on its own. The button names what it does and its detail what is set: "Trier", "Titre"; the options say the order ("Titre, de A à Z") |
| `HlModal` | a sheet to read, centred over the dimmed room (the technical sheet), never past the safe area: longer content scrolls; Back closes it |
| `HlTwoPane`, `HlFormScaffold`, `StatusPanel`, `FieldLabel` | two-pane screens: navigation or explanation on the left, content on the right; given the content's scroll `state`, the content fades into the room at an edge with more. `StatusPanel` is where a setup stands, beside its form: its state with its light first, then what is set up, large (an address in the technical voice), its path and kind, and what was found under a hairline; lit while the state is on |
| `HlTextField` | TV text field: focusable box first, keyboard on OK; focused and waiting, it says so at its end ("OK saisir", `KeyHint`); ember while typing, and only then. Every field has a name, read out even when the screen hides it because its sheet already names it. One line under it: what to type (`supporting`), or what is wrong (`error`), in ember after the attention light, its edge unchanged. A placeholder is an example, from the resources ("Ex. : 192.168.1.10"). A setting typed in a field is saved when the field is left (its keyboard action, or focus moving on), never while typing, and its result line then says "Enregistré". Once the keyboard is closed, up or down end typing and move on (Back closes it without telling the app) |
| `HlScrollArea` | long content read with the remote; focusable only when it has something to scroll; a key held down scrolls in one motion. Framed on a quiet panel, or unframed inside a sheet: square edges, so a hairline at its top stays whole, and the focus ring outside the content. Where there is more, the text fades into what the area sits on (`surface`: the panel, or the sheet) |
| `TechSpecs`, `InfoBlock`, `DefinitionList`, `DefinitionRow`, `SpecTable` | the technical sheet, set like the back of a Blu-ray case: one block per subject (a hairline, its name, what it says), every track listed; it does not scroll itself, the sheet scrolls it whole. The NFO and the settings that only state something are laid out the same way (see *Gestures*): pairs on one baseline, a value that is not text (a state and its light, a logo) in a `DefinitionRow`, rows of a kind in a `SpecTable` with aligned columns and the dot on the one in service |
| `ArtworkChoiceCard`, `SelectedMark` | one artwork to choose from, at the shape it is used in; the one in use carries the check of your choice, the watched mark in ember. A backdrop or a logo is previewed on the page behind; a poster is its own preview, so its picker keeps no room for one and its choices come larger (`posterChoice`, 180 dp), right under the header |
| `HlEmptyState` | the intertitle of a screen with nothing to show or that could not load: a short rule (ember for `attention`), what happened in the voice of the poster, what to check, the action focused and a quieter way out beside it; with no action, the Back hint. An error (`UiError`, through `ErrorIntertitle` and `ErrorMessage`) brings its own title, message and action (`ErrorAction`), always with attention: try again on the spot, sign in again, add or change the TMDB key; a title that has gone has none, Back is the way out |
| `ScanProgress` | a scan going on: what is read (`subject`, the first scan on home), the ember line and its count "3 / 9", the file being read; without a name, one line, the file after the count (a folder's card). Until the files are counted, a sliver and no count |
| `PosterWall`, `PosterWallScrim`, `HouseLights`, `CardSkeleton`, `HeroSkeleton`, `ProgressLine` | welcome wall, playback cue, loading and progress (on a plate at the foot of a card, `onArtwork`; on a light track beside text). A placeholder has the footprint of what is coming (a wall of empty frames, two short rules where a title and its facts go), never a shimmer; outside home, it waits `SKELETON_DELAY`, so a quick load shows nothing (`skeletonDelayPassed`) |
| `PosterStrip` | the latest posters of a folder side by side, at the head of its card (edge to edge, parted by hairlines) and of its sheet (small, spaced): a fixed number of places, the free ones empty frames, so every strip keeps one rhythm; with no poster yet, the folder's name set across it like a one-sheet |

## Cards

Artwork carries no text: the top of the screen names what is under focus. The card itself says the
rest, sparingly:

- **At rest**, a faint edge (`ARTWORK_EDGE`) keeps dark artwork from dissolving into the black room.
- **One mark at most**, top right, on a dark plate the size of a mark: the number of unwatched
  episodes of a show, else the line check of a title watched (white; the same check in ember is
  your choice, on an artwork to choose), else the ember dot of a title new
  to its library (added in the last week, not watched; a local folder never counts what its first
  scan found). Progress is the ember line at the foot, on its plate. A format (Ultra HD) is not
  a mark: most titles have one, so it would tell nothing apart; it stays in the header, the info
  line, the filter.
- **Without artwork**, or when it fails to load (a NAS asleep, a file gone), the title is set like a
  one-sheet: a short rule, the name in `cardTitle`, the year in the technical voice. `ArtworkImage`
  takes that fallback and shows it in place of a failed image, so a wall is never a row of blanks.
  A picture with no title to set (`PosterThumb`, `RowPreview`, the free places of a `PosterStrip`)
  is an **empty frame** instead, and so is a card still loading (`CardSkeleton`): the room, edged
  like artwork at rest (`ARTWORK_EDGE`), with the one-sheet's short rule at its foot in
  `contentDecorative`, waiting for a title (a frame too small for the rule keeps its edge alone).
  Never a grey box, which reads as broken.
- **An episode** reads as a number in tabular figures, its name, then its length and where it stands.
- **A person** has a name two lines tall, even a short one, so the roles of a cast row line up.
- **Stepping back**, the image loses colour through its own filter (`ArtworkImage`): no layer is drawn
  per card. The colour goes in three steps (`RECEDE_STEPS`), so an image recomposes three times at
  most while the darkening, drawn, stays continuous. Entering a row moves every card of it at once:
  on the player's small processor, that is the moment that counts.

**Read aloud.** To accessibility services a card is one element: what it shows (the image's
title, or its one-sheet), then where it stands, in words (`stateDescription`): "6 épisodes non
vus", "vu", "nouveau, en cours, 40 %". The mark itself is not read. A person does nothing when
chosen: it is read "name, role", once, and not as a button. What is chosen says so (`selected`):
the checked row of a choice sheet, the checked option of a menu, the result and the artwork in
use, like the current option of a track.

## Sheets

Two kinds of sheet, both over the room dimmed by our own scrim, both closed by Back (there is no
Close button: on a remote, Back already says it), both leaving with the motion they came in with.
Focus stays inside; when a sheet has gone, focus is back where it was.

- **To act: `HlSideSheet`.** A panel the full height of the screen against its right edge (520 dp),
  so the page it acts on stays in view, under a lighter scrim (`SCRIM_SIDE`). From the top: what
  it does, in the voice of the poster (`subject`: "Modifier"); what it is about (a name, facts, a
  line of description, and artwork when it helps); a hairline; the rows, which fade into the sheet
  at an edge with more; the Back hint at its foot.
  Rows that change an image show it as it is now (`RowPreview`), at the shape it is used in. Every
  action row says what it does in one line ("Cherche les nouveaux fichiers"), so the rows of a
  sheet keep one height.
- **Asking first: `SheetConfirm`.** An action that cannot be undone asks in the sheet itself, as a
  card in place of its rows, the way an intertitle stops a film: a short ember rule (attention),
  the question in the voice of the poster ("Retirer « Films » ?"), what follows, then Cancel,
  focused, and the action, its label ember at rest. Never red: ember already says attention.
  Cancel or Back brings the rows back with focus on the row that asked.
- **To read: `HlModal`.** A sheet centred on the screen (864 dp, radius `sheet`), for details on
  demand: what it is (`subject`), what it is about, the content, the Back hint. It never leaves the
  safe area: when the content is longer, it scrolls in one area between the header and the hint,
  which stay. Its height is the layout's, never a constant worked out for one screen.

Content closes a sheet through `SheetScope.dismiss(then)`: the sheet leaves, then its owner hears
of it and `then` runs; keys are ignored while it leaves, so a second OK cannot choose twice. An
action that opens another screen closes the sheet at once instead. Menus come and go with the same
motion (`rememberSheetMotion`).

A choice that needs another screen to happen (a new source needs a folder or a sign-in) is set
like the choices of `HlChoiceSheet`, the current one checked, and changes nothing until that
screen succeeds: Back from it finds everything as it was. The source changes in one place,
`SourceSwitcher`, which saves the new source before a server left behind forgets the app.

## Focus

Focus must read from the couch:

- **Artwork** grows, gets a 2 dp projector-white outline and glows with its own light: the tone
  its picture is mostly made of, by area and a little by saturation (`rememberGlowColor`; a red
  title on a teal poster glows teal), drawn as rings around it, since a cast shadow
  cannot light the black room. The tone is read once per picture and kept, so focus coming back
  finds the same light at once. Artwork without its own picture glows with the room's light. Its
  neighbours in the same row or grid step back: darker and with less colour (`SIBLING_DIM`,
  `SIBLING_SATURATION`, see *Cards*), never grey. A row without focus keeps its colours.
- **Controls, rows and navigation** turn projector white with black content. Pills and discs grow a
  little (`CONTROL_SCALE`, 1.04); rows as wide as their column (setting rows, navigation entries,
  menu options) do not grow at all (`ROW_SCALE`, 1): the white fill says it.
- **Track options** are the exception: white is the current option, so focus on another option is
  a grey pill, and focus on the current one a ring around it. Focus coming into a track lands on
  the current option (`focusProperties.enter`), so the white and the focus meet where you are.
- **Panels** that can be chosen (folder cards, list items, results) are raised, outlined in
  projector white and grow by 1.02 (`PANEL_SCALE`).
- **Areas to read** (`HlScrollArea`) are outlined in projector white; an unframed one draws its ring
  a gap outside the content, clear of its text and rules.
- **Text fields** get a white outline when focused, with "OK saisir" at their end, and a ember
  outline while typing.
- **Disabled controls** keep focus, so the remote reaches them: an outline in projector white,
  never the white fill, which would offer an action.

Growth is a share of the size, so each family has its own scale: 1.04 would take a 500 dp row
10 dp past its inset and the safe area. `HlFocus.reach(size, scale)` says how far an element grows
past its ends, the room its container keeps for it (`FocusReachTest`).

What has focus stays inside the safe area. A list stops it `HlFocus.edge` (28 dp, `safeV`) from the
top and the bottom of the screen, and keeps that much padding at its foot so the last row can come
up to it; a focused poster grows into the margin, never off the screen. A list that starts lower,
under a header or a hero, is already inside the safe area: it brings its rows back to its own top,
where the first one rests (`ListScroll(top)`), so the row above leaves whole instead of lingering as
a sliver. A row of cards keeps `HlSpace.s` above and below them, room for a poster to grow (8.4 dp at
1.10). The label of a round button overflows under it and slides along to stay inside the safe area.
Where what scrolls fades at an edge (`scrollEdges`), focus stays clear of the fade: a list keeps
`HlFocus.edge` from its ends, wider than `HlSpace.fadeEdge`, and `HlTwoPane` brings its rows back
down to `fadeEdge`, so the row above dissolves into the room instead of being cut.

Focus is never left nowhere. When what holds it goes away (a folder removed, a line deleted), it
moves to a neighbour, else to the action that adds one ("Ajouter un dossier").

**Coming back** to a screen finds focus on what was left, by what it is, not where it sat
(`Focus.kt`). For each screen:

1. Every element that opens another screen, and every card of a list, has a stable key (`focusKey`,
   `FocusKey`): `item:<id>`, `action:edit`, `pane:Library/row:source`. A card's key names its row
   (`section:…/item:42`), and the row or the pane takes focus when the card has gone.
2. The screen keeps its `FocusMemory` with its destination (`rememberFocusMemory`) and brings focus
   back once laid out (`RestoreFocus`), scrolling its list to the element first. A first visit
   starts where the screen starts: the first card, Lire, the entry of the pane open.
3. An action that changes its words stays one element: Lire becomes Reprendre on the same button,
   so a film stopped half way comes back with focus on it, even one started over.
4. A row or a pane returns to the child it last had when the remote comes back into it
   (`keepChildFocus`, its children marked `rememberedInGroup`): focus is redirected to that child.
   Never `focusRestorer`, which turns a request for one child into its own and, in Compose 1.7,
   crashes when the list changes under it; nor a restore by hand that cancels the move, which
   cancels the restored focus with it.
5. On screen: open something, come back, focus is on it, in its row, with the pane still open.

**Back** goes in two steps (`BackToStart`). Deep in a screen, it first brings focus back to where
the screen starts; from there, it leaves. Home: below the first row, Back goes up to the tabs, and
the next one quits, with no "press again". A wall: past its first line, Back returns to the first
poster, then home. Settings: from the pane, Back returns to its entry, then leaves. A detail page
scrolled down: Back returns to the actions, then leaves. Sheets and menus are windows of their own
and keep their Back; while the lights go down, Back brings them up.

## Messages

A message goes where the action was, in the app's voice: no Toast (a test forbids it), no snackbar,
no dialog. A title that does not play says so under the actions that asked for it: the attention
light (`HlStatus`, `Attention`), "Lecture impossible", then the reason as a `detail` line; the next
action or the next screen takes it away. A screen that could not load says what happened in place
of what it would show, as an intertitle (`HlEmptyState`, see *Gestures*), with what fixes it; so
does a screen with nothing to show yet, with the verb that fills it. Raw exception text never
reaches the screen: it goes to the log.

## Writing

- French uses *vous*, typographic apostrophes (’), and a no-break space before `: ; ! ?` and inside
  `« »`. Archivo has no narrow no-break space, so the normal one (U+00A0) is used.
- A number never leaves its unit: `3,27 Go`, `2 h 02`, `10 bits`, `3840 × 1608` (`formatDuration`,
  `formatDimensions`, `formatBytes`, `formatRatio` in `ui/util/Format.kt`). What is left of a title
  is a duration, "Il reste 1 h 26", never a timecode; progress reads "3 / 9", everywhere.
- A button says what OK does, and a toggle says it too: "Marquer comme vu", then "Marquer comme non
  vu"; its lit icon carries the state. A button bears the name of what it opens ("Fiche technique",
  "Fichier NFO"). An empty screen always offers a verb ("Ajouter un dossier"), never a place.
- A time inside a sentence goes on in lower case, "Analysé il y a 3 minutes", "Jamais analysé"; set
  alone as a value, it takes a capital, "Il y a 3 minutes" (`relativeTime`). No arrow in a sentence:
  words ("aucune correspondance : lecture par le flux HTTP").
- Facts are separated by layout (`FactRow`). Inside a single text (a button's detail), `FACT_GAP`.
  A line of facts never cuts one: short of room, it leaves out whole facts from the end, as the
  billing block leaves out names, so the ones that matter come first (`MetaLine`: year, length,
  rating, score, then genres, one by one). Only a fact alone on a line too short ends in an
  ellipsis. Label and value pairs (`DefinitionList`) sit on one first baseline.
- Values are words a person uses: "Détecté", "Hors ligne", "Aucune", never "absent" or "0 fichier".
- What is under way ends in an ellipsis and says what is done, not the control: "Connexion…",
  "Vérification…". An example in a field starts "Ex. :" and lives in the resources.
- A failure says what happened and what to do ("Le dossier n’a pas pu être lu. Réessayez, ou
  vérifiez le partage sur le NAS."), never an exception's text: that goes to the log only.
- Text written elsewhere (TMDB, NFO, servers) goes through `Typeset.text` in the mappers, for display
  only (sorting and searching read it as written): an apostrophe between two letters becomes ’,
  three dots …, a balanced pair of straight double quotes « … » in French; `»`, `:` and `!` never
  start a line. Single quotes are left alone: they elide as often as they quote (Rock 'n' Roll).
  `StringsTest` holds the resources to the same rules, and values-fr to every key of values.
- Say only what the app can know. Some players list stand-in display modes: the Zidoo lists 1080p
  at 30 Hz while it sends 2160p60, set in its own menu. There, Affichage says the player sets its
  output and where ("Réglages du lecteur > Affichage"), shows no mode and no rate, and offers no
  choice that would do nothing; "4K 60 Hz" is offered only where the system lists it (`DisplayModes`).

## Brand (proposal, not chosen yet)

The launcher icon and the TV banner are the app's first frame, before the room. Those shipped
today (a lamp and its beam in a gradient; a wordmark under a halo, in pixels) are being redrawn.
Four directions wait in the catalogue (**Brand**), each as an adaptive icon foreground
(`brand_icon_<name>_foreground`) and a banner (`brand_banner_<name>`); the manifest keeps the old
pair until one is chosen. Every direction holds to the same rules:

- **The room.** The ground is `@color/room`, pure black. No gradient, no glow, no beam, no halo.
- **Ember** is the wordmark's, or the one thing lit; nothing else is coloured.
- **The type is the app's.** The outlines come from `archivo.ttf` at the app's widths (condensed 70:
  the wordmark's SemiBold, a title's Bold), set with the font's kerning and converted to paths once.
- **Sizes.** The icon's art stays inside the 66 dp safe circle of its 108 dp layer, so no launcher's
  mask cuts it. The banner is 160 × 90 dp, Android TV's banner (320 × 180 px at xhdpi), drawn on a
  320 × 180 grid, and the name reads at that size.

| Direction | Icon | Banner |
|---|---|---|
| Monogram (`monogram`) | an H set like a film's title, condensed Bold | the H as a large initial, the wordmark on its baseline |
| Wordmark (`wordmark`) | the name on two lines, like a title on a poster | the name at the foot of a dark room |
| Your seat (`seat`) | the room from above: the screen lit, the house dark, one seat in ember, yours ("your choice") | the name in the screen's place, in projector white |
| Three steps (`steps`) | the house lights going down in three steps; the house rising in tiers | the sign a capital high, then the name |

Once one is chosen, it becomes `ic_launcher_foreground` (with a `<monochrome>` version) and
`banner.xml`; `banner.png`, its lint exception and the other candidates go, and this section says
what the brand is.

## Guard

`DesignTokenGuardTest` reads every file under `ui/` except `ui/design/` and fails on a size, text size,
font, colour, opacity or shape written in a screen, on ember named in a screen (it says the state
instead), and on arithmetic with a token (`safeH - m`): an alignment worked out in a screen gets a
name in `Dimens.kt`. Across the whole app, the design system included, it fails on a Material icon
other than Play: icons are drawn in `HlIcons`. A screen never loads an image itself
(`AsyncImage`): it goes through `ArtworkImage`, `PosterThumb` or `RowPreview`, which know what to
show when it fails. `SpacingGridTest` keeps `HlSpace` and
`HlInset` on the 4 dp grid; `TypeScaleTest` keeps the type scale honest, `ContrastTest` the contrasts,
`ScrimTest` the floor of the veils, `ResourceTokensTest` the resources in step with the tokens,
`StringsTest` the French typography of the resources, `CatalogCoverageTest` the catalogue complete.
Deliberate exceptions go in the guard's `allowed` list, with the reason; a value needed twice belongs
in the design system instead.

## Catalogue

Debug builds show **Settings > About > Design system**: every token and component in its states, to
review changes on a real screen. Each component is laid out as a row of its states (at rest, chosen,
lit, loading, failed, empty), each named underneath; focus is checked live with the remote. The
catalogue shows components as they are, defects included: that is how they are seen. Its sections
live in `ui/catalog` (`CatalogScreen` for tokens, controls and sheets; `CatalogGestures` for the three
gestures, side by side then at the width screens give them; `CatalogIcons` for every glyph,
the marks of done and your choice, and what ember never says; `CatalogForms`, `CatalogCards`,
`CatalogReading`; `CatalogStress` for the longest French labels, the fullest rows, the home hero at
the largest text size, the scripts Archivo lacks and a line on the device: size in dp, density, text
scale; `CatalogBrand` for the launcher icon and the TV banner, drawn by Android at a launcher's size).
`CatalogCoverageTest` fails
when a public component of `ui/design/components` is missing; the few that fill the screen are listed
there with the reason. It fails too when a glyph of `HlIcons` is not on the icon sheet.

## Adding a screen

Start from `Backdrop`, `HlTwoPane` or `HlFormScaffold`, compose existing components, and add a token
or component here before using a new value twice. A vertical list ends with
`contentPadding(bottom = HlFocus.edge)`; if it starts below the top of the screen, it sits in
`ListScroll(top = its top padding)`. `HlTwoPane` does this for its content pane; give it the
content's scroll state, and the content fades at its edges.
