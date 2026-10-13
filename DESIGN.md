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
   (spacing separates them). In French, the interface uses *vous*.

## Tokens

| Token | File | Content |
|---|---|---|
| `Hl.colors` | `Color.kt` | `background` black, `surface` / `surfaceRaised`, `outline`, `content` (projector white `#F2F0EB`) / `contentSecondary` / `contentTertiary`, `tungsten` `#F2B66D`, `focus` / `onFocus`, `ambientFallback` |
| `Hl.type` | `Type.kt` | Archivo, one family at three widths: **condensed** (`display`, `headline`, `wordmark`), **normal** (`title`, `body`, `bodySmall`, `meta`, `caption`), **wide** (`section`, `label`, `badge`) |
| `HlSpace` | `Dimens.kt` | 4 dp grid: `xxs` 4 … `xxl` 48, plus `safeH` 48 / `safeV` 28 |
| `HlShapes` | `Dimens.kt` | radius follows hierarchy: `artwork` 4 dp, `field` 6, `panel` 10, `control` pill, `avatar` circle |
| `HlSize` | `Dimens.kt` | poster 112 dp, landscape 220, episode 264, avatar 76, icon 20, progress 3, focus border 2 |
| `HlFocus` | `Dimens.kt` | focus scale (artwork 1.10, cards 1.06, controls 1.04), sibling dim 0.38, glow 18 dp |
| `HlMotion` | `Motion.kt` | `FAST` 150, `STANDARD` 250, `SLOW` 600, `DIM` 700 ms; `SETTLE` 220 ms before a backdrop changes |
| `HlIcons` | `Icons.kt` | line icons drawn for the app (folder, server, display, playback, lamp); core Material icons for the rest |
| `HlBringIntoView` | `Scroll.kt` | lists scroll only as much as needed; replaces the TV default that pivots focus to a third of the screen |

## Components

| Component | Use |
|---|---|
| `Backdrop` (`Hero`, `Full`, `Quiet`) | full-screen artwork faded to black where text sits, plus the ambient light |
| `rememberAmbientColor` | the light a picture spills into the room, animated |
| `PosterCard`, `LandscapeCard`, `PersonCard` | artwork with the shared focus treatment (`ArtworkSurface`) |
| `MediaRow` | horizontal row with header and focus restoration; dims the other cards when one is focused |
| `HeroInfo`, `TitleArt`, `MetaLine`, `Badge` | what a title is: clear logo or condensed title, facts, formats |
| `HlButton` (`Primary`, `Secondary`), `HlIconButton` | pill actions; the icon button shows its label only when focused |
| `HlTab`, `HlSegmented` | navigation, sorting and exclusive choices; the tungsten line marks the selection |
| `HlListItem` | a choice in a vertical list, with an optional icon in a lit disc |
| `HlTextField` | TV text field: focusable box first, keyboard on OK; tungsten while typing |
| `HlFormScaffold`, `StatusPanel`, `FieldLabel` | setup screens: explanation and live status on the left, form on the right |
| `PosterWall` | tilted, drifting wall of posters (welcome) |
| `HouseLights` | fade to black before the player starts and back after |
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
