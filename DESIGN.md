# Houselights design system

Houselights is named after the lights of a cinema, which dim before the film starts: the room is dark and
quiet, and the light comes from the film.

The system lives in `app/src/main/java/app/houselights/ui/design`. **Screens never hard-code a colour, size,
opacity, shape, gradient or font**: they compose the tokens and components below, and the guards hold it on
every build (see *Guard*). Debug builds show every token and component in its states under
Settings > About > Design system (see *Catalogue*).

## Principles

1. **The film lights the room.** The room is pure black and colour comes from the picture: the veils and
   plates over it take its own dark, and a focused card glows in its own tone (`LocalAmbient`,
   `rememberGlowColor`). Ember is the one fixed light, a closed list of six meanings (see *Colour*).
2. **Focus is projector white, in two gestures.** A picture is lifted by its frame and its glow, a panel and
   the main action by their frame, a plate fills; nothing grows. In a row, one frame travels from card to card
   (see *Focus*). Ember never marks focus.
3. **Ten-foot first.** Nothing below 12 sp, no content below 14 sp; text reaches 4.5:1 and marks 3:1 on what
   they sit on (see *Contrast*). Content stays inside the overscan-safe area (`HlSpace.safeH`, `safeV`).
4. **One canvas.** Screens are laid out on 960 × 540 dp (`HlLayout.CANVAS_WIDTH`). `HouselightsTheme` derives
   the density from the screen's real width, not the declared one (the Zidoo declares 240 dpi); the viewer's
   text scale is kept.
5. **Calm motion.** Motion answers the user, on the system's own curves. Two moments are orchestrated: the
   house lights (at launch and before playback) and the welcome wall's drift. Screens cut, never cross-fade:
   the backdrop is handed across the cut (`BackdropRelay`) and the words change at once.
6. **Words do one job.** Sentence case, no all-caps labels, no punctuation between facts. French uses *vous*.
   Capitals are reserved for the billing block and what extends it (end credits, a row billing a director's
   films).
7. **A title is always shown.** Moving focus up to the bar or the buttons keeps the last title; before any
   focus, the first one.
8. **Depth is light, not glass.** No frosted glass, blur, parallax, reflection or drop shadow.

## Identity

The app is recognised by its type, its vocabulary and three moments, not by decoration. One family,
**Archivo**, in three widths used as voices:

| Voice | Width | Says | Styles |
|---|---|---|---|
| Poster | condensed (70) | where you are and what things are called: titles, the places of the bar (`place`), rows (`rowTitle`), names under cards (`cardName`), what a panel is about, large figures, the billing block | `display`, `headline`, `subject`, `place`, `placeCurrent`, `rowTitle`, `cardName`, `cardTitle`, `titleSignature`, `billing`, `billingJoin` |
| Reading | normal (100) | everything read or chosen: overviews, list names, button words, values | `title`, `lead`, `body`, `entry`, `meta`, `caption`, `group` |
| Technical | expanded (125), tabular figures | formats as on a case's back (`jaquette`), specifications, printed marks | `spec`, `jaquette`, `badge` |

- **No fourth voice.** An intermediate width reads as a mistake.
- **Three weights, one meaning each:** Regular reads, SemiBold names, Bold announces (and marks the place you
  are in). Medium belongs to the billing block. Each width declares only the weights it uses, so no bold is
  synthesised (`TypeScaleTest`).
- **A second face only when the text demands it:** raw text read as written (the NFO) uses the system
  monospace (`code`). Scripts Archivo lacks fall back to the system font.
- **Blu-ray case vocabulary** in the technical voice: Ultra HD, Full HD, Dolby Vision, HDR10, TrueHD Atmos;
  ratios as `2,39:1`, frame sizes as `3840 × 1608`.

Three moments carry the identity:

- **Launch.** A cold start shows Gradation alone on black, in its ember light (`splash_mark`, drawn by Android
  at the device's density): no progress bar, no text. Home is composed underneath (rows, first-view pictures
  preloaded with `PicturesAhead` and `AwaitAhead`, hero picture prepared). The launch screen leaves after
  home's first complete frame (`drawnWhole`, `LocalLaunchScreen`), at most 1.5 s after process start, and at
  once when the app starts elsewhere. `LaunchCue` then plays over `SLOW`: the bars go out widest first, each
  over `FAST`, the last as the black starts to lift off home over `STANDARD`, every step easing out
  (`lightsOut`); from Android 12 the system draws the mark, which goes out with the last bar. What was
  prepared underneath appears without a fade. A warm start shows at once.
- **House lights.** Playing a title runs `HouseLightCue` over `DIM`: the room dims (`Modifier.houseLit`), the
  veils lift, then black (`HouseLights`) and the player. The player starts once the room is dark, not after a
  fixed delay; returning runs the cue backwards. During the cue, OK is ignored and Back cancels.
- **The credits.** Director and lead cast read as one sentence, "un film de NAME avec NAME, NAME et NAME"
  (`BillingBlock`): condensed capitals, small words receding, on one line, keeping as many whole names as
  fit. The other crafts close a title's page as end credits (`EndCredits`).

The **brand** is Gradation, in the ember light, and the **wordmark**, projector white (see *Brand*). On screen,
Gradation is home's place in its bar (`GradationMark`), in place of a word; the **lockup** (`Lockup`), Gradation
beside the name, drawn, never typeset, flat and without glow, opens the welcome screen (`HlSize.wordmarkHero`,
26) and About (`HlSize.wordmark`, 12).

## Gestures: small surfaces

Small surfaces (a settings pane, a message, a running scan) borrow exactly one gesture from film materials.
`CatalogGestures` shows them side by side.

| Gesture | For | Rules |
|---|---|---|
| **Case back** | what a pane states | `InfoBlock`: a hairline, the name in `group`, the value. Pairs in a `DefinitionList` (labels in `caption`, a state with its light in a `DefinitionRow`), several of a kind in a `SpecTable` with the ember dot on the one in use. Formats in the technical voice; sizes and counts in `meta`, tabular. Only what acts is a row. Unknown values are omitted, never shown as zero. No gauges or charts. |
| **Intertitle** | every message | A short rule, one line in `subject`, one line of secondary `body` (`readableNarrow`), at most one action and a quieter one. An ember rule means attention. It replaces the content it concerns, aligned on the screen margin: never centred, never a snackbar, Toast or dialog. `HlEmptyState` on a screen, `SheetConfirm` in a sheet. |
| **Counter** | progress | The ember line with its count in tabular figures ("3 / 9", `scan_progress`) and the current file in a quiet `caption` (`ScanProgress`); before the files are counted, a sliver and no count. Durations read "2 h 02", time left "Il reste 20 min"; a timecode only names a frame. |

The library in numbers follows the case back: figures in `subject` over what they count in `caption`, then one
row per value, each opening what it counts.

## Tokens

### Colour (`Color.kt`)

`HlPalette` holds the colours, named by what they are (neutrals by lightness, `N0` to `N96`, three embers and
`Deep`). `HlColors` names their roles; only roles reach components.

| Role | Palette | Value | Use |
|---|---|---|---|
| `background` | `N0` | `#000000` | the room; masks over artwork; the window and launcher icon ground (`@color/room`) |
| `glass` | `N8` | `#131316` | glass: side sheets and menus, opaque over the dimmed room |
| `outline` | `N21` | `#33333A` | badge and field borders; every edge at `EDGE` |
| `content` | `N96` | `#F4F4F5` | projector white: what is read first |
| `contentSecondary` | `N65` | `#A3A3AB` | what explains: summaries, descriptions, facts, hints |
| `contentTertiary` | `N57` | `#8C8C95` | what is guessed: joins, placeholders, key hints, disabled |
| `contentDecorative` | `N28` | `#45454C` | never text: the rule of an empty frame, skeletons |
| `ember` | `Ember` | `#FF7A4D` | states, solid, where a gradient has no room (a dot, a check, a word) |
| `emberFrom`, `emberTo` | `EmberStart`, `EmberEnd` | `#FF9F1C` → `#FF2E63` | the ember light (`emberLight()`): progress lines, an attention rule, a field being typed in, a lit edge, a place's name, the main action; the mark's bars |
| `onEmber` | `N0` | `#000000` | ink on the ember light (the main action), 5.8:1 at its raspberry end; never white |
| `emberOnLight` | `EmberEnd` | `#FF2E63` | ember on projector white: marks only (3.3:1), never words |
| `focus` | `N96` | `#F4F4F5` | focus fill and outline |
| `onFocus` | `N0` | `#000000` | a focused control's name and icon |
| `onFocusSecondary` | `N36` | `#5D5D5D` | the line under a focused name (6.01:1) |
| `ambientDeep` | `Deep` | `#111113` | a picture's own dark, neutral: what veils and plates take where no picture lends its own |

**Neutrals are neutral**: no hue, only a touch of cool; warmth belongs to the films and to ember.

**The picture's light** (`ImageLight`), read once from a backdrop's 96 × 54 sample (`SAMPLE_PX`): the tone
covering most of the picture, weighted by its saturation, among its coloured tones (`lightOf`: a red title on
teal is teal, a few sparks do not tint a red picture; a tone under `HlScrim.GLOW_GREY` of saturation is grey,
and under a tenth of colour, `TINT_SHARE`, the picture is grey). Its **dark**: that hue, saturation at most
0.75, value 0.11 lowered until its luminance is at most 0.006; `Deep` for a grey picture. Its **glow**: that
hue at value 0.9, saturation 0.45 to 0.85; light grey for a grey picture.

**The light a screen lies on** (`AmbientLight`): `AmbientBackdrop` provides the light of the pictures it draws
as `LocalAmbient`, read while drawing and blended as their layers crossfade: `deep()`, `plateAlpha()` and
`glow()`. Without a picture it is neutral: `Deep`, `PLATE`, no glow. A plate in it is `plate(light)`;
`plate(deep, alpha)` names one directly.

**Grounds.** Three materials: the room (`background`), glass (`glass`, set off by its hairline) and artwork.
A component reads what it lies on (`LocalGround`: `Ground.Room`, `Glass`, `Artwork`) instead of being told
(`GroundTest`); sheets and raised panels provide glass, artwork surfaces and `OnArtwork { }` provide artwork.
A thing on its ground takes one step up, `groundStep()` laid as `groundStepFill()`: `FILL` on the room (a
step a TV still shows), `FILL_FAINT` on glass. What must hide what is under it reads the ground opaque
(`groundColor`, `groundFill`).

**Opacity.** `HlAlpha` names every opacity:

| Family | Tokens |
|---|---|
| Fills (`content` over its ground) | `FILL_FAINT` 6 % (wells: a meter's track, a disabled control, an absent film's card), `FILL` 9 % (a thing on its ground: a placeholder, a quiet panel, a control on glass), `FILL_STRONG` 20 % (a progress line's track beside text) |
| Veils (`background` over what is under it) | `VEIL_SIDE` 60 % (the room beside a side sheet), `VEIL` 80 % (card marks and their progress track on artwork; the least a receding backdrop takes) |
| Plates (a picture's own dark over it) | `PLATE` 74 %, raised over a light picture until its quietest text keeps 4.5:1, up to `PLATE_MAX` 92 %; set off by a `PLATE_EDGE` hairline (`plateEdge`, `content` at 11 %) |
| Edges | `EDGE` (`outline` at 60 %, `HlColors.edge`), `EDGE_ARTWORK` (`content` at 12 %, around a picture at rest) |

Check fills on a TV with the catalogue's black-level chart (`BLACK_LEVELS`).

**Ember is a closed list.** It says six things:

- *In use*: a title's progress, a scan, the track playing, the setup in use, a field being typed in.
- *Your choice*: the current choice's check, a chosen value or match, an active filter, a favourite.
- *Needs attention*: an error with its mark, the action a question asks about.
- *New*: the dot of a title new to the library, a row's count of new titles.
- *Where you are*: the current place in the bar (its word, or Gradation for home), a place's title (never a
  task's), the open section's icon, the current season.
- *The action to play*: Lire or Reprendre, filled with the ember light.

Gradation at launch is the only exception. What is done or simply true is projector white (a watched title,
`StatusState.Done`). Ember never marks focus, a fact (a score is grey) or a data series. It never comes alone:
every ember state has a second sign (shape, mark or word); attention has its own circled exclamation. Screens
never name ember: they state the state and the component lights it (`HlStatus`, `AttentionNote`, `lit`,
`valueLit`, `detailLit`).

**Your choice, one way.** One glyph, `HlIcons.Check`: white means done, ember means your choice; a toggle that
is done fills it (`CheckFilled`). Over artwork both sit on the same dark plate (`SelectedMark`). On a focused
plate the ember check stays lit, raspberry (`emberOnLight`). In words, a choice is said beside its check
("Votre choix") or with a state's light (`HlStatus` On, "Actuel"); how something was done is a plain `meta`
word ("Automatique"). A `Badge` is a printed mark (an age rating, a protocol), never a state; formats are
words (`FormatLines`).

### Contrast

Text reaches 4.5:1 and icons and marks 3:1 on the surface they actually sit on, each translucent layer composed
over what is under it, over the lightest picture where it lies on one. `ContrastTest` lists every pair the
system draws and the catalogue's contrast board shows them; add a pair there before using it.

- Control states are named colours, never an opacity on the current one: the line under a name is
  `contentSecondary` at rest, `ember` when lit, `contentTertiary` disabled, `onFocusSecondary` with focus
  (`FocusStyle.quietInk`).
- Forbidden: white on the ember light (the main action's ink is black); raspberry words on white;
  `contentTertiary` on `FILL_STRONG` (3.9:1); grey words on a plate on the bare picture below its floor; ember
  words on a plate over the bare picture, where it is a mark only (its raspberry end 4.3:1 even at
  `PLATE_MAX`); `contentTertiary` on a wall's field out of its title's band.
- A disabled control sits in a `FILL_FAINT` well with `contentTertiary` words (over a picture it keeps its
  plate); a sheet omits an action that cannot be taken.
- The quietest text on a plate over the bare picture needs its raised opacity: 4.6:1 over white at
  `PLATE_MAX`.

**On artwork.** A translucent white fill vanishes over a light image. What sits on a picture takes a dark
plate: a control inside `OnArtwork { }` the picture's own dark (`plate(light)`, edged by `plateEdge`); a card's
marks and progress track, black at `VEIL` (`groundPlate()`). Over a white image the raspberry end of a progress
line still reaches 3.5:1.

### Veils (`Scrim.kt`)

`HlScrim` holds the recipes laid over artwork. A backdrop takes one of four styles (`BackdropStyle`):

| Style | Where | Picture | Left veil | Bottom veil |
|---|---|---|---|---|
| `Hero` | home | whole | `LEFT_HOME` 0.95 → 0.48 | black from 40 % to 77 % of the height |
| `Full` | a title's page | whole | `LEFT_DETAIL` 0.96 → 0.67 | black from 45 % to 96 % |
| `Quiet` | a person's titles, the poster editor | dimmed to `QUIET_PICTURE` 0.28, files of 1280 px at most | `LEFT_QUIET` 0.6 → 0.25 | what its quietest text needs, short of black |
| `Diffuse` | a library wall | its diffuse field | none | black, 0.5 at the foot |

- **The left and top veils are the picture's own dark** (`ambientDeep`), the bottom one the room's black, so
  the floor stays the room. They go into the picture's cached layer as two gradients, dithered; the top's dark
  turns to black where neither veils.
- **No veil draws a line.** Stops are joined by one smooth monotone curve (`alphaAt`) rendered in 32 steps
  (`colorStops`); straight segments meeting at an angle show as a line across the picture.
- **The left veil follows the text column in dp** (`safeH` + `readableNarrow`), its stops at the column's
  band edges, then fades out over up to `BackdropDefaults.homeFade` (650), `detailFade` (500) or `quietFade`
  (420), in proportion to its last stop; never as a share of an assumed screen width.
- **It keeps a floor per band** (its four bands), read from the sample (`ArtworkSample.leftVeil`): the opacity
  the quietest text needs for its contrast, the top veil's dark counted over the picture. A veil in the
  picture's dark lightens what it covers: it needs `(grey - ceiling) / (grey - dark)`, at most 0.915 over
  white (a black one, 0.85). Bands never get lighter leftwards. Highlights under a twentieth of a band are
  ignored (`FLOOR_QUANTILE`).
- **One exception to 4.5:1** (`HlScrim.LEFT_CONTRAST`): over the column's left half the quietest text keeps
  4.5:1; over the third quarter, 3.5:1; over the last, 3:1. Nowhere else does text go below 4.5:1.
- **The top veil** (`TOP`) is 0.74 at the edge, 0.44 at 72 dp and gone at 164 dp.
- **Plates** over a picture rise from `PLATE` to what its quietest text needs over the header's band
  (`ArtworkSample.plateFloor`, `BackdropDefaults.plateBand` 112), at most `PLATE_MAX`; every plate on the
  screen takes it. Over a diffuse field they stay at `PLATE`.
- **A wall's diffuse field** (`Diffuse`): its backdrop's sample averaged down to 64 × 36, blurred three times,
  its colour lifted 1.2 times, its 95th percentile brought to luminance 0.039 (`FIELD_LUMINANCE_MAX`) and
  every point capped there, rounded down. Enlarged with bilinear filtering under black veils (`DIFFUSE_TOP`
  0.66, gone at 200 dp; `DIFFUSE_BOTTOM` 0.5 at the foot), in one fill with an ordered 8 × 8 dither under one
  level: it shows rings otherwise. `contentSecondary` keeps 4.6:1 on it bare, `contentTertiary` only in the
  title's band or on a plate. A wall never decodes its picture: 9 KB a title, memory-cached beside its sample.
- **One fade at a time.** A picture, its veils and its dark crossfade together over `TINT`, linear, once the
  title has settled, the leaving one held whole under the next, then released: the screen never dips, and
  plates follow the same progress. A picture coming during a fade waits for its end, the last to come in
  place of any waiting: two layers at most.
- **No picture, no light.** `Backdrop(null)` is the black room: the neutral dark, no glow. It comes in turn
  like a picture, the one showing going out into it.

### Type (`Type.kt`)

Nine sizes: **12 · 14 · 16 · 20 · 24 · 26 · 32 · 44 · 64 sp**, line heights on a 2 sp grid, and three
signatures at their own measure. Each style has one job; a screen picks one and never overrides its size, line
height, family, weight or tracking (`DesignTokenGuardTest`). Unstyled text is `body`; `HouselightsTheme` maps
tv-material's roles onto the scale.

| Style | Size / line | Voice, weight | Role |
|---|---|---|---|
| `display` | 64 / 72 | poster, Bold | a title's page without logo |
| `headline` | 44 / 48 | poster, Bold | screen titles (`ScreenTitle`, `TaskTitle`); home's hero title without logo |
| `subject` | 32 / 36 | poster, SemiBold | what a panel, sheet or pane is about; an intertitle's line; a stated figure |
| `place`, `placeCurrent` | 26 / 30 | poster, SemiBold; Bold | the words of a track (`HlSegmented`): Bold and lit for the current one |
| `rowTitle` | 24 / 28 | poster, SemiBold | a row's title; the selected title over a wall |
| `cardName` | 20 / 24 | poster, SemiBold | a name under a card |
| `cardTitle` | 20 / 24 | poster, Bold | a title on a card without artwork |
| `title` | 24 / 30 | reading, SemiBold | a tagline set large; an episode's name in its heading |
| `lead` | 20 / 26 | reading, SemiBold | a tagline under a title, a list choice's name, the typed query |
| `body` | 16 / 24 | reading, Regular | overviews, descriptions |
| `entry` | 16 / 20 | reading, SemiBold | a name to read or choose: settings, menu options, button words |
| `meta` | 16 / 20 | reading, Regular | facts and values: year, runtime, score, sizes |
| `caption` | 14 / 18 | reading, Regular | secondary lines: hints, roles, paths, labels, the line under a name |
| `group` | 14 / 18 | reading, SemiBold | the title over a group ("Vidéo", "État"); an info block's name |
| `code` | 14 / 22 | monospace, Regular | raw text (NFO XML) |
| `spec` | 20 / 26 | technical, SemiBold | a technical value stated large ("Ultra HD 60 Hz") |
| `jaquette` | 14 / 18 | technical, SemiBold | formats as on a case's back, a duration inside a button |
| `badge` | 12 / 14 | technical, Bold | printed marks: an age rating, a protocol; a card's year |
| `titleSignature` | 96 / 96 | signature | a short title without logo on one line; else `display` on two even lines (`TitleArt(measured = true)`) |
| `billing` / `billingJoin` | 18 and 14 / 24 | signature | the billing block's capitals and small words |

- **The ember light on words** (`emberLit`): the place you are in, a place's title. A component sets it,
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
| `HlInset` | container padding: `tight` 8 (results holding a thumbnail), `card` 24, `sheet` 32 |
| `HlShapes` | radius follows hierarchy: `thumb` 4 (images up to 48 dp), `badge` 4, `mark` 6, `control` 10 (buttons, tiles, a track's plate, menu options), `field` 10, `row` 12, `artwork` 12, `wide` 14, `panel` 16 (menus, source cards), `sheet` 24 (a side sheet's free corners, square against the edge). Every radius is in dp, never a pill or a disc; only dots are round, through a local `CircleShape` (`ShapesTest`). Nested shapes keep parallel corners: **outer radius = inner radius + gap** (a menu: 10 + `HlMenuDefaults.inset` 6; a mark in a wide image's corner: 6 + 8; a focus frame: the picture's + 3) |
| `HlSize` | artwork (`poster` 120 × 180 in a row, `wallPoster` 112 × 168 on a wall, `landscape` 240 × 135, `episode` 264, `portrait` 96 at 3:4, `PORTRAIT_RATIO`), `control` 48 on a page and `controlCompact` 44 in a screen's header, `tile` 48 and `tileCompact` 40, `segment` 40, icons (`icon` 20, `tileIcon` 22, `largeIcon` 24, `inlineIcon` 16), `mark` 22, dots (`dot` 8, `newDot` 10 ringed `newDotRing` 2.5, `newsDot` 7, `wordDot` 7), setting rows `row` 60 and `sheetRow` 52, row previews 40, meter 10, progress lines (`progressPoster` 4, `progressWide` 5, `progress` 3 beside text, `heroProgress` 220 × 4), `intertitleRule` 24 × 2, `barMark` 18, the lockup's capitals (`wordmark` 12, `wordmarkHero` 26) |
| `HlLayout` | the screen grid, read by screens (`LayoutTokensTest`): reading widths (640, 520, caption 400), side columns (280, 340), pane top 72, the home hero band (148), `wallGutter` (seven posters across the safe area), `detailGap`, `detailHeroMin`, track table columns, `insetRows` |
| `…Defaults` | internal component parts: `HlSideSheetDefaults` (520), `HlSettingRowDefaults` (value 360, room above and below 6), `HlIconButtonDefaults` (label 200, edge 24), `HlMenuDefaults` (320, inset 6), `TechSpecsDefaults`, `QrCodeDefaults` (module 6.5, whole pixels at 1080p and 4K), `BackdropDefaults` |
| `HlControl` | padding of buttons, track words and badges; icon-to-text gap 8 |
| `HlFocus` | the `frame` 2.5, `frameGap` 3 off the edge: their `reach` 5.5, whatever the size, held by a row's `room` 12, which the glow passes as far as a row draws (30), and a wall's `wallRoom` 24, where the grid cuts the glow once faded; siblings stepping back (`SIBLING_BRIGHTNESS` 0.64, `SIBLING_SATURATION` 0.65, `RECEDE_STEPS` 3); `glow` 28; `edge` 28 |

### Motion (`Motion.kt`, `Auditorium.kt`)

| Token | Content |
|---|---|
| `HlMotion` curves | three of the system's own: `focusPull` ("mise au point", 0.20 0.75 0.25 1) for what arrives, `fallBack` ("retombée", 0.45 0 0.55 1) for what leaves or crosses, `lightsOut` (ease-out) for lights going out. No Material curve, no curve drawn elsewhere, no animation on a default spring or a cross-fading container; every tween names its easing (`MotionGuardTest`) |
| `HlMotion` durations | `FOCUS_IN` 220 and `FOCUS_OUT` 260 ms (a frame, a glow, a recede), `PLATE_IN` 120 and `PLATE_OUT` 180 (a plate's white), `TINT` 480 (a backdrop's fade, linear, once settled); `FAST` 150, `STANDARD` 250, `SLOW` 600, `DIM` 1000 ms; `SETTLE` 300 ms before the title under focus changes the screen (`rememberSettled`); `SKELETON_DELAY` 400 ms; `HOLD` 400 ms, the longest anything waits for a picture; `WALL_DRIFT` 140 s, one loop of the welcome wall; `ENTER_SCALE`, which a menu grows from |
| `awaitMotion` | what follows a motion waits for its end, never a fixed `delay`, so nothing waits with animations off |
| `LocalHouseLights`, `HouseLightCue`, `LaunchCue`, `LocalLaunchScreen` | house-light level (read while drawing, each layer redrawn only during its own step: `rememberCueStep`), the playback and launch cues, the launch screen until the first screen is drawn whole |

### Icons (`Icons.kt`)

`HlIcons` holds every icon, all Lucide's ([license](third_party/lucide/LICENSE)), their SVG paths as Lucide draws
them: 24 dp grid, stroke 2, round caps and joins. A state that reads as on is the same drawing filled (`Heart`,
`HeartFilled`) or its check cut out of a filled circle (`CheckFilled`); Play is filled, as on a remote. There is no
other family: no Material icon is drawn, imported or declared as a dependency (`DesignTokenGuardTest`).

### Scrolling (`Scroll.kt`)

| Token | Content |
|---|---|
| `rememberListScroll`, `ListScroll`, `RowScroll` | lists scroll only as needed (`EdgeScroll`) and keep focus `HlFocus.edge` from their ends; a list under a header brings rows back to its top (`ListScroll(top)`); rows keep focus 80 dp from the sides |
| `rememberRowState` | a row resting at its start stays there when titles arrive in front of it |
| `scrollEdges(state, color)` | scrolling content fades into its opaque surface over `HlSpace.fadeEdge` where there is more; never on poster rows |
| `revealOnFocus()`, `keepVerticalFocus()` | a group comes fully into view when focused; up and down never leave a column |
| `scrollsAsBlock()`, `ListScroll(top, toTop = true)` | a block (a row with its header, a hero with its actions) scrolls as one; pages turn by whole blocks |
| `fadeOutsideSafeArea()` | text fades out across the safe-area margin as it scrolls (layer alpha, no offscreen buffer) |

## Layout

The screen is a TV seen from three metres, through overscan that may crop its margins.

- **No word is cut by the screen.** Pictures may cross the safe area; text fades out before the edge
  (`fadeOutsideSafeArea`), or into an opaque surface (`scrollEdges`).
- **Pages turn by whole blocks** (`scrollsAsBlock`). On home and a title's page a block moves to the top
  keyline, and the gap between blocks (`HlLayout.detailGap`, 32) takes the previous one off screen. A title's
  page first shows its hero plus the first row's header and a strip of images (`HlLayout.detailHeroMin`).
- **Home is three bands** on `safeH`: the bar (the places, home's as Gradation, the search and settings
  tiles), the hero band (`HlLayout.hero`, 148: logo, one line of facts, two lines of overview or progress), the
  rows; `HlSpace.l` between them (`HomeLayoutTest`).
- **Three vertical gaps:** `HlSpace.s` within a block, `HlSpace.l` between home's bands, `HlSpace.xl` between
  the blocks of a scrolling page.
- **Reading measures:** `readable` 640, `readableNarrow` 520 beside artwork, `captionMeasure` 400.
- **A focused poster stays inside the safe area**, its frame included (`HomeLayoutTest`, `DetailLayoutTest`,
  `FocusReachTest`).
- **A wall** keeps `HlFocus.wallRoom` (24, the gap between its lines) above its first line: the grid cuts a
  focused poster's glow there, once faded (`FocusReachTest`).
- **The welcome screen**: the lockup and tagline in the middle of the left column, notes under them; the task's
  title on one line over the two choices, raised on glass, a focused one's frame inside the safe area
  (`WelcomeLayoutTest`).

## Focus

Focus must read from the couch. It is said in one place, `FocusStyle` (`components/FocusStyle.kt`), as
projector white in two gestures and a ring:

| Gesture | For | Treatment |
|---|---|---|
| **Frame** (`FocusStyle.frame`) | what carries its own light: pictures, panels, the main action | a white frame (`HlFocus.frame`) `HlFocus.frameGap` off the edge, its corners parallel; a picture glows in its own light. 220 ms on (`focusPull`), 260 off (`fallBack`) |
| **Fill** (`FocusStyle.fill`) | plates: buttons, tiles, track words, rows, menu options | white plate, black ink. 120 ms on, 180 off |
| **Ring** (`FocusStyle.ring`) | what is read or typed in, and what cannot act | a white outline inside the edge, drawn by a field or a scroll area, and by a disabled surface; a field turns ember while typing |

No component writes its own focused colour or frame, nothing scales with focus, every focusable surface takes a
gesture, and what takes focus shows it (`FocusStyleGuardTest`). The one exception is a synopsis sheet's text, the
only thing in its sheet, focused to scroll.

**The surface.** `HlFocusSurface` (`components/FocusSurface.kt`) draws every focusable control, row, panel and
picture. OK (centre, Enter, the keypad's Enter) presses on its way down and clicks on its way up, only on the
surface that took the press; held until it repeats, it long-clicks instead, and the release clicks nothing;
focus leaving lets go of a press (`OkPressTest`). There is no other way to click (`FocusStyleGuardTest`). It
reads as one element with its role. Its light and glow are read while drawing, in no layer of their own, a
plate's light in its content's clip layer: focus moving recomposes a surface as it turns, and a plate once more
when its ink turns.

**The frame that travels.** In a `MediaRow`, cards leave their frame to the row (`RowFrame`): one frame travels
from the card left to the card reached in the focus pull, following the row's scroll, goes out in place in the
fall back when focus leaves the row, and jumps while an arrow is held. It is read while drawing; nothing
recomposes (`RowFrameTest`). **A row holds framed cards only**: a control there would show focus twice
(`FocusStyleGuardTest`). A wall's cards keep their own frame.

**Above its neighbours.** The focused picture draws on top, so its glow lies over its neighbours, the one it left
above the rest until its light is out, read while placing; a card whose picture is not its root raises its whole
column (`CardColumn`, `CardRaiseTest`).

**Its light.** A picture glows in its own tone (`rememberGlowColor`, sampled once focus rests); a card without
a picture of its own (a person, an artwork choice) glows in the light of the picture under the screen
(`LocalAmbient`). The glow fades over `HlScrim.GLOW_STEPS` rings out to `HlFocus.glow`, the last one clear;
each lit ring is drawn once at its combined alpha (`drawGlow`). Its siblings step back through their image's
colour filter in `RECEDE_STEPS` (`SIBLING_BRIGHTNESS`, `SIBLING_SATURATION`), marks and progress with them,
inside a row or a wall (`RecedeTest`).

**An arrow held.** While an arrow is held, or pressed again within 140 ms (`ScrubTracker`), focus runs: what it
crosses lights at once, nothing glows, the row's frame jumps; the run ends 120 ms after the last arrow (`Scrub`,
`ScrubTest`).

- **Nothing is brighter than focus**, and ember never marks it: on a focused plate only marks stay lit, in
  `emberOnLight`.
- **Track options**: the focused word is the white plate; the current one is lit, never white unless focused;
  home's Gradation, current, stays lit on the plate in `emberOnLight`. Focus entering a track lands on the
  current option (`focusProperties.enter`, `SegmentedFocusTest`).
- **Disabled controls** keep focus, ringed, never filled. A read-only row (the technical sheet) is not
  disabled: it fills white and OK does nothing.
- **Nothing grows.** Focus reaches `HlFocus.reach` past what it frames, whatever its size: the room a
  container keeps (`FocusReachTest`).
- **Focus stays inside the safe area.** Lists stop it `HlFocus.edge` (28 dp) from the top and bottom and pad
  their foot by as much; rows of cards keep `HlFocus.room` above and below for the frame, the glow passing it as
  far as a row draws. Where content fades at an edge, focus stays clear of the fade.
- **Focus is never lost.** When its element disappears, it moves to a neighbour, else to the action that adds
  one ("Ajouter un dossier").

**Coming back** to a screen finds focus on what was left, by identity, not position (`Focus.kt`):

1. Every element that opens a screen has a stable key (`focusKey`, `FocusKey`), such as `item:<id>`,
   `action:edit`, `section:…/item:42`. If it is gone, its row (`focusGroupKey`) or pane takes focus, else the
   screen's start.
2. The screen keeps a `FocusMemory` (`rememberFocusMemory`) and restores it once laid out (`RestoreFocus`):
   the element if it is placed, else scrolled to, else its group; it then keeps it in view while the screen
   settles. The window's own first focus is ignored, and a reload never moves focus (`RestoreFocusTest`).
3. An action that changes its words stays one element (Lire becomes Reprendre on the same button).
4. A row or pane returns to the child it last had (`keepChildFocus`, children marked `rememberedInGroup`,
   `MediaRowFocusTest`). Never `focusRestorer` (it crashes in Compose 1.7 when the list changes) nor a manual
   restore that cancels the move (`FocusStyleGuardTest`).

**Back** goes in two steps (`BackToStart`): deep in a screen, it first returns focus to where the screen
starts, then leaves. Home: Back from a lower row goes up to the bar and the first row; from the first row or
the bar, it quits, with no "press again". Sheets and menus handle their own Back; during the house-light cue,
Back cancels it.

## Components

`Hl` prefixes the building blocks that stand for a platform control or container (`HlButton`, `HlSideSheet`,
`HlSettingRow`). Parts and app-specific pieces are named for what they show (`MenuOption`, `PosterCard`,
`BillingBlock`, `TechSpecs`).

### Pictures and titles

| Component | Use |
|---|---|
| `Backdrop` (`Hero`, `Full`, `Quiet`, `Diffuse`) | full-screen artwork under its veils (see *Veils*); a wall's is its diffuse field. Each picture is decoded off screen, then drawn with its veils into one cached layer; one fade at a time, two layers at most (`FadeBetween`, `BackdropFadeTest`). Handed across cuts (`BackdropRelay`), the handed-on picture waiting up to `HOLD` for the arriving one; a screen without backdrop lets it go out over `SLOW` (`LeavingBackdrop`). Only the first picture, in memory under the launch screen, shows at once (`showsAtOnce`). Steps back (`recede`) when focus is lower on the page, to the veil text needs over the whole picture, at least `VEIL` (`ArtworkSample.floorAcross`) |
| `AmbientBackdrop` | a screen on its picture, its light provided as `LocalAmbient` (see *Colour*) |
| `PosterCard`, `LandscapeCard`, `PersonCard`, `ArtworkSurface`, `ArtworkImage` | artwork with the shared focus treatment (see *Cards*); every picture is requested at its laid-out size, a wall's poster at its cell's (`requestedPoster`). Names under a card in `cardName`; a wide card's facts follow its name when both fit, else go under it. A person in two titles or more opens their page (`PersonScreen`); a person of a single title does nothing |
| `AbsentCard` | a saga's missing films, in release order: a faint plate with a short rule, the title in `cardTitle` and the year in `badge`, both tertiary; focusable, framed without a glow, "Absent de votre bibliothèque" with focus; OK does nothing |
| `HeroInfo`, `TitleArt`, `EpisodeHeading`, `MetaLine`, `BillingBlock` | what a title is: logo or condensed title, facts, credits. Logos are normalised to the same optical weight (`EvenLogo`, `SURFACE` 0.35) and requested as shown (`titleArtRequest`). A name waits for its picture (`WithItsPicture`, `HOLD` at most) |
| `ScreenTitle`, `TaskTitle`, `HlTitleSwitch` | a screen's title in `headline`. A place's name (a wall, Réglages, Rechercher, a person) is lit in ember, one per screen; a task or a form (Identifier, Ajouter un dossier, a choice of artwork) is white; never a film's title. `DesignTokenGuardTest` lists the places |
| `TitleLine` | the selected title over a wall: name in `rowTitle` and facts on one baseline, ellipsised |
| `FormatLines`, `Badge`, `FactRow`, `Score` | formats in the technical voice, nothing said twice (`Formats`), read from the file (`MkvProbe`), with the frame rate and the best track's bitrate: on a title's page, the plate of a case's back (`plate`, `HlShapes.control`), a line for picture and one for sound, named in a caption, the words in `jaquette`; in a version row, unframed words. A `Badge` is a printed mark, never a format or state. A score is a quiet fact |
| `HeadOverview`, `SynopsisSheet` | three lines of overview; when cut, focusable and opening the full text in a side sheet |
| `EndCredits`, `Craft` | the crafts not in the billing block, under their heading, on two columns around a gutter, three names a craft at most; rows focusable to read, composed only when reached |
| `MediaRow`, `FilmsOfHeading` | a horizontal row under its header (`SectionHeader`, in `rowTitle`) with its `count` ("1 film sur 4", `filmCount`) and its `news` (an ember dot, "3 nouveaux": the cards wearing the new dot, `wearsNewDot`); focus returns to its last card. Its cards share one travelling frame and it holds framed cards only (see *Focus*). Library rows (`LibraryRows`) bill a director's films or the best rated |
| `VersionsSheet`, `VersionRow` | several copies of a film are one title; the Versions button lists them and sets which one Lire plays; the current copy carries the ember check and "Votre choix" |
| `SearchQuery`, `LetterGrid` | library search (`SearchScreen`) under its title: the typed query on a field the keys always type in (glass, the ember ring, a still raspberry cursor), an on-screen keyboard of square tiles (`tileCompact`, glass, white with focus; `LETTER_KEYS` A to Z then 0 to 9, Delete and Space as buttons), results in rows (titles, people, sagas), accent-insensitive, debounced 150 ms |
| `PosterWall`, `PosterWallScrim`, `HouseLights`, `CardSkeleton`, `HeroSkeleton`, `ProgressLine` | the welcome wall (nine tilted columns at `HlScrim.WALL`, drifting over `WALL_DRIFT`) and its black veil; the playback cue's black; placeholders shaped like what is coming (after `SKELETON_DELAY`, `skeletonDelayPassed`; never a shimmer); progress lines in the ember light, stretched over the part seen: burning at a picture's foot (`HlSize.progressPoster`, `HlSize.progressWide`) on a black track, beside text or in home's hero on `FILL_STRONG` |
| `PosterStrip` | a folder's latest posters in fixed slots, empty frames for the free ones; without posters, its name set like a one-sheet |
| `ArtworkChoiceCard`, `SelectedMark` | one artwork to choose, at its shape of use, its name in `meta`; the one in use carries the ember check |

### Controls

| Component | Use |
|---|---|
| `HlButton` (`Primary`, `Secondary`), `HlIconButton`, `HeaderControls`, `HlActionRow` | actions 48 dp tall, radius 10. The primary one (Lire, Reprendre) is the ember light in black ink (`onEmber`), `HlIcons.Play`, a duration in `jaquette`; focus frames it. A secondary one is a plate by its ground (over a picture `plate(light)` edged by `plateEdge`, glass on the room, `FILL` on glass) that focus fills white. `HlIconButton` is a square tile (48, its glyph `HlSize.tileIcon`) showing its label below on focus, on a plate, at most `HlIconButtonDefaults.labelEdge` from the screen's side, hidden while a sheet is open; `HlActionRow` keeps room for it. In a header (`HeaderControls`): buttons 44, tiles 40, glyphs 20. Toggles name what OK does and show the state with the icon (`lit`: the glyph in ember, raspberry on white; `HlIcons.CheckFilled`). `detailLit` lights a chosen value in the detail. Disabled: a faint well (over a picture, its plate), tertiary ink, ringed by focus, OK ignored. `busy`: a pending dot and `busyLabel` ("Connexion…"), width kept, OK ignored |
| `HlSegmented` | where you are among a few places, as words in `place`: home's bar (its first place, home, drawn as Gradation with `markFirst`), seasons (`scrollable`, chosen as focus moves with `selectOnFocus`, each with its `SegmentDetail`), NFO views, form choices. The current word is lit in ember (`placeCurrent`), the others grey, on no plate; focus is the white plate, with a raspberry dot (`HlSize.wordDot`) before the current word. Home's Gradation is a place like the words, read as its name: the ember light when current, `emberOnLight` on focus's plate, a word's ink otherwise, on a plate padded as a word's. Words line up with the start edge and plates hang past it, each word as wide as in bold and set from its start: nothing moves as focus or the choice moves. Focus entering lands on the current option (`enterAt`). Exposed as tabs |
| `HlTextField` | a glass plate edged by a hairline (`field`), ringed white with focus, keyboard on OK, "OK saisir" hint (`KeyHint`); while typing, the ember ring and a raspberry cursor. One line below: `supporting` or `error` (`AttentionNote`). Its label is always read out, shown or not (`showLabel`); `password` hides the value. Placeholders are examples from resources. Saved when left, never while typing |
| `HlMenuButton`, `MenuSection`, `MenuOption` | a sectioned menu anchored to its button (sort, filters), on raised glass (`panel`, 320 wide), opening below or above within the safe area and growing from `ENTER_SCALE`; options 44 tall, the chosen one white with its ember check (raspberry on focus's white plate), the others grey; a sort applies once the menu has closed, filters at once with the menu kept open (`closeOnSelect`) |
| `HlScrollArea` | long content read with the remote: focusable only when scrollable, ringed white with focus, `HlSize.scrollStep` per press, a held key adding steps; `HlScrollArea(lineHeight)` lays out only visible lines |
| `QrCode` | an address for a phone: black modules on projector white at whole pixels (`QrCodeDefaults`), the address below in `spec`. Never on artwork |

### Panels, lists and settings

| Component | Use |
|---|---|
| `HlPanel` (`Quiet`, `Raised`, `lit`), `HlPanelButton`, `HlRowSurface` | the one panel recipe (`Raised` is glass) and its focusable form, framed, raised the same way; the row menu options, navigation entries and setting rows are made of |
| `HlListItem`, `HlResultItem`, `PosterThumb` | list choices on a panel button, raised over the welcome wall; a search result with its poster, the one in use edged in ember ("Actuel") |
| `HlNavItem`, `HlSettingGroup`, `HlSettingRow` | settings: navigation entries with their value, no well nor chevron, focus selecting them, the open pane's icon lit (raspberry with focus); rows on what holds them, white with focus. A row that leads somewhere or chooses is `row` 60 on the room, `sheetRow` 52 in a sheet, a chevron shown with focus where it leads; a value it only states is not focusable, 48 tall. A value lights with `valueLit` or `valueAttention`; a `RowPreview` may lead it. Disabled in a `FILL_FAINT` well, on the room only (`SettingRowTest`). What a pane only states is a block, not a row (see *Gestures*) |
| `HlChoiceSheet`, `HlChoiceSetting` | a setting's choices in a side sheet: the current one checked and focused; choosing applies and closes. A change that failed says so in the row ("Non enregistré") |
| `traktPane` | Trakt (`HlIcons.History`), with "Mes fichiers" only: the account's state (`HlStatus`, or an `AttentionNote` once Trakt ended it) and, connected, its last sync in a `DefinitionRow` (when, then the titles watched and in progress); then a row that syncs now, disabled while a sync runs, which its value says, a failed exchange said under it; then one row that connects or disconnects, its words following the account so focus stays on it. Connecting opens a side sheet: the code in `place`, projector white, never ember, beside the `QrCode` of Trakt's page, its time left as a pending state; nothing in it takes focus, Back cancels. A failure is said under the row; a build without Trakt's keys says so in one line |
| `HlTwoPane`, `HlFormScaffold`, `StatusPanel`, `FieldLabel` | two-pane screens (navigation or explanation left, content right, a gap and no rule between); `StatusPanel` shows where a setup stands beside its form, edged in ember when on |
| `HlDivider`, `HlStatus`, `AttentionNote`, `HlMeter`, `MeterLegend` | a hairline; a state with its light (`StatusState`: on a dot, off a ring, pending a quiet dot, done, attention) and its reason; attention beside what it concerns; a segmented gauge inked white and grey (`MeterInk`), never ember |
| `InfoBlock`, `DefinitionList`, `DefinitionRow`, `SpecTable` | what a pane or the NFO states, aligned on one baseline (see *Gestures*) |
| `TechSpecs`, `TechSheet` | the technical sheet in a side sheet: poster and title, then Image, Son, Sous-titres, Fichier, one fact per row. Tracks named by language and format (`TrackNames`), the default track marked in the margin. Its content is decided in `TechSheet`, pure and tested |
| `HlSideSheet`, `SheetScope`, `RowPreview`, `SheetConfirm` | side sheets (see *Sheets*): glass, its free corners `sheet`, a hairline on the screen's side; it comes 24 dp in as it lights (`FOCUS_IN`, `PLATE_IN`), read while drawing |
| `HlEmptyState`, `HlErrorState` | the intertitle of an empty or failed screen, its action primary and focused as it appears (`EmptyStateFocusTest`). A `UiError` brings its own title, message and action (`ErrorAction`) |
| `ScanProgress` | a running scan: what is read, the ember line and its count, the current file |

## Cards

Artwork carries no text: the top of the screen names what has focus.

- **At rest**, a faint edge (`EDGE_ARTWORK`) keeps dark artwork from dissolving into the room.
- **One mark at most**, top right, on a dark plate: unwatched episodes, else the check of a watched title,
  else the ember dot of a new title (added in the last week, not watched; ringed in black). Progress is the
  ember line burning at the foot, 4 dp on a poster and 5 on a wide image, its light stretched over the part
  seen. Formats are never a card mark.
- **Without artwork** or on failure, the card is set like a one-sheet: a short rule, the name in `cardTitle`,
  the year in `badge` and secondary ink, readable on a wall's field. Pictures without a title (`PosterThumb`, `RowPreview`, free `PosterStrip` slots) and
  loading cards (`CardSkeleton`) show an empty frame: one step up (`groundStep()`), the artwork edge and a
  short rule in `contentDecorative`. A person without a portrait is the bare frame, the name under it.
- **Episodes** read as a number in tabular figures, a name, then length and progress.
- **People**: the name right under the portrait, the role under it.
- **With focus**, artwork keeps its size, in a row as on a wall, a portrait as a poster: its frame and its glow
  lift it.
- **Stepping back**, siblings lose colour through the image's own filter in `RECEDE_STEPS` steps; no layer is
  drawn per card.

**Accessibility.** A card is one element: its title, then its state in words (`stateDescription`: "6 épisodes
non vus", "vu", "nouveau, en cours, 40 %"). Marks are not read. A person who does nothing is read once as
"name, role", not as a button. What is chosen says so (`selected`).

## Sheets

A sheet comes over the room behind our own scrim, closes with Back (no Close button) and leaves with the
motion it came in with. Focus stays inside and returns where it was (`SheetFocusTest`).

- **`HlSideSheet`**: a full-height panel against the right edge (520 dp) under a lighter veil (`VEIL_SIDE`).
  From the top: what it does in `subject`; what it is about (artwork, name, essentials, a status); a hairline;
  the rows, fading into the sheet where there is more; the Back hint. Rows that change an image preview it
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

## Messages

A message appears where the action was, in the app's voice: no Toast (a test forbids it), no snackbar, no
dialog. A title that fails to play says so at the end of its actions (`HlStatus` `Attention`, "Lecture
impossible", the reason in `caption`). A screen that cannot load, or has nothing yet, shows an intertitle with
what fixes it (`HlEmptyState`). Exception text never reaches the screen; it goes to the log.

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
at a time, together as wide as they are tall.

- **Ground:** pure black for the icon (`@color/room`) and the banner, flat: no glow or halo.
- **Colour:** one horizontal gradient, `emberFrom` to `emberTo`, runs across the three bars from the wide bar's
  left edge to the thin bar's right (`Gradation.light`, `drawGradation`), so the light reddens as it goes down;
  nothing else is coloured. The meaning is in the widths, so the mark also works in one colour, white.
- **Name:** HOUSELIGHTS in expanded Archivo capitals, weight decreasing letter by letter from 900 to 350, with
  optical spacing; outlines converted once to paths (`wordmark.xml`). On the banner it sits under the mark.
- **Sizes:** the icon's mark is 46 of 108 units, inside the 66-unit safe circle; the banner follows Android
  TV's 320 × 180 grid; the launch mark is 36 units. Each is optically centred (`Gradation.PULL`).

All are vectors: `ic_launcher_foreground` (with the icon's `<monochrome>` layer), `banner.xml`, `splash_mark`.
On screen, `GradationMark` is home's place in its bar only (`HlSize.barMark`, the height of its words' capitals, a
brush per bar: `Gradation.lights`): in its ember light where you are, else in one ink (see `HlSegmented`).

- **Lockup** (`Lockup`): the mark in its light, `Gradation.LOCKUP_MARK` (1.4) times the name's capitals and centred
  on them, the name `Gradation.lockupGap` (0.622, the wide bar's width) after it (`lockupBars`). Its box is the
  name's, the mark hanging past it, so it lines up as the name alone did; it reads as the name.

`BrandTest` checks their geometry, gradient and palette, and the lockup against its reference; `CatalogBrand` shows
them as Android draws them.

## Performance

The player's GPU (a Mali-G51) fills a 1080p screen only a few times per frame, so:

- **Read while drawing.** Every animated colour, opacity, scale or position is read in a draw or layer block
  (`drawBehind`, `drawWithCache`, `graphicsLayer { }`, a `ColorProducer`), never in composition; brushes,
  paths and strokes are cached.
- **No layer more.** A plate's light is drawn in its content's clip layer; nothing at rest takes a
  `graphicsLayer` it does not animate; no full-screen layer is added; glows are non-overlapping rings
  (`drawGlow`); a transparent rectangle is not drawn.
- **Two backdrop layers at most.** A backdrop is one cached layer, recorded again only when its picture
  changes or the house lights move; one fade at a time (`FadeBetween`).
- **A wall's field in one fill.** 64 × 36, enlarged, veiled and dithered by one paint (`DiffusePaint`); a wall
  never decodes its picture.
- **Prepare the next screen during the gesture before it.** Settled on a title, home and walls request its
  page's logo (`DetailAhead`); while a library's name lights in home's bar, its wall's first page loads
  (`LibraryAhead`).
- **Light nothing that focus only crosses** (`Scrub`), and nothing moves for ever but the welcome wall.
- **Measure on the player**, compiled as users run it (`cmd package compile -m speed-profile`), with
  `dumpsys gfxinfo <package> framestats` and `setprop debug.hwui.overdraw show`. See CONTRIBUTING.md.

Reference measures on the Zidoo Z1000 Pro (2026-10-20): the `bench` build as `scripts/install.sh` leaves it,
compiled `speed-profile` with the baseline profile, two passes; in brackets v0.1.0 compiled `speed`, three passes.
Each screen from a fresh start, an arrow every 400 ms, then an arrow held 3 s.

| Measure | Tool | Budget | Home | Wall | Title's page | Settings |
|---|---|---|---|---|---|---|
| Janky frames | `gfxinfo framestats` | +0.5 point at most | 0.8–1.5 % (3.0–4.9 %) | 0–0.2 % (0.3 %) | 1.1–1.3 % (1.1–1.3 %) | 19 % (19 %) |
| Frame time, 90th / 99th percentile | `gfxinfo framestats` | 16.7 ms | 24 / 32–34 ms (27–29 / 38–40) | 18–19 / 25–26 ms (22–23 / 27–31) | 18–19 / 32–36 ms (18–19 / 34–40) | 57–61 / 150 ms (61–65 / 150) |
| Overdraw | `debug.hwui.overdraw` | the room 1×, plates 2× | room and backdrop 1×, plates 2×, a card 3× | field 1×, posters 3× | backdrop 1×, plates 2×, the ember button 3× | rows 2×, the pane's pictures 3× |
| GPU memory | `dumpsys gfxinfo` | two backdrop layers | 86 MB | 35 MB | 31 MB | 22 MB |
| Field and veils without bands | `screencap`, runs of one level | 1 to 2 px | — | levels alternate pixel by pixel: dithered, no band | smooth, no band | — |
| Cold start to home's first frame | `am start -W` | 1.5 s | 0.50–0.55 s (0.62–0.69 s) | — | — | — |

Settings' janky frames are its pane, recomposed whole when its entry changes (120 to 150 ms in `draw`), as before
the identity. Home's slowest frames on a row wait on the main thread for a picture (40 to 48 ms); a row's hold
draws at 12 to 15 ms of GPU per frame, as a wall's scroll does. The room is painted twice, by the window and
the screen: the only overdraw left at rest.

## Guard

| Test | Holds |
|---|---|
| `DesignTokenGuardTest` | no size, text size, tracking, font, text style, colour, palette, opacity, shape or gradient in a screen; no ember named in a screen; no arithmetic on tokens; images only through `ArtworkImage`, `PosterThumb` or `RowPreview`; one place lit per screen, and only the places; no Toast; no Material icon, drawn or declared; no shadow or blur |
| `FocusStyleGuardTest` | focus said in one place, drawn by one surface, never by a screen; every surface takes a gesture and what takes focus shows it; a row holds framed cards only; nothing grows with focus and a surface's light takes no layer; OK clicks through the surface only; no tv-material surface, no Compose Material; never `focusRestorer` |
| `MotionGuardTest` | the system's own curves only, drawn in `Motion.kt`; every tween names its easing and every animation its spec; no spring, no cross-fading container |
| `ShapesTest` | radii by hierarchy and in dp, nested corners parallel, a sheet square against the edge; nothing round but dots |
| `ContrastTest`, `ScrimTest` | every pair drawn, on what it sits on; forbidden pairs stay below; veil floors, plate floors and smoothness |
| `AmbientTest`, `DiffuseTest`, `BackdropTest`, `BackdropFadeTest`, `BackdropRelayTest` | a picture's light; the field's cap, ramp and dither; decoded sizes; one fade at a time; the relay across cuts |
| `RowFrameTest`, `CardRaiseTest`, `OkPressTest`, `RecedeTest`, `ScrubTest` | the frame that travels, without recomposing; the focused card on top; OK on its release; siblings stepping back in steps; an arrow held |
| `FocusReachTest`, `HomeLayoutTest`, `DetailLayoutTest`, `WelcomeLayoutTest`, `SettingRowTest` | the frame inside the room kept for it and inside the safe area, a row's glow drawn whole past it, a wall's faded where its grid cuts it; home's bands, a page's first view and the welcome screen; setting rows' heights |
| `TypeScaleTest`, `SpacingGridTest`, `LayoutTokensTest`, `GroundTest`, `TextRoomTest` | type scale, 4 dp grid, screen grid, grounds, text room at every text size |
| `ResourceTokensTest`, `StringsTest`, `BrandTest` | resources in step with tokens; typography of resources, every translation complete; the mark's vectors and the lockup |
| `CatalogCoverageTest` | every public component, shape, `HlIcons` glyph, colour role and type style in the catalogue |
| `DesignDocTest` | every system name this document cites still exists |
| `RunCatchingGuardTest`, `LayersGuardTest` | no `runCatching` around focus requests (`tryRequestFocus`) or in suspending code (`suspendRunCatching`); no layer under the screens depending on the UI |

Deliberate exceptions go in a guard's `allowed` list with their reason; a value needed twice belongs in the
design system.

Remote behaviour is tested as the remote drives it, on Robolectric with the app's theme on the TV canvas
(`ui/testing/Remote.kt`): `MediaRowFocusTest`, `SheetFocusTest`, `SegmentedFocusTest`, `RestoreFocusTest`,
`ScrollAreaTest`, `TitleSwitchTest`, `TextFieldMessageTest`, `SupportRowTest`, `OkPressTest`. A focus rule that
changes comes with its test.

## Catalogue

Debug builds show **Settings > About > Design system**: every token and component as a row of its states
(rest, chosen, lit, loading, failed, empty), focus checked live with the remote. Sections live in `ui/catalog`:

| Section | Shows |
|---|---|
| `CatalogScreen` | the palette and its roles; black levels, then a picture's tinted veils and a wall's diffuse field; every type style; panels and states, lists and settings, sheets, cards without artwork, the fullest action row and track |
| `CatalogContrast` | each ink on what it sits on, over the lightest picture, with its ratio |
| `CatalogFocus` | every shape with its radii; the frame (poster, wide image, portrait, absent card, panel, the main action) and the fill and ring, live |
| `CatalogGestures` | case back, intertitle and counter side by side, then intertitles, error states, counters, case backs, empty frames and poster strips |
| `CatalogIcons` | every glyph on the room and on focus; done and your choice; ember for states only |
| `CatalogControls` | buttons in their states, a place's title and a task's, tiles, controls on a picture's plates, home's bar and the seasons |
| `CatalogForms` | settings on the room and in a sheet, text fields, status panels, panel buttons, dividers, menus, choice sheets and settings, scroll areas |
| `CatalogCards` | a media row whose frame travels, with its new titles; cards on a picture's light; surfaces and images; wide cards, a saga with absent films, people; progress lines, skeletons, artwork choices, results, thumbnails and previews |
| `CatalogReading` | title art, episode headings, meta and title lines, formats, end credits, the billing block, hero info, info blocks, definition lists, the technical sheet, search and its keys, the synopsis, versions |
| `CatalogStress` | the longest labels, home's bar on the brightest image, the largest text size, fallback scripts, device metrics |
| `CatalogBrand` | the icon and banner as a launcher draws them, the launch mark in its circle, Gradation as home's place, the lockup |

## Adding a screen

1. Start from `AmbientBackdrop`, `HlTwoPane` or `HlFormScaffold` and compose existing components.
2. Add a token or component here before using a new value twice.
3. End a vertical list with `contentPadding(bottom = HlFocus.edge)`; if it starts below the top of the screen,
   wrap it in `ListScroll(top = its top padding)`. `HlTwoPane` does both for its content pane.
