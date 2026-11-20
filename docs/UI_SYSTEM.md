# Cinema UI system

A TV-first interface built on one focus language, one glass material and one
token set. The rules exist because the viewer is three metres away, holds a
D-pad, and the box may be a 1 GB 32-bit Amlogic stick: every cue has to be
readable at distance, and every animation has to cost nothing.

Source of truth:

| Concern | File |
| --- | --- |
| Focus primitive, ring, growth buckets | `app/src/main/java/com/streamline/cinema/ui/FocusSystem.kt` |
| Glass materials and surface modifiers | `app/src/main/java/com/streamline/cinema/ui/Glass.kt` |
| Colour, type, spacing, radii, sizes | `app/src/main/java/com/streamline/cinema/ui/Theme.kt` |
| Shared components (buttons, cards, tabs, chips, rows) | `app/src/main/java/com/streamline/cinema/ui/Components.kt` |

---

## 1. One focus language

There is exactly one way to show focus. `FocusableSurface` (or the
`glassSurface` / `glassFrame` modifiers it is built on) draws it, and that is the
only highlight in the app.

Focus gives **three redundant cues at the same time**:

1. **It grows** — proportionally to the element (see §2).
2. **Its fill lightens** — a white lift over the base tint (7% for controls,
   4% over artwork).
3. **A 2 dp warm ring appears** — `#FFDEA0`, inset 1 dp, with a 1 px black seam
   just inside it.

The redundancy is the point. A washed-out panel, a colour-blind viewer or a
cheap panel with poor gamma can lose any one cue; losing all three is not
plausible. Because the ring and lift are drawn by the same code path, a new
screen physically cannot invent its own highlight — it either uses the
primitive or it has no focus state at all.

```
base tint ──► focus lift ──► ring inset 1 dp ──► 1 dp black seam at 1.9 dp
```

Nothing else is a focus cue. Selected state is not focus: a tab keeps its gold
underline, a settings row keeps its gold text, an approved kids title keeps its
`✓ KIDS` badge — whether or not the D-pad is on it.

### Growth buckets

| Bucket | Growth | Used by |
| --- | --- | --- |
| `ICON` | 14% | transport dock buttons, back button, star/remove toggles, avatar choices |
| `PILL` | 6% | tab pills, chips, skip-intro pill, compact buttons |
| `CARD` | 5% | posters, continue-watching cards, live channel tiles, profile tiles, curation tiles |
| `CONTROL` | 4% | standard buttons and text fields |
| `ROW` | 1% | full-width rows: settings, guide, episodes, sources, OSD menu rows |
| `NONE` | 0% | static surfaces that are drawn but never focused |

Small targets move by several millimetres and read instantly. A full-width row
moves by 1% (a few millimetres on a 1080p screen) — enough to feel alive,
never enough to push a neighbour.

### Timing

- **120 ms**, `FastOutSlowInEasing`, `animateFloatAsState`.
- Monotonic: the value approaches 1 and settles. No spring, no overshoot, no
  bounce — a bouncing highlight on a held D-pad reads as lag.
- Holding a direction produces a trail of acknowledged stops, not a queue of
  animations: each newly focused item starts at zero and settles while the
  previous one collapses.
- The focus amount is a `State<Float>` read inside `graphicsLayer {}` and
  `drawBehind {}` / `drawWithContent {}` lambdas. Grow, lift and ring
  invalidate draw/layer only — **no recomposition per frame**.

### Scale never reflows layout

Growth is a `graphicsLayer` transform on the surface itself. The layout node
keeps its measured size:

- neighbours do not move,
- the grid does not re-measure,
- the focused item can overlap its neighbours (cards raise `zIndex` to 2 while
  focused),
- the user's spatial memory of "the third card on the second row" stays valid
  while focus travels.

The scale layer is applied **outside** the rounded clip. If the clip wrapped the
scale, the card would only zoom internally and never appear to grow.

---

## 2. Focus system

### Two states

| | Unfocused | Focused |
| --- | --- | --- |
| Scale | 1.00 | bucket factor (1.01 – 1.14) |
| Fill | base tint | base + white lift (7% / 4% media) |
| Ring | none | 2 dp `#FFDEA0`, 1 dp black seam |
| Sound | — | focus tick (if enabled) |
| Click | — | select tick + action |

There is no third "pressed" visual. Press feedback is the sound plus whatever
the action itself does (navigate, toggle, open). A third state would add latency
and a second vocabulary.

### D-pad rules

- **Every focusable thing is reached by direction.** No focus traps, no
  "press OK to reveal the next row".
- **Scrollables focus and scroll at the same time.** On a TV the D-pad can only
  scroll what it can focus. Guide rows, episode rows, settings rows, source
  rows, search keys and OSD menu rows are individually focusable; Compose brings
  each into view as it takes focus. This is the scrolling mechanism, not an
  accessibility extra.
- **Direction is explicit where layout is ambiguous.** Rails pinned inside a
  grid declare `focusProperties` (up/down/left/right) rather than letting the
  geometry guess: e.g. the skip-intro pill cancels left/right so a seek drag
  never falls into it.
- **Long press is a shortcut, never the only path.** Where a remote's centre
  key is held (episode options, save channel), the same action is reachable from
  a visible button or dialog. A held centre key swallows the release so it
  cannot also start playback.
- **Focus returns where it left.** Per-surface focus memory restores the last
  card/row when a player, dialog or detail page closes.

### BACK precedence

One back press consumes exactly one layer, top to bottom:

1. Dialog / sheet open → close it, restore focus to its opener.
2. Player OSD visible → hide the OSD (playback continues).
3. Detail page open → close detail, restore focus to its card.
4. Profile gate open (and dismissible) → close the gate.
5. Non-HOME tab → return to HOME.
6. HOME at root → second press within the exit window leaves the app.

The first press of the double-back exit shows the "press back again" hint; a
lone press never quits.

### Reachability invariant

> Every action visible on screen is reachable with direction keys and one OK,
> without touching the screen.

Practical consequences:

- Touch-only affordances may exist as shortcuts (tapping a hero dash), but the
  same result is always reachable from a focusable control.
- Text fields are focusable; clicking OK opens the keyboard.
- Disabled controls stay focusable in the sense that they are skipped by
  direction — a disabled button is not a focus stop, and it renders at 40%
  alpha so "skipped" is visible.

---

## 3. Component contracts

### `FocusableSurface`

```kotlin
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(CinemaDimens.ControlRadius),
    scale: FocusScale = FocusScale.CONTROL,
    base: Color = Color.Transparent,
    tint: Color = Color.Transparent,
    enabled: Boolean = true,
    role: Role = Role.Button,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
)
```

It owns: focus tracking, the proportional grow, the fill lift, the ring, the
focus/select ticks, click and optional long-click, semantics, the disabled
alpha, and an optional externally supplied `FocusRequester`.

Rules for callers:

- Pass `base` for the resting fill and let the primitive lighten it. Do not
  compute `if (focused)` fills outside.
- Pass the right bucket. Do not invent a factor.
- Content is laid out by the caller; the primitive only draws the surface.
- Use `glassSurface(focused = …)` directly when the element already has a
  bespoke click/focus pipeline (per-card focus memory, key handlers, long-press
  state machines). Same ring, same buckets.

### Surface modifiers

| Modifier | Fill | Lift | Ring | Use |
| --- | --- | --- | --- | --- |
| `glassSurface` | base tint + sheen + optional tint | yes | yes | panels, rows, pills, buttons, menus |
| `glassFrame` | none (image is the fill) | 4% white | yes | posters, stills, channel art, video frames |
| `glassEdge` | none | none | yes | imagery that must keep its artwork untouched |
| `focusScale` | — | — | — | scale-only growth for containers that already draw their surface |

### Component table

Shapes use `CardRadius 14`, `ControlRadius 12`, `RowRadius 10`,
`PillRadius 999`. One pill law: every single-line text badge, chip, status
badge and HUD toast is the same stadium (`RoundedCornerShape(PillRadius)`);
no 2–28 dp "rounded rectangle" badges, and no `RoundedCornerShape(50)`
literals. Padding stays symmetric so the label is centred on both axes.

Pill labels also set an explicit `lineHeight` (14 sp on `CinemaType.Pill`,
roughly 1.2× the font size on 7–9 sp badges). Without one, `Text` inherits
`bodyLarge`'s 24 sp line box, which doubles a small badge's height and turns
its capsule into a circle.

`TvButton` centres content on both axes (`FocusableSurface` takes a
`contentAlignment`). Without it, a short label pins to the top of the 46 dp
minimum height. Its optional glyph occupies a leading slot that is mirrored
after the label, so the label — not the glyph+label pair — sits on the centre
line.

The top bar (`TopTabBar`) switches to its dense spacing below 1000 dp of width
as well as above 1.15× font scale: 16 dp edge padding, 12 dp brand gap, 10 dp
trailing gaps and 8 dp tab padding. The fixed items (brand, six tabs, search,
clock, profile chip) otherwise over-subscribe the row, and the chip — the last
fixed child — is measured into whatever sliver is left. Avatar images therefore
use `requiredSize`, so a cramped parent crops rather than distorts them.

| Component | Shape | Size (min) | Scale | Padding | Notes |
| --- | --- | --- | --- | --- | --- |
| `TvButton` primary/secondary | `ControlRadius` | 46 dp tall (compact 36) | `CONTROL` / `PILL` compact | 22 dp (14 dense, 12 compact) horizontal | gold base for primary, glass control for secondary |
| Tab item | pill | 40 dp tall | `PILL` | 14×8 | gold underline = selected, not focus |
| Chip / filter pill | pill | content | `PILL` | 12×6 | selected = gold text + wash |
| Poster card | `CardRadius` | poster aspect + 2 title lines | `CARD` | 0 | `focusScale` on the column; `glassFrame` on the poster |
| Continue card | `CardRadius` | continue width | `CARD` | 0 | long-press opens episode menu; play is the tap |
| Star / remove toggle | circle | 32–36 dp | `ICON` | — | overlays the poster corner |
| Media card (generic) | `CardRadius` | poster | `CARD` | 0 | same contract as poster card |
| Live channel tile | 10 dp | channel width | `CARD` | 8 | `16:9` art, LIVE pill top-end |
| Live channel row | 10 dp | 76 dp tall | `ROW` | 10×8 | list mode |
| Guide row | `RowRadius` | 48 dp | `ROW` | 14×10 | time / channel / now / next columns |
| Episode row | `RowRadius` | 48 dp | `ROW` | 10 + 48 gutter | OK plays, hold opens options |
| Source card | `CardRadius` | content | `ROW` | 16×12 | provider grouping, badges |
| Settings nav row | 9 dp | 22 dp | `ROW` | 12×2 | narrow column |
| Settings row | `RowRadius` | 44 dp | `ROW` | 14×8 | label + control |
| Settings option row | `RowRadius` | 38 dp | `ROW` | 28/14×7 | selected gold wash |
| `TvTextField` | `ControlRadius` | 46 dp | `ROW` | 14×10 | OK opens keyboard |
| OSD transport button | circle | 52 dp (primary 66) | `ICON` | — | icon or text label |
| OSD menu row | 9 dp | 48 dp (compact 30) | `ROW` | 14×10 | label wins width over value |
| Skip / intro pill | pill | content | `PILL` | 22×10 | cancels left/right |
| Status badge (LIVE, TUNING, DEAD, ON NOW, UP NEXT, ✓ KIDS) | pill | content | `ROW` on art | 5×2–6×3 | solid status colour + Ink text; one red (`Offline`) for live/dead |
| Rating / resume badge on art | pill | content | — | 9×4 (11×4 resume) | black @ 55% on artwork, gold text; solid gold only for resume |
| Back button | circle | 46 dp | `ICON` | — | top-start |
| Info row | 6 dp | content | `ROW` | 8×3 | scrollable stats panel |
| Profile tile | `CardRadius` | profile width | `CARD` | 12 | active = gold wash |
| Avatar choice | circle | 60 dp | `ICON` | 5 | selected = gold wash |
| Dialog action row | `ControlRadius`/`RowRadius` | 44–48 dp | `ROW` | 14×8–10 | open-with, imports |
| Curation tile | `CardRadius` | poster aspect | `CARD` | 0 | `✓ KIDS` badge for approved |

---

## 4. Tokens

### Colour

Measured with the WCAG 2.1 relative-luminance formula against `Ink`
(`#07080D`), the app's background.

| Token | Value | Contrast on Ink | Role |
| --- | --- | --- | --- |
| `Ink` | `#07080D` | — | app background |
| `InkSoft` | `#0C0E15` | — | backdrop top of gradient |
| `Surface` | `#11141C` | — | base surface |
| `Panel` | `#181C27` | — | panels, list rows |
| `PanelRaised` | `#222736` | — | focused list row base |
| `Gold` | `#F4B942` | ~10.6:1 | brand, primary action, selection |
| `GoldDim` | `#B98B2C` | ~5.6:1 | secondary brand, EQ fill |
| `Crimson` | `#E94560` | ~4.6:1 | alert accent (large text / fills) |
| `Tmdb` | `#01B4E4` | ~8.3:1 | TMDB credit |
| `TextPrimary` | `#F2F4F8` | **18.2:1** | body copy |
| `TextSecondary` | `#CFD6E4` | **13.7:1** | supporting copy |
| `TextMuted` | `#939CAF` | **7.3:1** | metadata, captions |
| `TextFaint` | `#626A7B` | **3.7:1** | **secondary-only** — timestamps, hints beside a brighter label |
| `Border` | `#2A3040` | — | static 1 dp border |
| `Online` | `#54C98A` | ~9.4:1 | healthy status |
| `Offline` | `#E5484D` | ~5.2:1 | failure status |
| `Pinging` | `#F5C451` | ~11.6:1 | in-flight status |
| `Unchecked` | `#6E7689` | 4.4:1 | unknown status (icon + text) |
| `FocusTokens.Ring` | `#FFDEA0` | **15.4:1** (13.1:1 on `Panel`) | the only focus ring |

`TextFaint` must never carry the only copy of a label or an action; it is a
context token. Everything that must be read on its own uses `TextMuted` or
brighter.

### Type scale — nine steps, one voice

| Step | Style | Size / line | Weight | Use |
| --- | --- | --- | --- | --- |
| 1 | `HeroTitle` | 46 / 48 | ExtraBold | full-bleed hero |
| 2 | `DetailTitle` | 34 / 38 | ExtraBold | detail hero |
| 3 | `PageTitle` | 26 / 31 | ExtraBold | tab heading |
| 4 | `SectionTitle` | 19 | Bold | rail and section heading |
| 5 | `CardTitle` | 14 | SemiBold | card and control label |
| 6 | `Body` | 13 / 19 | Normal | reading copy |
| 7 | `BodySmall` | 12 / 17 | Normal | dense secondary copy |
| 8 | `Pill` / `Meta` / `Kicker` | 11 | SemiBold/Bold | pills, ratings, uppercase kickers |
| 9 | `Overline` / `Caption` | 10 | SemiBold/Medium | uppercase field labels, captions |

The wordmark lockup (15 sp logotype + 9 sp subline) is branding, not a step.
Text scaling to 1.3× / 1.5× is honoured app-wide; layouts reserve two title
lines on cards so the meta row never jumps between neighbours.

### Spacing, radii, strokes

| Token | Value |
| --- | --- |
| `SpaceXs` / `Sm` / `Md` / `Lg` / `Xl` / `Xxl` | 4 / 8 / 12 / 16 / 24 / 32 dp |
| `Gutter` / `ScreenGutter` | 48 dp |
| `RadiusCard` | 14 dp |
| `RadiusControl` | 12 dp |
| `RadiusRow` | 10 dp |
| `RadiusPill` | 999 dp (pill) |
| `StrokeHairline` | 1 dp |
| `StrokeRing` | 2 dp |

### Component sizes

| Token | Value |
| --- | --- |
| `SizeButton` / `SizeButtonCompact` | 46 / 36 dp |
| `SizeIcon` / `SizeIconLarge` | 44 / 52 dp |
| `SizeTransport` | 66 dp |
| `SizeTab` | 40 dp |
| `SizeRow` | 48 dp |
| `TopBarHeight` | 58 dp |
| `TopBarScrim` | 34 dp |

### Glass material

Surfaces stay inside a 10–40% opacity band so a panel still reads as a solid
object over artwork:

| Token | Value | Use |
| --- | --- | --- |
| `Glass.Sheet` | `#0B0F16` @ 62% | floating sheets, dialogs |
| `Glass.Control` | `#141924` @ 72% | buttons, fields |
| `Glass.Frame` | `#0D1119` @ 55% | behind poster artwork |
| `Glass.AccentWash` | gold @ 12% | selected/active tint (the only gold wash; solid gold is reserved for primary actions and resume) |
| `Glass.DangerWash` | red @ 14% | destructive action tint |
| `Glass.Hairline` | white @ 8% | static separation edge (never focus) |
| `Glass.Sheen` | white @ 12% | top sheen of a sheet |

Meta pills have two fills only: `PanelRaised` (auto-eased to 62%) on a panel or
page, and black @ 55% over artwork. `InfoPill(..., solid = true)` opts a status
pill out of the easing when it pairs Ink text with a solid colour.

The sheen is a three-stop vertical gradient (12% white → 2% white → 10% black,
scaled by a per-surface `sheen` factor). It is drawn once and never animated.

---

## 5. What the design deliberately omits

These are decisions, not gaps. Each one usually makes a TV UI feel slower,
heavier or less certain:

- **No screen transitions.** Tabs and the detail page swap instantly. A 150 ms
  crossfade delays the first frame of the page the viewer already asked for,
  and a full-screen crossfade keeps two render trees alive.
- **No parallax, no shimmer, no looping decoration.** Loading skeletons are
  static blocks. A looping gradient is a per-frame GPU cost on the cheapest
  target device and buys nothing while the viewer waits for data.
- **No card zoom-in or fly-in on entry.** Rails appear already laid out. Focus
  growth is the only motion on a card.
- **No gesture-only affordances.** Every touch gesture is a shortcut for a
  focusable control. Long-press on an episode is a shortcut for the visible
  options action.
- **No tap ripple.** Ripple is a touch idiom; on a D-pad the focus ring plus the
  select tick is the acknowledgement.
- **No focus glow, underline or border outside the primitive.** One ring, one
  lift, one growth curve.
- **No spring physics on focus.** Monotonic 120 ms. Overshoot on a held D-pad
  reads as the UI lagging behind the remote.

---

## 6. Port adaptation map

The design is not Compose-specific. What follows maps each rule to the four
common TV stacks.

| Concern | Compose (this app) | Android Views | React Native TV | Web / TV browsers |
| --- | --- | --- | --- | --- |
| Focus entry | `Modifier.clickable` / `focusable`, `onFocusChanged` | `android:focusable`, `OnFocusChangeListener`, `setOnKeyListener` | `TouchableOpacity` / `Pressable` with `hasTVPreferredFocus`; `onFocus` via `TVFocusGuideView` | `tabindex="0"`, `:focus`, `:focus-visible` |
| Focus tracking | `var focused by remember { mutableStateOf(false) }` | `View.setSelected()` or a custom state | `useState` + `onFocus`/`onBlur` | CSS `:focus` or `focus`/`blur` events |
| Ring | `drawWithContent { drawFocusRing(shape, amount) }` (2 dp stroke, 1 dp seam) | `GradientDrawable` stroke swapped on focus, or a foreground `View` 2 dp ring | `borderWidth: 2` + `borderColor` on focus, plus an inner 1 px shadow | `outline: 2px solid #FFDEA0; outline-offset: -3px` |
| Growth | `graphicsLayer { scaleX/scaleY }`, transform-only | `View.setScaleX/Y` (does not relayout) | `transform: [{ scale }]` in `Animated.timing` | `transform: scale()` with `transition: transform 120ms` |
| Scroll on focus | Compose auto-scrolls `LazyColumn`/`verticalScroll` | `ScrollView` / `RecyclerView` `requestChildRectangleOnScreen` | `FlatList` + `scrollToIndex` in `onFocus`, or `TVFocusGuideView` | `el.scrollIntoView({ block: 'nearest' })` on focus |
| Back | `BackHandler` chain, top layer wins | `onBackPressedDispatcher` / `OnKeyListener(KEYCODE_BACK)` | `BackHandler` / `TVEventHandler` | `history.pushState` + `popstate`, or `Back` key handler |
| Long press | held `KeyDown` timer + swallow the `KeyUp` | `OnKeyListener` with `KeyEvent.getRepeatCount()` | `onLongPress` on `Pressable` (works with remote OK) | `keydown` timestamp on Enter, `keyup` swallows the click |
| Disabled | 40% alpha, not a focus stop | `setFocusable(false)`, alpha 0.4 | `focusable={false}`, `opacity: 0.4` | `tabindex="-1"`, `aria-disabled`, `opacity: .4` |

### Three porting traps

1. **Scaling inside a clip.** If you apply the rounded clip outside the scale
   transform, the element only zooms internally and never appears to grow. Put
   the transform on the outside and the clip inside (this app: `.graphicsLayer`
   before `.clip`). The Views equivalent is scaling the parent, not the clipped
   child; on the web, `transform` must be on the element that is not the
   `overflow: hidden` wrapper.
2. **Recomposition per frame.** Reading an animated value inside a composable
   body (or inside React render, or by writing `style` from a `requestAnimationFrame`
   loop) re-runs layout and text shaping every frame. Read the animated value
   inside the draw/layer lambda (Compose), use `View.setScaleX` (Views), use
   `Animated`/`useNativeDriver` (RN), or a CSS transition (web).
3. **Guessing direction from geometry.** Letting the engine infer "down" across
   a rail bound into a grid produces focus jumps that look random. Where the
   layout is ambiguous, declare the target explicitly (`focusProperties`,
   `nextFocusDown`, `TVFocusGuideView`, or a key handler), and make every
   scrollable item a focus stop so the D-pad can actually scroll it.

### One-day minimum subset

If you only have a day to bring another stack up to this language:

1. **One focus helper** that tracks focus, applies the scale bucket, the fill
   lift and the 2 dp ring — nothing else draws a highlight.
2. **Four buckets** (icon 14%, pill 6%, card 5%, row 1%) and 120 ms monotonic
   easing.
3. **Every scrollable item focusable**, with scroll-into-view on focus.
4. **The token file**: `Ink`, `Gold`, four text levels, `Ring`, the nine type
   steps, radii 14/12/10/pill.
5. **The BACK chain** in the precedence order in §2.

That subset reproduces the feel. Everything else in this document is polish
built on top of it.

---

## 7. Performance budget

- Focus animation is draw/layer-only: no recomposition, no layout, no
  allocation per frame.
- Growth is a transform; the composable tree is never re-measured on focus.
- One ring draw and one lift draw per focused surface per frame; no shaders,
  blurs, shadows or offscreen layers.
- Glass fill and sheen are static draws, cached in the layer; they do not
  animate.
- No crossfades, parallax or looping animations: two full-screen decodes are
  never alive at once for a transition, and idle screens do not animate.
- Skeleton states are static blocks, so a loading screen costs the same as a
  loaded one.
