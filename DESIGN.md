# Houselights design system

Houselights is named after the lights of a cinema auditorium, which dim before the film starts.
The interface follows that idea. The room is dark and quiet, and the light comes from the films themselves.
Everything lives in `app/src/main/java/app/houselights/ui/design`. **Screens never hard-code a color, a
size or a font**: they use the tokens and components below.

## Principles

1. **One light source.** The room is pure black (`#000000`), like the masking around a screen. Color comes
   from the artwork: the *ambient light* (`LocalAmbient`) is picked from the focused backdrop and spills
   onto the room and around the focused card. Tungsten, the only fixed accent, is used sparingly.
2. **Ten-foot first.** Nothing below 12 sp. Content stays inside the overscan-safe area
   (`HlSpace.safeH` / `safeV`). Every focusable element shows an unmistakable focus state.
3. **Calm motion.** Motion answers the user (focus, opening, playing). There are two exceptions.
   The *house lights* fade to black before playback (`HouseLights`). The *poster wall* drifts slowly
   on the welcome screen.
4. **Words do one job.** Sentence case, no all-caps labels, no decorative punctuation between facts
   (spacing separates them). In French, the interface uses *vous*. The one exception is the billing
   block, set in capitals like the credits at the foot of a poster.

## The screen and the room

Houselights is an auditorium, and this is what tells it apart. Nothing is added for decoration: the
identity comes from treating artwork as the lit screen and the interface as the room around it.

- **House lights.** Playing a title runs one cue (`HouseLightCue`, `DIM`): the room fades first
  (text, buttons, rows: `Modifier.houseLit`), the scrims lift so the picture shows whole, the
  screen's light spills further into the dark room, then black and the player. Coming
  back runs the cue backwards.
- **Billing block.** On a detail page, director and leading cast are set like poster credits:
  read as a sentence, "a film by NAME with NAME, NAME and NAME": names in condensed capitals, the
  small words and commas receding. On one line: it keeps as many leading names as fit, and never
  breaks inside a name.

## Tokens

| Token | File | Content |
|---|---|---|
| `Hl.colors` | `Color.kt` | `background` black, `surface` / `surfaceRaised`, `outline`, `content` (projector white `#F2F0EB`) / `contentSecondary` / `contentTertiary`, `tungsten` `#F2B66D`, `focus` / `onFocus`, `ambientFallback` |
| `Hl.type` | `Type.kt` | Archivo, one family at three widths: **condensed** (`display`, `headline`, `wordmark`), **normal** (`title`, `body`, `bodySmall`, `meta`, `caption`), **wide** (`section`, `label`, `badge`) |
| `HlSpace` | `Dimens.kt` | 4 dp grid: `xxs` 4 … `xxl` 48, plus `safeH` 48 / `safeV` 28 |
| `HlShapes` | `Dimens.kt` | radius follows hierarchy: `artwork` 4 dp, `field` 6, `panel` 10, `control` pill, `avatar` circle |
| `HlSize` | `Dimens.kt` | poster 112 dp, landscape 220, episode 264, avatar 76, icon 20, progress 3, focus border 2 |
| `HlFocus` | `Dimens.kt` | focus scale (artwork 1.10, cards 1.06, controls 1.04), sibling dim 0.38, glow 18 dp |
| `HlMotion` | `Motion.kt` | `FAST` 150, `STANDARD` 250, `SLOW` 600, `DIM` 1000 ms; `SETTLE` 220 ms before a backdrop changes |
| `LocalHouseLights`, `HouseLightCue` | `Auditorium.kt` | how far the house lights are down (read while drawing), and the order of the cue: room, scrims, black |
| `HlIcons` | `Icons.kt` | line icons drawn for the app (folder, server, display, playback, document, storage, edit, poster, backdrop, logo, search, lamp); core Material icons for the rest |
| `HlBringIntoView` | `Scroll.kt` | lists scroll only as much as needed; replaces the TV default that pivots focus to a third of the screen |
| `revealOnFocus()` | `Scroll.kt` | when focus enters a group (row and header, hero and buttons, settings section), the whole group comes into view |
| `keepVerticalFocus()` | `Scroll.kt` | up and down never leave a column (settings panes, forms); only left and right do |

## Components

| Component | Use |
|---|---|
| `Backdrop` (`Hero`, `Full`, `Quiet`) | full-screen artwork faded to black where text sits, plus the ambient light; its scrims lift when the lights go down |
| `BillingBlock` | poster-style credits on detail pages |
| `rememberAmbientColor` | the light a picture spills into the room, animated |
| `PosterCard`, `LandscapeCard`, `PersonCard` | artwork with the shared focus treatment (`ArtworkSurface`) |
| `MediaRow` | horizontal row with header and focus restoration; dims the other cards when one is focused |
| `HeroInfo`, `TitleArt`, `MetaLine`, `Badge` | what a title is: clear logo or condensed title, facts, formats; a `lit` badge marks a state (in use, your choice) |
| `HlButton` (`Primary`, `Secondary`), `HlIconButton` | pill actions; the icon button shows its label only when focused |
| `HlTab`, `HlSegmented` | navigation, sorting and exclusive choices; the tungsten line marks the selection |
| `HlListItem` | a choice in a vertical list, with an optional icon in a lit disc |
| `HlMenuButton`, `MenuSection`, `MenuOption` | icon button opening a sectioned menu: closes on choice (sorting) or stays open (filters) |
| `DefinitionList` | label and value pairs on a quiet panel (settings, NFO) |
| `HlModal` | panel over the dimmed room for details on demand or a short list of actions; focus stays inside (on Close, or on `initialFocus`), Back or Close dismisses |
| `ArtworkChoiceCard`, `SelectedMark` | one artwork to choose from, at the shape it is used in, captioned; the one in use carries the tungsten check |
| `HlResultItem` | a search result: poster, name, year, original title and overview, styled like `HlListItem` |
| `TechSpecs` | spec sheet of a file (video, audio tracks, subtitles by language, container, size, bitrate, duration), shown in an `HlModal` |
| `HlTextField` | TV text field: focusable box first, keyboard on OK; tungsten while typing; always one line of body text tall, empty or not |
| `HlFormScaffold`, `StatusPanel`, `FieldLabel` | setup screens: explanation and live status on the left, form on the right |
| `PosterWall` | tilted, drifting wall of posters (welcome) |
| `HouseLights` | the last step of the cue: black over everything before the player starts |
| `CardSkeleton`, `ProgressLine` | loading placeholders and progress |

## Focus

Focus must read from the couch:

- **Artwork** grows, gets a 2 dp projector-white outline and glows with the ambient light. Its neighbours
  in the same row or grid dim.
- **Controls** turn projector white with black content.
- **Text fields** get a white outline when focused and a tungsten outline while typing.

## Adding a screen

Start from `Backdrop` or `HlFormScaffold`, compose existing components, and add a token or component
here before using a new value twice.
