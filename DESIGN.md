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
2. **Focus is projector white, in two gestures.** A picture is lifted by its frame and its glow, a panel
   button and the main action by their frame, a plate fills; nothing grows, but a saga's panel, which opens with focus,
   its picture cut wider and never scaled. In a row, one frame travels from card to card (see *Focus*). Ember never
   marks focus.
3. **Ten-foot first.** The core is read at 15 sp, what surrounds it at 13, 12 only for marks in their box (see
   *Type*); text reaches 4.5:1 and marks 3:1 on what they sit on (see *Contrast*). Content stays inside the
   overscan-safe area (`HlSpace.safeH`, `safeV`).
4. **One canvas.** Screens are laid out on 960 × 540 dp (`HlLayout.CANVAS_WIDTH`). `HouselightsTheme` derives
   the density from the screen's real width, not the declared one (the Zidoo declares 240 dpi), and its text size
   from the system's times « Taille du texte » (`textScaleOf`, see *Type*). Sheets and menus, in windows of their own
   that would bring the declared density, are handed the canvas's, text size included (`WindowDensityTest`); the
   pictures loaded ahead are asked at it too (`PicturesAhead`).
5. **Calm motion.** Motion answers the user, on the system's own curves. Two moments are orchestrated: the
   house lights (at launch and before playback) and the welcome wall's drift. Screens cut, never cross-fade:
   the backdrop is handed across the cut (`BackdropRelay`) and the words change at once.
6. **Words do one job.** Sentence case, no all-caps labels, no punctuation between facts. French uses *vous*.
   Capitals are reserved for the billing block and what extends it (a row billing a director's films).
7. **A title is always shown.** Moving focus up to the buttons keeps the last title, and down into a saga's panel's
   actions its panel open; before any focus, the first one.
8. **Depth is light, not glass.** No frosted glass, blur, parallax, reflection or drop shadow.

## Identity

The app is recognised by its type, its vocabulary and three moments, not by decoration. One family,
**Archivo**, in three widths used as voices:

| Voice | Width | Says | Styles |
|---|---|---|---|
| Poster | condensed (70) | where you are and what things are called: titles, the words of a track (`place`), rows (`rowTitle`), names under cards (`cardName`), what a panel is about, large figures, the billing block | `display`, `headline`, `subject`, `place`, `placeCurrent`, `rowTitle`, `cardName`, `cardTitle`, `titleSignature`, `figureSignature`, `billing`, `billingJoin`, `codeSignature` |
| Reading | normal (100) | everything read or chosen: overviews, running text, taglines, names to choose, button words, values | `lead`, `body`, `entry`, `meta`, `group` |
| Technical | expanded (125), tabular figures | formats as on a case's back (`jaquette`), specifications, printed marks | `spec`, `jaquette`, `badge` |

- **No fourth voice.** An intermediate width reads as a mistake.
- **Three weights, one meaning each:** Regular reads, Medium names, SemiBold announces (and marks the place you
  are in). Bold belongs to the signatures (`titleSignature`, `figureSignature`). Each width declares only the
  weights it uses, so no bold is synthesised (`TypeScaleTest`).
- **A second face only when the text demands it:** raw text read as written (the NFO) uses the system
  monospace (`code`). Scripts Archivo lacks fall back to the system font.
- **Blu-ray case vocabulary** in the technical voice: Ultra HD, Full HD, Dolby Vision, HDR10, TrueHD Atmos; measures
  as a case prints them: ratios `2,39:1`, frame sizes `3840 × 1608`, channels `7.1`.

Three moments carry the identity:

- **Launch.** A cold start shows Gradation alone on black, in its ember light (`splash_mark`, drawn by Android
  at the device's density): no progress bar, no text. Home is composed underneath (its rows and its doors read,
  each picture preloaded with `PicturesAhead` and `AwaitAhead` as soon as what shows it is read: what the rows show
  at once, the case proposed once drawn, the doors' posters once read, their counts cut ahead into the app's
  `CutPosterCache`). The launch screen leaves after
  home's first complete frame (`drawnWhole`, `LocalLaunchScreen`), at most 1.5 s after process start or, when the
  first window opens later than that (a process a receiver started after an update, a slow cold start), 1.5 s after
  that window, and at once when the app starts elsewhere. `LaunchCue` then plays over `SLOW`: the bars go out widest first, each
  over `FAST`, the last as the black starts to lift off home over `STANDARD`, every step easing out
  (`lightsOut`); from Android 12 the system draws the mark, which goes out with the last bar. What was
  prepared underneath appears without a fade. A warm start shows at once.
- **House lights.** Playing a title runs `HouseLightCue` over `DIM`: the room dims (`Modifier.houseLit`), the
  veils lift, then black (`HouseLights`) and the player. The player starts once the room is dark, not after a
  fixed delay, the pictures held in memory let go first (Coil's, and the doors' cut posters but their colours,
  `CutPosterCache.clear`); returning runs the cue backwards. During the cue, OK and Menu are ignored and Back cancels
  (`UnseenKeysTest`).
- **The credits.** Director and lead cast read as one sentence, "un film de NAME avec NAME, NAME et NAME"
  (`BillingBlock`): condensed capitals, small words receding, on one line, keeping as many whole names as
  fit.

The **brand** is Gradation, in the ember light, and the **wordmark**, projector white (see *Brand*). On screen, the
**lockup** (`Lockup`), Gradation in its light beside the name, drawn, never typeset, flat and without glow, opens
the welcome screen (`HlSize.wordmarkHero`, 26) and About (`HlSize.wordmark`, 30), and heads home
(`HomeGridDefaults.lockup`, 11.5).

## Gestures: small surfaces

Small surfaces (a settings pane, a message, a running scan) borrow exactly one gesture from film materials.
`CatalogGestures` shows them side by side.

| Gesture | For | Rules |
|---|---|---|
| **Case back** | what a pane states | A `FactPlate` of `PlateFact`s: each label in secondary `meta` over its value, words in `entry`, a format or a figure in `spec`, its measure after it in `meta`, a state by its dot. Several of a kind in a `SpecTable` on a plate, the one in use by its white dot. Measures (sizes, counts, rates, durations) are tabular. Only what acts is a row. Unknown values are omitted, never shown as zero. No charts. |
| **Intertitle** | every message | A short rule, one line in `subject`, one line of secondary `body` (`readableNarrow`), at most one action and a quieter one. An ember rule means attention. It replaces the content it concerns, aligned on the screen margin: never centred, never a snackbar, Toast or dialog. `HlEmptyState` on a screen, `SheetConfirm` in a sheet. |
| **Counter** | progress | The ember line with its count in tabular figures ("3 / 9", "1 204 / 3 871": `scanCount`, its figures' thousands grouped as every count's, `formatCount`) and the current file, quiet, in `meta` on its line or under a label (`ScanProgress`); before the files are counted, a sliver and no count. On a focused plate, a raspberry line on the plate itself, the part to come pale (`ProgressLine`), so a count at its end reads as a whole gauge's. On a row (`HlSettingRow`'s `progress`), the count follows its line: an update downloading, "2,1 Mo / 6,4 Mo", each figure with its unit. Durations read "2 h 02", time left "Il reste 20 min"; a timecode only names a frame. |

The library in numbers follows the case back: its figures on a plate (`FactPlate`), each in `spec` over what it
counts in secondary `meta`, then a group of rows per kind, each row opening what it counts.

## Secondary screens

What is not the picture (settings, sheets, forms, search) keeps one grammar, read from three metres:

1. **Scale.** A pane's title in `subject` on its screen title's baseline (`PaneHead`); a row `HlSize.row` (52) tall at least, its name in
   `entry`, what it does in secondary `meta` on one line beside its value (`PaneRowLineTest`); running text in `body` (15 on
   24); a group's title in `group`. Nothing under 13 but a mark in its box (`badge`): notes (`AttentionNote`), a scan's file
   and a state's reason are `meta`, formats `jaquette`.
2. **Air.** Groups `HlSpace.l` apart, never a rule: no `HlDivider` in a pane or a sheet. Words are spaced from their
   baselines, as the board measures them, not from their boxes (`SheetRhythmTest`).
3. **Density.** Five or six things in view; the rest scrolls.
4. **Plates.** A group is one plate (`HlPlate`, `HlShapes.panel`, edged by `plateEdge`), its rows `HlInset.group`
   inside it, one after the other: on a field of posters its dark at `PLATE` (`plate(light)`), in a sheet the film's
   `glassPlate`, glass on the bare room.
5. **Light.** Settings and their forms lie on the library's posters (`PosterField`, `roomPosters`), read once a visit:
   the folders' latest, a Jellyfin library's as home showed them, before any folder the welcome wall's; capped as a
   wall's field, its light the screen's; a pane fades into it short of black (`VEIL`). Search lies in the light of the
   result in focus, as a wall (`Diffuse`); a sheet's glass takes its film's dark (`sheetGlass`), its plates its light.
6. **Values.** Formats and figures in the technical voice (`jaquette` in a row, `spec` when stated); what a pane
   states is a case's back (`FactPlate`), never a row; a state is a white dot (`HlStatus`): whole when on, a ring
   when off, quiet while pending.
7. **Ember** keeps its six meanings: where you are is the screen's title, and in the settings the open place's icon;
   its word stays white.
8. **Focus.** Pictures are framed (`Showcase`, `GalleryTile`), plates fill white, all on `HlFocusSurface`; what is
   read is ringed (`ReadingPlate`, `HlScrollArea`).
9. **Places.** The settings' places are words alone, `HlSize.navRow` tall, seven inside the safe area. Focus lights
   a place at once; its pane opens once focus settles there (`HlMotion.SETTLE`) or on OK (`SettingsPlaces`).
10. **Sheets** say what they do in `subject` and what about on the line under it, with no hint at their foot;
    their glass and plates keep their film's light; an NFO's is wide (`HlSideSheetDefaults.wideWidth`) to hold
    its XML's lines, a synopsis keeps the standard width. Under a sheet, the screen shows no focus.

| Component | Measures |
|---|---|
| `PosterField` | up to `FIELD_POSTERS` posters' samples side by side, each cropped to fill its slot (`posterStrip`), made a wall's field (`diffuseField`) and drawn as one (`DiffusePaint`) under its veils; its light read from them (`lightOf`) and provided to what lies on it; the last two kept and shown at once, a new one coming in over `TINT` over the one showing, which stays whole |
| `HlNavItem` | `HlSize.navRow`; icon `HlSize.icon`, grey (`contentTertiary`), the open place's ember; its name in `entry`, `contentSecondary`, white when open; its plate hangs `HlSpace.s` past the column (`hang`) |
| `HlSettingGroup`, `HlSettingRow` | title from its baseline, `HlSpace.s` under the block above and `HlSpace.s` over the plate (`GroupTitle`); a row `HlShapes.row` inside it, `HlSpace.m` from its sides, its value in `meta` or `jaquette` (`valueStyle`); a value of parts (`valueBreaks`) takes a line each, ranged right, where on one line it would cut the name or the description |
| `FactPlate`, `PlateFact` | `HlInset.card` across, `HlInset.plate` down; facts `HlSpace.xxl` apart; label, `HlSpace.xs`, value; a note `HlSpace.l` under them |
| `Showcase` | `HlSize.showcase` wide, four posters full height (`SHOWCASE_SLOTS`) melting into glass in their light (`tintedGlass`, `SHOWCASE_FOOT`); the name in `subject` at `ShowcaseDefaults.nameTop`, the facts under it; framed with focus, no glow |
| `GalleryTile`, `Gallery` | an image at its shape of use: a poster `HlLayout.posterChoice` wide, a backdrop and a logo's box (`LOGO_BOX_RATIO`) `HlSize.galleryWide`; its name in `cardName` under it; tiles `HlSpace.m` apart, the others stepping back while one has focus |
| `ReadingPlate` | a group's plate read with the remote (a technical sheet's block): `HlInset.reading` across, `HlSpace.m` over its name and `HlInset.plate` at its foot, its hairline focus's ring; OK does nothing; its name `HlSpace.m` over its first line, marks `HlSpace.xl` apart, their measures a line each, tracks `HlSpace.m` apart |
| `HlSideSheet` | its glass in the film's dark (`sheetGlass`), read while drawing; `subject` title, its baseline `HlSpace.xl` under the safe area's top, the facts' first in white, then a state by its dot (`stateText`), the `places` it shows at the end of the title's line, a description `HlSpace.l` under them; `HlSpace.fact`, then plates `HlSpace.m` apart, or without `scrolls` one that takes the height left, scrolling under nothing of the head; `wide`: `HlSideSheetDefaults.wideWidth`, inset `safeH` on both sides |
| `HlFormScaffold` | its column `TwoPaneDefaults.sideWide`: title, what it does in `body` from its baselines, `HlSpace.xl` under the title's foot and over a `StatusPanel`; the form from the safe area's top, `HlSpace.xxl` beside it, its parts `HlSpace.l` apart, a choice's track and the actions `HlSpace.xs` further; with `room`, on its posters and `OnArtwork`, the form ending at the safe area's foot as it starts at its top and fading into the field where it scrolls (`fadeIntoField`). A form of many rows (« Ajouter un dossier »'s levels, by hundreds) is a lazy list held by its `list`, each item setting its gap, a long group's plate drawn a row at a time (`PlateSlice`), its corners on its first and last rows, its edge outside only |
| `StatusPanel` | an `HlPlate`, `HlInset.card` across, `HlInset.reading` over its state and `HlInset.plate` at its foot: the state, `HlSpace.fact`, a label in secondary `meta` (`HlSpace.xs`) or a `name` in `entry` (`HlSpace.xxs`) over the value in `spec`, its detail, `HlSpace.m`, its protocol printed and its kind in white `HlSpace.s` apart. One mark a failure: its message's, beside its cause, the panel's result or the field's own message, the state then saying it by the ring (`Off`) and its words, never `Attention` |
| `HlTextField` | its name in white `group`, a step under what is typed (an `optional` one adds its remark in grey `meta`), `HlSpace.s`, the field `HlSize.field` tall, its text in `body`, its example in tertiary `body`; two side by side `HlSpace.m` apart |
| `AddFolderForm`, `NfsIdentity` | the form of a folder edited (« Modifier le dossier »; a folder is added through « Ajouter un dossier »'s steps, and a folder of the player's disks edited through them): its fields in the NAS's words (« Adresse du NAS », « Chemin de montage » over NFS or « Dossier partagé » over SMB beside « Sous-dossier (facultatif) », SMB's account), then its actions, Enregistrer and Tester, both testing first; for NFS only, one row under them on its plate, no title: « Compte NFS », "Celui que voit le NAS, root par défaut", its value root in `meta` or "UID 1026 · GID 100" in `jaquette`, the user over the group where one line would cut the row's words (the largest text size, long numbers). OK unfolds what it changes ("Laissez vide pour root…") over its two fields side by side, empty for root, whole numbers only, both or neither (the test asks for the other); OK folds them, what they hold kept. The panel adds it to its facts when it is not root, its path as the player will open it, "…" for a part still to type. A failure is said once, under the field that changes it, which takes focus, the form scrolling to show it whole (the NAS silent under its address, what the path lacks or a folder missing under its last field filled, a folder holding one of the library's under « Sous-dossier », an SMB account under its user, a password under it, the one kept that the Keystore did not open for now too, said kept and never refused, the panel « Connexion non testée », the NAS not asked, Tester or Enregistrer pressed again reading it again); one no field fixes (the NAS refusing this player, by its address, or the NFS account, whose row unfolds and takes focus; a folder not saved) is the panel's, focus left on the button pressed; a mount refused for an export the NAS does not list is said as the export it lacks. What the NAS proposes for a path it does not have, naming what it changes (« Utiliser /volume1/media » for an export typed short, « Utiliser « Films » » for a shared folder or a folder within one letter of one it has), lies on a plate under the actions and takes focus; OK writes it in the path's fields and tests again. Over NFS, an export the NAS lacks with nothing near it, once the test had the NAS's list (MOUNT's EXPORT), gets its plate too (#59): a NAS sharing one folder proposes it, « Utiliser /volume1/video », « Le seul dossier partagé du NAS »; two or more, « Choisir un dossier partagé », « Parmi les 3 que partage le NAS » (the list's count, « Non autorisé » included), in a proposal's place and with its focus, never with one, no icon, as it has none; without the list, or with none listed, the message alone, focus on its field, nothing opening to fail. OK on it opens the guided flow's step 4 over the form (`FolderFlow` with a `SharePick`, `Route.AddFolder`'s `pick`), the app's one list of shared folders; the one chosen there comes back in « Chemin de montage », the subfolder kept, tested at once from Tester, which takes focus, nothing saved before Enregistrer; Back returns to the form as it was, its message and its line, focus on the line; a process stopped meanwhile finds the step, then the form as it was, from their saved state. A path in its sentences is never cut (`wholePath`), nor the Synology path its message about an export writes itself, a word joiner after each slash. A test says what it found, "412 dossiers et 3 vidéos", the first level's folders and videos apart, and once the NAS answered, the path's fields show the folder as it was read (« /volume1/video/Films » in « Sous-dossier » under « /volume1/video » reads « Films »); a folder holding neither is said so and never saved; Tester says what Enregistrer would refuse. Under the panel, what the NAS must allow, this player's address in it, only while the panel has no result and only whole at the viewer's text size, else left out. « Terminé » on the last field (SMB's password, NFS's « Sous-dossier » or its group when « Compte NFS » is open) does what the first button does, the others go to the next; Back first brings focus to the first control at the form's top, then leaves, nothing kept. A folder's sheet says where it is under its name: its NAS's address, then its path as the panel composes it, apart (`FACT_GAP`); a folder of the player's disks its disk as « Ajouter un dossier » names it (« Disque interne », a USB disk's name), no protocol printed, its « Modifier le dossier » saying « Son dossier sur le disque » (first once a scan no longer finds the folder) and opening the flow's steps on its disk, the form being a NAS's. An edited folder that cannot be read says "Ce dossier n’a pas pu être relu" in the form's place, the storage's line under it, with Réessayer (`HlEmptyState`): nothing to save, or its password would be erased |
| `SearchQuery`, `LetterGrid` | the query `HlSize.field` tall, its icon `HlSize.icon`, the words in `lead`, the hint `HlSpace.s` under it in secondary `meta`; the keys `HlSpace.m` under it, `tileCompact` square, `HlSpace.xs` apart, a letter in `lead`, Erase and Space `HlSpace.s` aside in `entry`; all in the dark of the light the page lies on, no edge |
| `ResultsHeading`, `ResultName` | a row's title in `rowTitle`, its count in secondary `meta` `HlSpace.s` after it; under it, the result lending the page its light in `cardName`, its year in `meta`, the line kept; rows `HlSpace.xl` apart |
| `PaneHead`, `PaneLockup` | the title's baseline on `ScreenTitle`'s, read from the headline at the viewer's text size; an aside `HlSpace.fact` after it, a state's dot before its words; a sentence's baseline `HlSpace.xl` under the title's, its box `HlSpace.xxs` under it, a text to read `HlSpace.xxl` under it with `HlSpace.m` kept below; the head ends on the title's baseline, the first block `HlSpace.l` under it. About's lockup at `HlSize.wordmark`, its name's foot on that baseline, its box ending under the mark |
| `HlGroup` | a group's title as `HlSettingGroup` sets it, over what is not rows: a `FactPlate`, a `DefinitionPlate`, a plate of modes |
| `ShowcaseRow` | showcases `HlSpace.m` apart in a row running on to the screen's end (`bleed`), the next one cut by it; a focused one kept inside the safe area with its frame (`safeH` and `HlFocus.reach` at the end), clear of the row's start; while its showcases are still to read, a pane keeps their room (`ShowcaseSpace`), so what is under them never moves as they come |
| `JaquetteLine` | pairs printed on one line (About's version and licence): labels in secondary `meta`, values in `jaquette`, on a `control` plate of the light |
| `DefinitionPlate`, `PlateDefinition` | definitions `HlSpace.m` apart, labels in secondary `meta` in their column (`PaneDefaults.plateLabel`), a value on their baseline or a logo level with their top, a note under it |
| `MeterFact` | a meter a pane states is `HlSize.meterLarge` across its plate, its scale beside the total; each part's figure in `spec` under its name and its ink's square (`HlSize.swatch`), an empty part's square its ring and its figure a word |
| `DeviceCodePlate` | a code to type elsewhere (Trakt's), `DeviceCodeDefaults.inset` in: instructions, the code in `codeSignature` by groups of four, projector white, its time left; the `compact` `QrCode` at the plate's end. The code keeps its own width: beside the QR code where it fits, under it where it does not, never cut (`DeviceCodeTest`). It holds focus without showing it, so Back cancels in the pane; it takes focus only from the row that asked for it |

**Ajouter un dossier** (`AddFolderFlow`, `FolderFlow`) is a guided flow in a `HlFormScaffold` on the room's posters: the
task's title at every step, the step's sentence, then the `StatusPanel` filling in as choices are made (the NAS by its
announced name or « Adresse » over its address, the path chosen so far, its protocol printed, the SMB account or the NFS
user and group), nothing under it. Each step is a list of `HlSettingRow`s in groups, the rows that open a typing step
last, on their plate, with `HlIcons.Edit`:

1. the NAS: « Déjà utilisés » first, its protocol kept and asked no more; « NAS trouvés sur le réseau » added as they
   answer, under the rows there, its first row holding the first focus on a first folder while it searches, the first NAS
   found taking its place, as each NAS found takes the search row's place, the search row under it, so none arrives
   above focus (focus on the search row as it goes goes to the NAS above it); « Sur ce lecteur », « Disque interne » (« Le stockage de ce lecteur ») then each USB disk by
   its name (« Disque USB »), straight to step 5 at the disk's root once Android lets the app read it (its window, else
   its settings, the row « Non autorisé » and the panel saying what to allow meanwhile), the panel « Sur ce lecteur »
   over the disk's name; « Saisir une adresse » last;
2. the protocol, for a NAS never used that answers both alone, SMB focused;
3. the SMB account, skipped when a folder of the NAS has one, its password reused, never shown;
4. the shared folders, untitled, an export that leaves this player out marked « Non autorisé »; « Saisir un chemin »,
   then « Changer de compte » or « Compte NFS »;
5. the folder, one level at a time: « Choisir ce dossier » first, saying what the level holds (« 38 dossiers et 2
   vidéos ») or why it cannot be chosen, then its subfolders (`HlIcons.Folder`), each saying how it stands to the
   library before anything is chosen (`standing`), focus at a shared folder's root on the one named for the kind the
   library lacks, deeper on « Choisir ce dossier »;
6. « Que contient « Films2 » ? »: Films or Séries, the kind its name says focused; OK adds.

Listing is the test: OK on a row says « Connexion… » or « Ouverture… » in its value's place, and the next step is drawn
once its list has come, so focus is placed once. A failure is the row's two words by the ring and the sentence in the
panel, its mark the only one, the panel « Connexion réussie » when the NAS answered and refused; a typing step's is
under its field, which takes focus; the account kept whose password the Keystore did not open for now opens the account
step, its user filled, saying under the password that it is kept, never that the NAS refused it, which was not asked
(`FlowMessage.PasswordUnread`), as a stopped process finds it again past that step, Back then OK on the NAS or on SMB
reading it again and trying it; and what the NAS proposes (« Utiliser
/volume1/media ») lies on a plate under the button, focused. A path typed starts over SMB with its share, listed or not
(a hidden one, "video$", never is); over NFS with the longest export listed, else the longest start that mounts. Back
from « Changer de compte » gives back the account the shares were listed with; from « Compte NFS », a user without its
group (or the reverse) gives back the pair it was opened with. An NFS read refused names the user presented: root, or « Compte NFS »'s. Back goes up a level, then a step, focus on the row chosen there, first to a scrolled list's start
(`BackToStart`); from the first step it leaves. What was chosen and typed, the password aside, survives a stopped
process. A first folder leads home; another to Réglages › Bibliothèque, on its showcase while it scans.

**Modifier le dossier** on a folder of the player's disks is the same flow, so titled, its sentence the same at every
step and saying what stays (« Choisissez le dossier où sont désormais les vidéos de « Films » : ses titres, vos
visionnages et vos choix sont conservés. »), the panel adding the folder's kind. It opens at step 5 on the folder's disk
where the folder is, « Choisir ce dossier » focused, else at the nearest folder above it still there, focused as a level
is; the library's other folders are no choice, the folder's own place is. « Choisir ce dossier » saves it at once under
its id (« Enregistrement… »), its titles keeping what was done with them, then scans: no step 6, as its kind does not
change, a choice of one being none. Back goes up a level at a time, then from the disk's root to the folder's sheet, open
again on « Modifier le dossier »; saved, Réglages › Bibliothèque on its showcase. Its disk unplugged, step 1 holds that
disk's row alone, « Ne répond pas », the panel saying to plug it back in, by its name, else « Le disque USB », as Android
names no disk it does not have; OK opens it once it is; a disk unplugged while it is browsed is said so on the folder's
row. A folder the storage does not give says "Ce dossier n’a pas pu être relu" in the rows' place, as the form does, with
Réessayer focused; one removed meanwhile « Dossier introuvable », Back the way out (`HlEmptyState`), no sheet opening
again for it.

**Choisir un dossier partagé**, on the form of « Modifier le dossier » (`AddFolderForm`), opens step 4 alone over the
form (`FolderFlow` with a `SharePick`), titled « Modifier le dossier », its sentence the step's: the folders the form's
test just read, their clients judging « Non autorisé », shown at once and focus placed once, the NAS asked nothing more
but its name, as it announces itself; the shared folders alone, without « Saisir un chemin » or « Compte NFS », which
the form has. The panel says « En ligne », the NAS having given its list as its mount failed, then its name and address,
NFS printed, the folder's kind and its NFS account when it is not root; the library's other folders on that NAS stand as
in the flow, the folder edited apart. Focus goes to the shared folder the folder had when last saved, if listed, else to
the first not in the library whole. OK on one, « Non autorisé » too (the form's test says what to allow), chooses it for
the form, once, opening nothing (`PICKED_SHARE`); Back returns to the form, nothing chosen; a process stopped on the step
finds it again (`FolderPickTest`).

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
| `contentDecorative` | `N28` | `#45454C` | never text: the rule of an empty frame, skeletons, a divider (`HlDivider`) |
| `ember` | `Ember` | `#FF7A4D` | states, solid, where a gradient has no room (a dot, a check, a word) |
| `emberFrom`, `emberTo` | `EmberStart`, `EmberEnd` | `#FF9F1C` → `#FF2E63` | the ember light (`emberLight()`): progress lines, an attention rule, a field being typed in, a lit edge, a place's name, the action to play (Lire, Reprendre); the mark's bars |
| `onEmber` | `N0` | `#000000` | ink on the ember light (the action to play), 5.8:1 at its raspberry end; never white |
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

**Plates on glass** keep the film's light: a sheet's plate is `glassPlate(light)`, the glass a step up
(`GLASS_STEP`) and the film's glow at `GLASS_GLOW`, read while drawing, its quietest text at 4.5:1 under the lightest
glow; a showcase's glass takes its posters' glow (`tintedGlass`), a sheet's glass its film's dark (`sheetGlass`:
`deep()` at `GLASS_DEEP` over glass, so a grey picture leaves it neutral).

**Opacity.** `HlAlpha` names every opacity:

| Family | Tokens |
|---|---|
| Fills (`content` over its ground) | `FILL_FAINT` 6 % (wells: a meter's track, a disabled control, an absent film's card), `FILL` 9 % (a thing on its ground: a placeholder, a quiet panel, a control on glass), `FILL_STRONG` 20 % (a progress line's track beside text) |
| Veils (`background` over what is under it) | `VEIL_SIDE` 60 % (the room beside a side sheet), `VEIL` 80 % (card marks and their progress track on artwork; the least a receding backdrop takes) |
| Plates (a picture's own dark over it) | `PLATE` 74 %, raised over a light picture until its quietest text keeps 4.5:1, up to `PLATE_MAX` 92 %; set off by a `PLATE_EDGE` hairline (`plateEdge`, `content` at 11 %). On glass: `GLASS_STEP` 3.5 % of `content`, then `GLASS_GLOW` 5 % of the film's glow (`glassPlate`); a sheet's glass `GLASS_DEEP` 50 % of the film's dark (`sheetGlass`) |
| Edges | `EDGE` (`outline` at 60 %, `HlColors.edge`), `EDGE_ARTWORK` (`content` at 12 %, around a picture at rest), `FIGURE_EDGE` (`content` at 30 %, around a digit cut in a poster) |
| Home's doors | `DOOR_TINT` 55 % (a library door's tint of its posters at rest, whole with focus) |

Check fills on a TV with the catalogue's black-level chart (`BLACK_LEVELS`).

**Ember is a closed list.** It says six things:

- *In use*: a title's progress, a scan, a field being typed in.
- *Your choice*: the current choice's check, a chosen value or match, an active filter, a favourite.
- *Needs attention*: an error with its mark, the action a question asks about.
- *New*: the dot of a title new to the library, a row's count of new titles.
- *Where you are*: a place's title (never a task's), the open section's icon, the current season.
- *The action to play*: Lire or Reprendre, the one button filled with the ember light; any other is a plate.

Gradation at launch is the only exception. What is done or simply true is projector white (a watched title, a
file's default track, a state's dot, `HlStatus`). Ember never marks focus, a fact (a score is grey) or a data series. It never comes alone:
every ember state has a second sign (shape, mark or word); attention has its own circled exclamation. Screens
never name ember: they state the state and the component lights it (`HlStatus`, `AttentionNote`, `lit`,
`valueLit`, `detailLit`).

**Your choice, one way.** One glyph, `HlIcons.Check`: white means done, ember means your choice; a toggle that
is done fills it (`CheckFilled`). Over artwork both sit on the same dark plate (`SelectedMark`). On a focused
plate the ember check stays lit, raspberry (`emberOnLight`). In words, a choice is said beside its check
("Votre choix") or as a state, its dot white ("Actuel", `HlStatus`); how something was done is a plain `meta`
word ("Automatique"). A `Badge` is a printed mark (an age rating, a protocol), never a state; formats are
words (`FormatLines`).

### Contrast

Text reaches 4.5:1 and icons and marks 3:1 on the surface they actually sit on, each translucent layer composed
over what is under it, over the lightest picture where it lies on one. `ContrastTest` lists every pair the
system draws and the catalogue's contrast board shows them; add a pair there before using it.

- Control states are named colours, never an opacity on the current one: the line under a name is
  `contentSecondary` at rest, `ember` when lit, `contentTertiary` disabled, `onFocusSecondary` with focus
  (`FocusStyle.quietInk`).
- Forbidden: white on the ember light (the action to play's ink is black); raspberry words on white, and a raspberry
  line on a track there (2.1:1: on a focused plate a progress line lies on the plate);
  `contentTertiary` on `FILL_STRONG` (3.9:1); grey words on a plate on the bare picture below its floor; ember
  words on a plate over the bare picture, where it is a mark only (its raspberry end 4.3:1 even at
  `PLATE_MAX`); `contentTertiary` on a wall's field out of its title's band (a place's icon is a mark there);
  raspberry words on a sheet's plate.
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
| `Hero` | « À regarder », a person's page with few titles | whole | `LEFT_HOME` 0.95 → 0.48 | black from 40 % to 77 % of the height |
| `Full` | a title's page | whole | `LEFT_DETAIL` 0.96 → 0.67 | black from 45 % to 96 % |
| `Quiet` | a person's titles, the poster editor | dimmed to `QUIET_PICTURE` 0.28, files of 1280 px at most | `LEFT_QUIET` 0.6 → 0.25 | what its quietest text needs, short of black |
| `Diffuse` | a library wall, search | its diffuse field | none | black, 0.5 at the foot |

- **Home's case melts into its dark** from a third of its height, whole under its words (`CASE_FOOT`, `ambientDeep`);
  a door's still darkens under its logo, black to 0.55 at its foot (`STILL_FOOT`). A library door's tint is its
  posters' light lowered (`DOOR_TINT_LIGHT`) and coloured (`DOOR_TINT_SATURATION`); the search door's wall is out
  (`WALL_THUMB_LIGHT`, `WALL_THUMB_SATURATION`), melting into the door's glass towards its band (`WALL_FOOT`, 0.65 at
  its foot), a saga behind another in its shade (`SAGA_BEHIND`).
- **A saga's panel melts into its dark** as the case does (see *Layout*, the saga's page). Closed, from 72 % of its height
  (`VOLET_FOOT`, 0.55 at 84 %), lighter than the case so its poster's own title still shows; then the year's band,
  anchored to the year rather than to a share of the height: `VOLET_YEAR` 0.9 from `HlSpace.xs` over its top, whole
  (`VOLET_YEAR_FLOOR`) from its top to the foot, every year as high, so the bands make one line along the foot, the
  spine's included. Its top is the hero's top veil (`TOP`) raised to the floor its number needs over its band
  (`ArtworkSample.topFloor`, across the share of the picture it shows), melting back onto it over `VoletDefaults.melt`
  under the band, never on an edge. Open, its words melt on the case's curve
  (`CASE_FOOT`) anchored to them: clear `VoletDefaults.melt` (64) over their top, whole as far under it. A film the
  library lacks or not out yet keeps its picture without colour (`ABSENT_SATURATION` 0), its light lowered
  (`ABSENT_BRIGHTNESS` 0.45): one cached filter a step back, the siblings' step multiplied in.
- **A showcase's posters melt into its glass** from a third of their height (`SHOWCASE_FOOT`), deep under its name, in
  the glass's colour read while drawing (`LocalPlate`), its first poster's tone taken once, not animated. The melt is
  drawn over that band only, its tint made once per colour, the last few kept; no plate is drawn under the posters.
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
- **The settings' field** (`PosterField`) is a wall's, made from a library's posters side by side, each cropped to
  fill its slot: the same cap, veils and single fill, no side veil (the cap keeps the places' words at 4.6:1). The last
  fields made show at once; a new one comes in over the one showing, which stays whole: the room never dips.
- **One fade at a time.** A picture, its veils and its dark crossfade together over `TINT`, linear, once the
  title has settled, the leaving one held whole under the next, then released: the screen never dips, and
  plates follow the same progress. A picture coming during a fade waits for its end, the last to come in
  place of any waiting: two layers at most.
- **No picture, no light.** `Backdrop(null)` is the black room: the neutral dark, no glow. It comes in turn
  like a picture, the one showing going out into it.

### Type (`Type.kt`)

Eight sizes, **12 · 13 · 15 · 16 · 20 · 26 · 32 · 44 sp**, and the signature **72**; eight line heights on the 4 sp
grid (16, 20, 24, 28, 32, 40, 52, 72), one a size but 15's and 16's: a name's 20, a paragraph's 24. The doors' count is sized
to them. Each style has one job; neither a screen nor a component overrides its size, line height, family, weight or
tracking: type is made in `Type.kt` only (`DesignTokenGuardTest`). Unstyled text is `body`; `HouselightsTheme` maps
tv-material's roles onto the scale (display L/M/S: `display`, `headline`, `subject`; headline: `headline`, `subject`,
`rowTitle`; title: `lead`, `entry`, `entry`; body: `body`, `meta`, `meta`; label: `entry`, `group`, `badge`).

**The core reads at 15:** overviews and paragraphs (`body`), button words and every name to choose (`entry`). What
surrounds it is 13: facts, descriptions, values, notes, counts (`meta`), a group's title (`group`), formats (`jaquette`).

**A floor a voice.** Reading: 13, formats included. 12 only in a mark's box (`badge`: an age rating, a protocol, a
card's year). The poster: 16, but the billing block's small words (13). Taglines, an episode's name and the
keyboard's letters are `lead` (16), a step over the names.

| Style | Size / line | Voice, weight | Role |
|---|---|---|---|
| `titleSignature` | 72 / 72 | poster, Bold | a short title without logo on one line, measured against the logo's box (440), under the standard text size as at it; else `display` on two even lines (`TitleArt(measured = true)`); a saga's panels' numbers to three films; the folder home's first scan reads (`FolderBlock`) |
| `display` | 44 / 52 | poster, SemiBold | a title's page without logo: two lines fill the logo's box (104); a saga's panels' numbers for four and five films |
| `codeSignature` | 44 / 52 | poster, SemiBold, +0.06 em | a code to type elsewhere (`DeviceCodePlate`): `display`'s letters set apart |
| `headline` | 32 / 40 | poster, SemiBold | screen and task titles (`ScreenTitle`, `TaskTitle`); a hero's title without logo, the case's, a saga's open film's; a saga's panels' numbers from six films |
| `subject` | 26 / 32 | poster, Medium, tabular | what a pane is about, what a sheet does; an intertitle's line; a folder's or a showcase's name; a figure or a name stated (the storage's total, the Trakt account) |
| `rowTitle` | 20 / 28 | poster, Medium | a row's title; the selected title over a wall; a door's title without logo |
| `place`, `placeCurrent` | 20 / 28 | poster, Medium; SemiBold | the words of a track (`HlSegmented`): SemiBold and lit for the current one |
| `cardName` | 16 / 20 | poster, Medium | a name under a card or on a door, the case's action, a result's name |
| `cardTitle` | 16 / 20 | poster, SemiBold | a title on a card without artwork |
| `lead` | 16 / 24 | reading, Medium | a tagline, an episode's name in its heading, the typed query and the keyboard's letters |
| `body` | 15 / 24 | reading, Regular | overviews, paragraphs, a synopsis, an NFO's values, what is typed in a field |
| `entry` | 15 / 20 | reading, Medium | a name to choose: button and menu words, a key's word, a setting, a place, a version, a value in words |
| `code` | 15 / 24 | monospace, Regular | raw text (NFO XML) |
| `spec` | 15 / 20 | technical, Medium | a technical value a pane or a plate states ("Ultra HD 60 Hz"), a technical sheet's marks, the panel's address |
| `billing` / `billingJoin` | 16 and 13 / 20 | poster, SemiBold; Regular | the billing block's names in white, its small words in grey |
| `meta` | 13 / 20 | reading, Regular, tabular | facts and values: year, runtime, score, sizes, a path; what a row or a choice does, a hint, a status's reason, a note; a row's count, time left, a role under a name, a measure beside a format |
| `group` | 13 / 20 | reading, Medium | the title over a group ("Image", "État") or a plate, a field's name, a reading plate's name: a step under the names it heads |
| `jaquette` | 13 / 20 | technical, Medium | formats as on a case's back, never a measure but on a title's plate (`FormatLines`); a duration inside a button; a door's labels; an address under a QR code |
| `badge` | 12 / 16 | technical, SemiBold | printed marks in their box: an age rating, a protocol, a card's year |
| `figureSignature` | the doors' / 0.74 em | width 62, Bold | a library's count on its home door, its digits cut in posters (`DoorFigures`), proportional figures; sized by `figureAt`, one size for every door |

The lines that set a frame keep it (`meta` 20, `cardName` 20, `rowTitle` 28, `body` 24, `entry` 20): home's grid,
its doors' band and a hero's facts do not move with the scale. A row (52), a place (48) and a field (52) are least
heights: a row with a description grows a little at Grande and more at Android's largest.

- **A folder's name is a title style doing a name's job** on home's first scan (`FolderBlock`), where *subject* names a
  folder elsewhere: the folder just chosen set as a film's opening title, to its measure in its column
  (`FirstScanDefaults.column` 344), `titleSignature` on one line, else `display` on two balanced lines, white and never
  ember, a folder read not being where you are. It keeps the name the folder was given, in every language.
- **A saga's panels' numbers are a title style doing a figure's job**: `display`, one size whatever the saga's length,
  « 25 » keeping room on each side of a closed panel 96 wide. They are set as the style is, their proportional figures
  kept, since a panel's number never changes in place; from 30 sp they keep their size at every text size.
- **Text is set level.** The one turned text is a saga's spine: its name upright, reading from the foot up as a French
  spine does, in its numbers' style while it fits on one line over its band, else in `rowTitle`,
  ending in an ellipsis past its height (`VerticalName`), measured on its side and drawn turned, without a layer.
- **The ember light on words** (`emberLit`): the place you are in, a place's title. A component sets it,
  never a screen (`DesignTokenGuardTest`).
- **Tabular figures** (`tnum`) in technical styles and wherever a figure changes (`subject`, `meta`). `case` only
  for billing capitals, never with `tnum`.
- **Tracking follows size** (`trackingFor`): −0.01 em from 32 sp, none from 15 to 26, +0.01 em at 13,
  +0.02 em at 12. Billing capitals +0.04 em, a code to type +0.06 em.
- **Boxes keep to their letters** (`LineHeightStyle`, `Trim.Both`): spacing is measured from the type. A text
  laid out line by line (an NFO's XML) trims only its first and last lines (`TextLines`).
- **Line breaking:** titles and taglines balance their lines (`LineBreak.Heading`), paragraphs and facts break for
  reading (`LineBreak.Paragraph`), nothing is hyphenated.
- **Fallback scripts.** Archivo covers Latin and Vietnamese; line heights leave room for stacked marks and
  for fallback fonts. The catalogue's stress section shows the extremes.
- **Text scale.** A frame holding text is never a fixed height: use `heightIn(min = …)`, or `textRoom(height)`
  / `textRoom(style)` where the frame must not move with its content.
- **Taille du texte** (Réglages › Affichage, `TextSize`) multiplies the system's text size by one step of Android's
  on either side of Standard: Compacte 0.85, titles included, in a straight line; Grande 1.15 on Android's curve,
  which Compose applies from 1.03 (12 to 15 grow 15 to 16 %, 16 13 %, 20 9 %, 26 3 %, 30 sp and up keep their size). The
  product stays within 0.75 (1.5 px a sp at 1080p, the Zidoo menus' step) and 1.3 (Android's largest, which the
  layouts are checked at): Grande on Android's largest adds nothing, and its choice says so. Grid, margins, pictures,
  focus, icons and fixed heights stay in dp; what is measured to text follows (`textRoom`: the doors' band, a plate,
  a head). Under 1 a title chooses its signature as at 1, then takes the size, so a smaller step never grows one
  (`TextScaleTest`, `TextSizeLayoutTest`, `TitleArtTest`).

### Space and size (`Dimens.kt`)

| Token | Content |
|---|---|
| `HlSpace` | 4 dp grid: `xxs` 4, `xs` 8, `s` 12, `m` 16, `l` 24, `xl` 32, `xxl` 48; `fact` 20 between facts; safe area `safeH` 48 / `safeV` 28; `fadeEdge` 24 |
| `HlInset` | container padding: `tight` 8 (results holding a thumbnail), `card` 24, `sheet` 32; `group` 4 (a group's plate around its rows), `plate` 24 (a plate of facts, top and foot), `reading` 20 (a reading plate's sides, a status panel's top) |
| `HlShapes` | radius follows hierarchy: `thumb` 2 (images up to 48 dp), `badge` 2, `mark` 3, `control` 6 (buttons, tiles, a track's plate, menu options), `field` 6, `row` 8, `artwork` 8, `wide` 11, `panel` 12 (menus, plates, showcases), `sheet` 16 (a side sheet's free corners, square against the edge). Every radius is in dp, never a pill or a disc; only dots are round, through a local `CircleShape` (`ShapesTest`). Nested shapes keep parallel corners: **outer radius = inner radius + gap** (a menu: 6 + `HlMenuDefaults.inset` 6; a group's rows: 8 + `HlInset.group` 4; a mark in a wide image's corner: 3 + 8; a focus frame: the picture's + 3) |
| `HlSize` | artwork (`poster` 120 × 180 in a row, `wallPoster` 112 × 168 on a wall, `landscape` 240 × 135, `episode` 264, `portrait` 96 at 3:4, `PORTRAIT_RATIO`; posters at 2:3, `POSTER_RATIO`), `control` 44 on a page and `controlCompact` 40 in a screen's header, `tile` 44 and `tileCompact` 40, `segment` 40, icons (`icon` 20 beside words, `tileIcon` 22 on a tile, `largeIcon` 24, `inlineIcon` 16 in a line of facts), `mark` 22, the Menu key `menuKeyWidth` × `menuKeyHeight` 24 × 22 (its frame `keyEdge` 1.5, its lines `keyGlyph` 18), dots (`dot` 8, its ring `dotRing` 1.75, `newDot` 10 ringed `newDotRing` 2.5, `newsDot` 7 before a row's new titles and `doorDot` 8 before a door's, `wordDot` 7), setting rows `row` 52 at least in a pane as in a sheet, a text field and the search query `field` as tall, the settings' places `navRow` 48, a backdrop at its use 16:9 (`PREVIEW_RATIO`), a `showcase` 416, a gallery's wide images `galleryWide` 244 (a logo's box at `LOGO_BOX_RATIO`), meter 10 (`meterLarge` 14 when a pane states it, its parts' `swatch` 10), progress lines (`progressPoster` 4, `progressWide` 5, `progress` 3 beside text, `heroProgress` 220 × 4), `intertitleRule` 24 × 2, the lockup's capitals (`wordmark` 30, `wordmarkHero` 26) |
| `HlLayout` | the screen grid, read by screens, the catalogue not counted (`LayoutTokensTest`): reading widths (640, 520), the hero band of « À regarder » and a person's page (148), `WALL_COLUMNS` (seven posters across the safe area, `wallGutter` apart), `detailGap`, `detailHeroMin`, a table's value column (`specFormat`), a reading sheet's definition labels (`readingLabel` 180), a saga's polyptych inside the safe area by a frame's reach (`voletSide` 53.5, `voletTop` 33.5 from the top and the foot), Modifier's poster tile (`posterChoice` 180), the contact sheet's (`POSTER_CHOICES` 6 of `posterChoiceCell` 88, `BACKDROP_CHOICES` 5 of `backdropChoiceCell` 160, `LOGO_CHOICES` 4 of `logoChoiceCell` 204, `choiceGutter` 12, `choiceSectionGap` 32, `choiceNameGap` 16, `choiceNameDrop` 8, `choiceWords`, the print `choicePrint` 240 and `choicePrintGap` 32, the band `choiceBand`) |
| `…Defaults` | internal component parts: `TwoPaneDefaults` (the side column 256, leaving the settings' pane 560; `sideWide` 344, leaving a form 472), `HeroDefaults` (a hero's title 380 × 56), `HomeGridDefaults` (home's grid: its head, case, rows and band, the doors' parts), `HlSideSheetDefaults` (520, an NFO's `wideWidth` 736), `HlSettingRowDefaults` (value 360, room above and below 6), `ShowcaseDefaults` (its words' inset, where its name sets), `HlIconButtonDefaults` (label 280 at the default text size, grown with it, edge 24, a hint's or the Menu key's line `hintRoom` 20), `HlMenuDefaults` (320, inset 6), `TechSpecsDefaults` (a track's format, its block's widest between 188 and 280), `QrCodeDefaults` (module 6.5, `moduleCompact` 5 beside words or over notes, whole pixels at 1080p and 4K), `DeviceCodeDefaults` (a code's plate inset 28), `PaneDefaults` (a plate's label column 172, a table's names 120), `BackdropDefaults`, `FirstScanDefaults` (home's first scan: the wall's `COLUMNS` 4 and `FRAMES` 8, `gap` 8, `wall` 472, the folder's block's `column` 344 and `line` 220, `buttonGap` 16 over the wall, a step every `STEP_MS`), `VoletDefaults` (a saga's panels: `open` 428 at least, a hero's title box and a card's inset each side; `gap` 8; `closed` 96, a portrait's width, whatever the saga's length; the numbers `numberTop` 16 under the edge; the open words' `melt` 64, over which the numbers' floor melts back too; `opensAtOnce`, false) |
| `HlControl` | padding of buttons, track words and badges; icon-to-text gap 8 |
| `HlFocus` | the `frame` 2.5, `frameGap` 3 off the edge: their `reach` 5.5, whatever the size, held by a row's `room` 12, which the glow passes as far as a row draws (30), and a wall's `wallRoom` 24, where the grid cuts the glow once faded; siblings stepping back (`SIBLING_BRIGHTNESS` 0.64, `SIBLING_SATURATION` 0.65, `RECEDE_STEPS` 3); `glow` 28; `edge` 28 |

### Motion (`Motion.kt`, `Auditorium.kt`)

| Token | Content |
|---|---|
| `HlMotion` curves | three of the system's own: `focusPull` ("mise au point", 0.20 0.75 0.25 1) for what arrives, `fallBack` ("retombée", 0.45 0 0.55 1) for what leaves or crosses, `lightsOut` (ease-out) for lights going out. No Material curve, no curve drawn elsewhere, no animation on a default spring or a cross-fading container; every tween names its easing (`MotionGuardTest`) |
| `HlMotion` durations | `FOCUS_IN` 220 and `FOCUS_OUT` 260 ms (a frame, a glow, a recede), `PLATE_IN` 120 and `PLATE_OUT` 180 (a plate's white), each pair in on `focusPull` and out on `fallBack` (`HlMotion.inOut`), `TINT` 480 (a backdrop's fade, linear, once settled; a poster arriving on the first scan's wall); `FAST` 150, `STANDARD` 250, `SLOW` 600, `DIM` 1000 ms; `SETTLE` 300 ms before the title under focus changes the screen (`rememberSettled`); `SKELETON_DELAY` 400 ms; `HOLD` 400 ms, the longest anything waits for a picture; `WALL_DRIFT` 140 s, one loop of the welcome wall; `ENTER_SCALE`, which a menu grows from |
| A poster arriving (`FirstWall`) | the one motion that answers a scan rather than the viewer, as a new field of the settings' room comes in: once its picture is decoded at its size (or its one-sheet ready), the frame's opaque ground (`groundFill`) lifts off it over `TINT`, linear, read while drawing, without a layer nor the loader's fade (`ArtworkImage`'s `onShow`); a batch together, each when its picture is ready, never staggered. Renewing a frame is the same arrival: the poster it showed stays whole until the one coming is decoded, then is cut under the ground, never two posters one in the other; at most one a step (`FirstScanDefaults.STEP_MS` 2 s). Filling, the step that fills the last frames renews none, and no renewal begins until every title on the wall has its picture; a renewed frame's ground lifts plain, an empty frame's rule and edge fading with the ground of its first arrival alone. The titles there as the wall comes, once read (back from Réglages, its state kept with home, or with a new home), show at once |
| `LocalHouseLights`, `HouseLightCue`, `LaunchCue`, `LocalLaunchScreen` | house-light level (read while drawing, each layer redrawn only during its own step: `rememberCueStep`), the playback and launch cues, the launch screen until the first screen is drawn whole |

### Icons (`Icons.kt`)

`HlIcons` holds every icon, all Lucide's ([license](third_party/lucide/LICENSE)), their SVG paths as Lucide draws
them: 24 dp grid, stroke 2, round caps and joins. A state that reads as on is the same drawing filled (`Heart`,
`HeartFilled`) or its check cut out of a filled circle (`CheckFilled`); a connection encrypted is a lock, closed
(`Lock`), one that is not the same lock open (`LockOpen`); Play is filled, as on a remote; the Menu key's
lines are `square-menu`'s, its square drawn as the key's frame (`MenuKey`); a title out of the library, nothing deleted,
is a list crossed out (`list-x`, `HlIcons.Remove`), never a bin; a field emptied at once is a cross (`x`,
`HlIcons.Clear`), the search keys' Erase (`delete`, `HlIcons.Erase`) taking one letter. There is no other family: no
Material icon is drawn, imported or declared as a dependency (`DesignTokenGuardTest`).

### Scrolling (`Scroll.kt`)

| Token | Content |
|---|---|
| `rememberListScroll`, `ListScroll`, `RowScroll`, `OwnScroll` | lists scroll only as needed (`EdgeScroll`) and keep focus `HlFocus.edge` from their ends; a list under a header brings rows back to its top (`ListScroll(top)`); rows keep focus 80 dp from the sides; a list that brings its element with focus where it belongs by its own state (the contact sheet) scrolls nothing of its own as focus moves |
| `rememberRowState` | a row resting at its start stays there when titles arrive in front of it |
| `scrollEdges(state, color)` | scrolling content fades into its opaque surface over `HlSpace.fadeEdge` where there is more; never on poster rows |
| `fadeIntoField(state, start, end)` | a form on a field of posters fades itself into the field over `HlSpace.fadeEdge` where there is more, a fade into black not showing over plates as dark: offscreen only while it fades, its layer reaching `start` and `end` past its sides so hanging plates and focus frames are not cut |
| `revealOnFocus()`, `revealOnOpen(opens)`, `keepVerticalFocus()`, `keepHorizontalFocus()` | a group comes fully into view when focused, a block when it unfolds under its row; up and down never leave a column, left and right never a contact sheet's line |
| `scrollsAsBlock()`, `ListScroll(top, toTop = true)` | a block (a row with its header, a hero with its actions) scrolls as one; pages turn by whole blocks |
| `fadeOutsideSafeArea()` | text fades out across the safe-area margin as it scrolls (layer alpha, no offscreen buffer) |

## Layout

The screen is a TV seen from three metres, through overscan that may crop its margins.

- **No word is cut by the screen.** Pictures may cross the safe area; text fades out before the edge
  (`fadeOutsideSafeArea`), or into an opaque surface (`scrollEdges`).
- **Pages turn by whole blocks** (`scrollsAsBlock`). On a title's page a block moves to the top
  keyline, and the gap between blocks (`HlLayout.detailGap`, 32) takes the previous one off screen. A title's
  page first shows its hero plus the first row's header and a strip of images (`HlLayout.detailHeroMin`).
- **Home is a grid of doors** across the safe area (`DoorGrid`, `HomeGridDefaults`): the lockup
  (`HomeHead`, its capitals 11.5) in a band of 20 under the safe area's top, 20 over the grid; then the case down the
  left (252 × 444) and three rows beside it, 12 apart: what is watched (136), the libraries (148), the tools (136). A
  row shares its 600 by parts of four (`placeDoors`), so a door of two parts is as wide as two of one and their gap,
  and the doors of different rows line up: 294 + 294, 141 + 141 + 294, four of 141, three of 192 (`DoorGridTest`).
  A row left out gives its height to the one under it (`gridHeights`). `HomePlan` composes it from what home read:
  - **The case** (`ResumeCase`) resumes the title last in progress: its backdrop cropped upright from its top, a file of
    1280 px at most (else its poster), melting into its dark (`HlScrim.CASE_FOOT`), its logo or name in `headline`, its
    facts 12 apart (`MetaLine`), its progress and time left, their room never under a message's two lines, then its
    action, a plate the case's focus fills white; why it did not play takes the facts' place (see *Messages*). With
    nothing in progress, it proposes a film not seen, drawn once a session, else a show not seen, whose page it opens
    ("Voir la série", no Play glyph), else any title (`UnseenPick`): "Pas encore vu" in `jaquette` over one not seen,
    its genres, Lire. What it proposes is read with every read of the doors, asked first, beside the libraries', shown
    as soon as it comes (`readDoorsAsync`), so the case changes in place, focus kept; only an empty library has none,
    focus then on the first door. OK plays; Menu opens its options, which the Menu key says at the end of its action's
    plate while it has focus: its page, and from its start once started; after a failed play, the failure first.
  - **What is watched**: À suivre, the next episode or saga film not already in progress (`nextUp`: as a saga's Lire,
    the one in progress, else the one after the last watched, else the first not seen, `nextIn`), its still and logo
    (`DoorStill`), its season's episodes along the band's top edge, those watched in the ember light, "S1 · É4" in
    `jaquette`; without one, the second title in progress and its part seen; without either, Nouveautés takes the
    row. Nouveautés: the latest titles' posters side by side, newest first (`DoorPosters`), and how many joined this
    week by the ember dot. Each opens « À regarder » on its row.
  - **The libraries**: a door each, up to four; past four, the fourth gathers the rest ("Bibliothèques", a side sheet
    listing them). Its count is its picture (`DoorFigures`): each digit cut in a poster of the library, one shown
    nowhere else on the screen first, then one shown elsewhere, then the same again, its upper half, edged in ink at
    `FIGURE_EDGE`; a digit is ink while its poster first loads, and every digit when the library has no poster;
    reloaded, its posters stay until the new ones are cut, each scaled to its line once, off the main thread
    (`CutPoster.scaledTo`);
    one size for every door's count (`figureSignature`, `rememberFigureSize`), the largest that keeps 14 across and
    13 down for the longest, four digits included. Under it the posters' tint (`doorTint`: averaged to 24 × 9
    from copies an eighth of their size, `tintPoints`, enlarged with bilinear filtering), dark at rest (`DOOR_TINT`),
    whole with focus, the door glowing across their tones (`glowTones`). The band says what is new: "4 nouveaux" by
    the ember dot (`HlSize.doorDot`) for films, "3 épisodes nouveaux" for shows; the door is read name first
    ("Films, 412 titres, 4 nouveaux"). The sagas are no library of this row.
  - **The tools**: Sagas, two sagas' posters stacked (`DoorSagas`) and their count, opening the films' wall on the
    sagas alone (`Route.Library`'s `view`); without sagas, or with Sagas set to Masquées, Rechercher takes their
    place. Rechercher: a wall of small posters, out, that fills the door (`DoorWall`, `wallThumbs`): posters shown
    nowhere else first, then those shown elsewhere, then the same again. Réglages: the app's state as a case's back
    (`DoorFacts`): Source ("Mes fichiers · NAS", only the dot quiet), Trakt where it follows the source ("Non relu pour
    l’instant" for a session kept the Keystore did not open, `TraktFact`), the last scan
    ("Il y a 2 h") or the one running, and a problem TMDB had in the last scan ("TMDB", its words in ember after the
    attention mark: "Clé refusée", "Certificat refusé", else "Injoignable"); what the app does not know is left out,
    and what the door cannot hold goes by rank (`keptFacts`): the source's host, then the source, then Trakt, the last
    scan staying, alone at the largest text.
  A door (`HomeDoor`) is its picture over a band of the picture's dark (`ambientDeep`) set off by a hairline: its icon
  (18), its name in `cardName`, what it says at its end in `meta` (`jaquette` for a mark), dropped whole when the
  name leaves it no room; a name too long for its icon goes without it, a door of one part keeping them 6 apart
  (`bandGapNarrow`). Framed with focus, nothing grows
  (`HomeLayoutTest`, `HomePlanTest`).
- **Home's first scan** (`FirstScan`, `FirstScanDefaults`) holds home for the whole pass begun on an empty library
  (`ScanState.firstPass`, kept to the pass's end, where `homeFilling` goes once home's row is full), and wherever no
  row shows while a scan runs. Under the head, on the margin, the folder's block (`FolderBlock`, its column 344, see
  *Components*) stands on the safe area's foot, TMDB's trouble over it (`AttentionNote` in `meta`, `xl` over the
  block) with « Modifier la clé » `m` under it for a refused key alone, so nothing in the block moves as they come,
  the column keeping `l` under the head. At the safe area's end, on the same foot, the wall of the titles found
  (`FirstWall`: two whole lines of four `wallPoster`, 168 to 512, every frame inside the safe area). From the first
  title, while the first pass runs, « Aller à l'accueil » stands over the wall from the head's foot, aligned on its
  first frame, 16 over its posters (`GoHomeButton`, `buttonGap`), « L'analyse continue en arrière-plan » under it on
  one line, the 78 they take at 1.3 within the 100 the head leaves; the settings tile's label falls under it, at the
  head's end, to their right without touching them. The screen gives way by itself once every folder's files are read
  (`ScanState.finishing`), or on « Aller à l'accueil », in one cut once home's one read has its shelves
  (`shelvesRead`), focus placed once on the case; until then nothing moves, the button keeping its white plate, no
  wheel nor word more, a second OK changing nothing. The choice is kept with the pass while the app runs
  (`FirstScanLeft`), not in the screen's state, which Back clears as it quits: leaving and coming back lands on the
  grid, the scan having waited; it is forgotten at the pass's end. Without a title by then, « Aucun film pour
  l'instant » or « L'analyse n'a pas abouti », as before. At 1.0 and 1.3, in every language, TMDB's three notes,
  « Modifier la clé » and a long name on two lines hold in the column, its lines whole, and the button and its line
  over the wall (`FirstScanLayoutTest`, `HomeViewModelTest`).
- **« À regarder »** (`WatchScreen`): home's rows under their title (`ScreenTitle`), the hero band (`HlLayout.hero`,
  148: logo, one line of facts, two lines of overview or progress), then the rows, `HlSpace.l` apart; opened on the
  row of the door that opens it, Back home to that door (`WatchPageTest`, `PagesNavigationTest`). It reads home's
  view model: a change of the library reloads them once, whichever shows it first (`LibraryShown`).
- **A wall is a page**, as search and the settings are, whether home's door opens it (Films, Séries, Sagas, a
  server's libraries) or another screen does, filtered (a person, a format): its name lit at the safe area's top
  (`ScreenTitle`), its count beside it, Filtrer and Trier at the end of that line, the selected title under it, then
  two whole lines of posters from the start (`WallPageTest`).
- **Three vertical gaps:** `HlSpace.s` within a block, `HlSpace.l` between the bands of « À regarder »,
  `HlSpace.xl` between the blocks of a scrolling page.
- **Reading measures:** `readable` 640, `readableNarrow` 520 beside artwork.
- **A focused poster stays inside the safe area**, its frame included, as a pane's showcases do (`HomeLayoutTest`,
  `DetailLayoutTest`, `FocusReachTest`).
- **A wall** keeps `HlFocus.wallRoom` (24, the gap between its lines) above its first line: the grid cuts a
  focused poster's glow there, once faded (`FocusReachTest`).
- **A filtered wall losing a title**: a title its poster's options mark that the wall's filter (Vus, Non vus, En cours)
  no longer keeps, or take out of Favoris on a wall filtered on them, leaves at once, not waiting for the wall to read
  again, the count with it, the posters after it moving up one place with no motion, as a wall read again; focus is on
  the poster taking its place, set once, else the one before it (see *Focus*); emptied, the wall says « Aucun titre
  avec ces filtres », « Retirer les filtres » focused. The same after Back from its page. A read under way meanwhile,
  a scan's progress or the one a change before asked, never brings it back nor its count: the change lies over every
  read until one begun after its save says it, here, in « À regarder », on a person's page and in search
  (`PendingChanges`). A change not saved is undone alone, its mark or its favourite (`Undone`): another asked of the
  title still saving or saved keeps showing, and two not saved, a mark then a favourite or a mark then its opposite, leave it as the source
  has it. The title is back in its place with focus, its title line saying why in place of its facts,
  « Non enregistré · Serveur injoignable » by the attention mark, while it has focus; a wall started over meanwhile, on
  another filter, sort or view, takes it back where it read it and kept it away for the change, and one that never
  held it, an unwatched film on Vus, never takes it in. Unfiltered, the poster stays, its mark changing, focus
  where it is; a favourite ends its title line's facts with the filled heart, on a wall and on a person's page that has
  one (`TitleLine`). In « À regarder » a title leaves its row as it leaves what the row holds, Reprendre at once, À
  suivre's episode giving its place to the next as the rows read again; on a person's page and in search the title
  stays, its mark changing, a change not saved said in the hero or under its row (`WallMarkTest`, `WatchPageTest`).
- **The films wall** shows a saga as one poster in place of its films, from the number of films the Sagas setting
  asks, ranked by them (`SagaWall`), and counts both ("8 titres · 1 saga"). Its filter menu opens on Afficher,
  Films et sagas or Sagas: the sagas alone are titled Sagas on the films' wall, named first on the button
  ("Filtrer · Sagas · Non vus"), and filtered through their films. A wall opened on part of the library shows
  every film, and so does the wall once Sagas is set to Masquées, with nothing to choose. Jellyfin gathers a
  saga's films on the server (`collapseBoxSetItems`), but not under a filter, ranks it by the collection's own
  fields (when it was made, its year, its rating) and counts it as a title; its sagas alone are not filtered
  (`FilterMenuTest`, `FilmsWallTest`, `SagaWallTest`).
- **A saga's page** (`SagaVolets`) is its polyptych, « Les volets », on a page that never scrolls: a tall panel a film in
  release order after the saga's own, its box, across the safe area less a frame's reach each side (`HlLayout.voletSide`,
  `voletTop`: 853 × 473 on the canvas, every frame inside the safe area, `VoletsLayoutTest`). Each open panel carries
  its actions under its words. The room is the open panel's diffuse light (`AmbientBackdrop`, `Diffuse`) once focus
  settled, black on a film the library lacks (`Backdrop(null)`), and changes nothing as focus goes down into the
  actions.
  - **The panels** (`Volets`, laid out by `VoletPlan`, pure): the closed ones `VoletDefaults.closed` wide (96, a
    portrait's: 30 % of the poster shows) whatever the saga's length, `VoletDefaults.gap` apart; the open one takes what
    they leave, never under `VoletDefaults.open` (428: 541 for three films, 437 for four). From five films the films
    scroll by whole panels (`VoletPlan.scrollTo`), the open one two closed panels after the saga's spine where it can, a
    closed panel whole after it, the saga's closed panel pinned at the start, a film scrolled away going under it; a
    panel past the polyptych's end shows its picture alone, its number and year gone, no text cut by the screen's edge
    (`standsWhole`). Their posters are asked for in view, along the way a move takes and four panels past them on either
    side alone, eight on the side a held arrow runs to, none far from the view on arrival; kept while within eight, a
    far panel keeping its dark (`PosterWindow`, `VoletPostersTest`). A closed panel is its poster at its natural size,
    cut by its width, never scaled: its number at the top on its axis, its mark (a card's check, held ring or new dot;
    the ring of a film missing, the quiet dot of one to come) and its year at its foot, every year shown, the share seen
    burning at its very foot; no state word. The open panel shows its backdrop over its poster once focus settled (else
    its poster on its dark), its number at its start, and its words in a hero's band (`VoletWords`): its logo or its
    title in `headline` on two lines at most, broken at its separator (a dash, a French colon), which goes there, else
    the separator held mid-line, no line ending or beginning with it (`titleLines`), its facts without genres, its state
    last and never dropped (« Vu », « Il reste 1 h 14 », « À suivre », « Absent de votre bibliothèque », « Sortie le 15
    décembre 2026 », « Annoncé »), two lines of overview. They wait for their picture, `HOLD` at most, then come in one
    cut.
  - **The saga's panel**, open on arrival: its backdrop, else its first film's, never its poster, which prints its name;
    its name set to its measure (`TitleArt(measured = true)`: the signature on one line, else `display`), its facts on
    one line (the years of its films out, the films held of those out, « 3 vus » once one is watched, their length or,
    once one is begun, « Il reste 14 h 08 », then « 2 à venir », which gives way first, then the length) and two lines
    of its overview, OK opening it whole once cut (`SagaVoletWords`). Closed, it is its spine: its backdrop stepping
    back with the panels, its name upright (`VerticalName`, see *Type*) over its band, which says where focus is
    (« 5 sur 9 »), no number.
  - **A film the library lacks** or not out yet keeps TMDB's poster and backdrop in grey (see *Veils*), framed without a
    glow, stepping back further than its siblings; it has no actions, its words over the band of its neighbours' marks
    and years, OK opening its synopsis (`SynopsisSheet`), never a way to get it.
  - **The actions** (`HlActionRow`), under the open panel's words, `HlSpace.l` under its overview, an action row's gap
    apart, a tile's label room alone over its foot, so a focused tile's label lies on the band of its neighbours' years.
    The saga's: « À suivre » and the film it plays next (`nextUpIn`), the label in white `group`, the name in secondary
    `meta`, cut at the line's end; then Lire or Reprendre it, saying its length or its time left, Depuis le début once
    started, « Marquer la saga comme vue » (it marks the films held), and Modifier, its poster and its backdrop in the
    title page's own sheet (`EditMenu`, no Identifier), where its source keeps its pictures: a Jellyfin box set has
    none. A film held's: Lire or Reprendre it, Depuis le début, its check marking it alone (`togglePlayed`), and the
    info tile opening its page (« Voir la fiche »). Lire is as wide as its words, the tiles after it, the row packed at
    its start, its detail going whole before a tile gives way (`detailWhole`). What failed to play or to save, or why
    the saga could not be read again, takes the « À suivre » line's place over them, whichever panel is open, Réessayer
    after the saga's actions; the player changed (#58), « Faire confiance au nouveau lecteur » takes the open panel's
    Lire's place. A saga with no action at all has focus on its panel, Back leaving at once. While the panel itself has
    focus, never with a tile's label, what OK does on it shows in its bottom corner, on the line of its neighbours'
    years, one quiet line of marks along the polyptych's foot (`OkHint`): the OK key (`OkKey`) a hint's line tall
    (`HlIconButtonDefaults.hintRoom`), « OK » in the technical voice in a tertiary frame, then in a hint's tertiary
    `meta` « Voir la fiche » on a film held, « Lire le synopsis » on one the library lacks, « Lire le résumé entier » on
    the saga's own while its overview is cut (`SagaHintTest`). The Menu key does nothing on the page. At 1.3 in every
    language, the words and the actions hold, a film's title alone cut in its measure (`SagaWordsTest`). A box set (a
    source that lists only the films it holds) has a panel for each of them, numbered in the server's order, none grey.
  - **Remote** (`SagaPageTest`): the page opens on the saga's Lire, its panel open. Up from any action goes to its
    panel; Down from an open panel enters its actions, on its Lire, the panel staying open with its words, its picture
    and the room, its frame alone gone (principle 7 holds here without exception); Left and Right move along the
    actions, Left from the first and Right from the last on to the panel beside, focus on the panel itself, which opens
    (nothing before the saga's Lire, nor past the last film's last action) (`VoletSides`), and on a panel open the panel
    reached as the one left closes,
    a move cut short going on from where each panel stood and a long saga's scroll moving with the widths
    (`VoletsMotionTest`), a step recomposing those two panels and the spine alone. Back from anywhere opens the saga's
    panel again, focus on its Lire, then leaves the page. OK opens a film held, the synopsis of one the library lacks,
    the saga's own once cut. Back from a film's page, its panel is laid out open from the first frame, focus where it
    was, nothing moving, its picture whole at once (`SagaRoomTest`). A panel keeps its place as the library gains the
    film it lacked, open and focused if it was.
  - **Read** as a group named « Films »: each panel by its title, its number and its year, then its state as a card's
    (« Dune : Deuxième partie, film 2, 2024 », « en cours, 55 % »), the saga's as « Dune, saga », then what its words
    say open, as a case is read: the saga's facts and its overview, a film's length, what is left of it and its
    overview; its words read as it, once, its actions after it. A panel OK opens nothing on (a film the library lacks
    without an overview, the saga's own uncut) offers no action; marks are not read.
  - **A film's page** keeps its saga's row, whole in release order, its heading the saga's name and count, never the
    poster focused: the films held as posters, the film itself in its place, the others as grey `AbsentCard`s, OK
    opening their synopsis (`DetailSagaRowTest`).
  - This page bends four rules, each written where it lives: the panel with focus opens wider (see *Focus*), the
    panels carry words (see *Cards*), its numbers are title styles and its spine the one turned text (see *Type*).
- **The contact sheet** (« la planche-contact », `ArtworkScreen`): where a film's, a show's or a saga's picture is
  chosen, its choices in sections (`choiceSections`), shown at once and searched by eye, beside or under the one with
  focus shown at its use. The head, untitled: Automatique, the one in use beside it, checked in ember and focused on arrival, never
  again in its language's section, then what the title's files give (its NFO's picture, a file beside it, a logo's
  title in type); then a section a language in the order TMDB's are ranked, the viewer's language, English, then
  « Sans texte » for posters (« Toutes langues » for logos, which nearly always carry the title written), « Sans
  texte » first for backdrops; a saga's films' last, « Ses films », one picture a film, the one it shows (its choice,
  else its own), in release order, named by its title and year (`ArtworkOrigin.FILM`). At most 30 pictures of a
  language, the best rated, and 80 in all (`LocalEditor`), so every language keeps its section; a saga's films are never
  cut, TMDB's taking what they and Automatique leave. A section is named even for a single picture, the only place its language is said, in a
  search row's style with its count after it (`ResultsHeading`); no total, no sentence under the title. The line with
  focus always comes to the sheet's top, the wall's room over it for its glow, the sheet scrolling itself to it
  (`OwnScroll`) whatever is over it in its list, a section's name (`choiceSectionGap` from what is before it); the
  lines passed leaving cut clean under its section's name, held over it with its count (`choiceNameDrop`), so every
  line says its language, the head's none; a section coming under it has its name over its first line, faded across
  the margin rather than cut; the last line comes to the top too, nothing under it. Up and Down go from line to line
  and section to section, in the column they are in, Left and Right along a line, never into the next
  (`keepHorizontalFocus`); OK chooses, saves and returns to the title; Back leaves without choosing. Nothing is
  written under a choice but « Automatique », which runs under the next column when that one has no words
  (`choiceWords`, on one line at 1.3 in the twelve languages), and a saga's film's name and year; the choices recede
  as a wall's do, nothing grows, no blur, no glass, no shadow. Every measure is named in `HlLayout`, on the 4 dp grid.
  - **A poster** stands beside the sheet: under the task's title (`TaskTitle`) and the title's name (`meta`, two lines
    at most), the print (`ArtworkPrint`, `choicePrint` 240 × 360, nearly three choices tall), to the safe area's
    foot over its words; `choicePrintGap` 32 from it the sheet, six posters a line (`POSTER_CHOICES`,
    `posterChoiceCell` 88 × 132, `choiceGutter` 12 apart, lines 24 apart, sections 32), the last column 4 short of
    the safe area's end; its line with focus level with the print's top, its section's name on the line of the
    title's name. The print follows the focus once settled (`rememberSettled`), never focused itself: the picture it
    showed stays whole, its check and its words with it, until the next is decoded, then is cut; its ember check when
    it shows the one in use, and its one line, kept when empty: « Automatique » (and the film whose picture it is), a
    saga's film and its year. At a larger text size it gives up the height the head's words take. The room is the
    title's backdrop, quiet.
  - **A backdrop** lies behind: the one with focus fills the screen under a title's page's veils (`Full`), the title's
    logo and facts set over it as its page sets them, under the task's title alone; the sheet is a band at the
    screen's foot (`choiceBand`: the held name, the wall's room, a line and its words inside the safe area, the top
    of the next, 214 at the standard text size, 230 at 1.3), five backdrops a line (`BACKDROP_CHOICES`,
    `backdropChoiceCell` 160 × 90). **A logo** shows at its place over the title's backdrop, the same band, four a
    line in a logo's box (`LOGO_CHOICES`, `logoChoiceCell` 204, `LOGO_BOX_RATIO`). One left edge: the title, the
    preview, the section's name and the first choice start at the safe area's, the first frame reaching into the
    margin as on a wall. The preview stays whole above the band; with TMDB's pictures missing, its note stands under
    it, over the band, which shows one line.

  TMDB's notes (no key, the key refused, a certificate, out of reach or the title not identified, `onlineNote`)
  stand in place of its sections, after the head (`AttentionNote`). `ArtworkSectionsTest`, `ArtworkScreenTest`,
  `ArtworkLayoutTest`, `LocalEditorTest`.

  **A saga's** (#48) poster and backdrop are chosen there, from its Modifier: Automatique, today's rule (TMDB's picture
  of the saga, else its first film's, which the print names after « Automatique »), and the one in use; then TMDB's
  pictures of the saga (`collection/{id}/images`), a section a language; then « Ses films ». TMDB out of reach, the note
  says the connection alone (`artwork_offline_saga`), a saga never being to identify; a saga an NFO's set names and
  TMDB does not know has its films' pictures alone, the note saying why as a fact, in secondary text, without a mark,
  nothing having failed (`artwork_saga_unknown`). The choice is kept by the saga's key, never on its row, in a table of
  its own (`SagaChoiceEntity`), so scans keep it, and the saga leaving with its last film and coming back finds it;
  Automatique clears it. A saga known by its films' NFO set alone (`name:`) that TMDB names later, a key added, a scan
  or an identification, takes its choice to its TMDB id's key in the transaction that moves each film, unless TMDB's
  saga has one of its own; two such sagas joining one, the one of the most films; the name's row goes with its last
  film (`SagaChoiceCarry`, `SagaChoiceKeyTest`). Its walls, home's Sagas door, search, a film's saga and its page, its
  panel and its room, show it at their next read (`SagaChoiceTest`). A Jellyfin box set has no Modifier: its server's
  interface changes its pictures.
- **The welcome screen**: the lockup and tagline in the middle of the left column, notes under them (a connection
  that could not be read back; settings that could not be and were reset, until a source is saved; the last
  choice's failure); the task's title on one line over the two choices, raised on glass, each saying what it is in a
  line of words and no mark ("Vos vidéos sur un NAS, un ordinateur du réseau ou un disque branché au lecteur…", "Les films et les séries de votre
  serveur Jellyfin."), both reserving the lines the longer takes at the viewer's text size, so they keep one height, a
  focused one's frame inside the safe area (`WelcomeLayoutTest`). A saved connection the Keystore did not open for now is no lost one: a
  `PartFailed` (`SECRETS_UNREAD`) as wide as the tagline, its Réessayer focused, and home once it is read, unless a
  choice was made since the last read asked (`rememberReadBack`); « Mes fichiers » keeps that server, sealed as stored.
  Reached with no library left it adds no note, the confirmation having said why. Back quits at once, nothing being
  under it; « Mes fichiers » without a folder opens « Ajouter un dossier » at its first step, Back coming back to it.

## Focus

Focus must read from the couch. It is said in one place, `FocusStyle` (`components/FocusStyle.kt`), as
projector white in two gestures and a ring:

| Gesture | For | Treatment |
|---|---|---|
| **Frame** (`FocusStyle.frame`) | what carries its own light: pictures, a showcase, panel buttons, the action to play | a white frame (`HlFocus.frame`) `HlFocus.frameGap` off the edge, its corners parallel; a picture glows in its own light. 220 ms on (`focusPull`), 260 off (`fallBack`) |
| **Fill** (`FocusStyle.fill`) | plates: buttons, tiles, track words, rows, menu options | white plate, black ink (`FocusStyle.ink`, `hintInk`, `emberMark`); a picture on it stays as it is. 120 ms on, 180 off |
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
fall back when focus leaves the row or a sheet opens over it, lighting again on its card as the sheet goes, and
jumps while an arrow is held. It is read while drawing; nothing recomposes (`RowFrameTest`). **A row holds framed
cards only**: a control there would show focus twice (`FocusStyleGuardTest`). A wall's cards keep their own frame.

**Above its neighbours.** The focused picture draws on top, so its glow lies over its neighbours, the one it left
above the rest until its light is out, read while placing; a card whose picture is not its root raises its whole
column (`CardColumn`, `CardRaiseTest`).

**Its light.** A picture glows in its own tone (`rememberGlowColor`, sampled once focus rests); a card without
a picture of its own (a person, an artwork choice) glows in the light of the picture under the screen
(`LocalAmbient`). The glow fades over `HlScrim.GLOW_STEPS` rings out to `HlFocus.glow`, the last one clear;
each lit ring is drawn once at its combined alpha (`drawGlow`). Its siblings step back through their image's
colour filter in `RECEDE_STEPS` (`SIBLING_BRIGHTNESS`, `SIBLING_SATURATION`), marks and progress with them,
inside a row or a wall (`RecedeTest`); the filter is read while drawing (`RecedingPainter`), so a picture never
composes again as it steps back, nor its card's surface as focus comes into its row (`rememberCardReading`,
`ArtworkRecedeTest`).

**An arrow held.** While an arrow is held, or pressed again within 140 ms (`ScrubTracker`), focus runs: what it
crosses lights at once, nothing glows, the row's frame jumps; the run ends 120 ms after the last arrow (`Scrub`,
`ScrubTest`).

- **Nothing is brighter than focus**, and ember never marks it: on a focused plate only marks stay lit, in
  `emberOnLight`.
- **Track options**: the focused word is the white plate; the current one is lit, never white unless focused.
  Focus entering a track lands on the current option (`focusProperties.enter`, `SegmentedFocusTest`).
- **Home's grid** reads as it lies: Right from the case goes to the first library's door, Left from a row's first
  door to the case, Up and Down to the door over or under, the one last visited among two (Down from Films: Sagas,
  or Rechercher once visited, `doorBelow`); coming back, focus is on the door left; a door with focus that goes after
  a reload leaves it on the door last visited still there; the first focus waits for the case (`HomeGridFocusTest`).
  Without a grid (a message), the settings tile stands at the end of home's head. A note standing there (titles of a
  folder not found on the NAS, `HeadNote`) never takes focus: Up from the grid's first row reaches its button, and
  once it goes with its note while it has focus, focus goes back to the case (`HomeHeldTest`).
- **Home's first scan** has three stops at most: the settings tile, « Aller à l'accueil », and « Modifier la clé » for
  a refused key. Nothing takes focus as it comes; the button takes it as it comes only while nothing the viewer placed
  with an arrow or OK has it, never from the tile or the key, nor coming back from Réglages, where focus is back on
  the tile; a window made as the pass runs focuses the tile on its own as it is first laid out, which is not the
  viewer's, and the button takes it. Down from the tile goes to the button, Up from it back to the tile, Left from it
  to « Modifier la clé », Up or Right from that back to the button; the wall and the block are read, never chosen. At
  the cut focus is placed once on the case (`FirstScanFocusTest`).
- **Disabled controls** keep focus, ringed, never filled. What is only read (a technical sheet's block, an NFO)
  is not disabled: it takes the ring and OK does nothing.
- **Nothing grows.** Focus reaches `HlFocus.reach` past what it frames, whatever its size: the room a
  container keeps (`FocusReachTest`). One thing opens wider with focus, on one page: a saga's panel (`Volets`, see
  *Layout*). The panel reached opens over `FOCUS_IN` on `focusPull` as the one left closes, the polyptych keeping its
  width, both read from one animation while placing; its picture is cut wider around it, never scaled, and its frame
  and glow stay the sign of focus. A held arrow jumps (`Scrub`), and `VoletDefaults.opensAtOnce` makes every press jump,
  the picture, the words and the room still coming once settled (« greffe 3 »: decided on the Zidoo Z1000 Pro before
  0.4.0, never a setting). `FocusStyleGuardTest` allows it in `Volets.kt` alone and fails once it no longer opens.
- **Focus stays inside the safe area.** Lists stop it `HlFocus.edge` (28 dp) from the top and bottom and pad
  their foot by as much; rows of cards keep `HlFocus.room` above and below for the frame, the glow passing it as
  far as a row draws. Where content fades at an edge, focus stays clear of the fade.
- **Under a sheet, focus is out.** While a sheet is open (`OpenSheets`), the screen under it shows no focus: what
  opened it keeps focus, unlit, a row's card without its row's frame, and lights again as the sheet goes.
- **The queue** (« À identifier »): Identifier opens on a title's suggestions, under « Suggestions », the best focused, TMDB
  asked nothing; a search replaces them. OK on one identifies the title and opens the next in the list's order, its best
  suggestion focused; « Passer », first in the aside, nearest the results (Left from one), opens it unchanged, as
  « Laisser tel quel » and « Rétablir l’automatique » do once done; after the last, Réglages, never an empty list; Back
  returns to the list, on the title the queue reached, never from title to title. OK does nothing for `HlMotion.SETTLE`
  once results come under focus, the first ones and each list a search puts in their place, nor the release of a press
  given meanwhile or before (`calmOk`): a second OK given too soon never accepts what came under it unseen
  (`IdentifyQueueTest`, `IdentifyScreenTest`, `CalmOkTest`).
- **Focus is never lost.** When its element disappears, it moves to a neighbour, else to the action that adds
  one ("Ajouter un dossier"): a title identified, to the one taking its place in « À identifier », a row gone from a
  plate to the plate's first row (`focusGroupKey`). A poster leaving a wall with focus (a title its options mark out of
  a filtered wall, or gone as the wall reads again after Back from its page) leaves it to the poster now in its place,
  else the one before it, read as the change composes, while the poster leaving still holds it, so focus that left the
  posters meanwhile stays where it is (`FocusTakesPlace`); one gone while its page showed is found by the one at its
  place as the wall comes back (`RestoreFocus`'s `inPlace`); the wall emptied, « Retirer les filtres » takes it. In
  « À regarder » the same, row by row: a card's row emptied goes, focus on the row under it, else the one over it
  (`WallMarkTest`, `RestoreFocusTest`).

**Coming back** to a screen finds focus on what was left, by identity, not position (`Focus.kt`):

1. Every element that opens a screen has a stable key (`focusKey`, `FocusKey`), such as `item:<id>`,
   `action:edit`, `section:…/item:42`. If it is gone, its row (`focusGroupKey`) or pane takes focus, else the
   screen's start.
2. The screen keeps a `FocusMemory` (`rememberFocusMemory`) and restores it once laid out (`RestoreFocus`):
   the element if it is placed, else scrolled to, else its group; it then keeps it in view while the screen
   settles. The window's own first focus is ignored, and a reload never moves focus (`RestoreFocusTest`).
3. An action that changes its words stays one element (Lire becomes Reprendre on the same button). Lire and the
   Réessayer in its place are two, one in ember: focus on one goes to the other (`FocusOnPlayAfter`).
4. A row or pane returns to the child it last had (`keepChildFocus`, children marked `rememberedInGroup`,
   `MediaRowFocusTest`). Never `focusRestorer` (it crashes in Compose 1.7 when the list changes) nor a manual
   restore that cancels the move (`FocusStyleGuardTest`).

**Back** goes in two steps (`BackToStart`): deep in a screen, it first returns focus to where the screen starts,
then leaves. Home's grid has no depth: from any door, a first Back says "Appuyez encore sur Retour pour quitter" at
the end of its head (`HomeHead`'s `status`), and a second one within 2.5 s (`QUIT_WINDOW_MS`) quits; the line goes
when that time is out or as soon as focus moves to another door. While the library's first pass runs, on its first
scan as on the grid after it, the line says "Appuyez encore sur Retour pour quitter : l’analyse reprendra quand vous
reviendrez", the scan waiting while the app is away; it takes what the lockup and the tile leave, on two lines at
the largest text, over the head's band and clear of « Aller à l'accueil » (`FirstScanLayoutTest`). Under the launch
screen nothing has been seen yet: the first Back quits (`QuitAgainTest`). A page goes back to the screen that opened
it: home, on the door it came from, or the person's page or the library in numbers that opened a wall
(`PagesNavigationTest`); OK pressed twice before a page shows opens it once (`homeActions`). « À regarder » goes at
once; a wall, from past its first line, first returns to its first poster. Sheets and menus handle their own Back;
during the house-light cue, Back cancels it.

**Menu** (or the Zidoo remote's Pop Menu, sent as F12) opens the options of what has focus, on its way up
(`onMenuKey`), as holding OK does (`pressWithOk`): remotes that send no repeats reach them that way (the
Zidoo's turns a held OK into a click; AVA and Unfolded Circle remotes never repeat). What has options says so while it
shows focus, with the Menu key (`MenuKey`): the remote's key drawn small, its lines (`HlIcons.Menu`) in secondary ink
in a frame of tertiary ink, `HlShapes.mark`, `HlSize.menuKeyWidth` × `menuKeyHeight`; on a card, in its picture's
bottom right corner, white on the marks' dark plate, `HlSpace.xs` from its edges as the state mark is, the state at the
top and the gesture at the bottom, above its progress line, which stays whole, its size at every text size, laid on
the picture so nothing moves as it comes (`MenuKeyTest`, `PlatedMenuKey`); at the end of a result's name
(`HlResultItem`, its options through `HlPanelButton`), its room kept; under a tile on its label's second line, in place of a hint; on home's case
at the end of its action's plate, in the plate's ink. The key shows on the card with focus alone, never under a sheet,
and is not read: accessibility services read the long click instead, "Plus d’options" (`onLongClickLabel`). Options
open in a side sheet about the element, the action wanted most first and focused, so Menu then OK does it; an action
closes the sheet and focus is back on the element.

**Menu on a poster** (#47) opens what makes sense without opening its page (`PosterOptions`, see *Sheets*): on a
wall's posters (Films, Séries, Sagas), « À regarder »'s posters and its stills of Reprendre and À suivre, search's
titles and sagas, and a person's page, wherever the source keeps what is watched; never on home's doors nor its case,
a saga's page, whose actions are in its panels, or a title page's rows, whose episodes keep their own options
(`PosterOptionsTest`, `SagaPageTest`).

## Components

`Hl` prefixes the building blocks that stand for a platform control or container (`HlButton`, `HlSideSheet`,
`HlSettingRow`). Parts and app-specific pieces are named for what they show (`MenuOption`, `PosterCard`,
`BillingBlock`, `TechSpecs`).

### Pictures and titles

| Component | Use |
|---|---|
| `Backdrop` (`Hero`, `Full`, `Quiet`, `Diffuse`) | full-screen artwork under its veils (see *Veils*); a wall's is its diffuse field. Each picture is decoded off screen, then drawn with its veils into one cached layer; one fade at a time, two layers at most (`FadeBetween`, `BackdropFadeTest`). Handed across cuts (`BackdropRelay`), the handed-on picture waiting up to `HOLD` for the arriving one; a screen without backdrop lets it go out over `SLOW` (`LeavingBackdrop`). Only the first picture, in memory under the launch screen, shows at once (`showsAtOnce`). Steps back (`recede`) when focus is lower on the page, to the veil text needs over the whole picture, at least `VEIL` (`ArtworkSample.floorAcross`) |
| `AmbientBackdrop` | a screen on its picture, its light provided as `LocalAmbient` (see *Colour*) |
| `PosterCard`, `LandscapeCard`, `PersonCard`, `ArtworkSurface`, `ArtworkImage` | artwork with the shared focus treatment (see *Cards*); every picture is requested at its laid-out size, a wall's poster at its cell's (`requestedPoster`); one told once it shows (`onShow`) comes without the loader's fade, its caller bringing it in. Names under a card in `cardName`; a wide card's facts follow its name when both fit, else go under it. A person in two titles or more opens their page (`PersonScreen`); a person of a single title does nothing |
| `AbsentCard` | a saga's films the library lacks or not out yet, in release order: TMDB's poster in grey (see *Veils*), else a faint plate with a short rule, the title in `cardTitle` and the year in `badge`, both tertiary, what it is with focus ("Absent de votre bibliothèque", "Sortie le 15 décembre 2026"); framed without a glow, stepping back further than its siblings; OK opens its synopsis, else does nothing; read as its title, its year and what it is |
| `Volets`, `VoletPlan`, `VoletWords`, `SagaVoletWords`, `VerticalName` | a saga's polyptych (see *Layout*): the panels side by side, the one open the one last focused, opening with focus; their widths and a long saga's scroll by `VoletPlan`, pure, the spine pinned; the open film's words and the saga's; an open panel's actions under them, what OK does in its corner on the years' line (`OkHint`); the spine's name upright |
| `HeroInfo`, `TitleArt`, `EpisodeHeading`, `MetaLine`, `BillingBlock` | what a title is: logo or condensed title, facts, credits. Logos are normalised to the same optical weight (`EvenLogo`, `SURFACE` 0.35) and requested as shown (`titleArtRequest`), never enlarged past their file's pixels but above 1080p to the size they have there (`LogoSizeTest`). Without a logo, a title's page sets its name within the logo's box (440 × 112): in `titleSignature` on one line where it fits, else in `display` on one line or two even ones (`TitleArtTest`). A name waits for its picture (`WithItsPicture`, `HOLD` at most) |
| `ScreenTitle`, `TaskTitle` | a screen's title in `headline`. A place's name (a wall, Réglages, Rechercher, a person) is lit in ember, one per screen; a task or a form (Identifier, Ajouter un dossier, a choice of artwork) is white, as a sheet's, in `subject`; never a film's title. `DesignTokenGuardTest` lists the places |
| `TitleLine` | the selected title over a wall: name in `rowTitle` and facts on one baseline, ellipsised |
| `PosterWallGrid` | a wall of posters, a library's or a person's: `HlLayout.WALL_COLUMNS` across the safe area, each poster as wide as its picture is requested (`requestedPoster`, `PicturesAhead.wall`); a scrolled line comes to where the first rests |
| `FormatLines`, `Badge`, `FactRow`, `Score` | formats in the technical voice, nothing said twice (`Formats`), read from the file (`MkvProbe`), with the frame rate and the best track's bitrate: on a title's page, a case's back open on the picture like the facts above it (`plate`), a line for picture and one for sound, each after its grey glyph (`HlIcons.Display`, `HlIcons.Sound`, named for screen readers), the formats in white `jaquette`, the codec in grey `jaquette`, and their measures (frame rate, channels, bitrate) in a secondary `meta`; the sound is the track Lire asks of the player, its language last among the measures, else the file's best track; in a version row, unframed words, measures in `meta`. A `Badge` is a printed mark, never a format or state. A score is a quiet fact |
| `HeadOverview`, `SynopsisSheet` | three lines of overview; when cut, focusable and opening the full text in a side sheet; a saga's film the library lacks opens its own, its facts saying what it is (`SynopsisSheet(facts, tagline, overview)`) |
| `MediaRow`, `FilmsOfHeading` | a horizontal row under its header (`SectionHeader`, in `rowTitle`) with its `count` ("1 film sur 4", `filmCount`) and its `news` (an ember dot, "3 nouveaux": the cards wearing the new dot, `wearsNewDot`), and the change it is `pending` by the pending dot, never ember ("Classement des épisodes…" while a show's episodes are placed in another order, its cards kept until the page reads again); focus returns to its last card. Its cards share one travelling frame and it holds framed cards only (see *Focus*). Library rows (`LibraryRows`) bill a director's films or the best rated |
| `VersionsSheet`, `VersionRow` | several copies of a film are one title; the Versions button lists them and sets which one Lire plays; the current copy carries the ember check and "Votre choix"; from a source that keeps no choice the sheet only reads, says the server plays its first copy and marks none; Jellyfin keeps none, the app keeping its choice while it runs, the server's first copy until one is chosen. In a folder each copy resumes where it was left, never at another's place (two editions may share a title), and Lire plays the copy chosen here, the latest choice made on the film, else the copy started and not watched that was played last, else the best (`VersionRank.played`): a copy added to a film begun keeps it where it was. The film is that copy on its card and page, in Reprendre, the En cours filter, its saga and À suivre, and for Trakt, and the sheet marks it whether chosen or not; the film is watched as soon as any copy is. A Jellyfin film's copies all resume where the film was, the one place the server keeps for it |
| `TracksSheet`, `tracksHint` | the audio and subtitles of what Lire plays, as the player numbers them (`TrackPlan`); the Captions tile after the copies, its second line saying what Lire asks of the player ("Français, sous-titres forcés"), shown when the file's track order is known and there is a choice to make. A wide side sheet in two columns, audio then subtitles, each headed by "Selon vos réglages" and then the file's tracks in order; the choice applies at once, the sheet staying open, and holds for the film's copies or the whole show. Settings > Lecture sets the same for every title (audio language, subtitle mode, subtitle language), "Selon le lecteur" by default |
| `SearchQuery`, `LetterGrid`, `ResultsHeading`, `ResultName` | library search (`SearchScreen`), a place in the light of the result in focus (`Diffuse`), kept while it is found, a person leaving it: the typed query on a field the keys always type in (the ember ring, a still raspberry cursor), an on-screen keyboard of square tiles (`LETTER_KEYS` A to Z then 0 to 9, Erase and Space), all glass in the picture's dark, white with focus; results in rows (titles, sagas as wide images, people), each counted, the result in light named under its row; accent-insensitive, debounced 150 ms; a search that failed says why with Retry (`HlErrorState`), the keys keeping focus |
| `PosterWall`, `PosterWallScrim`, `HouseLights`, `CardSkeleton`, `HeroSkeleton`, `ProgressLine` | the welcome wall (nine tilted columns at `HlScrim.WALL`, drifting over `WALL_DRIFT`) and its black veil; the playback cue's black; placeholders shaped like what is coming (after `SKELETON_DELAY`, `skeletonDelayPassed`; never a shimmer); progress lines in the ember light, stretched over the part seen: burning at a picture's foot (`HlSize.progressPoster`, `HlSize.progressWide`) on a black track, beside text, in a hero and on home's case on `FILL_STRONG`; on a focused plate, solid raspberry on the plate (`focused`) |
| `HomeHead`, `HeadNote`, `DoorGrid`, `ResumeCase`, `HomeDoor`, `DoorStill`, `DoorPosters`, `DoorFigures`, `DoorSagas`, `DoorWall`, `DoorFacts` | home's grid (see *Layout*): its head and a note standing at its end, the grid that lays and links its doors, the case, a door and the pictures it holds: a still with its logo, a band of posters, a count cut in posters (`CutPosters`), two sagas stacked, a wall of posters out, the app's state (`StateFact`) |
| `PosterStrip` | a folder's latest posters in fixed slots, empty frames for the free ones; without posters, its name set like a one-sheet |
| `FirstWall`, `FirstTitle`, `FirstWallState`, `FolderBlock`, `GoHomeButton` | home's first scan: the titles found on two whole lines of four `wallPoster` frames, empty frames first, first found first, a poster or a one-sheet (`FallbackArtwork` on the ground's step), each arriving as *Motion* says; full, renewed in place, the latest title found coming into the oldest frame at each step, round after round, none while one waits for its picture; not focusable, read as how many titles were found ("1 240 titres", `titleCount`). The folder's block: « Lecture du dossier… » then « Analyse du dossier… » in `lead`, the name to its measure (see *Type*), where it is and its printed protocol, the Counter at `heroProgress`, the file under it, or while the folder is listed the videos found so far in its place (« 1 240 vidéos trouvées… »), each line keeping its room. « Aller à l'accueil », a secondary button, with « L'analyse continue en arrière-plan » under it in `meta`, read out as one, taking focus as it comes only while nothing has it |
| `Showcase` | a library folder in its posters' light (see *Secondary screens*): its name in `subject` beside its printed protocol, its facts, the first in white, and its state by its light (`stateLight`): the white dot ("Analysé il y a 3 heures"), the ring while titles of it are held ("12 introuvables", in its last scan's place, answered or not); or what is happening to it (`status`); framed with focus, without a glow |
| `GalleryTile` | one of a title's images to change, at its shape of use, a logo fitted on a neutral step; its name under it in `cardName`, white with focus; framed and glowing in the film's light |
| `PosterField` | the room of the settings and their forms, under them: a library's posters as a wall's field (see *Veils*), its light theirs (`LocalAmbient`) |
| `ArtworkChoiceCard`, `SelectedMark` | one picture of the contact sheet to choose, at its shape of use, a logo fitted in its box on the ground's step, a failed one set in type; the one in use carries the ember check. Nothing under it, its section saying its language, but a `name` in `cardName` and its `detail` in secondary `meta` (« Automatique », a saga's film and its year), as wide as its column and its gap, or two when the next has none (`captionWidth`), faded across the safe area's margin |
| `ArtworkPrint` | the contact sheet's print (« l'épreuve »): the choice with focus shown large beside it, at most its width at its shape and as tall as its column leaves it, never focused, decoded at its width; the picture it showed kept whole, its check and its words with it, until the next is decoded, then cut, nothing fading or growing; the ember check for the one in use; one line under it, kept when empty, its name in `cardName` and its detail in secondary `meta` on its baseline, the longer giving way |

### Controls

| Component | Use |
|---|---|
| `HlButton` (`Primary`, `Secondary`), `HlIconButton`, `HeaderControls`, `HlActionRow` | actions 44 dp tall, radius 6. The primary one, Lire or Reprendre and nothing else, is the ember light in black ink (`onEmber`), `HlIcons.Play`, a duration in `jaquette`; focus frames it. A detail of several facts (a film, then its time left) drops whole facts from its end where the button is given less room than it needs, the first alone ending in an ellipsis. A secondary one is a plate by its ground (over a picture `plate(light)` edged by `plateEdge`, glass on the room, `FILL` on glass) that focus fills white. `HlIconButton` is a square tile (44, its glyph `HlSize.tileIcon`) showing its label below on focus, on a plate as wide as `tileLabelWidth` allows, at most `HlIconButtonDefaults.labelEdge` from the screen's side, hidden while a sheet is open; `HlActionRow` keeps room for it. In a header (`HeaderControls`): buttons 40, tiles 40, glyphs 20. Toggles name what OK does and show the state with the icon (`lit`: the glyph in ember, raspberry on white; `HlIcons.CheckFilled`). On a show's page with a season track, the watched toggle marks the season shown when it holds episodes ("Marquer la saison comme vue", `shownSeason`); Menu or a long OK opens its options (`ShowMarks`): "Marquer la saison 2 comme vue" or "non vue", then "Marquer la série comme vue" or "non vue", each as it stands. The Menu key under its label says so, on a hint's line (`hintRoom`), which a show's `HlActionRow` keeps (`hinted`); a tile without options may say a second line there in tertiary `meta` (`hint`, the tracks tile's), bound as the label. On a show of one season, without a track, the watched toggle marks the show, its one season, and says so ("Marquer la saison comme vue", `soleSeason`), with no options; films and episodes have none on their tile either. `detailLit` lights a chosen value in the detail. Disabled: a faint well (over a picture, its plate), tertiary ink, ringed by focus, OK ignored. `busy`: a pending dot and `busyLabel` ("Connexion…"), OK ignored; a button with a busy label takes from the start the wider of its words at rest and busy (`reserveFor` another busy label it shows in turn: Enregistrer tests, then saves), so neither it nor what stands beside it moves as they change (`ButtonWidthTest`) |
| `HlSegmented` | where you are among a few places, as words in `place`: seasons (`scrollable`, chosen as focus moves with `selectOnFocus`, each with its `SegmentDetail`), NFO views, form choices. The current word is lit in ember (`placeCurrent`), the others grey, on no plate; focus is the white plate, with a raspberry dot (`HlSize.wordDot`) before the current word. Words line up with the start edge and plates hang past it, each word as wide as when current and set from its start: nothing moves as focus or the choice moves. Focus entering lands on the current option (`enterAt`). Exposed as tabs |
| `HlTextField` | a glass plate `HlSize.field` tall edged by a hairline (`field`), its name in white over it, ringed white with focus, keyboard on OK, "OK saisir" hint (`KeyHint`); OK starts typing after the text, where the keyboard's erase reaches it all, and at rest the text shows from its start; while typing, the ember ring and a raspberry cursor, which the remote's Left and Right move a character once Back hid the keyboard (shown, it takes the arrows): held they repeat and stop at the text's edge, a press from its start or its end leaving that way, as Up and Down leave at once, and OK shows the keyboard again at the cursor; a keyboard's own Left and Right, the one shown or one plugged in, move it as in any field, Shift selecting, and never leave. Below it in `meta`: `supporting`, or `error` (`AttentionNote`), the field's own failure, which its form says there and brings focus to. Empty and focused, its example ends before "OK saisir", never under it. While it holds text and it or its cross has focus, a cross lies at its end inside its frame (#78, `ClearTile`): a header's tile, `HlSize.tileCompact` with its glyph `HlIcons.Clear` at `HlSize.icon`, `HlSpace.s` in from the edge and centred in the field's height, its glyph grey on the glass at rest, the tile white with focus; the text, its example and "OK saisir" end before it, and no form's width changes; a field without focus has none, so a form at rest stays bare. Right at rest reaches it, as Right from the text's end while typing does, ending typing; Left from it comes back to the field, Right goes on to the next, Up and Down leave as from the field, which names it as its Right (`focusProperties`), Compose's search for the next focus not looking inside a frame; the field beside lands on the field, never on its cross. OK on it empties the field as erasing it all would (`onValueChange("")`), nothing else running, focus back on the field at rest, the keyboard hidden, so that OK opens it; the keyboard's « Suivant » goes on to the next field, never to the cross. A screen reader reads it as a button, "Effacer" and the field's name without its "(facultatif)" (`field_clear`); a password has one too, and emptied says again that the one saved stays. Focused at rest, "OK saisir" shows only where it leaves the text whole before the cross: else it gives way, the white ring saying the focus, so that a path reads whole up to its cross at every text size; a text longer still stops at the cross, shown from its start (`TextFieldClearTest`). A short field takes the width its whole name, or its widest value with "OK saisir" and its cross, needs at the text size in use, never under `HlLayout.fieldShort` (`rememberFieldWidth`: Identifier's « Année (facultatif) », « Ex. : 1999 », an empty year searching every year). Its label is always read out, shown or not (`showLabel`), "(facultatif)" after it when `optional`; `password` hides the value. Placeholders are examples from resources. Saved when left, never while typing |
| `HlMenuButton`, `MenuSection`, `MenuOption` | a sectioned menu anchored to its button (sort, filters), on raised glass (`panel`, 320 wide), opening below or above within the safe area and growing from `ENTER_SCALE`; options 40 tall, the chosen one white with its ember check (raspberry on focus's white plate), the others grey; a sort applies once the menu has closed, filters at once with the menu kept open (`closeOnSelect`) |
| `HlScrollArea` | long content read with the remote: focusable only when scrollable, or always where it is what a sheet is read for (`alwaysFocusable`), ringed white with focus, `HlSize.scrollStep` per press, a held key adding steps; in a sheet a group's plate in the film's light; `HlScrollArea(lines)` lays out only the lines shown of a raw text in `code`, each where the whole text would set it |
| `QrCode` | an address for a phone: black modules on projector white at whole pixels (`QrCodeDefaults`), the address below in `spec`; `compact` beside words in a pane, the address centred in secondary `jaquette`; `small` in a sheet that says more under it, `compact`'s modules and the address as it is. A `qrencode` vector, or a text known only at run time encoded here (`QrModules`: level M, the smallest version up to 6, an address in capitals in alphanumeric mode, so the log's fits version 3). Never on artwork |

### Panels, lists and settings

| Component | Use |
|---|---|
| `HlPanel` (`Quiet`, `Raised`, `lit`), `HlPanelButton`, `HlRowSurface` | the one panel recipe (`Raised` is glass) and its focusable form, framed, raised the same way, its options opened by Menu or a held OK (`onLongClick`, read out as `onLongClickLabel`); the row menu options, navigation entries and setting rows are made of |
| `HlListItem`, `HlResultItem`, `PosterThumb` | list choices on a panel button, raised over the welcome wall, what one does in `meta`; a search result with its poster and its overview in `meta`, the one in use edged in ember ("Actuel"), a `note` on its own line under its facts, which no fact gives way to (the title one to identify may be, "Peut-être « Dune » (2021)"), with options the Menu key at the end of its name's line while it has focus, its room kept; a small poster at a width, or as tall as it is given (a sheet's header) |
| `HlPlate` | the plate of a group (see *Secondary screens*): its field's dark on a `PosterField`, `glassPlate` on glass, glass on the bare room, read while drawing; what it holds lies on glass |
| `HlNavItem`, `HlSettingGroup`, `HlSettingRow` | settings: the places, words alone (`navRow`), no well nor chevron, focus opening the one it settles on (OK at once), the open one white with its icon lit (raspberry with focus); a group's rows on one plate, no rule between them, white with focus, no chevron: OK does what the words say. A row is `row` 52 at least in a pane as in a sheet, an icon included, its name in `entry`, its description in secondary `meta` on one line beside its value (`PaneRowLineTest`), its value in `meta` or `jaquette` (`valueStyle`); a value it only states is not focusable, 44 tall. A value lights with `valueLit` or `valueAttention`. A state the row says in its value's place (« Vérification… », « Non autorisé », « Connexion… ») follows its light (`valueLight`), ranged right as values are, quiet, never ember; the light alone where there are no words (a search under way). Disabled in a `FILL_FAINT` well, in a pane only (`SettingRowTest`). What a pane only states is a `FactPlate`, not a row. A pane's secret that can no longer be read is said at its head by an `AttentionNote` asking for it again; one the Keystore did not open for now by a `PartFailed` whose Réessayer reads it again, Add mapping disabled meanwhile so that no mapping is written over those kept |
| `FactPlate`, `PlateFact` | what a pane states, as a case's back (see *Gestures*): facts across a plate, each label over its value, then a note; nothing in it takes focus |
| `HlChoiceSheet`, `HlChoiceSetting` | a setting's choices on one plate in a side sheet, what they are about in its `facts` (a show's title and year): the current one checked and focused; choosing applies and closes. A change that failed says so in the row ("Non enregistré") |
| `EpisodeOrderSheet` | the order a show's episodes follow, from Modifier's row "Ordre des épisodes" (`HlIcons.Sort`, the one in use on its line: "Automatique · Saisons TMDB", "DVD, d’après le NFO" (Automatique by its NFO, so "Diffusion d’origine, d’après le NFO" holds on one line beside the icon), "DVD · DVD Order", "Saisons TMDB"), shown for a show of "Mes fichiers" TMDB numbers more than one way, once a scan listed its groups: an `HlChoiceSheet` about the show, Automatique first, said by what it resolves to, then TMDB's seasons ("1 saison · 14 épisodes") and its groups by their kind in the app's language over their TMDB name and count ("DVD Order · 14 épisodes"); the one in use and Back change nothing, focus back on Modifier's button. Another is applied in the app's scope, the episode row pending meanwhile (`MediaRow`), a failure said at the end of the page's actions. Read from the library alone, never TMDB: the page opens whether TMDB answers or not |
| `traktPane` | Trakt (`HlIcons.History`), with "Mes fichiers", a state said beside its title by its dot: not connected, what it keeps to read and the row that connects; connecting, the `DeviceCodePlate` in the pane, never ember, Back cancelling, focus going to the code from its row only and returning to the row; connected, a plate with the account's name in `subject`, its last sync, and what it holds watched and in progress in `spec`, then the rows that sync now (disabled while a sync runs, which its value says, a failed exchange said under it) and disconnect, the one row whose words follow the account so focus stays on it. Between them, while the last sync that read Trakt left entries out, a row counts them ("2 éléments ignorés"), never in ember: the sync went through. Its sheet (`SkippedSheet`) says why, lists the entries by their title and year, ten at most, then on one line a kind what it leaves unnamed, the rest past ten ("Et 590 autres") else those naming nothing ("1 élément sans titre"), over where Trakt keeps it, on a `ReadingPlate`, then the marks a history read in part holds back and how to fix it, its label set as a name; nothing takes focus but, in a sheet taller than the screen, the plate, which Down rings and the remote reads the sheet through from. An `AttentionNote` once Trakt ended the connection; a build without Trakt's keys says so beside the title. Under a server, while it keeps an account (connected, or its session not opened at this launch), Trakt pauses: "En pause" by the pending dot, "Trakt suit vos dossiers ; il reprend quand vous y revenez." read in place of what it keeps, the account's plate when its session was read, its last sync alone while a name Trakt could not say waits for the folders (nothing is asked of Trakt meanwhile), then disconnecting, the one row, no note asking to connect again; disconnected there, its place leaves the settings and the place after it opens, focused (`SettingsPlaces`). With the folders, a session kept that the Keystore did not open at this launch is neither connected nor not: under the `PartFailed` whose Réessayer reads it again, "Non relu pour l’instant" by the pending dot, "Votre connexion à Trakt est conservée sur ce lecteur, mais n’a pas pu être relue pour l’instant…" read in place of what it keeps, then disconnecting, the one row, nothing to connect over the session kept nor to sync; read, it is connected, focus kept on its row |
| `ToIdentifyScreen` | « À identifier »: in Réglages › Bibliothèque, the last row of the actions' plate but the files left out (`IgnoredFilesSheet`) while titles are to identify (TMDB found none for them, or several none of which is clearly ahead), "Non reconnus avec certitude", their count in white `jaquette`, never ember (no `valueAttention`): nothing failed, a choice is left; gone, from under focus or while its list was open, focus is on « Analyser maintenant » (`ToIdentifyRowTest`). OK opens the list, an `HlTwoPane` (`sideWide`): the task's title in white and what it does, then the count and a result a title (`HlResultItem`), the latest added first: its poster or an empty frame, its name, its year and why as facts, what it may be as its note, where it lies as its overview (the folder, then the file or the show's folder). Focus opens on the first title, stays on its title as the list reloads, and goes to the one taking the place of a title identified, else the last; Back from past the first title returns to it, then to the row (`ToIdentifyScreenTest`). OK opens Identifier in the queue (see *Focus*); Menu on a title TMDB hesitated on opens a side sheet titled Identifier, its name, year and why by the ring, then the group Suggestions: the three best, each its overview on one line and its year as value, no poster, the best first and focused, so Menu then OK accepts it, no search asked, one not saved saying so over the titles ("Non enregistré" and why) while its title is listed; a title not found on TMDB has no Menu key, OK alone leading on. Empty, it says so ("Rien à identifier"), and Back leaves it |
| `IgnoredFilesSheet` | The videos a shows folder holds that the scans place nowhere, their name giving no episode number or no show: in Réglages › Bibliothèque, the last row of the actions' plate, after « À identifier », while there are some (`IgnoredFilesRow`), their count as its title ("3 fichiers ignorés"), what they have in common under it ("Leur nom ne permet pas de les ranger"), never in ember: the scan went through; Menu does nothing. Gone, from under focus or while its list was open, focus is on « Analyser maintenant » (`IgnoredFilesRowTest`). OK opens a side sheet built as `SkippedSheet`: the count as its title, why they are not in the library, then on a `ReadingPlate` each file by its name in `entry` over, in secondary `meta` on one line spaced as facts (`FACT_GAP`), why first ("Aucun numéro d’épisode", "Aucun nom de série"), then its folder and where it lies in it, so a long line cuts the place, not the reason; ten at most, the rest counted ("Et 590 autres"); then how to fix it, its label set as a name (`FixText`), its examples never broken at a line's end. Nothing takes focus but, in a sheet taller than the screen, the plate, which Down rings and the remote reads the sheet through from; Back closes it, focus back on the row. A scan placing them all while it is open leaves it on the files it shows until Back. The list is kept by folder and written only when it changes; a folder out of reach keeps its last |
| `UpdatesGroup` | About, between État and Générique, "Mises à jour", in the project's release alone (`UpdaterAvailability`): the state row, its title the installed version ("Houselights 0.4.0"), which never changes while its words follow the update ("Vérifié il y a 2 h" and "À jour" while GitHub's answer is under two days old, "Version 0.4.1 disponible" and its size, "Téléchargement de la 0.4.1…" and its count, a failure's title and "Échec" in ember, "Requise" in ember while Android must allow the install, "Dans 20 min" while GitHub asks to wait), OK always opening the update sheet, as `JournalRow` does; then « Recherche automatique » (Une fois par jour, Jamais) and « Versions proposées » (Stables, Stables et bêta, following the installed build until chosen), each a `HlChoiceSetting` |
| `TrustRow` | About, under État, only while playback refuses the player as changed: « Faire confiance au nouveau lecteur », "Lecteur vidéo 6.4.42, mis à jour le 12 octobre 2026" ("installé le" for an app in its place), on its own plate, so the pane's first row and its first focus; OK opens the trust sheet (`PlayerTrustSheet`). The state stays a `FactPlate`, its note unchanged ("A changé depuis la dernière lecture" and the package); never for another refusal (not a Zidoo, a certificate unreadable), which trusting would not make safe. The player is read again each time the sheet closes and when OK on the row finds nothing to ask (its updates uninstalled meanwhile): no longer changed, trusted or not, the row leaves, focus going on to the pane's next row first, and the player reads "Détecté" (`AboutDeviceTest`) |
| `JournalRow`, `JournalSheet` | About, under Soutenir Houselights, "Envoyer le journal" ("Pour signaler un problème, depuis votre téléphone"): a QR code sheet whose page, served on the home network while it is open and ten minutes at most (`JournalServer`), shows the app's own log on a phone (`AppLog`), each line masked (`Redact.text`); the sheet closes with the page, and both once the app leaves the screen |
| `HlTwoPane`, `HlFormScaffold`, `StatusPanel`, `FieldLabel` | two-pane screens (navigation or explanation left, content right, a gap and no rule between); a form lies on the settings' posters (`room`); `StatusPanel` shows where a setup stands beside its form, on a plate, its state by the white dot, a failure by the ring, its message carrying the one mark, a server found by its name, its protocol printed and its kind a fact; a server found picked, the protocol its check verified and the connection in Réglages' words ("HTTPS", "Connexion chiffrée"), "Vérification…" alone while it is checked. Jellyfin's sign-in opens on the first server found, waiting up to a second for discovery's first answer as home's first focus waits for its case, else on the address, servers answering later coming under those shown, focus left where it is ("Choisissez votre serveur ou saisissez son adresse…" once one is found); its account and Se connecter scroll as one block; `FieldLabel` names a track of choices in `group` |
| `ConnectionState` | how a server found will be reached, beside its name, as its check says (`ServerCheck`): the lock closed and white, "Chiffré", only when its HTTPS address answered as that server with a certificate the player trusts; open and grey, "Non chiffré"; the quiet dot, "Vérification…"; the ring, "Ne répond pas", the row still a choice, which signing in checks again first ("Vérification…" meanwhile). Never ember: none is an error or a choice. Its column as wide as the longest of the four at the viewer's text size, the check's place kept after it on every row (`ChoicePlace`), so states line up; the address under the name is where signing in reaches it |
| `HlDivider`, `HlStatus`, `AttentionNote`, `HlMeter` | a hairline in `contentDecorative` between a menu's sections or a panel's parts, never in a pane or a sheet; a state with its light (`StatusState`: on and done the white dot, off its ring, pending a quiet dot, attention its circled mark) and its reason in `meta`; attention beside what it concerns, in `meta`, in focus's ink on a focused plate, its mark raspberry, on one line where it takes a line of facts; a segmented gauge inked white and grey (`MeterInk`), never ember |
| `SpecTable` | several of a kind a pane states, as a table in `meta` on a plate, the one in use by its white dot (see *Gestures*) |
| `TechSpecs`, `TechSheet` | the technical sheet in a side sheet, its file's container and size among its facts ("MKV, 23,4 Go"), read as a case's back, block by block, each a `ReadingPlate` named in `group`: Image, its formats as marks in `spec` (Ultra HD, Dolby Vision, HEVC), what measures each under it in secondary `meta`; Son and Sous-titres, a track a line, its format in `spec` in a column as wide as the block's widest, 188 to 280 (`TechSpecsDefaults`), before its language in white, the file's default after the white dot, a state, then what measures it; Fichier as `PlateFact`s, its name whole under them. A value speaks in the voice of its kind (`TechValue`). A block taller than the sheet is scrolled through before focus leaves it. Its content is decided in `TechSheet`, pure and tested; only a title with a file has its tile |
| `HlSideSheet`, `SheetScope`, `SheetConfirm` | side sheets (see *Sheets*): glass, its free corners `sheet`, a hairline on the screen's side, `wide` to read; it comes 24 dp in as it lights (`FOCUS_IN`, `PLATE_IN`), read while drawing |
| `HlEmptyState`, `HlErrorState` | the intertitle of an empty or failed screen, its action a plate (ember is for Lire and Reprendre), focused as it appears (`EmptyStateFocusTest`). A `UiError` brings its own title, message and action (`ErrorAction`), the library out of reach titled by where it lies (`LocalLibraryPlace`): "NAS injoignable", "Serveur injoignable", a disk of the player's own unplugged "Disque introuvable" |
| `ScanProgress` | a running scan: what is read, the ember line and its count, the current file |

## Cards

Artwork carries no text: the top of the screen names what has focus. A saga's panel (`Volets`) is no card but a case,
built as home's (`ResumeCase`): a picture with its words on its own dark. Closed, it carries its number, its mark and its
year on the year's band; open, its words on their melt (see *Veils*), as the case does.

- **At rest**, a faint edge (`EDGE_ARTWORK`) keeps dark artwork from dissolving into the room.
- **One mark at most**, top right `HlSpace.xs` in (`markCorner`), on the marks' dark plate (`MarkPlate`, `HlShapes.mark`,
  `VEIL`, receding with the picture): a title held while its file is missing (among many of its folder
  gone at once, `MediaItem.held`) by the ring of a state that is off, white, never ember, first, so a held title shows
  no check nor dot meanwhile; else unwatched episodes, else the check of a watched title,
  else the ember dot of a new title (added in the last week, not watched; ringed in black). Progress is the
  ember line burning at the foot, 4 dp on a poster and 5 on a wide image, its light stretched over the part
  seen. Formats are never a card mark.
- **Two corners**: the state at the top right, the gesture at the bottom right, on the same plate. A card with options
  shows the Menu key there while it has focus (see *Focus*, Menu), above its progress line; it may cover a corner of
  what a poster prints at its foot, its title sometimes, only on the card with focus, the line above naming it.
- **Without artwork** or on failure, the card is set like a one-sheet: a short rule, the name in `cardTitle`,
  the year in `badge` and secondary ink, readable on a wall's field. Pictures without a title (`PosterThumb`, free `PosterStrip` slots) and
  loading cards (`CardSkeleton`) show an empty frame: one step up (`groundStep()`), the artwork edge and a
  short rule in `contentDecorative`. A person without a portrait is the bare frame, the name under it.
- **Episodes** read as a number in tabular figures, a name, then length and progress. OK plays one; Menu or a long OK
  opens its options (`EpisodeOptions`), which the Menu key in its still's corner says on the card with focus alone, as
  on a poster: a side sheet titled with its name, "Saison 1 Épisode 2 52 min" under it (the season by its number,
  in the app's language, specials by the source's name), then "Marquer comme vu" or "Marquer comme non vu", focused;
  "Lire depuis le début" once started; "Audio et sous-titres" where the tracks apply, as the tracks tile, saying what
  Lire plays and opening the show's; last, for an episode held while its file is missing, « Retirer de la
  bibliothèque », it alone, as the title page's tile. A card with none of these opens nothing.
  A mark is made with no question, its season's count, the show and Lire following at once, a started episode back to
  its start, as the watched tile does. A mark that did not save comes undone and is said under the row, in place of
  the overview, until an OK there.
- **People**: the name right under the portrait, the role under it.
- **With focus**, artwork keeps its size, in a row as on a wall, a portrait as a poster: its frame and its glow
  lift it.
- **Stepping back**, siblings lose colour through the image's own filter in `RECEDE_STEPS` steps; no layer is
  drawn per card.

**Accessibility.** A card is one element: its title, then its state in words (`stateDescription`: "introuvable",
"6 épisodes non vus", "vu", "nouveau, en cours, 40 %"), a saga's panel after its number and year, then its words; a
card with options, its long click too, "Plus d’options". Marks and the Menu key are not read.
A person who does nothing is read once as "name, role", not as a button. What is chosen says so (`selected`).

## Sheets

A sheet comes over the room behind our own scrim, closes with Back (no Close button) and leaves with the
motion it came in with. Focus stays inside and returns where it was (`SheetFocusTest`).

- **`HlSideSheet`**: a full-height panel against the right edge (520 dp) under a lighter veil (`VEIL_SIDE`).
  From the top: what it does in `subject`, and what it is about in a line of facts (its name first, in white, then
  the year), printed marks first (a protocol, `Badge`), its artwork as tall as both as the title sets beside it, on
  one line or two: the title gives up its width, never the artwork; a status; then its plates, `HlSpace.m` apart, in
  the film's light (`glassPlate`) on its glass in the film's dark (`sheetGlass`), fading into the sheet where there is
  more, never under its head; focus near either end scrolls to it (`SheetScroll`), so a group's title above the first
  row and a note under the last are never left under a fade (`SheetEndsTest`). No hint: Back is the remote's own. Every action row describes itself in one line, so
  rows keep one height (`PaneRowLineTest`, in every language, at the default text size and 1.3), and lies on a plate.
  Modifier shows the images it changes as a gallery (`Gallery`), Identifier on a plate under them, and under it, for a
  show TMDB numbers more than one way, Ordre des épisodes (`EpisodeOrderSheet`), which closes Modifier first. A saga's
  has its poster and its backdrop alone, its years as its facts: no logo, TMDB giving a collection none, and no
  Identifier, a saga being named by its films (`EditMenuTest`). Its lines fall
  within 3 dp of the board's baselines (`SheetRhythmTest`).
- **Reading sheets.** The synopsis keeps the standard width (520): its text in `body`, 440 dp, about 64 characters a
  line, under its tagline in `lead`, set `HlSpace.l` + `HlSpace.xxs` from its baseline to the head and the text, its
  director among its facts. The NFO is wide (`HlSideSheetDefaults.wideWidth`, 736), set `safeH` in on both sides, so
  its XML keeps the reading measure (`HlLayout.readable`): how it reads after its facts, its views (Informations, XML) as
  places at the end of its title's line, its details as definitions (a label in secondary `meta`,
  `HlLayout.readingLabel` wide, beside its value in `body`, `HlSpace.fact` apart) or its XML in `code`, on a plate
  focused to be read.
- **`SheetConfirm`**: an irreversible action asks in the sheet, as an intertitle in place of the rows: ember
  rule, the question in `subject`, the consequence, then Cancel (focused) and the action, its label in ember.
  Never red. Cancel or Back returns to the row that asked.
- **A poster's options** (`PosterOptions`, on the episodes' `OptionsSheet`): titled with the title's name, an
  episode's by its show's, and under it its facts, where it stands, so the gesture reads before OK ("1988   2 h 48   Il
  reste 1 h 14", "2 saisons   6 épisodes non vus", "2021 – 2024   2 films   1 film vu sur 2"). Its mark first and
  focused, in its page's words, watched or not as it stands, the check empty or filled: a film's "Marquer comme vu", a
  show's whole, "Marquer la série comme vue", a saga's with its films, "Marquer la saga comme vue"; then a film's
  or a show's favourite, "Ajouter aux favoris" or "Retirer des favoris" by the heart, empty or filled; then
  "Modifier", where the source keeps the title's pictures, the title page's own sheet (`EditMenu`), opened as this one
  closes, a sheet never opening over another, its images and Identifier opening their screen, Back from it on the
  poster. A saga's has its mark then Modifier, its poster and its backdrop (`editsFromPoster`), from a wall, search
  and its page's own Modifier tile; a Jellyfin box set's, its mark alone, its source keeping no pictures. No tracks, no facts of its files, no Lire: OK on the poster opens its page. Each row closes the sheet, then
  acts; Back does nothing. A mark goes through the source as from the page (`setPlayed`, so Trakt follows), a
  favourite too (`setFavorite`), shown at once, and comes undone when it was not saved (`PosterChanges`,
  `TraktSyncTest`). Two departures, said: the sheet is titled with what it concerns, as an episode's, where a side
  sheet says what it does; and a show or a saga marked unwatched whole asks no question, where an irreversible action
  asks (`SheetConfirm`), as the page's tile does: a mark is done again in one OK, only Trakt's dates of plays not
  coming back.
- **A question of two answers** that is not irreversible (`HeldSheet`) is a sheet of its own: what it is about in its
  head, what the app sees and what each answer is for in `body`, then the answers on one plate above what they concern,
  so they stay in view at the largest text size, the one that changes nothing first and focused, each saying in its line
  what it does; nothing in ember, no second question. Back answers nothing. What they concern lies under them on a
  `ReadingPlate`, ten at most and how many more ("Et 2 autres"), that Down rings and reads the sheet through from.
  « 12 titres introuvables »: the folder by its printed protocol and name, « Garder » ("Ils restent en attendant leurs
  fichiers"), then « Retirer » ("Rien n’est effacé du NAS, ni ce qui a été vu"), then each title and its year (an
  episode by its show and number) in white `entry` over its file as the NAS names it in `meta`, two lines at most.
- **The trust sheet** (`PlayerTrustSheet`), « Faire confiance au nouveau lecteur », is such a question, opened only by
  what fixes a player refused as changed (`ErrorAction.TRUST_PLAYER`, see *Messages*), none of whose entries trusts
  anything. Its head names the player by its name and version as its update declares them ("Lecteur vidéo",
  "6.4.42"), "Autre signature" after them for an app signed otherwise under its name, never its package; then, in
  `body`, what trusting means, the day Android installed it in the sentence, where it never drops ("… son lecteur le
  12 octobre 2026 : jusqu’à sa prochaine mise à jour, il recevra à chaque lecture les mots de passe de vos dossiers.
  Sinon, désinstallez les mises à jour du lecteur."), Jellyfin's access named only while Jellyfin is the source; an app
  in the player's place has its own ("Ce n’est plus le lecteur qui a reçu vos mots de passe…"). Then « Ne pas lui
  faire confiance » ("Il ne recevra aucun mot de passe"), first and focused, and « Faire confiance à ce lecteur » ("Il
  recevra vos mots de passe"), each on one line at 1.3 (`PaneRowLineTest`). A departure from `SheetConfirm`, said: a
  password confided is not taken back, as the irreversible is not, but nothing in the app is erased and the trust ends
  by itself with the player's next update, so it is a question of two answers, nothing in ember, which would say a
  destruction. Trusting takes a Down then an OK: OK there does nothing for `HlMotion.SETTLE` after focus arrives, nor on
  a key let go there that went down elsewhere, as the update sheet's row, so the OK that opened the sheet, given again
  too soon, falls on the answer that changes nothing. It pins the player it named alone: changed again meanwhile,
  nothing is pinned and it names the new one, its safe answer focused again (`PlayerTrustSheetTest`).
- **The technical sheet** is read block by block, each ringed with focus and scrolled through when taller than the
  sheet; OK does nothing.
- **The update sheet** (`UpdateSheet`) is one sheet wherever it opens: from About, "Mises à jour"; on home, opened by
  the app, "Une nouvelle version est disponible". Its head gives the version ("Version 0.4.1", the installed one when
  none is offered), "Bêta" printed for a pre-release, and how it stands by its light ("Prête à installer", "Autorisation
  d’installer requise", a failure's title by the attention mark), none while its row already says it. Then what is new
  on a `ReadingPlate`, focused only when the sheet scrolls; then one action row, focused, whose words follow the step,
  focus never moving: "Mettre à jour" ("6,4 Mo depuis github.com"), "Téléchargement…" with its Counter,
  "Vérification…", "Installer", "Installation…", "Autoriser l’installation", "Réessayer", "Rechercher maintenant". OK
  does nothing while the row only says what is under way, nor for `HlMotion.SETTLE` after its words change or after
  the app opened the sheet on home, nor on a key let go once they changed, a second press given too soon never doing
  twice what the first asked (`UpdateSheetTest`). "Annuler le téléchargement" is the row under it, reached with Down
  alone, focus back on the action row as it goes; on home "Plus tard", which Back is too. Back closes the sheet in every step and
  cancels nothing; Android's window opens only over the sheet, never by itself. Under the row, what Android will do:
  ask to confirm, keep the library, close the app (open it again on Android 9). Where Android's settings cannot allow
  the install, the sheet says so without guessing a menu, and gives the release page's address then adb's line as
  text; a failure says what happened and what to do, its row the remedy, "Rechercher maintenant" when nothing in the app
  mends it: always a row to focus.
- **A QR code sheet** shows the `QrCode` in place of the rows; nothing in it takes focus. Envoyer le journal's
  (`JournalSheet`) says under its `small` code, in secondary `meta`, what the log keeps and when its page closes; its
  description always says what the code opens, a log holding almost nothing in the same sentence, and no home network
  or a page that cannot open in place of the code. Taller than the screen (the largest text), its notes lie on a
  `ReadingPlate` that Down rings and reads the sheet through from.

Content closes a sheet with `SheetScope.dismiss(then)`: the sheet leaves, then `then` runs; keys are ignored
meanwhile. Opening another screen closes the sheet at once. Menus share the motion (`rememberSheetMotion`).

A choice that needs another screen (a new source needs a folder or a sign-in) changes nothing until that
screen succeeds. Sources change in one place, `SourceSwitcher`, and changing signs out of nothing.

A library is a Jellyfin server or one folder at least, derived from the source and the folders (`shownSource`),
never stored: without one the welcome screen shows, alone (`WelcomeWhenLost`), at launch and as soon as a removal or a
sign-out leaves none; folders that cannot be read count as some, home saying why (`NoLibraryTest`). Removing a folder
says what leaves; the last one not already being removed adds where the app goes, "C’est votre dernier dossier :
vous reviendrez à l’écran de bienvenue.", or "vous retrouverez « Salon », sans vous reconnecter." when a server
is kept: it then takes the folder's place unasked, as at a launch, the source first, home shown, the folder removed
after; if it cannot, nothing is removed and the folder's card says why (`FolderRemovalTest`).

**Changer de source** lists the folders, a Jellyfin server, then another one (`HlIcons.Add`), the current one
checked, focus on what changes the source; apart, on its own plate, the row that ends a server's session
(`HlIcons.SignOut`), the one choice asked first. A server left for the folders stays signed in, kept, one at most:
under its name, "Jellyfin · léa · sans vous reconnecter", it comes back in one OK once the server takes its token
(the Source row says "Connexion…" meanwhile and OK on it does nothing; a token refused opens the sign-in filled in,
address and account; a server forgotten or replaced meanwhile is left so), and "Oublier « … »" forgets it. On a
server, "Mes fichiers" switches at once, its line "2 dossiers · Trakt reprend" once Trakt is connected, and "Se
déconnecter de « … »" signs out, the folders taking its place, or with none at once, the welcome screen following
("vous choisirez de nouveau votre bibliothèque"). A row's line joins its parts with a middle dot, each row on one
line at the largest text size (`PaneRowLineTest`), a long account's name ending in an ellipsis. The head says the
server stays connected in a sentence; the Source row, beside its value, in fewer words, in every language.

## Messages

A message appears where the action was, in the app's voice: no Toast (a test forbids it), no snackbar, no dialog. Back
once on home is said at the end of its head (`HomeHead`'s `status`), gone after 2.5 s. The update that brought the app
back is said there the same way, once (`updateSaid`): "Houselights 0.4.1 installé" by the white dot (`statusState`
`Done`), "La version 0.4.1 n’a pas été installée" by the attention mark. A title that fails to play says
so where play was asked (`PlayOrigin`): at the end of a page's actions, under the episode row for an episode, until an
OK there, another play or another screen (`PlaybackOutcome`). On home's case it takes the facts' place, the logo never
moving: "Lecture impossible" and the reason on a line each (`HlStatus` `Attention`, `maxLines` 1, else an ellipsis), the
error's title in a word, the player's own ("Lecteur Zidoo non installé", "requis", "changé"), the library out of reach by
where it lies ("NAS injoignable", "Serveur injoignable", a disk of the player's own unplugged "Disque introuvable"), as
every screen titles it (`titleText`); its plate does what fixes it, as `PartFailed`'s button (Réessayer and its arrow
plays it again, Se reconnecter, "Faire confiance" for a player changed, the whole « Faire confiance au nouveau
lecteur » not holding there), else stays its own. A click dismisses it, a held OK keeps it for the options: the whole
sentence first, then only what may work (`HomePlayFailureTest`).

A player refused as changed is said by what fixes it: the firmware's player updated on a test-keys firmware
(`UiError.PLAYER_CHANGED`), "Le lecteur Zidoo a changé : Houselights ne lui confie plus vos mots de passe ni vos
accès. Faites-lui confiance si vous avez mis à jour la Zidoo ou son lecteur, sinon désinstallez les mises à jour du
lecteur."; an app in its place (`UiError.PLAYER_REPLACED`), "… si vous l’avez installé vous-même, sinon réinstallez le
lecteur fourni avec la Zidoo."; never to clear the app's data. Its fix, « Faire confiance au nouveau lecteur »
(`ErrorAction.TRUST_PLAYER`), opens the trust sheet (see *Sheets*) and trusts nothing, wherever the failure shows: on
home's case "Faire confiance", in its options under Ouvrir la fiche; on a title's page, a saga's too, in Lire's place
while the failure is said, an episode's included (the player is at fault, not the episode), Depuis le début gone with
Lire, keyed as Lire so focus stays, held from the OK pressed on it, which clears the failure, until its click
(`RemedyHold`), Lire back and focused under the sheet (`DetailFailuresTest`); in About, its row (`TrustRow`). Each
clears the failure before the sheet opens: without trust given, the next play needing a password says it again.

A secret the Keystore did not open for now is said as kept, never as refused nor as disconnected, with what to do: the
password of « Modifier le dossier » or of the guided flow's account kept under its field ("Le mot de passe enregistré
n’a pas pu être lu pour l’instant : il est conservé. Saisissez-le de nouveau, ou réessayez dans un moment.", the NAS
never asked), a folder's scan on its showcase and in home's place ("…il est conservé, et sera essayé de nouveau à la
prochaine analyse.", `ScanErrorKind.PASSWORD_UNREAD`), Trakt's session in its pane (see *Components*), on home's
settings door ("Non relu pour l’instant") and in the journal's line to paste ("Trakt non relu pour l’instant"); a password
the NAS refuses and an account disconnected keep their words.

A title to identify says so at the end of its page's actions while nothing failed there: "À identifier" by the ring
(`HlStatus` `Off`), why under it in grey ("Plusieurs titres possibles", "Introuvable sur TMDB"), never in ember: a state,
not a failure; a failure takes the place while it is said. Its Identifier button, a plate with the search glyph, comes
after Lire and its parts, before the tiles, only where the source can change the title; identified, both go and focus is
on Lire (`DetailToIdentifyTest`). Modifier then calls Identifier "Trouver ce titre sur TMDB", as for a title left as it
is, "Associer à un autre titre TMDB" staying for a title matched (`EditMenuTest`), and Identifier says why in its panel,
by the ring, its sentence following why; "Laisser tel quel" leaves a title as it is (no longer to identify, nor searched
for by its name), said "Laissé tel quel" when it opens again, with "Rétablir l’automatique".

What fails on a title's page once its title shows is said where it shows, with Réessayer (`PartFailed`): the seasons or
a saga under the hero, a season's episodes on their row, what a reload cannot read again kept and said under the hero,
a saga's page read again in vain over its panel's actions, its panels kept, Réessayer after its actions, OK there putting
focus on Lire first;
what a show's Lire starts, unread, puts what fixes it in Lire's place as `PartFailed`'s button (Réessayer, Se
reconnecter, nothing when nothing does, focus then on the first action) and why at the row's end, focus following from
one to the other. Failing with the seasons or, in the same read, a season's episodes, it is one failure said once,
there: theirs, its Réessayer reloading all that failed, busy until all of it answers, nothing under the hero, the
episode row keeping its title alone (`DetailFailuresTest`); Lire read again alone takes its place, and what still fails
goes back to its own, as do episodes failing later, on a season chosen. A screen that cannot load, or has nothing yet,
shows an intertitle with what fixes it (`HlEmptyState`): home's folders without a film, Ajouter un dossier and Voir
les dossiers; without a folder home draws nothing, the welcome screen taking its place. Exception text never reaches
the screen; it goes to the log.

Titles a folder lost at once while it answers (more than a quarter of them, ten at least: a disk of the NAS's pool
offline, a volume not mounted yet) are no failure the app can tell from a clean-up: they are held, shown, and asked
about once, never in ember, always by the ring of a state that is off. Home says so at the end of its head, where it
says how things stand, "12 titres de « Films » introuvables sur le NAS" with Voir (`HeadNote`), until the user
answers in the sheet it opens or the files are back; Réglages › Bibliothèque counts them on their folder's showcase,
answered or not, its sheet's first row opening theirs (two OKs), « Retirer » there at any time. A title held says on
its page, at the end of its actions, "Introuvable sur le NAS depuis 14 h" (rounded down to the hour), its actions
ending with « Retirer de la bibliothèque », which takes it alone out and closes the page, opening the film's again
when a copy of it is left; an episode held offers it last in its options (Menu), the show's page staying. A film in
several copies is held as the copy Lire plays is. Lire or Reprendre on a title held is never tried, the room staying
lit: in that place, "Ce titre n’est plus là" and "Rebranchez le disque du NAS ou retirez ce titre." (`UiError.HELD`,
`PlaybackOutcome.tryPlay`), under the episode row for an episode, on home's case in its facts' room with since when.
The files back end it all without a word (`HomeHeldTest`, `DetailHeldTest`, `HeldFolderTest`, `HeldCardTest`). A folder
gone from a NAS that answers (deleted or moved there: not found inside a share or an export that answered, over NFS a
name the NAS says it does not have) holds all its titles so too from the second pass in a row that finds it so,
however few, asked about once, nothing leaving without the user; back, what is listed is back, what lies under a
subfolder it cannot read is shown unmarked, and what is still missing is settled as on any pass, a few leaving, many
a drop asked about anew. Its showcase says "Ce dossier n’est plus sur le NAS : il a peut-être été déplacé ou
supprimé.", where a share or an export missing keeps "Vérifiez son orthographe.". A NAS out of reach or waking its
disks, a share or an export missing, an account, a guest or a folder refused say nothing of the files and hold
nothing (`ScanMissingTest`, `NfsListingTest`, `ScanErrors.isFolderGone`). A folder on the player's own
disks is held so too while its disk answers; its disk unplugged, or its folder not on it, it does not answer, as a NAS
asleep: nothing is held, nothing asked, and its titles stay as they were until the disk is back (`ScanLocalDiskTest`).

A title held that is also to identify, or left as it is, says it is held: its file is not there, and identifying can
wait. "Introuvable sur le NAS depuis 14 h" takes the place of "À identifier" at the end of its actions, Lire saying it
is gone without trying; its Identifier button waits with "À identifier", so its row is that of any title held, since
when and the refusal holding beside it as on a film's, in German and at 1.3 too (`DetailToIdentifyLayoutTest`).
Modifier still calls Identifier "Trouver ce titre sur TMDB" and opens it, and Identifier still says why, with its
suggestions, or "Laissé tel quel". A film held, or a show every episode of which is, waits outside « À identifier »,
its count and its queue until a file is back
(`DetailHeldTest.aTitleHeldAndToIdentifySaysItIsHeldAndOpensIdentifierFromModifier`,
`LocalEditorTest.aTitleHeldStillSaysWhyItIsToIdentifyOutOfTheList`,
`LocalEditorTest.aShowEveryEpisodeOfWhichIsHeldWaitsOutOfTheList`,
`LibraryDaoTest.aShowEveryEpisodeOfWhichIsHeldWaitsOutOfTheList`).

The app asks nothing for itself: supporting the project is one row in About, with no reminder, badge or
prompt. A version found is offered once by its sheet on home (`UpdateOnHome`), in its "available" state, once home is
drawn and in front, never over another sheet, a first scan, the grid while the library's first pass goes on, or an
update under way, behind the player, under the launch screen, for a failure or with the check off: « Plus tard »,
Back, or the version acted on from About wait for a newer one, never a reminder, no mark on a door; About's row states
it meanwhile (`HomeUpdateTest`).

## Writing

- French uses *vous*, typographic apostrophes (’) and a no-break space (U+00A0) before `: ; ! ?` and inside
  `« »`. Every other language keeps the same polite register (*Sie*, *usted*, *u*…) and its own typography.
- The interface speaks the device's language, or the one chosen in Affichage (`InterfaceLanguage`), at the text size
  chosen there (« Taille du texte »: Compacte, Standard, Grande), Standard until changed.
- A number never leaves its unit: `3,27 Go`, `2 h 02`, `3840 × 1608` (`formatDuration`, `formatDimensions`,
  `formatBytes`, `formatRatio` in `ui/util/Format.kt`). Time left is a duration ("Il reste 1 h 26"); progress
  reads "3 / 9".
- A button says what OK does ("Marquer comme vu" / "Marquer comme non vu") or names what it opens ("Fiche
  technique"). An empty screen offers a verb ("Ajouter un dossier").
- Relative time is lower case inside a sentence ("Analysé il y a 3 minutes"), capitalised alone ("Il y a
  3 minutes", `relativeTime`). No arrows in sentences.
- A path inside a sentence is never cut: a word joiner follows each of its slashes (`wholePath`), so the line breaks
  before it ("Autorisez 192.168.1.42 sur /volume1/photo").
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
On screen, its light, a brush per bar (`Gradation.lights`), is the launch's and the lockup's.

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
  (`drawGlow`); a transparent rectangle is not drawn, nor a plate under what covers it (a showcase's posters, a saga's
  panels), nor a veil past where it clears (`inkedBand`); a saga's panel draws its dark only while no picture covers it
  whole, and its poster only while its backdrop does not, a picture covering once it has no see-through part and its
  loader's fade has run (`voletCover`, `onWhole`; `VoletGroundTest`, `ArtworkWholeTest`).
- **Two backdrop layers at most.** A backdrop is one cached layer, recorded again only when its picture
  changes or the house lights move; one fade at a time (`FadeBetween`).
- **A wall's field in one fill.** 64 × 36, enlarged, veiled and dithered by one paint (`DiffusePaint`); a wall
  never decodes its picture. The settings' field is made once from posters' samples and drawn the same way, the last
  two kept; a new one is drawn over the one showing for `TINT` only.
- **Prepare the next screen during the gesture before it.** Settled on a title, « À regarder » and walls request its
  page's logo (`DetailAhead`); as a library's door on home is chosen, its wall's first page loads
  (`LibraryAhead`); as « Serveur Jellyfin » takes focus on the welcome screen, its servers are looked for
  (`DiscoveryAhead`), so its sign-in opens on them.
- **Home's grid holds two backdrops at most**, the case's and the next episode's; the rest are posters, at their
  laid-out size, the search door's small ones. A count is cut in software copies of its posters, kept apart in memory
  (`cutPosterRequest`), drawn through a shader at their drawn size; tints and glows come from averaged posters
  enlarged with bilinear filtering, never a blur. The app keeps the cuts of its library doors and one more
  (`CutPosterCache`); before the player, and in the background when the system is short of memory (`trim`), their
  posters go, their tones and tint stay: a door comes back glowing, its digits ink until they are cut again. A door
  on screen keeps the posters it draws, which it holds anyway.
- **Light nothing that focus only crosses** (`Scrub`), and nothing moves for ever but the welcome wall.
- **A scan reloads screens every 20 s at most** (`LibraryChanges`), its first titles and its end at once, as any
  other change; the library's very first, begun on no title, every 3 s until home's row of latest films or of latest
  shows is full (`homeFilling`, 24 titles), home growing as they are found, and every screen with it, a wall then
  holding less than a page: a title's page reads its title again and reloads only if it changed, a wall reloads its
  titles, its filters and sagas waiting for the end, as home's library rows do (`settledVersion`). The settings'
  counts and posters follow it every 2 s at most (`LocalLibrary`).
- **Nothing is read behind home's first scan.** For hours, its pass's progress reads no row, door or cut poster: home
  reads once, at the cut, then follows the pass as above, every 3 s while its row fills, then every 20 s. The first
  scan reads its titles and their count every 2 s at most, only while it holds home, Réglages over it included so its
  wall comes back with them, or shows (`LocalLibrary.firstTitles`, `firstTitleCount`), first found first as the scan
  made them; its eight posters are decoded at their size (224 × 336, 2.4 MB), then one more at most every 2 s once
  full, the poster it replaces let go once the new one shows: nine at most, nothing in a layer at rest, no backdrop
  (`HomeViewModelTest`, `FirstWallTest`).
- **Measure on the player**, compiled as users run it (`cmd package compile -m speed-profile`), with
  `dumpsys gfxinfo <package> framestats` and `setprop debug.hwui.overdraw show`. See CONTRIBUTING.md.

Reference measures on the Zidoo Z1000 Pro: frames and start on 2026-10-21 with the 0.2.0 `bench` build compiled
`speed`, three passes; in brackets v0.1.0 compiled `speed`, three passes; overdraw, memory and bands on 2026-10-20.
Each screen from a fresh start, an arrow every 400 ms, then an arrow held 3 s. Home is 0.2.0's, in rows: its grid
is to measure.

| Measure | Tool | Budget | Home | Wall | Title's page | Settings |
|---|---|---|---|---|---|---|
| Janky frames | `gfxinfo framestats` | +0.5 point at most | 0.7–1.2 % (3.0–4.9 %) | 0–0.2 % (0.3 %) | 1.0–1.5 % (1.1–1.3 %) | 15.7–15.9 % (19 %) |
| Frame time, 90th / 99th percentile | `gfxinfo framestats` | 16.7 ms | 24 / 32–34 ms (27–29 / 38–40) | 19–20 / 24–27 ms (22–23 / 27–31) | 13–17 / 34–36 ms (18–19 / 34–40) | 48–57 / 125–150 ms (61–65 / 150) |
| Overdraw | `debug.hwui.overdraw` | the room 1×, plates 2× | room and backdrop 1×, plates 2×, a card 3× | field 1×, posters 3× | backdrop 1×, plates 2×, the ember button 3× | rows 2×, the pane's pictures 3× |
| GPU memory | `dumpsys gfxinfo` | two backdrop layers | 86 MB | 35 MB | 31 MB | 22 MB |
| Field and veils without bands | `screencap`, runs of one level | 1 to 2 px | — | levels alternate pixel by pixel: dithered, no band | smooth, no band | — |
| Cold start to home's first frame | `am start -W` | 1.5 s | 0.60–0.64 s (0.62–0.69 s); 0.50–0.55 s compiled `speed-profile` with the baseline profile | — | — | — |

Settings' janky frames are its pane, recomposed whole when its entry changes (120 to 150 ms in `draw`), as before
the identity; places crossed now open no pane, only the one focus settles on (to measure again). Home's slowest
frames on a row wait on the main thread for a picture (40 to 48 ms); a row's hold draws at 12 to 15 ms of GPU per
frame, as a wall's scroll does. The room is painted once, by the window (`@color/room`): no screen paints a black of
its own under its content; a field or a backdrop covers it whole.

## Guard

| Test | Holds |
|---|---|
| `DesignTokenGuardTest` | no size, text size, tracking, font, text style (its line height style included), colour, palette, opacity, shape or gradient in a screen, and no type of a component's own (a named exception: an empty field measured one line tall); `badge` only where its files are listed, in a mark's box; no ember named in a screen; no arithmetic on tokens; images only through `ArtworkImage` or `PosterThumb`; one place lit per screen, and only the places; no Toast; no Material icon, drawn or declared; no shadow or blur |
| `FocusStyleGuardTest` | focus said in one place, drawn by one surface, never by a screen; every surface takes a gesture and what takes focus shows it; a row holds framed cards only; nothing grows with focus, no size animated or measured from an animation but a saga's panel opening (its one exception, by file, and none left once it no longer opens), and a surface's light takes no layer; OK clicks through the surface only; no tv-material surface, no Compose Material; never `focusRestorer` |
| `MotionGuardTest` | the system's own curves only, drawn in `Motion.kt`; every tween names its easing and every animation its spec; no spring, no cross-fading container |
| `ShapesTest` | radii by hierarchy and in dp, nested corners parallel, a sheet square against the edge; nothing round but dots |
| `ContrastTest`, `ScrimTest` | every pair drawn, on what it sits on; forbidden pairs stay below; veil floors, plate floors and smoothness |
| `AmbientTest`, `DiffuseTest`, `PosterFieldTest`, `RoomPostersTest`, `BackdropTest`, `BackdropFadeTest`, `BackdropRelayTest` | a picture's light; the field's cap, ramp and dither; posters laid side by side into a field, the last at once, the next over it; the room's posters read once a visit; decoded sizes; one fade at a time; the relay across cuts |
| `RowFrameTest`, `CardRaiseTest`, `OkPressTest`, `RecedeTest`, `ScrubTest` | the frame that travels, without recomposing, out under a sheet; the focused card on top; OK on its release; siblings stepping back in steps; an arrow held |
| `FocusReachTest`, `FocusRoomTest`, `HomeLayoutTest`, `DoorGridTest`, `DetailLayoutTest`, `WelcomeLayoutTest`, `FormLayoutTest`, `SettingRowTest`, `PaneRowLineTest`, `PaneHeadTest`, `SheetRhythmTest`, `DeviceCodeTest`, `VoletPlanTest`, `VoletsLayoutTest`, `SagaWordsTest` | the frame inside the room kept for it and inside the safe area, a row's glow drawn whole past it, a wall's faded where its grid cuts it; home's grid and its doors' parts, a page's first view, the welcome screen and the forms; setting rows' heights; a pane's or a sheet's row described on one line; a pane's title and About's lockup on the screen title's baseline at every text size; a sheet's lines on the board's; a code never cut; a saga's panels' widths and scroll, numbers and years, their frames in the safe area at every text size, and its page's words whole in every language at 1.3 |
| `TypeScaleTest`, `TypeRolesTest`, `SpacingGridTest`, `LayoutTokensTest`, `GroundTest`, `TextRoomTest` | type scale (sizes, lines, a floor a voice, tv-material's roles), the components' roles on it, 4 dp grid, screen grid read by screens (not the catalogue), grounds, text room at every text size |
| `TextScaleTest`, `TextSizeLayoutTest`, `TextSizeRowTest` | « Taille du texte »: the system's size times the step, within 0.75 and 1.3, Grande on Android's curve; home, a title's page and the settings cutting nothing at 0.75 that 1.3 or 1 does not; its row and sheet, kept in the settings |
| `ResourceTokensTest`, `StringsTest`, `BrandTest` | resources in step with tokens; typography of resources, every translation complete; the mark's vectors and the lockup |
| `CatalogCoverageTest` | every public component, shape, `HlIcons` glyph, colour role and type style in the catalogue |
| `DesignDocTest` | every system name this document cites still exists |
| `RunCatchingGuardTest`, `LayersGuardTest` | no `runCatching` around focus requests (`tryRequestFocus`) or in suspending code (`suspendRunCatching`); no layer under the screens depending on the UI or on the app's container (`AppContainer`, `HouselightsApp`) |

Deliberate exceptions go in a guard's `allowed` list with their reason; a value needed twice belongs in the
design system.

Remote behaviour is tested as the remote drives it, on Robolectric with the app's theme on the TV canvas
(`ui/testing/Remote.kt`): `MediaRowFocusTest`, `SheetFocusTest`, `SegmentedFocusTest`, `HomeGridFocusTest`,
`PagesNavigationTest`, `WallPageTest`, `WatchPageTest`, `QuitAgainTest`, `HomeHeldTest`, `RestoreFocusTest`, `ScrollAreaTest`, `TextFieldMessageTest`, `AddFolderFlowTest`, `FolderPickTest`, `SagaPageTest`,
`SupportRowTest`, `JournalSheetTest`, `TraktPaneTest`, `OkPressTest`. A focus rule that
changes comes with its test. Robolectric's keys come from no device; a text field's are sent from the remote's D-pad
(`pressOnRemote`, `holdOnRemote`), whose arrows and OK Compose's own text field reads otherwise, or from a keyboard,
the one shown or one plugged in (`pressOnShownKeyboard`, `pressOnPluggedKeyboard`), whose arrows it keeps
(`TextFieldCaretTest`), and a field's cross is reached and pressed from the remote's D-pad (`TextFieldClearTest`).

## Catalogue

Debug builds show **Settings > About > Design system**: every token and component as a row of its states
(rest, chosen, lit, loading, failed, empty), focus checked live with the remote. Sections live in `ui/catalog`:

| Section | Shows |
|---|---|
| `CatalogScreen` | the palette and its roles; black levels, then a picture's tinted veils and a wall's diffuse field; every type style; panels and states, lists and settings, sheets, cards without artwork, the fullest action row and track |
| `CatalogContrast` | each ink on what it sits on, over the lightest picture, with its ratio |
| `CatalogFocus` | every shape with its radii; the frame (poster, wide image, portrait, absent card, panel, the action to play) and the fill and ring, live |
| `CatalogGestures` | case back, intertitle and counter side by side, then intertitles, error states, counters, case backs, empty frames and poster strips |
| `CatalogFirstScan` | home's first scan: the folder listed with its wall empty, with its count, its files counted and the wall filling with « Aller à l'accueil », in one-sheets, TMDB refusing the key with its note and « Modifier la clé » over the block; then live, the wall filling and renewing in place |
| `CatalogIcons` | every glyph on the room and on focus; done and your choice; ember for states only; the Menu key, alone and after a name; the OK key, and what OK does on an open saga panel (`OkHint`) |
| `CatalogControls` | buttons in their states, a place's title and a task's, tiles, controls on a picture's plates, and the seasons |
| `CatalogSecondary` | the settings' room of posters with its places, a group, a plate of facts and states; a pane's heads, the lockup, printed pairs, definitions, a meter that states, a code to type; showcases in their row; a gallery whose images step back; a plate in a film's light and the sheets that hold them, a reading plate, one wide to read, an NFO's |
| `CatalogForms` | settings in their group and in a sheet, servers found by their connection, text fields, status panels, panel buttons, dividers, menus, choice sheets and settings, scroll areas |
| `CatalogCards` | a media row whose frame travels, with its new titles; cards on a picture's light; surfaces and images; wide cards, a saga with absent films, their posters in grey or without one, people; progress lines, skeletons, a contact sheet of each form (a poster's beside its print, a backdrop's band behind it, a logo's) and a saga's TMDB does not know, its films' alone, its choices and prints in their states, results (with a note, with options), thumbnails |
| `CatalogVolets` | a saga's polyptych on three, five and eight films, closed panels 96 wide, numbers in `display` at every length: the saga's panel open on arrival (541 for three films, 428 from five, which scroll), its actions under its words, closed panels with their marks and years, a film watched, in progress, held, missing in grey with its ring, announced in grey with the quiet dot, a picture failed, the spine pinned once a film opens with its band, the panel past the edge without its number and year; focus opens a panel as the one left closes, a held arrow jumps |
| `CatalogDoors` | home's head, its case resuming and proposing, then after a failed play by each remedy, and each door at its size: a still and its season, a band of posters, counts cut in posters and in ink at one size, sagas, a wall out, the app's state |
| `CatalogReading` | title art, episode headings, meta and title lines, formats, a row's billing, the billing block, hero info, the technical sheet, search and its keys, the synopsis, versions |
| `CatalogStress` | the longest labels, the largest and smallest text sizes the app lays out at, fallback scripts, device metrics |
| `CatalogBrand` | the icon and banner as a launcher draws them, the launch mark in its circle, the lockup |

## Adding a screen

1. Start from `AmbientBackdrop`, `HlTwoPane` or `HlFormScaffold` and compose existing components.
2. Add a token or component here before using a new value twice.
3. End a vertical list with `contentPadding(bottom = HlFocus.edge)`; if it starts below the top of the screen,
   wrap it in `ListScroll(top = its top padding)`. `HlTwoPane` does both for its content pane.
4. A screen that shows the source is composed in `WithSource` (`AppNavigation`): with no library left, or restored
   after its connection could no longer be read, or not opened for now, it gives way to the welcome screen
   (`mustWelcome`), never reading a source there is not.
