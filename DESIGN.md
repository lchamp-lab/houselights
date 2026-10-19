# Houselights design system

Houselights is named after the lights of a cinema, which dim before the film starts: the room is dark and
quiet, and the light comes from the films.

The system lives in `app/src/main/java/app/houselights/ui/design`. **Screens never hard-code a colour, size,
opacity, shape or font**: they compose the tokens and components below, and `DesignTokenGuardTest` enforces
it on every build (see *Guard*). Debug builds show every token and component in its states under
Settings > About > Design system (see *Catalogue*).

## Principles

1. **One light source.** The room is pure black. Colour comes from the artwork: the ambient light
   (`LocalAmbient`), picked from the focused backdrop, lights the focused card. Ember, the only fixed accent,
   marks states and nothing else.
2. **Ten-foot first.** Nothing below 12 sp, no content below 14 sp; text reaches 4.5:1 and marks 3:1 (see
   *Contrast*). Content stays inside the overscan-safe area (`HlSpace.safeH`, `safeV`). Every focusable
   element shows an unmistakable focus state.
3. **One canvas.** Screens are laid out on 960 × 540 dp (`HlLayout.CANVAS_WIDTH`). `HouselightsTheme` derives
   the density from the screen's real width, not the declared one (the Zidoo declares 240 dpi); the
   viewer's text scale is kept.
4. **Calm motion.** Motion answers the user. Only two orchestrated moments: the house lights dimming (at
   launch and before playback) and the drifting poster wall of the welcome screen. Screens cut, never
   cross-fade: the backdrop is handed across the cut (`BackdropRelay`) and the words change at once.
5. **Words do one job.** Sentence case, no all-caps labels, no punctuation between facts. French uses
   *vous*. Capitals are reserved for the billing block and what extends it (end credits, a row billing a
   director's films).
6. **A title is always shown.** Moving focus up to the tabs or buttons keeps the last title; before any
   focus, the first one.

## Identity

The app is recognised by its type and vocabulary, not by decoration. One family, **Archivo**, in three
widths used as voices:

| Voice | Width | Used for | Styles |
|---|---|---|---|
| Poster | condensed (70) | titles, places, rows and names under cards, what a panel is about, large figures, the billing block | `display`, `headline`, `subject`, `place`, `rowTitle`, `cardName`, `cardTitle`, `titleSignature`, `billing` |
| Reading | normal (100) | everything read or chosen: overviews, list names, button words, values | `title`, `lead`, `body`, `entry`, `meta`, `caption`, `group` |
| Technical | expanded (125), tabular figures | formats, specifications, printed marks | `spec`, `jaquette`, `badge` |

- **No fourth voice.** An intermediate width reads as a mistake.
- **Three weights, one meaning each:** Regular reads, SemiBold names, Bold announces. Medium belongs to the
  billing block. Each width declares only the weights it uses, so no bold is synthesised (`TypeScaleTest`).
- **A second face only when the text demands it:** raw text read as written (the NFO) uses the system
  monospace (`code`). Scripts Archivo lacks fall back to the system font.
- **Blu-ray case vocabulary** in the technical voice: Ultra HD, Full HD, Dolby Vision, HDR10, TrueHD Atmos;
  ratios as `2,39:1`, frame sizes as `3840 × 1608`.

Three moments carry the identity:

- **Launch.** A cold start shows the mark alone on black (`splash_mark`, drawn by Android at the device's
  density): no progress bar, no text. Home is composed underneath (rows, first-view pictures preloaded with
  `PicturesAhead` and `AwaitAhead`, hero picture prepared). The launch screen leaves after home's first
  complete frame (`drawnWhole`, `LocalLaunchScreen`), at most 1.5 s after process start, and at once when the
  app starts elsewhere. The mark's bars then go out widest first, halving the light at each step, and the
  black lifts (`LaunchCue`: `SLOW` overall, `FAST` per bar, `STANDARD` for the black, ease-out). What was
  prepared underneath appears without a fade. A warm start shows at once.
- **House lights.** Playing a title runs `HouseLightCue` (`DIM`): the room fades (`Modifier.houseLit`), the
  scrims lift, then black and the player. The player starts once the room is dark, not after a fixed delay;
  returning runs the cue backwards. During the cue, OK is ignored and Back cancels.
- **Billing block.** Director and lead cast read as one sentence, "un film de NAME avec NAME, NAME et NAME":
  condensed capitals, small words receding, on one line, keeping as many whole names as fit.

The **wordmark** (`Wordmark`, see *Brand*) is drawn, never typeset: projector white, flat, no glow. It
appears on the welcome screen (`HlSize.wordmarkHero`, 30) and in About (`HlSize.wordmark`, 12).

## Gestures: small surfaces

Small surfaces (a settings pane, a message, a running scan) borrow exactly one gesture from film materials.
`CatalogGestures` shows them side by side.

| Gesture | For | Rules |
|---|---|---|
| **Case back** | what a pane states | `InfoBlock`: a hairline, the name in `caption`, the value. Pairs in a `DefinitionList` (a state with its light in a `DefinitionRow`), several of a kind in a `SpecTable` with the ember dot on the one in use. Formats in the technical voice; sizes and counts in `meta`, tabular. Only what acts is a row. Unknown values are omitted, never shown as zero. No gauges or charts. |
| **Intertitle** | every message | A short rule, one line in `subject`, one line of secondary `body` (`readableNarrow`), at most one action and a quieter one. An ember rule means attention. It replaces the content it concerns, aligned on the screen margin: never centred, never a snackbar, Toast or dialog. `HlEmptyState` on a screen, `SheetConfirm` in a sheet. |
| **Counter** | progress | The ember line with its count in tabular figures ("3 / 9", `scan_progress`) and the current file in a quiet `caption` (`ScanProgress`). Durations read "2 h 02", time left "Il reste 20 min"; a timecode only names a frame. |

The library in numbers follows the case back: figures in `subject` over what they count in `caption`, then one
row per value, each opening what it counts.

## Tokens

### Colour (`Color.kt`)

`HlPalette` holds the colours, named by what they are (neutrals by lightness, `N0` to `N96`, and three
embers). `HlColors` names their roles; only roles reach components.

| Role | Palette | Value | Use |
|---|---|---|---|
| `background` | `N0` | `#000000` | the room; masks over artwork; the window and launcher icon ground (`@color/room`) |
| `glass` | `N8` | `#131316` | glass: side sheets, menus, dialogs |
| `outline` | `N21` | `#33333A` | badge borders; every edge at `EDGE` |
| `content` | `N96` | `#F4F4F5` | projector white: what is read first |
| `contentSecondary` | `N65` | `#A3A3AB` | what explains: summaries, descriptions, facts, hints |
| `contentTertiary` | `N57` | `#8C8C95` | what is guessed: joins, placeholders, key hints, disabled |
| `contentDecorative` | `N28` | `#45454C` | never text: the rule of an empty frame, skeletons |
| `ember` | `Ember` | `#FF7A4D` | states only, solid, where a gradient has no room (a dot, a check, a word) |
| `emberFrom`, `emberTo` | `EmberStart`, `EmberEnd` | `#FF9F1C` → `#FF2E63` | the ember light (`emberLight()`): progress lines, an attention rule, a field being typed in, a lit edge; the mark's bars |
| `onEmber` | `N0` | `#000000` | ink on an ember fill (the main action), 5.8:1 at its raspberry end; never white |
| `emberOnLight` | `EmberEnd` | `#FF2E63` | ember on projector white: marks only (3.3:1), never words |
| `focus` | `N96` | `#F4F4F5` | focus fill and outline |
| `onFocus` | `N0` | `#000000` | a focused control's name and icon |
| `onFocusSecondary` | `N36` | `#5D5D5D` | the line under a focused name (6.01:1) |
| `ambientDeep` | `Deep` | `#1C1B1B` | a picture's own dark, neutral until one lends it (`LocalAmbientDeep`, read while drawing): plates (`plate()`) and veils |

**Neutrals are neutral**: no hue, only a touch of cool; warmth belongs to the films and to ember.

**Grounds.** Three materials: the room (`background`), glass (`glass`, set off by its hairline) and
artwork. A component reads what it lies on (`LocalGround`: `Ground.Room`, `Glass`, `Artwork`) instead of
being told (`GroundTest`). A thing on its ground takes one step up, `groundStep()` laid as
`groundStepFill()`: `FILL` on the room (a step a TV still shows), `FILL_FAINT` on glass. What must hide what is
under it reads the ground opaque (`groundColor`, `groundFill`).

**Opacity.** `HlAlpha` names every opacity:

| Family | Tokens |
|---|---|
| Fills (`content` over its ground) | `FILL_FAINT` 6 % (wells, tracks, disabled), `FILL` 9 % (secondary controls, things on the room), `FILL_STRONG` 20 % (primary controls, the current option of a track) |
| Veils (`background` over a picture) | `VEIL_SIDE` 60 % (beside a side sheet), `VEIL` 80 % (behind a dialog, the plate under what sits on artwork) |
| Plates (a picture's own dark over it) | `PLATE` 74 %, set off by a `PLATE_EDGE` hairline (`content` at 11 %) |
| Edges | `EDGE` (`outline` at 60 %, `HlColors.edge`), `EDGE_ARTWORK` (`content` at 12 %, around a picture) |

Check fills on a TV with the catalogue's black-level chart (`BLACK_LEVELS`).

**Ember is a closed list.** It says four things:

- *In use*: a title's progress, a scan, the track playing, the setup in use, a field being typed in.
- *Your choice*: the current choice's check, a chosen value or match, an active filter, a favourite.
- *Needs attention*: an error with its mark, the action a question asks about.
- *New*: the dot of a title new to the library.

The mark's bars are the only exception. What is done or simply true is projector white (a watched title,
`StatusState.Done`). Ember never marks focus, where you are, a fact (a score is grey) or a data series. It
never comes alone: every ember state has a second sign (shape, mark or word); attention has its own circled
exclamation. Screens never name ember: they state the state and the component lights it (`HlStatus`,
`AttentionNote`, `lit`, `valueLit`, `detailLit`).

**Your choice, one way.** One glyph, `HlIcons.Check`: white means done, ember means your choice. Over artwork
both sit on the same dark plate (`SelectedMark`). In words, a choice takes a state's light (`HlStatus` On:
"Votre choix", "Actuel"); how something was done is a plain `meta` word ("Automatique"). A `Badge` is a printed
mark (an age rating, a protocol), never a state; formats are words (`FormatLines`).

**Contrast.** Text reaches 4.5:1 and icons and marks 3:1 on the surface they actually sit on, each translucent
layer composed over what is under it. `ContrastTest` lists every pair the system draws; add a pair there before
using it.

- Control states are named colours, never an opacity on the current one: the line under a name is
  `contentTertiary` at rest, `contentSecondary` when chosen, `onFocusSecondary` with focus.
- Forbidden: `contentTertiary` on `FILL_STRONG` (≈ 3.90:1); use `contentSecondary` (`FocusStyle.quietInk`).
- A disabled control sits in a `FILL_FAINT` well with `contentTertiary` words, on the room only: a sheet
  omits an action that cannot be taken.

**On artwork.** A translucent white fill vanishes over a light image. What sits on an image takes a dark
plate instead (`VEIL`, matte, `groundPlate()`): card marks and progress, and controls inside `OnArtwork { }`.
Over a white image, the raspberry end of a progress line still reaches 3.5:1.

### Veils (`Scrim.kt`)

`HlScrim` holds the recipes laid over artwork: the left veil behind the text column, the top veil under the
top bar, the bottom fade, and the welcome wall's veil.

- **No veil draws a line.** Stops are joined by one smooth monotone curve (`alphaAt`) rendered in 32 steps
  (`colorStops`); straight segments meeting at an angle show as a line across the picture.
- **The left veil follows the text column in dp** (`safeH` + `readableNarrow`), then fades over
  `BackdropDefaults.veilFade`, never as a share of an assumed screen width.
- **It keeps a floor per band** (the four bands of `HlScrim.LEFT`), read from a small copy of the backdrop
  (`ArtworkSample.leftVeil`): the opacity the quietest text needs for 4.5:1. Bands never get lighter
  leftwards. Highlights under a twentieth of a band are ignored (`FLOOR_QUANTILE`).
- **One exception to 4.5:1** (`HlScrim.LEFT_CONTRAST`): over the column's left half the quietest text keeps
  4.5:1; over the third quarter, 3.5:1; over the last, 3:1. Nowhere else does text go below 4.5:1.
- **The bottom fade** runs in one stroke (`fadeFrom` to `fadeTo`); on a detail page, from 45 % of the height.
  A dimmed picture (`Quiet`) stops at what its quietest text needs (`BackdropStyle.foot`).
- **No picture, no light.** `Backdrop(null)` is the black room, and its ambient light is transparent.

### Type (`Type.kt`)

Nine sizes: **12 · 14 · 16 · 20 · 24 · 26 · 32 · 44 · 64 sp**, line heights on a 2 sp grid. Nineteen roles,
one job each, plus the billing block's signature. A screen picks a role and never overrides its size, line height,
family, weight or tracking (`DesignTokenGuardTest`). Unstyled text is `body`; `HouselightsTheme` maps
tv-material's roles onto the scale.

| Style | Size / line | Voice, weight | Role |
|---|---|---|---|
| `display` | 64 / 72 | poster, Bold | a detail page's title without logo |
| `headline` | 44 / 48 | poster, Bold | screen titles; home hero title without logo |
| `subject` | 32 / 36 | poster, SemiBold | what a panel, sheet or pane is about; an intertitle's line; a stated figure |
| `place`, `placeCurrent` | 26 / 30 | poster, SemiBold; Bold | the places in the bar; Bold for the one you are in |
| `rowTitle` | 24 / 28 | poster, SemiBold | a row's title; the selected title over a wall |
| `cardName` | 20 / 24 | poster, SemiBold | a name under a card |
| `cardTitle` | 20 / 24 | poster, Bold | a title on a card without artwork |
| `title` | 24 / 30 | reading, SemiBold | an episode's name in its heading |
| `lead` | 20 / 26 | reading, SemiBold | row headers, card and result names, episode names, a tagline |
| `body` | 16 / 24 | reading, Regular | overviews, descriptions |
| `entry` | 16 / 20 | reading, SemiBold | a name to read or choose: settings, menu options, button words |
| `meta` | 16 / 20 | reading, Regular | facts and values: year, runtime, score, sizes |
| `caption` | 14 / 18 | reading, Regular | secondary lines: hints, roles, paths, the line under a name |
| `group` | 14 / 18 | reading, SemiBold | the title over a group ("Vidéo", "État") |
| `code` | 14 / 22 | monospace, Regular | raw text (NFO XML) |
| `spec` | 20 / 26 | technical, SemiBold | a technical value stated large ("Ultra HD 60 Hz") |
| `jaquette` | 14 / 18 | technical, SemiBold | formats as on a case's back, a duration inside a button |
| `badge` | 12 / 14 | technical, Bold | printed marks: an age rating, a protocol |
| `titleSignature` | 96 / 96 | signature | a short title without logo on one line; else `display` on two even lines (`TitleArt(measured = true)`) |
| `billing` / `billingJoin` | 18 and 14 / 24 | signature | the billing block's capitals and small words |

- **The ember light on words** (`emberLit`): the place you are in, a screen's title. A component sets it,
  never a screen (`DesignTokenGuardTest`).
- **Tabular figures** (`tnum`) in technical styles and wherever a figure changes (`subject`, `meta`,
  `caption`). `case` only for billing capitals, never with `tnum`.
- **Tracking follows size** (`trackingFor`): −0.01 em from 44 sp, none from 16 to 32, +0.01 em at 14,
  +0.02 em at 12. Billing capitals +0.04 em.
- **Boxes keep to their letters** (`LineHeightStyle`, `Trim.Both`): spacing is measured from the type.
- **Line breaking:** titles balance their lines (`LineBreak.Heading`), paragraphs break for reading
  (`LineBreak.Paragraph`), nothing is hyphenated.
- **Fallback scripts.** Archivo covers Latin and Vietnamese; line heights leave room for stacked marks and
  for fallback fonts. The catalogue's stress section shows the extremes.
- **Text scale.** A frame holding text is never a fixed height: use `heightIn(min = …)`, or `textRoom(height)`
  / `textRoom(style)` where the frame must not move with its content.

### Space and size (`Dimens.kt`)

| Token | Content |
|---|---|
| `HlSpace` | 4 dp grid: `xxs` 4, `xs` 8, `s` 12, `m` 16, `l` 24, `xl` 32, `xxl` 48; `fact` 20 between facts; safe area `safeH` 48 / `safeV` 28; `fadeEdge` 24 |
| `HlInset` | container padding: `tight` 8 (menus, thumbnail results), `compact` 12 (dense rows), `card` 24, `sheet` 32 |
| `HlShapes` | radius follows hierarchy: `thumb` 4 (images up to 48 dp), `badge` 4, `mark` 6, `control` 10 (buttons, tiles, a tab's plate, menu options), `field` 10, `row` 12, `artwork` 12, `wide` 14, `panel` 16 (menus, source cards), `sheet` 24 (a side sheet's free corners, square against the edge). No pill, no disc: only a status dot is round, through a local `CircleShape`. Nested shapes keep parallel corners: **outer radius = inner radius + gap** (a menu: 10 + `HlMenuDefaults.inset` 6; a focus frame: the picture's + 3) (`ShapesTest`) |
| `HlSize` | artwork (`poster` 120 × 180 in a row, `wallPoster` 112 × 168 on a wall, `landscape` 240 × 135, episode 264, `portrait` 96 at 3:4, `PORTRAIT_RATIO`), `control` 48 on a detail page and `controlCompact` 44 in a screen's header, `tile` 48 and `tileCompact` 40, icons 20, `mark` 22, dots 8, `newDot` 10 ringed `newDotRing` 2.5, setting rows `row` 60 and `sheetRow` 52, row previews 40, meter 10, `intertitleRule` 24 × 2, wordmark (`wordmark` 12, `wordmarkHero` 30) |
| `HlLayout` | the screen grid, read by screens (`LayoutTokensTest`): reading widths (640, 520, caption 400), side columns (280, 340), pane top 72, the home hero band (148), `wallGutter` (seven posters across the safe area), `detailGap`, `detailHeroMin`, track table columns, `insetRows` |
| `…Defaults` | internal component parts: `HlSideSheetDefaults` (520), `HlSettingRowDefaults` (value 360, room above and below 6), `HlIconButtonDefaults` (label 200, edge 24), `HlMenuDefaults` (320), `TechSpecsDefaults`, `QrCodeDefaults` (module 6.5, whole pixels at 1080p and 4K), `BackdropDefaults` |
| `HlControl` | padding of buttons, track options and badges; icon-to-text gap 8 |
| `HlFocus` | growth by family (`POSTER_SCALE` 1.08, `WIDE_SCALE` 1.05, `PANEL_SCALE` 1.02, `CONTROL_SCALE` 1); the `frame` 2.5 `frameGap` 3 off the edge; `reach(size, scale)`, growth and frame, held by a row's `room` 16; siblings stepping back (`SIBLING_BRIGHTNESS` 0.64, `SIBLING_SATURATION` 0.65, `RECEDE_STEPS` 3); `glow` 28; `edge` 28 |

### Motion, icons, scrolling

| Token | File | Content |
|---|---|---|
| `HlMotion`, `awaitMotion` | `Motion.kt` | two curves of the system's own: `focusPull` ("mise au point", 0.20 0.75 0.25 1) for what arrives, `fallBack` ("retombée", 0.45 0 0.55 1) for what leaves, and `lightsOut`; no Material curve, none drawn elsewhere, every tween names its easing (`MotionGuardTest`). `FOCUS_IN` 220 and `FOCUS_OUT` 260 ms, `PLATE_IN` 120 and `PLATE_OUT` 180, `TINT` 480 (linear, once settled), `WAIT_TURN` 1400 (looped); `FAST` 150, `STANDARD` 250, `SLOW` 600, `DIM` 1000 ms; `SETTLE` 300 ms before the title under focus changes the screen (`rememberSettled`); `SKELETON_DELAY` 400 ms; `HOLD` 400 ms, the longest anything waits for a picture; `WALL_DRIFT`; `ENTER_SCALE`. What follows a motion waits for its end (`awaitMotion`), never a fixed `delay`, so nothing waits with animations off |
| `LocalHouseLights`, `HouseLightCue`, `LaunchCue`, `LocalLaunchScreen` | `Auditorium.kt` | house-light level (read while drawing), the playback and launch cues, the launch screen until the first screen is drawn whole |
| `HlIcons` | `Icons.kt` | every icon, one drawing style (24 dp grid, 1.75 stroke, round caps and joins). Play is the one Material glyph, filled. A state that reads as on is the same drawing filled (`Heart`, `HeartFilled`). No other Material icon |
| `rememberListScroll`, `ListScroll`, `RowScroll` | `Scroll.kt` | lists scroll only as needed (`EdgeScroll`) and keep focus `HlFocus.edge` from their ends; a list under a header brings rows back to its top (`ListScroll(top)`); rows keep focus 80 dp from the sides |
| `scrollEdges(state, color)` | `Scroll.kt` | scrolling content fades into its opaque surface over `HlSpace.fadeEdge` where there is more; never on poster rows |
| `revealOnFocus()`, `keepVerticalFocus()` | `Scroll.kt` | a group comes fully into view when focused; up and down never leave a column |
| `scrollsAsBlock()`, `ListScroll(top, toTop = true)` | `Scroll.kt` | a block (a row with its header, a hero with its actions) scrolls as one; pages turn by whole blocks |
| `fadeOutsideSafeArea()` | `Scroll.kt` | text fades out across the safe-area margin as it scrolls (layer alpha, no offscreen buffer); components only |

## Layout

The screen is a TV seen from three metres, through overscan that may crop its margins.

- **No word is cut by the screen.** Pictures may cross the safe area; text fades out before the edge
  (`fadeOutsideSafeArea`), or into an opaque surface (`scrollEdges`).
- **Pages turn by whole blocks** (`scrollsAsBlock`). On home and detail pages a block moves to the top
  keyline, and the gap between blocks (`HlLayout.detailGap`, 32) takes the previous one off screen. A detail
  page's first view is its hero plus the first row's header and a strip of images (`HlLayout.detailHeroMin`).
- **Home is three bands** on `safeH`: the bar (track and settings button), the hero band (`HlLayout.hero`,
  148: logo, one line of facts, two lines of overview or progress), the rows; `HlSpace.l` between them
  (`HomeLayoutTest`).
- **Three vertical gaps:** `HlSpace.s` within a block, `HlSpace.l` between home's bands, `HlSpace.xl` between
  the blocks of a scrolling page.
- **Reading measures:** `readable` 640, `readableNarrow` 520 beside artwork, `captionMeasure` 400.

## Components

`Hl` prefixes the building blocks that stand for a platform control or container (`HlButton`, `HlSideSheet`,
`HlSettingRow`). Parts and app-specific pieces are named for what they show (`MenuOption`, `PosterCard`,
`BillingBlock`, `TechSpecs`).

### Pictures and titles

| Component | Use |
|---|---|
| `Backdrop` (`Hero`, `Full`, `Quiet`) | full-screen artwork under the `HlScrim` veils. Drawn once per picture: the next one is decoded off screen, then picture and veils go into one cached layer and two layers cross-fade. Handed across cuts (`BackdropRelay`); a screen without backdrop lets it fade to black (`LeavingBackdrop`). Steps back under a veil (`recede`) when focus is lower on the page (`ArtworkSample.floorAcross`) |
| `AmbientBackdrop`, `rememberAmbientColor` | a screen on its picture, and the picture's light, provided as a function (`LocalAmbient`) and read while drawing |
| `PosterCard`, `LandscapeCard`, `PersonCard`, `ArtworkSurface`, `ArtworkImage` | artwork with the shared focus treatment (see *Cards*), requested at its grown size (`requestedPoster`). Names under a card in `cardName`; a wide card's facts follow its name when both fit, else go under it. A person in two titles or more opens their page (`PersonScreen`); a person of a single title does nothing |
| `AbsentCard` | a saga's missing films as empty frames, in release order; focusable, framed without growing, OK does nothing |
| `HeroInfo`, `TitleArt`, `EpisodeHeading`, `MetaLine`, `BillingBlock` | what a title is: logo or condensed title, facts, credits. Logos are normalised to the same optical weight (`EvenLogo`, `SURFACE` 0.35) and requested as shown (`titleArtRequest`). A name waits for its picture (`WithItsPicture`, `HOLD` at most) |
| `ScreenTitle` | a screen's one place name (a library, Réglages, a person) in `headline`, lit in ember; never a film's title |
| `TitleLine` | the selected title over a wall: name in `rowTitle` and facts on one baseline, ellipsised |
| `FormatLines`, `Badge`, `FactRow`, `Score` | formats in the technical voice, nothing said twice (`Formats`), read from the file (`MkvProbe`): on a title's page, the plate of a case's back (`plate`, `HlShapes.control`), a line for picture and one for sound, named in a caption, the words in `jaquette`; in a version row, unframed words. A `Badge` is a printed mark, never a format or state. A score is a quiet fact |
| `HeadOverview`, `SynopsisSheet` | three lines of overview; when cut, focusable and opening the full text in a side sheet |
| `EndCredits`, `Craft` | the crafts not in the billing block, on two columns around a gutter; rows focusable to read, composed only when reached |
| `MediaRow`, `FilmsOfHeading` | a horizontal row under its header (`SectionHeader`, in `rowTitle`), its count and its new titles (an ember dot, "3 nouveaux"); focus returns to its last card. One frame for the row travels from the card left to the card reached (`RowFrame`, `RowFrameTest`); a wall's cards keep their own. Library rows (`LibraryRows`) bill a director's films or the best rated |
| `VersionsSheet`, `VersionRow` | several copies of a film are one title; the Versions button lists them and sets which one Lire plays |
| `SearchQuery`, `LetterGrid` | library search (`SearchScreen`) under its title: the typed query on a field the keys always type in (glass, the ember ring, a still raspberry cursor), an on-screen keyboard of square tiles (`tileCompact`, glass, white with focus), results in rows (titles, people, sagas), accent-insensitive, debounced 150 ms |
| `PosterWall`, `PosterWallScrim`, `HouseLights`, `CardSkeleton`, `HeroSkeleton`, `ProgressLine` | welcome wall, playback cue, placeholders shaped like what is coming (after `SKELETON_DELAY`, `skeletonDelayPassed`; never a shimmer), progress lines, burning at a picture's foot (`HlSize.progressPoster`, `HlSize.progressWide`) |
| `PosterStrip` | a folder's latest posters in fixed slots, empty frames for the free ones |
| `ArtworkChoiceCard`, `SelectedMark` | one artwork to choose, at its shape of use; the one in use carries the ember check |

### Controls

| Component | Use |
|---|---|
| `HlButton` (`Primary`, `Secondary`), `HlIconButton`, `HeaderControls` | actions 48 dp tall, radius 10. The primary one (Lire, Reprendre) is the ember light in black ink (`onEmber`), `HlIcons.Play`, a duration in `jaquette`; focus frames it. A secondary one is a plate (`plate()` edged by `plateEdge` over a picture, glass on the room) that focus fills white. `HlIconButton` is a square tile (48, its glyph `HlSize.tileIcon`) showing its label below on focus, on a plate, inside the safe area (`HlIconButtonDefaults`); `HlActionRow` keeps room for it. In a header (`HeaderControls`): buttons 44, tiles 40. Toggles name what OK does and show the state with the icon (`lit`: the glyph in ember, raspberry on white; `HlIcons.CheckFilled`). `detailLit` lights a chosen value in the detail. Disabled: a faint well, tertiary ink, ringed by focus, OK ignored. `busy`: a pending dot and `busyLabel` ("Connexion…"), width kept, OK ignored |
| `HlSegmented` | where you are among a few places, as words in `place` (home's bar, opened by Gradation with `withMark`; seasons with `scrollable`; NFO views; form choices). The current word is lit in ember (`placeCurrent`), the others grey; focus is the white plate, with a raspberry dot (`HlSize.wordDot`) before the current word. Words line up with the start edge and plates hang past it, each word as wide as in bold: nothing moves as focus or the choice moves. Focus entering lands on the current option. Exposed as tabs |
| `HlTitleSwitch` | a screen title with a tile that switches to its other view (Films and Sagas) |
| `HlTextField` | a glass plate edged by a hairline (`field`), ringed white with focus, keyboard on OK, "OK saisir" hint (`KeyHint`); while typing, the ember ring and a raspberry cursor. One line below: `supporting` or `error` (`AttentionNote`). Placeholders are examples from resources. Saved when left, never while typing |
| `HlMenuButton`, `MenuSection`, `MenuOption` | a sectioned menu anchored to its button (sort, filters), opening below or above within the safe area; the chosen option white with its ember check (raspberry on focus's white plate), the others grey; a sort applies once the menu has closed |
| `HlScrollArea` | long content read with the remote, focusable only when scrollable; `HlScrollArea(lineHeight)` lays out only visible lines |
| `QrCode` | an address for a phone: black modules on projector white at whole pixels (`QrCodeDefaults`), the address below in `spec`. Never on artwork |

### Panels, lists and settings

| Component | Use |
|---|---|
| `HlPanel` (`Quiet`, `Raised`, `lit`), `HlPanelButton`, `HlRowSurface` | the one panel recipe and its focusable form; the row every list of choices is made of |
| `HlListItem`, `HlResultItem`, `PosterThumb` | list choices; a search result with its poster, the one in use edged in ember ("Actuel") |
| `HlNavItem`, `HlSettingGroup`, `HlSettingRow` | settings: navigation entries with their value, no well nor chevron, the open pane's icon lit (raspberry with focus); rows that lead somewhere or state a value, words on the room (`row` 60, `sheetRow` 52 on glass), white with focus, a chevron shown with focus where a row leads, disabled in a `FILL_FAINT` well. What a pane only states is a block, not a row (see *Gestures*) |
| `HlChoiceSheet`, `HlChoiceSetting` | a setting's choices in a side sheet: the current one checked and focused; choosing applies and closes. A change that failed says so in the row ("Non enregistré") |
| `HlTwoPane`, `HlFormScaffold`, `StatusPanel`, `FieldLabel` | two-pane screens (navigation or explanation left, content right, a gap and no rule between); `StatusPanel` shows where a setup stands beside its form |
| `HlDivider`, `HlStatus`, `AttentionNote`, `HlMeter`, `MeterLegend` | a hairline; a state with its light (`StatusLight`) and its reason; attention beside what it concerns; a segmented gauge inked white and grey (`MeterInk`), never ember |
| `InfoBlock`, `DefinitionList`, `DefinitionRow`, `SpecTable` | what a pane or the NFO states, aligned on one baseline (see *Gestures*) |
| `TechSpecs`, `TechSheet` | the technical sheet in a side sheet: poster and title, then Image, Son, Sous-titres, Fichier, one fact per row. Tracks named by language and format (`TrackNames`), the default track marked in the margin. Its content is decided in `TechSheet`, pure and tested |
| `HlSideSheet`, `SheetScope`, `RowPreview`, `SheetConfirm` | side sheets (see *Sheets*): glass, its free corners `sheet`, a hairline on the screen's side; it comes 24 dp in as it lights (`FOCUS_IN`, `PLATE_IN`), read while drawing |
| `HlEmptyState`, `HlErrorState` | the intertitle of an empty or failed screen. A `UiError` brings its own title, message and action (`ErrorAction`) |
| `ScanProgress` | a running scan: what is read, the ember line and its count, the current file |

## Cards

Artwork carries no text: the top of the screen names what has focus.

- **At rest**, a faint edge (`EDGE_ARTWORK`) keeps dark artwork from dissolving into the room.
- **One mark at most**, top right, on a dark plate: unwatched episodes, else the check of a watched title,
  else the ember dot of a new title (added in the last week, not watched). Progress is the ember line at the
  foot. Formats are never a card mark.
- **Without artwork** or on failure, the card is set like a one-sheet: a short rule, the name in `cardTitle`,
  the year in the technical voice. Pictures without a title (`PosterThumb`, `RowPreview`, free `PosterStrip`
  slots) and loading cards (`CardSkeleton`) show an empty frame: one step up (`groundStep()`), the artwork
  edge and a short rule in `contentDecorative`.
- **Episodes** read as a number in tabular figures, a name, then length and progress.
- **People**: the name right under the portrait, the role under it.
- **Stepping back**, siblings lose colour through the image's own filter in `RECEDE_STEPS` steps; no layer is
  drawn per card.

**Accessibility.** A card is one element: its title, then its state in words (`stateDescription`: "6 épisodes
non vus", "vu", "nouveau, en cours, 40 %"). Marks are not read. A person who does nothing is read once as
"name, role", not as a button. What is chosen says so (`selected`).

## Sheets

A sheet comes over the room behind our own scrim, closes with Back (no Close button) and leaves with the
motion it came in with. Focus stays inside and returns where it was.

- **`HlSideSheet`**: a full-height panel against the right edge (520 dp) under a lighter veil (`VEIL_SIDE`).
  From the top: what it does in `subject`; what it is about (artwork, name, essentials); a hairline; the rows,
  fading into the sheet where there is more; the Back hint. Rows that change an image preview it
  (`RowPreview`). Every action row describes itself in one line, so rows keep one height.
- **`SheetConfirm`**: an irreversible action asks in the sheet, as an intertitle in place of the rows: ember
  rule, the question in `subject`, the consequence, then Cancel (focused) and the action, its label in ember.
  Never red. Cancel or Back returns to the row that asked.
- **Reading sheets** (the technical sheet) are rows too, one per fact, focusable one by one; OK does nothing.
- **A QR code sheet** shows the `QrCode` in place of the rows; nothing in it takes focus.

Content closes a sheet with `SheetScope.dismiss(then)`: the sheet leaves, then `then` runs; keys are ignored
meanwhile. Opening another screen closes the sheet at once. Menus share the motion (`rememberSheetMotion`).

A choice that needs another screen (a new source needs a folder or a sign-in) changes nothing until that
screen succeeds. Sources change in one place, `SourceSwitcher`.

## Focus

Focus must read from the couch. It is defined in one place, `FocusStyle` (`components/FocusStyle.kt`), as
projector white in two gestures and a ring, all drawn by one surface, `HlFocusSurface`
(`components/FocusSurface.kt`):

| Gesture | For | Treatment |
|---|---|---|
| **Frame** (`FocusStyle.frame`) | what carries its own light: pictures, panels, the main action | a white frame (`HlFocus.frame`) `HlFocus.frameGap` off the edge, its corners parallel; a picture grows by its `Lift` (`Lift.Poster`, `Lift.Wide`, `Lift.Panel`; `Lift.Still` frames without growing) and glows in its own tone. 220 ms on (`focusPull`), 260 off (`fallBack`) |
| **Fill** (`FocusStyle.fill`) | plates: buttons, tiles, tabs, rows, menu options | white plate, black ink, nothing grows (`CONTROL_SCALE`). 120 ms on, 180 off |
| **Ring** (`FocusStyle.ring`) | what is read or typed in, and what cannot act | white outline inside the edge; a field turns ember while typing |

No component writes its own focused colour, frame or scale, and every focusable surface takes a gesture
(`FocusStyleGuardTest`). tv-material's surfaces (their scale, glow and Material timing) are never drawn.
`HlFocusSurface` clicks on OK's release and long-clicks when it is held, reads as one element with its role,
and reads its light, growth and glow while drawing: focus moving recomposes a plate once, when its ink turns.

- **Nothing is brighter than focus**, and ember never marks it: on a focused plate only marks stay lit, in
  `emberOnLight`.
- **Artwork** grows, takes the frame and glows with its dominant tone (`rememberGlowColor`), drawn as rings.
  Siblings step back through their image's filter (`SIBLING_BRIGHTNESS`, `SIBLING_SATURATION`). While an
  arrow is held, focus scrubs across cards: the frame shows at once, nothing grows or glows (`Scrub`).
- **Track options**: the focused option is the white plate; the current one never is unless focused. Focus
  entering a track lands on the current option (`focusProperties.enter`).
- **Disabled controls** keep focus, ringed, never filled. A read-only row (the technical sheet) is not
  disabled: it fills white and OK does nothing.
- **Growth is a share of the size**, so each family has its own scale. `HlFocus.reach(size, scale)` gives
  the room a container keeps, frame included (`FocusReachTest`).
- **Focus stays inside the safe area.** Lists stop it `HlFocus.edge` (28 dp) from the top and bottom and pad
  their foot by as much; rows of cards keep `HlFocus.room` above and below for growth and frame. Where content fades at an
  edge, focus stays clear of the fade.
- **Focus is never lost.** When its element disappears, it moves to a neighbour, else to the action that adds
  one ("Ajouter un dossier").

**Coming back** to a screen finds focus on what was left, by identity, not position (`Focus.kt`):

1. Every element that opens a screen, and every card, has a stable key (`focusKey`, `FocusKey`), such as
   `item:<id>`, `action:edit`, `section:…/item:42`. If the card is gone, its row (`focusGroupKey`) or pane
   takes focus, else the screen's start.
2. The screen keeps a `FocusMemory` (`rememberFocusMemory`) and restores it once laid out (`RestoreFocus`),
   scrolling first.
3. An action that changes its words stays one element (Lire becomes Reprendre on the same button).
4. A row or pane returns to the child it last had (`keepChildFocus`, children marked `rememberedInGroup`).
   Never `focusRestorer` (it crashes in Compose 1.7 when the list changes) nor a manual restore that cancels
   the move.

**Back** goes in two steps (`BackToStart`): deep in a screen, it first returns focus to where the screen
starts, then leaves. Home: Back from the rows goes to the tabs, then quits, with no "press again". Sheets and
menus handle their own Back; during the house-light cue, Back cancels it.

## Messages

A message appears where the action was, in the app's voice: no Toast (a test forbids it), no snackbar, no
dialog. A title that fails to play says so under its actions (`HlStatus` `Attention`, "Lecture impossible",
the reason in `caption`). A screen that cannot load, or has nothing yet, shows an intertitle with what fixes
it (`HlEmptyState`). Exception text never reaches the screen; it goes to the log.

The app asks nothing for itself: supporting the project is one row in About, with no reminder, badge or
prompt.

## Writing

- French uses *vous*, typographic apostrophes (’) and a no-break space (U+00A0) before `: ; ! ?` and inside
  `« »`. Every other language keeps the same polite register (*Sie*, *usted*, *u*…) and its own typography.
- The interface speaks the device's language, or the one chosen in Affichage (`InterfaceLanguage`).
- A number never leaves its unit: `3,27 Go`, `2 h 02`, `3840 × 1608` (`formatDuration`, `formatDimensions`,
  `formatBytes`, `formatRatio` in `ui/util/Format.kt`). Time left is a duration ("Il reste 1 h 26"); progress
  reads "3 / 9".
- A button says what OK does ("Marquer comme vu" / "Marquer comme non vu") or names what it opens ("Fiche
  technique"). An empty screen offers a verb ("Ajouter un dossier").
- Relative time is lower case inside a sentence ("Analysé il y a 3 minutes"), capitalised alone ("Il y a
  3 minutes", `relativeTime`). No arrows in sentences.
- Facts are separated by layout (`FactRow`; `FACT_GAP` inside one text). Short of room, a line drops whole
  facts from the end (`MetaLine`); only a lone fact ends in an ellipsis.
- Values are words people use: "Détecté", "Hors ligne", "Aucune", never "absent" or "0 fichier".
- Work in progress ends in an ellipsis and names the work: "Connexion…", "Vérification…". Field examples start
  with "Ex. :" and live in the resources.
- A failure says what happened and what to do, never an exception's text.
- Text from elsewhere (TMDB, NFO, servers) goes through `Typeset.text` in the mappers, for display only:
  apostrophes, ellipses and, for French text, French quotes are typeset. `StringsTest` holds the resources to
  the same rules, and every translation to every key of values with its format arguments.
- Say only what the app can know: where a player lists stand-in display modes (the Zidoo), Affichage points to
  the player's own settings and offers no choice (`DisplayModes`).

## Brand: Gradation

**Gradation** (`Gradation`): three ember bars whose widths halve, 4:2:1, like house lights going down one stop
at a time, together as wide as they are tall. The name appears in white after them, as a title on screen.

- **Ground:** pure black (`@color/room`) for the icon and the banner, flat: no glow or halo.
- **Colour:** one horizontal gradient, `emberFrom` to `emberTo`, runs across the three bars from the wide bar's
  left edge to the thin bar's right (`Gradation.light`, `drawGradation`), so the light reddens as it goes down;
  nothing else is coloured. The meaning is in the widths, so the mark also works in one colour, white.
- **Name:** HOUSELIGHTS in expanded Archivo capitals, weight decreasing letter by letter from 900 to 350, with
  optical spacing; outlines converted once to paths (`wordmark.xml`).
- **Sizes:** the icon's mark is 46 of 108 units, inside the 66-unit safe circle; the banner follows Android
  TV's 320 × 180 grid; the launch mark is 36 units. Each is optically centred (`Gradation.PULL`).

All are vectors: `ic_launcher_foreground` (with its `<monochrome>` layer), `banner.xml`, `splash_mark`. On screen,
`GradationMark` opens home's bar only (`HlSize.barMark`, the height of its words' capitals).
`BrandTest` checks their geometry, gradient and palette; `CatalogBrand` shows them as Android draws them.

## Performance

The player's GPU fills a 1080p screen only a few times per frame, so:

- **Draw full-screen content once and keep it.** A backdrop is one cached layer; the window is the only black
  under the screens; a transparent rectangle is not drawn; glows are non-overlapping rings (`drawGlow`).
- **Prepare the next screen during the gesture before it.** Settled on a title, home and walls request its
  detail logo (`DetailAhead`); while home's pill slides to a library, the wall's first page loads
  (`LibraryAhead`).
- **Light nothing that focus only crosses** (`Scrub`).
- **Measure on the player**, compiled as users run it (`cmd package compile -m speed-profile`), with
  `dumpsys gfxinfo <package> framestats` and `setprop debug.hwui.overdraw show`. See CONTRIBUTING.md.

## Guard

| Test | Holds |
|---|---|
| `DesignTokenGuardTest` | no size, text size, font, colour, opacity or shape in a screen; no ember named in a screen; no arithmetic on tokens; no Material icon but Play; images only through `ArtworkImage`, `PosterThumb` or `RowPreview` |
| `FocusStyleGuardTest` | focus defined in one place, drawn by one surface; no tv-material surface |
| `MotionGuardTest`, `ShapesTest` | the system's own curves only; radii by hierarchy, nested corners parallel, no pill or disc |
| `ContrastTest`, `ScrimTest` | contrast pairs; veil floors and smoothness |
| `TypeScaleTest`, `SpacingGridTest`, `LayoutTokensTest`, `GroundTest` | type scale, 4 dp grid, screen grid, grounds |
| `ResourceTokensTest`, `StringsTest` | resources in step with tokens; typography of resources, every translation complete |
| `CatalogCoverageTest` | every public component and every `HlIcons` glyph in the catalogue |
| `DesignDocTest` | every system name this document cites still exists |
| `RunCatchingGuardTest` | no `runCatching` around focus requests (`tryRequestFocus`) or in suspending code (`suspendRunCatching`) |

Deliberate exceptions go in a guard's `allowed` list with their reason; a value needed twice belongs in the
design system.

Remote behaviour is tested as the remote drives it, on Robolectric with the app's theme on the TV canvas
(`ui/testing/Remote.kt`): `MediaRowFocusTest`, `SheetFocusTest`, `SupportRowTest`, `ScrollAreaTest`,
`TextFieldMessageTest`, `RestoreFocusTest`, `SegmentedFocusTest`. A focus rule that changes comes with its
test.

## Catalogue

Debug builds show **Settings > About > Design system**: every token and component as a row of its states
(rest, chosen, lit, loading, failed, empty), focus checked live with the remote. Sections live in
`ui/catalog`: `CatalogScreen` (tokens, controls, sheets), `CatalogFocus` (shapes, and focus in its gestures),
`CatalogGestures`, `CatalogIcons`, `CatalogForms`, `CatalogCards`, `CatalogReading`, `CatalogStress` (longest
labels, largest text size, fallback scripts, device metrics) and `CatalogBrand`.

## Adding a screen

1. Start from `AmbientBackdrop`, `HlTwoPane` or `HlFormScaffold` and compose existing components.
2. Add a token or component here before using a new value twice.
3. End a vertical list with `contentPadding(bottom = HlFocus.edge)`; if it starts below the top of the screen,
   wrap it in `ListScroll(top = its top padding)`. `HlTwoPane` does both for its content pane.
