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
   Tungsten, the only fixed accent, marks **states**: in use, your choice, needs attention.
2. **Ten-foot first.** Nothing below 12 sp, no content below 14 sp, every text at least 4.5:1 on the
   surface it sits on (see *Contrast*). Content stays inside the overscan-safe area (`HlSpace.safeH` /
   `safeV`). Every focusable element shows an unmistakable focus state.
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
  player. Coming back runs the cue backwards. While the lights go down the remote waits: a second OK
  does nothing, Back brings the lights up again and nothing plays.
- **Billing block.** On a detail page, director and leading cast read as a sentence, "a film by NAME
  with NAME, NAME and NAME": names in condensed capitals, small words and commas receding. On one line:
  it keeps as many leading names as fit, and never breaks inside a name.

## Tokens

### Colour (`Color.kt`)

| Token | Value | Role |
|---|---|---|
| `background` | `#000000` | the room; masks over artwork |
| `surface` / `surfaceRaised` | `#121110` / `#1D1B19` | quiet panels / dialogs, menus, focused fields |
| `outline` | `#3A3632` | hairlines, badge borders |
| `content` | `#F2F0EB` | projector white: primary text |
| `contentSecondary` | `#A39E96` | secondary text; 4.96:1 at worst, in a primary button |
| `contentTertiary` | `#8C867E` | the quietest text: 4.76:1 on `surfaceRaised`, its lowest permitted surface |
| `contentDecorative` | `#4A4642` | never text: empty frames, inactive marks |
| `tungsten` | `#F2B66D` | states only |
| `focus` | `#F2F0EB` | projector white again: the fill and outline of focus |
| `onFocus` | `#000000` | a focused control's name and icon |
| `onFocusSecondary` | `#5C5B59` | the line under that name (a summary, a runtime): black at 62 % on `focus`, 5.96:1 |
| `ambientFallback` | `#3B2B1C` | the light spilled into the room when a title has no artwork |

`HlAlpha` names every opacity, by role: `QUIET_SURFACE`, `CONTROL_FILL` / `CONTROL_FILL_STRONG`,
`WELL`, `BORDER`, `DIVIDER`, `SCRIM` / `SCRIM_SIDE`, `PLATE`, `TRACK`, `FOCUS_QUIET`, `LIT_DISC`,
`ARTWORK_EDGE`, `WORDMARK_GLOW`.

**Contrast.** Text reaches 4.5:1 and icons and marks 3:1 on the surface they really sit on, each
translucent layer composed over what lies under it. `ContrastTest` lists every pair the system
draws and checks it on every build: a new pair goes there before a component uses it. The states of
a control are named colours, never an opacity laid on the current one: the line under a name is
`contentTertiary` at rest, `contentSecondary` when chosen, `onFocusSecondary` with focus. Two pairs
are forbidden: `contentTertiary` on a `WELL` row inside a raised panel (≈ 4.09:1) and on
`CONTROL_FILL_STRONG` (≈ 3.66:1). There, the quiet line stays `contentSecondary`, as in setting
rows and buttons.

### Type (`Type.kt`)

Eight sizes with clear steps: **12 · 14 · 16 · 20 · 24 · 32 · 44 · 64 sp**, plus 80 for the wordmark as
a subject. Line heights sit on a 2 sp grid. Numbers use tabular figures (`tnum`) in every technical
style, so durations, sizes and counts line up and do not jitter.

| Style | Size / line | Role (one each) |
|---|---|---|
| `display` | 64 / 70 | title of a detail page without logo |
| `headline` | 44 / 48 | screen titles, home hero title without logo |
| `figure` | 32 / 36 | a figure that states something (storage used) |
| `subject` | 32 / 36 | what a panel or a sheet is about ("Modifier", "Fiche technique") |
| `cardTitle` | 20 / 22 | a title set on a card without artwork, like a plain one-sheet |
| `title` | 24 / 30 | pane and dialog titles, taglines |
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
| `wordmark` / `wordmarkHero` | 22 / 24 and 80 / 84 | the Houselights wordmark |

Archivo covers Latin (with Vietnamese); Greek, Cyrillic, Thai or Korean names fall back to the system
font. No other font is shipped; line heights leave room for the fallback.

### Space and size (`Dimens.kt`)

| Token | Content |
|---|---|
| `HlSpace` | 4 dp grid: `xxs` 4, `xs` 8, `s` 12, `m` 16, `l` 24, `xl` 32, `xxl` 48; `xxs` between a name and the line under it; `fact` 20 between facts; `safeH` 48 / `safeV` 28 |
| `HlInset` | inner padding of containers, by what they hold: `tight` 8 (menus, results with a thumbnail), `compact` 12 (dense rows), `card` 24 (panels, cards), `sheet` 32 (sheets) |
| `HlShapes` | radius follows hierarchy: `badge` 3, `artwork` 4, `field` 6, `row` 8 (rows that can be chosen), `panel` 12, `sheet` 16 (sheets and menus), `control` pill, `avatar` circle. A shape set close inside another keeps their corners parallel: **outer radius = inner radius + gap** (a menu: 8 + 8 = 16; a result: 4 + 8 = 12) |
| `HlSize` | artwork (poster 112, landscape 220, episode 264, avatar 76 in a column of 120, thumbnails), `control` 48 for every button, icons 20, discs 52, marks 20, dots 8, setting rows 64, previews in a row (48 tall, a slot of 84), meter 10 |
| `HlLayout` | measures of layout parts: reading widths (640, 520), side columns (280, 340), pane top 72, the reading sheet (864) and the side sheet (520), title art, hero, info line, track table columns |
| `HlControl` | inner padding of buttons and track options, badges; the gap between an icon and its text (8) |
| `HlFocus` | focus scale (artwork 1.10, cards 1.06, controls 1.04), siblings stepping back (dim 0.3, colour kept 0.3), ambient glow 18 dp |

### Motion, icons, scrolling

| Token | File | Content |
|---|---|---|
| `HlMotion` | `Motion.kt` | `FAST` 150, `STANDARD` 250, `SLOW` 600, `DIM` 1000 ms; `SETTLE` 220 ms before a backdrop changes; `WALL_DRIFT`; `ENTER_SCALE` |
| `LocalHouseLights`, `HouseLightCue` | `Auditorium.kt` | how far the house lights are down (read while drawing), and the order of the cue |
| `HlIcons` | `Icons.kt` | line icons drawn for the app (24 dp grid, 1.75 stroke): places, actions (Restart, Edit), marks (Check), the remote's Back; core Material icons for the rest |
| `HlBringIntoView`, `RowScroll` | `Scroll.kt` | lists scroll only as much as needed; rows keep focus 80 dp from the edges, so the first card always scrolls home |
| `revealOnFocus()`, `keepVerticalFocus()` | `Scroll.kt` | a group comes fully into view when focus enters it; up and down never leave a column |

## Components

Names say what a component is. `Hl` marks the building blocks that stand for a platform control or
container: buttons, fields, panels, dialogs, lists, menus, settings rows (`HlButton`, `HlModal`,
`HlSettingRow`). Parts of those blocks and the pieces made for this app are named for what they show,
without the prefix (`MenuOption`, `MeterLegend`, `PosterCard`, `BillingBlock`, `TechSpecs`).

| Component | Use |
|---|---|
| `Backdrop` (`Hero`, `Full`, `Quiet`) | full-screen artwork faded to black where text sits, plus the ambient light; its scrims lift when the lights go down |
| `rememberAmbientColor`, `CollectionLight` | the light a picture spills into the room; the colours of a wall when nothing is selected |
| `PosterCard`, `LandscapeCard`, `PersonCard`, `ArtworkSurface`, `ArtworkImage` | artwork with the shared focus treatment and a faint edge at rest (see *Cards*) |
| `MediaRow` | horizontal row with header and focus restoration; dims the other cards when one is focused |
| `HeroInfo`, `TitleArt`, `EpisodeHeading`, `MetaLine`, `BillingBlock` | what a title is: clear logo or condensed title, facts, credits |
| `Badge`, `BadgeRow`, `FactRow` | formats and ratings (a `lit` badge marks a state); facts on one line, spaced, never punctuated |
| `HlButton` (`Primary`, `Secondary`), `HlIconButton` | pill and round actions, all 48 dp tall; the icon button shows its label when focused |
| `HlSegmented` | the one way to show where you are or what is chosen among a few: home navigation, seasons (`scrollable`), NFO views, form choices. In a track, **white means current**: one white pill sits under the current option and slides to the option chosen (`STANDARD`, emphasized). The option under focus but not chosen is a grey pill; focus on the current option draws a ring around the pill, travelling with it. A long track scrolls as one piece, pill included. There is no standalone tab |
| `HlPanel` (`Quiet`, `Raised`, `lit`), `HlPanelButton` | the one panel recipe, and its focusable form |
| `HlIconDisc`, `HlDivider`, `HlStatus`, `HlMeter`, `MeterLegend` | an icon in a disc (lit when on), a hairline, a state with its light (tungsten when on or calling for attention), a segmented gauge |
| `HlListItem`, `HlResultItem`, `PosterThumb` | choices in a list (icon in line with the name, what the choice brings in badges under a hairline, a chevron on focus); a search result with its poster |
| `HlNavItem`, `HlSettingGroup`, `HlSettingRow` | settings: navigation entries with what they are set to; rows with their value, leading somewhere or stating it |
| `HlSideSheet`, `SheetScope`, `RowPreview` | acting on what a screen shows, beside it: a panel against the right edge (see *Sheets*); rows can lead with a preview of the image they change |
| `HlChoiceSheet` | the choices of a setting, in a side sheet: focus on the current one, choosing applies and closes, Back changes nothing |
| `HlMenuButton`, `MenuSection`, `MenuOption` | a button opening a sectioned menu anchored to it (sort, filters) |
| `HlModal` | a sheet to read, centred over the dimmed room (the technical sheet); Back closes it |
| `HlTwoPane`, `HlFormScaffold`, `StatusPanel`, `FieldLabel` | two-pane screens: navigation or explanation on the left, content on the right. `StatusPanel` is where a setup stands, beside its form: its state with its light first, then what is set up, large (an address in the technical voice), its path and kind, and what was found under a hairline; lit while the state is on |
| `HlTextField` | TV text field: focusable box first, keyboard on OK; tungsten while typing |
| `HlScrollArea` | long content read with the remote; focusable only when it has something to scroll |
| `TechSpecs`, `InfoBlock`, `DefinitionList` | the technical sheet, set like the back of a Blu-ray case: one block per subject (a hairline, its name, what it says); the NFO is laid out the same way |
| `ArtworkChoiceCard`, `SelectedMark` | one artwork to choose from, at the shape it is used in |
| `HlEmptyState` | what happened and what to do, with the action focused; a quieter way out beside it; with no action, the Back hint. An error (`UiError`) brings its own title, message and action (`ErrorAction`): try again on the spot, sign in again, add or change the TMDB key; a title that has gone has none, Back is the way out |
| `PosterWall`, `PosterWallScrim`, `HouseLights`, `CardSkeleton`, `ProgressLine` | welcome wall, playback cue, loading and progress |

## Cards

Artwork carries no text: the top of the screen names what is under focus. The card itself says the
rest, sparingly:

- **At rest**, a faint edge (`ARTWORK_EDGE`) keeps dark artwork from dissolving into the black room.
- **One mark at most**, top right, on a dark plate the size of a mark: the number of unwatched
  episodes of a show, else the line check of a title watched, else the tungsten dot of a title new
  to its library (added in the last week, not watched; a local folder never counts what its first
  scan found). Progress is the tungsten line at the foot. A format (Ultra HD) is not a mark: most
  titles have one, so it would tell nothing apart; it stays in the header, the info line, the filter.
- **Without artwork**, the title is set like a one-sheet: a short rule, the name in `cardTitle`, the
  year in the technical voice.
- **An episode** reads as a number in tabular figures, its name, then its length and where it stands.
- **Stepping back**, the image loses colour through its own filter (`ArtworkImage`): no layer is drawn
  per card.

## Sheets

Two kinds of sheet, both over the room dimmed by our own scrim, both closed by Back (there is no
Close button: on a remote, Back already says it), both leaving with the motion they came in with.
Focus stays inside; when a sheet has gone, focus is back where it was.

- **To act: `HlSideSheet`.** A panel the full height of the screen against its right edge (520 dp),
  so the page it acts on stays in view, under a lighter scrim (`SCRIM_SIDE`). From the top: what
  it does, in the voice of the poster (`subject`: "Modifier"); what it is about (a name, facts, a
  line of description, and artwork when it helps); a hairline; the rows; the Back hint at its foot.
  Rows that change an image show it as it is now (`RowPreview`), at the shape it is used in. An
  action that cannot be undone asks first, in the sheet itself, with focus on Cancel.
- **To read: `HlModal`.** A sheet centred on the screen (864 dp, radius `sheet`), for details on
  demand: what it is (`subject`), what it is about, the content, the Back hint.

Content closes a sheet through `SheetScope.dismiss(then)`: the sheet leaves, then its owner hears
of it and `then` runs; keys are ignored while it leaves, so a second OK cannot choose twice. An
action that opens another screen closes the sheet at once instead.

## Focus

Focus must read from the couch:

- **Artwork** grows, gets a 2 dp projector-white outline and glows with the ambient light. Its
  neighbours in the same row or grid step back: darker and with less colour (`SIBLING_DIM`,
  `SIBLING_SATURATION`, through `Modifier.receding()`), never grey. A row without focus keeps
  its colours.
- **Controls, rows and navigation** turn projector white with black content.
- **Track options** are the exception: white is the current option, so focus on another option is
  a grey pill, and focus on the current one a ring around it.
- **Panels** that can be chosen are raised and outlined in projector white.
- **Text fields** get a white outline when focused and a tungsten outline while typing.

## Messages

A message goes where the action was, in the app's voice: no Toast (a test forbids it), no snackbar,
no dialog. A title that does not play says so under the actions that asked for it: the attention
light (`HlStatus`, `Attention`), "Lecture impossible", then the reason as a `detail` line; the next
action or the next screen takes it away. A screen that could not load says what happened in place
of what it would show (`HlEmptyState`), with what fixes it. Raw exception text never reaches the
screen: it goes to the log.

## Writing

- French uses *vous*, typographic apostrophes (’), and a no-break space before `: ; ! ?` and inside
  `« »`. Archivo has no narrow no-break space, so the normal one (U+00A0) is used.
- A number never leaves its unit: `3,27 Go`, `2 h 02`, `10 bits`, `3840 × 1608` (`formatDuration`,
  `formatDimensions`, `formatBytes`, `formatRatio` in `ui/util/Format.kt`).
- Facts are separated by layout (`FactRow`). Inside a single text (a button's detail), `FACT_GAP`.
- Values are words a person uses: "Détecté", "Hors ligne", "Aucune", never "absent" or "0 fichier".
- Text written elsewhere (TMDB, NFO, servers) goes through `Typeset.text`, so `»`, `:` and `!` never
  start a line.

## Guard

`DesignTokenGuardTest` reads every file under `ui/` except `ui/design/` and fails on a size, text size,
font, colour, opacity or shape written in a screen, and on arithmetic with a token (`safeH - m`): an
alignment worked out in a screen gets a name in `Dimens.kt`. `SpacingGridTest` keeps `HlSpace` and
`HlInset` on the 4 dp grid; `TypeScaleTest` keeps the type scale honest, `ContrastTest` the contrasts,
`CatalogCoverageTest` the catalogue complete.
Deliberate exceptions go in the guard's `allowed` list, with the reason; a value needed twice belongs
in the design system instead.

## Catalogue

Debug builds show **Settings > About > Design system**: every token and component in its states, to
review changes on a real screen. Each component is laid out as a row of its states (at rest, chosen,
lit, loading, failed, empty), each named underneath; focus is checked live with the remote. The
catalogue shows components as they are, defects included: that is how they are seen. Its sections
live in `ui/catalog` (`CatalogScreen` for tokens, controls and sheets; `CatalogForms`, `CatalogCards`,
`CatalogReading`; `CatalogStress` for the longest French labels, the fullest rows, the scripts
Archivo lacks and a line on the device: size in dp, density, text scale). `CatalogCoverageTest` fails
when a public component of `ui/design/components` is missing; the few that fill the screen are listed
there with the reason.

## Adding a screen

Start from `Backdrop`, `HlTwoPane` or `HlFormScaffold`, compose existing components, and add a token
or component here before using a new value twice.
