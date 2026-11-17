# Serve Coach Live: iOS port spec, UI, voice and app state

Source: `experiments/04-live-coach/android`, app **0.13.24** (versionCode 74, `app/build.gradle.kts:62-63`).
Kotlin paths below are relative to `app/src/main/java/com/monti/servecoach/` unless they start with `core/` or `app/`.
`file:line` refers to the Kotlin source.

Scope: everything the player sees and hears, plus app state, settings, files and lifecycle. The camera, capture,
GPU tap, tracker and engine pipeline belong to the companion spec. Where this spec needs that pipeline, it names
an **interface point** (marked `⇄ PIPELINE`).

The shared `core` module (`SetupCheck`, `NoRead`, `ServeTracker`, …) is used unchanged. This spec only says how the
app calls it.

> **The snapshots are stale.** The PNGs in `app/src/test/snapshots/images/` were recorded before 0.13.22–0.13.24. They
> show things the current code no longer has: a NEXT SERVE button (removed), "AI COACH: ON/OFF" (now "SETUP
> ASSISTANT: …"), an "AI COACH" prefix in the box (now "ASSISTANT  "), the TEST VIDEO and REPORT buttons visible
> (now hidden in the developer row), and an outcome line without a strength ("BACKSPIN" where the code now draws
> "MEDIUM BACKSPIN"). Use the snapshots for geometry and look only. **This spec follows the code.**

---

## 0. Units and conventions for the port

| Android | iOS |
|---|---|
| `dp` | `pt` (1:1). The reference device is a Pixel 8 in landscape, 2400×1080 px at 428 dpi, which is about **897 × 404 dp**. An iPhone 15 in landscape is 852 × 393 pt, so the layout fits without scaling. |
| `sp` (TextView `textSize`) | `pt`, at a fixed size. Do **not** use Dynamic Type scaling: the layout is a fixed cockpit. |
| Canvas text sizes (`SpinScale`, `SpinSplit`) | These are written as `N * density`, i.e. dp, so they are fixed pt as well. |
| `letterSpacing = x` | This is in **em**. Use `NSAttributedString.Key.kern = x * fontSize` (pt). Android adds the spacing after every glyph, the last one included. Kern does the same. |
| `Color.argb(a,r,g,b)` | `UIColor(r/255, g/255, b/255, a/255)`. The hex values below are written `#RRGGBB @alpha`. |
| `includeFontPadding = false` | Use tight line boxes (`lineHeightMultiple` 1, no extra leading). |
| TextView auto-size uniform (min..max, step 1 sp) | `adjustsFontSizeToFitWidth = true` with `minimumScaleFactor = min/max`, single line. |
| Toast | A transient banner at the bottom centre. SHORT is about 2 s, LONG about 3.5 s. Any equivalent works. |

### 0.1 Palette (`Hud.kt:23-27`)

| Name | Value | Hex |
|---|---|---|
| CYAN | rgb(95,242,255) | `#5FF2FF` |
| AMBER | rgb(255,211,106) | `#FFD36A` |
| RED | rgb(255,95,109) | `#FF5F6D` |
| WHITE | rgb(240,248,250) | `#F0F8FA` |
| DIM | argb(150,95,242,255) | `#5FF2FF @150/255 (0.588)` |
| Screen background | black | `#000000` (`HudScreen.kt:39`) |

Other one-off colours are listed with the view that uses them.

### 0.2 Fonts (`app/src/main/assets/fonts/`, `Hud.kt:33-42`)

- **Oxanium.ttf**: one variable font on the `wght` axis (300..800). It is used at weights **300** (the big number), **600**
  (the split values) and **700** (the outcome line and the START/STOP label). On iOS, register the TTF and build each
  weight with `UIFontDescriptor` and `kCTFontVariationAttribute` = `[2003265652 /* 'wght' */: weight]`. If variations
  fail, Android falls back to the default instance.
- **IBMPlexMono-Regular.ttf** (called "mono" below) and **IBMPlexMono-Medium.ttf** ("monoMed").
- OFL licence files ship next to them. Copy them into the bundle too.

---

## 1. Screen layout (`HudScreen.kt`)

The app has one screen, landscape and full screen. Its views are stacked in a `FrameLayout`, back to front
(`HudScreen.kt:95-151`):

1. **preview**: the camera picture (`SurfaceView`). It is sized to the full screen **height**, with width = height × (camera
   w / camera h), and centred (`MainActivity.kt:517-523`). With a 16:9 camera on a 20:9 screen this leaves black bars at the
   left and right. On iOS use an aspect-fit preview layer centred on a black background.
2. **frame** (`HudFrame`, `Hud.kt:70-77`): a full-screen horizontal linear gradient from x = 0.34·W (transparent) to x = W
   (`argb(232,0,8,14)` = `#00080E @0.91`), clamped, so it is flat transparent left of 0.34·W.
   It has a `color` property that `tone()` sets, but **nothing draws it**. No frame or border changes colour with state.
3. **flash**: a full-screen view with a transparent fill and a **10 dp CYAN stroke** inset on its bounds (the ready
   flash, §1.9). It rests at alpha 0.
4. **stateText**: the status line, top left.
5. **col**: the readout column, right side.
6. **startBtn**: the START/STOP button.
7. **bottomLeft**: the stats text, the setup-assistant button and the hidden developer row.
8. (A 2×2 dp WebView at the bottom left hosts the engine. It belongs to the pipeline spec and is invisible.)

**Safe area:** Android draws edge to edge (`Theme.DeviceDefault.NoActionBar.Fullscreen`). On iOS, hide the status bar and
set `prefersHomeIndicatorAutoHidden`. Keep the preview edge to edge. Apply the margins below **from the screen edges**,
but take the max with the safe-area inset, so the 40 pt left margin clears the Dynamic Island when it is on the left.

### 1.1 Status line (stateText) (`HudScreen.kt:45-46, 98, 155, 159`)

- Frame: left 40, top 20, right margin **416** (= 400 column + 16), height 30. It is one line.
- Font: monoMed, 20 sp, letter-spacing 0.1 em. It auto-shrinks to fit, down to 12 sp. Colour is the current tone (see below).
- Text: always `"● " + s.uppercased()` (U+25CF, then a space). The dot is a character, so it takes the text colour.
  The initial text is `"● STARTING…"`.
- `tone(c)` sets **only this text's colour**: CYAN for ready or neutral, AMBER for setup problems, a serve being measured
  or a video test running, RED for a fault.

### 1.2 Readout column (col) (`HudScreen.kt:100-120, 173`)

- Width **400**, full height, anchored right. It is a vertical stack with content aligned left (start).
- Padding: left 8, top 22, right 36. Bottom is **84** when idle and **14** while running (`running()`, `:173`).
  The bottom padding has no visible effect unless the coach box grows.
- Children, top to bottom:

| # | View | Spec |
|---|---|---|
| a | **serveLabel** | mono 13 sp, DIM, spacing 0.18 em. Initial text `SPIN RATE`. |
| b | **ballRow** (top margin 6) | horizontal, centred vertically: **SpinBall** 112×112, then 10 pt gap, then the *numbers* stack (fills the rest: 400 − 8 − 36 − 112 − 10 = 234 pt) |
| b1 | numbers → **bigText** | Oxanium **300**, 64 sp, CYAN. Outer glow: shadow radius 12 dp, offset 0, colour `#5FF2FF @150`. The text is `"<rpm> RPM"`, with the `" RPM"` part at **0.3×** size (19.2 sp, same font and colour), so "RPM" sits on the baseline. Idle text `----`. |
| b2 | numbers → **SpinScale** (top margin 6) | full width of the numbers stack, height 30 (§1.6) |
| c | **typeText** (height 36, top margin 8) | Oxanium **700**, 28 sp, WHITE, spacing 0.06 em. One line, shrinking to 16 sp. The outcome headline, e.g. `MEDIUM BACKSPIN`. |
| d | **qualText** | mono 14 sp, DIM, spacing 0.04 em. e.g. `curves right · off the bat` |
| e | **SpinSplit** (top margin 8) | full width, height 84 (§1.7) |
| f | **coachText** (top margin 8) | the message box (§1.8) |

- When there is no spin to show, the **SpinBall** is *hidden but keeps its 112×112 space*, so the number stays put. In that
  case **SpinSplit is removed from the layout** (GONE), which shifts the coach box up (`showSpin`, `:271-282`).

### 1.3 START/STOP button (`HudScreen.kt:74-79, 122, 166-180`)

- **176 × 48**. Idle: bottom right, right margin 36, bottom margin 24. Running (live or video test): **bottom left**,
  left margin 40, bottom 24. The column then has the full height free.
- Label: Oxanium 700, 15 sp, letter-spacing **0.3 em**, not forced to caps, text `START` or `STOP`, centred.
- Colour: CYAN when idle, RED when running. One colour drives the label, the hexagon stroke and the hexagon fill tint.
- Background: a hexagon face (§1.4) with no press animation (`stateListAnimator = null`). On iOS, give a press
  highlight only if it is cheap. Android shows none.
- **NEXT SERVE was removed in 0.13.24** (`MainActivity.kt:1103-1104`). Do not add it.

### 1.4 Hexagon ("cut-corner") button face (`Hud.kt:167-185`)

The bounds are b, with d = 1 pt. `inset = 1.5`, `cut = b.height × 0.5`. The path is closed:

```
(L+cut, T+inset) → (R-cut, T+inset) → (R-inset, midY) → (R-cut, B-inset) → (L+cut, B-inset) → (L+inset, midY)
```

- Fill: the colour's RGB at **alpha 46/255 (0.18)**.
- Stroke: the colour at the alpha it was given, **2 pt** wide, default (miter) joins. It is drawn centred on the path, after the fill.
- START/STOP uses CYAN or RED. The small buttons use **DIM**, so their stroke is DIM and their fill is `#5FF2FF @46`.

### 1.5 Bottom-left block (`HudScreen.kt:123-151, 179`)

It is a vertical stack, left margin 40, bottom margin 24, sized to its content, and is **hidden entirely while running**.

1. **statsText**: mono 9.5 sp, DIM, several lines. It is refreshed every **400 ms** (`MainActivity.kt:132, 167-180`). Lines:
   - `App 0.13.24`
   - `Camera {w}×{h} @ {fps} fps` (× is U+00D7), when a camera config exists
   - `Picture check: done` or `Picture check: waiting (not watching yet)`, only while a live pipeline is tapped
   - `Recording {n} frames/s` (live only)
   - `Watching: {x.x} ms a frame, {lag} ms behind[, {n} frames missed]` (live only)
   - `Engine ready|loading[ · {n} waiting][ · {n} movements weren't readable serves]`

   The ⇄ PIPELINE fields come from the companion spec.
   **A long press on statsText toggles the developer row** (`:145`). This is the only hidden gesture.
   (The comment at `MainActivity.kt:510-511` says "version number tapped five times". That comment is stale.
   The code uses a long press.)
2. **tools row** (top margin 8): one small button, `SETUP ASSISTANT: OFF` or `SETUP ASSISTANT: ON`.
3. **devTools row** (top margin 8, **hidden by default**), horizontal with 10 pt gaps:
   `TEST VIDEO`, `REPORT`, `CHIP WINDOWS: OFF|ON`, `OFFER TO SHARE CLIPS: OFF|ON`.
   Whether the row is visible also decides whether error toasts show (`devMode`, `:89`).

**Small button** (`:123-127`): monoMed 11 sp, letter-spacing 0.14 em, text CYAN, padding 16 horizontal and 8 vertical,
DIM hexagon face, sized to its content.

### 1.6 RPM meter, SpinScale (`Hud.kt:83-112`)

The view's height is 30 and its width W is the numbers stack's width. The scale is 0–4000 RPM, with `x(r) = W·min(r,4000)/4000`.

- Track: rect from (0, 2) to (W, 8), colour `#5FF2FF @38`.
- Fill (when there is a reading): rect from (0, 2) to (x(rpm), 8) in solid CYAN. Square ends, no animation.
- Pro-average marker at **2760 RPM** (x = 0.69·W): an AMBER rect 1.5 pt wide, from y = −0.5 to y = 10.5 (it pokes 2.5
  above and below the bar).
- Labels: mono 11 pt, baseline at y = 21.5.
  - `NO SPIN` in DIM, left aligned at x = 0.
  - `PRO AVG` in AMBER, centred on the marker, clamped inside [0, W − textWidth].
- With no reading, only the track, the marker and the labels show.

### 1.7 Spin split bars, SpinSplit (`Hud.kt:124-165`)

The view's height is 84, its width W is the column's content width (about 356), and it draws nothing when spin is null.
Units are pt.

- `total = max(1, √(lift² + curve² + gyro²))`. All three bars share this scale, so they **do not** add up to full width.
- Fonts: labels mono **12.5 pt** with letter-spacing 0.1 em. Values Oxanium **600, 15 pt**.
- Header: `REV/S` in DIM, right aligned to W, baseline y = 11.
- Rows i = 0,1,2, baseline `y = 31 + 23·i`:

| i | Label | Magnitude | "Known" |
|---|---|---|---|
| 0 | `BACKSPIN` if lift ≥ 0, else `TOPSPIN` | \|lift\| | always |
| 1 | `SIDE RIGHT` if curve ≥ 0, else `SIDE LEFT` | \|curve\| | always |
| 2 | `GYRO` | \|gyro\| | `spin.dirKnown` |

- Label at x = 0. Colour DIM when known, `#5FF2FF @90` when not.
- Bar: x0 = **104**, x-end = W − 38 − 6. Centre y = baseline − 4.2, height 6 (±3).
  - Track: x0..end, `#5FF2FF @34`.
  - Fill: x0 .. x0 + (end − x0)·mag/total. CYAN when known, `#5FF2FF @110` when not.
- Value: `round(mag)` as an integer, right aligned to W at the baseline. WHITE when known, `#F0F8FA @150` when not.

### 1.8 Message box, coachText (`HudScreen.kt:60-67, 258-266`)

There is one box for setup advice, assistant speech, tips, NoRead text, video-test info and problems.

- Background: rounded rect, radius 6. Fill `#00141E @150` (argb(150,0,20,30)).
- Normal: stroke 1 pt `#5FF2FF @90`. Text mono **16 sp**, colour `#EBF8FC @235`, line-height multiple **1.15**.
  Padding: left 12, top 9, right 12, bottom 10. It wraps over several lines and spans the full column width.
- **Problem** style: text **20 sp**, WHITE, stroke **3 pt RED**. The text is prefixed `"⚠  "` (U+26A0, then two spaces).
- Blank text hides the box and removes it from the layout (GONE).
- Initial text: `Checking the setup…`.

**Problem hold** (`:215-230`):
- `problem(text)` shows the box in problem style and starts a **15 s** hold.
- During the hold, setup advice does not replace it. The latest advice is *stored* and shown when the hold ends,
  if no newer problem has arrived. Assistant text keeps buffering during the hold but is not displayed. It shows (the whole buffer) with the next fragment after the hold ends.
- Any other `message()` call (a reading, a NoRead, a video test) replaces the problem at once and clears the hold.

### 1.9 Ready flash (`HudScreen.kt:161-165`)

`readyCue()` runs only in LIVE mode, from `setReady`:
- cancel any running animation, set alpha 0,
- animate alpha to **1 over 140 ms**, then after a **260 ms** delay animate to **0 over 700 ms**.
- Easing is Android's default, **accelerate-decelerate** (≈ easeInEaseOut).
- Quirk: `ViewPropertyAnimator` keeps the 260 ms start delay. So from the second flash on, the *rise* also starts
  260 ms late. Matching this is optional. Doing the rise immediately is fine.

### 1.10 Screen states (what each view shows)

| State | Status line (colour) | serveLabel | bigText | typeText / qualText | Ball + split | Message box | Button | Bottom left |
|---|---|---|---|---|---|---|---|---|
| Launch | `● STARTING…` (CYAN) | `SPIN RATE` | `----` | empty | hidden | `Checking the setup…` | START, right | shown |
| No camera permission | `● THE COACH NEEDS THE CAMERA` | – | – | – | – | – | START | shown |
| No slow motion | `● THIS PHONE DOESN'T OFFER SLOW MOTION TO APPS` | | | | | | START does nothing (no cfg) | |
| Camera config found | `● READY` (soon replaced by setup advice) | | | | | | | |
| Setup (idle preview) | `● SETUP · <SHORT>`. CYAN if the advice is ok (GOOD or NO_TABLE), else AMBER | unchanged from the last session | unchanged | unchanged | unchanged | the advice text (§3.4) or `ASSISTANT  …` | START | shown |
| Starting | `● STARTING…`, then `● STARTING: WAIT FOR READY…` (CYAN) | | | | | | STOP (red), **bottom left** | hidden |
| Ready | `● READY: SERVE` (CYAN) + flash + voice + chime | last reading | | | ball turning | last message | STOP | hidden |
| Serve spotted | `● GOT IT: MEASURING SERVE #7…` (AMBER) | | | | ball **held still** | | | |
| Measuring | `● MEASURING SERVE #7…`, then `● MEASURING SERVE #7… 42%` | | | | | | | |
| Result (read) | → Ready | `SERVE 07` or `SERVE 07 · 54 km/h` | `2710 RPM` | `STRONG BACKSPIN` / `off the bat` | ball and 3 bars | tip, "move closer", or empty (hidden) | | |
| Result (NoRead) | → Ready | `SERVE 07 · NO READ` | `----` | empty / empty | ball hidden, split gone | `Couldn't read it: …` | | |
| Stopped | `● STOPPED` (CYAN), then setup advice again once the preview restarts | | | | | | START, right | shown |
| Problem | unchanged | | | | | `⚠  <text>` in red style | | |
| Video test | §4.5 | `VIDEO TEST` | `····` (4 × U+00B7) | `READING THE VIDEO` / empty | hidden | the file name | STOP, left | hidden |
| Video test, none found | `● VIDEO TEST DONE: NO SERVE FOUND` (CYAN) | `VIDEO TEST` | `NONE` | `NO SERVE FOUND` | hidden | why (§3.7) | START | shown |

Notes:
- Back in idle (after Stop or a video test), the restarted preview's first setup advice (about 1 s later) replaces the
  status line and the message box. The "Stopped" and "none found" texts are therefore brief.
- The readout (number, ball, split) **persists** between serves and across Stop/Start. Only `noRead`, `testStarted` and
  `testFoundNone` clear it. A new live session does not clear it.
- `serveLabel` serve numbers are two digits, zero padded (`%02d`). The prefix is `screen.source`: `SERVE` in live, or
  `VIDEO TEST · SERVE` for answers from a video test, which gives `VIDEO TEST · SERVE 01`.

---

## 2. SpinBall: the 3D ball (`SpinBall.kt`)

It is a square view (112×112 in the column). **w** = view width in pt. Centre c = w/2, ball radius **r = 0.345·w**.
It draws only when an axis is set and w ≥ 8.

### 2.1 Input → axis (`SpinBall.kt:38-39, 101-111`)

`Spin(lift, curve, gyro, dirKnown, afterBounce)` is in rev/s. lift > 0 means backspin, curve > 0 bends right, gyro > 0
points away down the table.

```
v     = (-lift, -curve, gyro)
a     = v/|v|            (no drawing if |v| < 1e-6)
rate  = |v|  (rev/s)
```

The frame is the **screen frame: x right, y DOWN, z toward the viewer**. This is a left-handed view frame, and the
sign conventions below were checked against it by tests (`SpinBallTest.kt`). **Copy the formulas exactly. Do not
re-derive them.**

### 2.2 Constants (`SpinBall.kt:54-72`)

```
WHITE   = (244,244,241)       INK = (20,20,20)
DOT_RGB = red (208,32,42), blue (31,71,196), green (30,154,60)
DOT_DIR[k] = (cos θ, sin θ cos φk, sin θ sin φk), θ = 61.4°, φk = 0°,120°,240°   (k = red, blue, green)
DOTCOS  = cos 17°            (dot angular radius 17°)
LIGHT   = normalize(-0.22, -0.30, 0.93)
AMBIENT = 0.46
REST    = rot(normalize(0, -0.978, -0.210), 1.876 rad)   (the start pose: marked side toward the viewer)
```

`rot(a, t)` is Rodrigues, row-major 3×3, with c = cos t, s = sin t, k = 1 − c:
```
[ c+x²k    xyk−zs   xzk+ys ]
[ yxk+zs   c+y²k    yzk−xs ]
[ zxk−ys   zyk+xs   c+z²k  ]
```

### 2.3 Per-pixel sphere (`SpinBall.kt:123-151`)

It is rendered into an **n×n** RGBA texture, with `n = (clamp(Int(2r_px), 96, 220) / 2) * 2`. **r_px is in device pixels** (Android `width` is px): 2r = 0.69 × 112 dp × 2.675 ≈ 207 px on the reference phone, so n ≈ 206. On iOS use `2r × UIScreen.scale` (@3x: 232 → capped to 220).
The texture is then scaled with bilinear filtering into the rect `(c−r, c−r, 2r, 2r)`. In Metal this is a fragment shader
over a quad. In Core Graphics, fill a CGImage buffer.

```
M = rot(a, angle) · REST          (3×3 product, row-major)
for each pixel (px,py):
  dx = (px+0.5 − n/2)/(n/2);  dy = (py+0.5 − n/2)/(n/2)          // dy grows DOWN
  rr = dx²+dy²; if rr ≥ 1 → transparent
  nz = √(1−rr)
  // body coords = Mᵀ · (dx,dy,nz)   ← TRANSPOSE: columns of M
  bx = M0·dx + M3·dy + M6·nz
  by = M1·dx + M4·dy + M7·nz
  bz = M2·dx + M5·dy + M8·nz
  col = (bx < 0) ? INK : WHITE                 // half the ball inked; the seam is the plane bx = 0
  if bx ≥ 0: for k in red, blue, green: if dot((bx,by,bz), DOT_DIR[k]) > DOTCOS { col = DOT_RGB[k]; break }
  lit = dx·LIGHT.x + dy·LIGHT.y + nz·LIGHT.z
  sh  = 0.46 + 0.54·max(lit, 0)
  out = opaque, each channel = Int(col·sh)  (truncate)
```

The edge is not anti-aliased in the texture. The bilinear upscale softens it.

### 2.4 Animation (`SpinBall.kt:188-247`)

- `angle += 2π · (rate / 55) · dt`, with dt in seconds **capped at 0.05**. The ball turns **55× slower than life**, so a
  45 rev/s serve shows about 0.82 turns/s.
- It redraws every **32 ms** (≈30 fps), e.g. with a CADisplayLink at `preferredFramesPerSecond = 30`.
- `show(spin)` sets the axis and rate and resets the dt clock. It **does not reset `angle`**: the new serve continues
  from the current pose.
- `hold(true)` freezes rotation, used from "serve spotted" until the answer (`HudScreen.busy`). `hold(false)` resumes
  without a jump (the dt clock is reset).
- `clear()` drops the axis and draws nothing.

### 2.5 Overlay marks, drawn over the ball, in order (`SpinBall.kt:266-329`)

The origin is the view centre. Colours are CYAN at the alpha given. Strokes have **round caps and round joins**.

1. **Axle** (dashed line). (ax, ay) = (−a.x, −a.y). Draw it if hypot(ax, ay) > 0.06.
   Alpha **110**, width **0.013·w**, dash [on 0.028·w, off 0.022·w], phase 0.
   It runs from −(ax,ay)·L to +(ax,ay)·L, with **L = 1.42·r**. It gets shorter as the axis tips into the screen.
2. **Gyro ring**, if |a.z| > 0.1. A circle of radius **R = 1.2·r**, alpha **118**, width **0.014·w**, dash [0.032·w, 0.028·w].
   The dashes start at 3 o'clock and run clockwise on screen.
   **Chevrons**, only if `dirKnown`: alpha **205**, width **0.017·w**, arm length k = **0.046·w**.
   tw = +1 if a.z > 0 (reads clockwise), else −1.
   For j = 0,1,2: θ = −π/2 + j·2π/3, q = (R cos θ, R sin θ), at = atan2(tw·cos θ, −tw·sin θ).
   Draw the open polyline `q + k·(cos(at+π−0.62), sin(at+π−0.62)) → q → q + k·(cos(at+π+0.62), sin(at+π+0.62))`.
   This is a "V" whose tip points along the ring in the direction of travel.
   If `dirKnown` is false the ring is drawn without chevrons.
3. **Face-travel arrow** (the one solid arrow). f = (a.y, −a.x), mm = |f|. Draw it if mm > 0.1. u = f/mm.
   l = r·0.66·min(mm + 0.25, 1), hw = 0.062·w.
   - Shaft: solid CYAN, width **0.022·w**, from −u·l to u·(l − 0.7·hw).
   - Head: filled CYAN triangle, with ah = atan2(u.y, u.x), tip u·(l + 0.45·hw), and base points
     `u·l − hw·(cos(ah−0.5), sin(ah−0.5))` and `u·l − hw·(cos(ah+0.5), sin(ah+0.5))`.

Test vectors (`SpinBallTest.kt`):
- Pure backspin (45,0,0): the near face moves **down** on screen and the arrow points down.
- A positive gyro gives clockwise chevrons.
- A pure gyro has no axle.
- A gyro of 40 rev/s turns the dots by 0.30 rad over the test's interval.

Port these tests to Swift: they guard the sign traps.

---

## 3. Words: shown and spoken

### 3.1 Text-to-speech (`MainActivity.kt:121-127, 1185`)

- Engine: the system TTS with `Locale.US`. **Rate and pitch are not set** (Android default 1.0/1.0). On iOS use
  `AVSpeechSynthesizer` with voice `en-US`. The default `AVSpeechUtteranceDefaultSpeechRate` sounds different from
  Android's, so tune it by ear (start around 0.5). Pitch 1.0.
- `say(text, queue=false)` **flushes**: it stops current speech and clears the queue (`stopSpeaking(at: .immediate)`
  then `speak`). `queue=true` appends.
- Utterance id `"ready"`: when it finishes, if mode is LIVE and no turn is open (`turnId == 0`), play the **chime** (§3.2).
- When the AI assistant starts, TTS is stopped (`MainActivity.kt:322`).

### 3.2 Ready chime (`MainActivity.kt:1080-1086`)

The code calls `ToneGenerator(STREAM_MUSIC, volume 90/100).startTone(TONE_PROP_BEEP2, 220 ms)`. Frequencies are not in the Kotlin.
**[Unverified]** AOSP `ToneGenerator.cpp` defines TONE_PROP_BEEP2 ("general double beep") as two beeps of a 400 Hz + 1200 Hz
dual tone with a silent gap. The segment durations are recalled as 100 ms on, 100 ms off, 100 ms on, but this was not checked.
If that is right, the 220 ms cap cuts the second beep to about 20 ms, so the player hears one beep and a click.
Check the AOSP source, or record a real device, before choosing between one and two beeps.
On iOS, synthesise it with an `AVAudioPlayerNode` buffer (sum of two sines, about 5 ms fades) at about 0.9 volume, or record
it from a phone. Check by ear against an Android device.
The chime also plays without speech when `setReady(null)` is called in LIVE mode (no current caller passes null).

### 3.3 Turn speech and outcome words

**Outcome** (`Hud.kt:49-66`, `SpinLevel`):
- `rpm = round(rps · 6) · 10` (RPM to the nearest 10, `MainActivity.kt:1164`).
- Band: `<600` → 0, `<1500` → 1, `<2400` → 2, else 3.
- `kind` = the engine `type`, lowercased, cut before `" ("` and then before `" + "`, trimmed.
  Engine types (`assets/engine.js:1955-1965`): `no spin`, `mixed`, `gyro (corkscrew)`, `backspin`, `topspin`,
  `sidespin`, `side-backspin`, `side-topspin`. Each may have ` (curves left|right)` and/or ` + gyro` appended.
- `outcome = (band 0 or kind empty or kind == "no spin") ? "Almost no spin" : ["", "Light", "Medium", "Strong"][band] + " " + kind`.
  Examples: "Strong backspin", "Medium side-backspin", "Light topspin", "Strong gyro", "Medium mixed".
  (The arrays `NAMES`/`spoken()` say "WEAK"/"weak" but **nothing uses them**. Band 1 is "Light".)
- **Screen headline** (typeText) = `outcome.uppercased()`, e.g. `STRONG BACKSPIN`.

**Qualifier line** (qualText) (`HudScreen.kt:286-292`): the parts joined with `" · "` (U+00B7):
1. the text inside the first `(...)` of the type, e.g. `curves right` or `corkscrew`,
2. `after the bounce` if `spin.afterBounce`, else `off the bat`. This part is always present, even when there is no spin
   object (then `off the bat`),
3. `drill way unknown` if spin exists, `!dirKnown` and |gyro| ≥ 1.

**Speech for a read serve** (`MainActivity.kt:1180`): `"<Outcome>, <rpm>."`, plus `" Tip: <fix>"` when a tip is due.
Then the turn adds `" Ready."` (§4.2). For example: `Strong backspin, 2710. Ready.` or
`Medium backspin, 2450. Tip: Open the face and brush along under the ball. Ready.`

**Message box for a read serve** (`MainActivity.kt:1158, 1177`), in order of precedence:
1. If the engine's `ballPx` is in [1, 24]: `The ball is only <round(ballPx)> px across: move the phone closer.`
2. Else, if a tip is due: `TIP  <title>. <fix>` (two spaces after TIP).
3. Else empty, which hides the box.

When (1) shows, the tip is **still spoken**.

**Speed**: if the engine's `speed` (m/s) > 0.1, km/h = round(speed·3.6) is shown in serveLabel only: `SERVE 07 · 54 km/h`.
It is never spoken.

### 3.4 Tips (`Tips.kt`)

`TipPicker(every = 3)` is reset at each Start. Call it once per **readable** serve with the engine's `coach.kind`
(`thick`, `slip`, `slow`, `good`, or `none`/`other`/`unknown`/empty):
- Count the kind if it is `thick`, `slip` or `slow`.
- On every 3rd readable serve, return the most-counted fault's (title, fix), or nothing if there were no faults. Then clear the counts.
  (Ties: `HashMap.maxBy` returns the first maximum in map order. The order is not defined, and any tie-break is acceptable.)

| kind | title | fix |
|---|---|---|
| thick | You pushed the ball instead of brushing it | Open the face and brush along under the ball. |
| slip | The ball slid on the rubber | Let the ball drop lower and brush slightly into it. |
| slow | The brush was too slow | Accelerate through the contact: snap the wrist. |
| good | Strong backspin | Keep this contact. *(never returned: "good" is not counted)* |

### 3.5 Unreadable serves (NoRead, `core/NoRead.kt`, used at `MainActivity.kt:1109-1149`)

`NoRead.Advisor(every = 4)` is reset at Start. `noReads.read()` is called for every readable serve and for "into the net".
The cases, in the order `show()` checks them:

1. **Net** (`result.net == true`): screen `Couldn't read it: Into the net`, speech `Into the net.` (+ " Ready.").
2. **Not clear** (no `serve`, or `serve.clear != true`):
   `cause = NoRead.of(serve.marking, serve.toward, serve.issues, result.error, result.crossed)`.
   `crossed` is computed by the app from the capture header's track (`NoRead.crossed(xs, W)`, `MainActivity.kt:1036-1041`)
   and stored on the result. Then `(spoken, shown) = noReads.next(cause)`:
   - no cause: spoken `Couldn't read that one.`, shown `Couldn't read it: <why>`, where why = result.error, else the
     first `serve.issues` entry, else `no clear reading` (the app's fallback text, not the Advisor's).
   - with a cause: spoken `Couldn't read that one: <short>.` plus `" <fix>"` only if this cause's fix was not given in
     the last 4 serves. Shown (always with the fix): `Couldn't read it: <short>. <fix>`.

   | Cause | short | fix |
   |---|---|---|
   | MARKING | the marking stayed facing the camera | Use the dot-marked ball. |
   | TOWARD | it flew toward the camera / *it flew away from the camera* | Film from the side of its flight. |
   | LIGHT | the ball was blurred or faint | Add more light. |
   | SMALL | the ball was too small | Move the phone closer. |
   | LOST | the ball got lost in the background | Put a plain wall behind its flight. |
3. **Bounce-only and slow** (no clear phase other than `bounce`, and rps < 10): **the screen is not updated**, the
   NoRead advisor is called with null, and the speech is `Couldn't read that one.` (+ Ready.).

Screen for cases 1 and 2: `noRead(serve, text)` (§1.10). The speech is spoken only in LIVE mode.

### 3.6 Setup advice (`core/SetupCheck.kt:118-134`, `SetupGuide.kt`, `MainActivity.kt:245-274`)

**Input to `SetupCheck.advise(y, u, v, 320, 180, tiltDeg, moving)`**: three **full-size 320×180 planes** (Y, U, V as raw
bytes; U and V nearest-neighbour sampled from the half-resolution chroma, `SetupGuide.kt:99-113`). An iOS bi-planar CbCr
buffer has to be de-interleaved and upsampled the same way.

**When.** While idle with the camera preview running. A 320×180 YUV sample of a small 16:9 camera stream is checked every
**≥300 ms**, using the device tilt (accelerometer) and motion (gyro magnitude > 0.25 rad/s within the last 600 ms counts as
"moving"). A kind is passed on only once it has held for **900 ms** and differs from the last one passed on
(`SetupGuide.kt:86-97`). ⇄ PIPELINE: the small stream comes from the companion spec's preview session.

Tilt on iOS: Android's accelerometer at rest reads **+9.8 on the axis pointing up**. Core Motion's `gravity` points
**down** and is in g. The device axes are the same (x right, y toward the top in portrait, z out of the screen). So use
`up = −gravity × 9.81`. Android ROTATION_90 (= iOS landscapeRight) gives `tilt = degrees(atan2(up.y, up.x))`, and a level
phone reads 0. Flat check: |up.z| > 8.5 m/s² (|gravity.z| > 0.87 g) → 0. Porting this literally without the sign flip
reads 180° and gets stuck on "Level the phone". Android's `SENSOR_DELAY_UI` is about 60 ms.

| Kind | short (status line) | text (box and voice) | ok? |
|---|---|---|---|
| MOVING | hold still | Hold the phone still, or set it on a tripod. | no (never spoken) |
| DARK | too dark | It's too dark. Turn on more lights or face the light. | no |
| TILTED | level the phone | Level the phone. It's tilted about N degrees. | no |
| NO_TABLE | no table in view | I can't see the table. Best: the table low in the picture, the ball flying across above it. A plain wall behind works too. | **yes** (cyan) |
| TABLE_LOW | tilt down | Tilt the phone down a little: more of the table should show. | no |
| END_ON | face the side | Face the side of the table, about level with the net, so the ball flies across the picture. *or* Turn to face the side of the table, so the ball flies across the picture. | no |
| FAR | move closer | Move closer: the table looks small. About 1.5 m from the ball's path. | no |
| NEAR | move back | Move back a little: the table fills the picture. | no |
| TABLE_HIGH | tilt up | Tilt the phone up a little: leave room above the table for the toss. | no |
| GOOD | looks good | Looks good. Best with the dot-marked ball, bright light and a plain wall behind the flight. Press Start when you're ready. | yes |

Shown with `screen.advice(short, text, ok)`, which sets the status line to `● SETUP · <SHORT>` (CYAN if ok, else AMBER).
The box shows the text unless a problem is being held (the text is then stored, §1.8) or the assistant is mid-turn.

**Spoken** (flush):
- If the AI assistant is connected and ready, the advice is *sent to it* instead (§5), MOVING included.
- Otherwise it is spoken unless the kind is MOVING:
  - a different kind from the last spoken one is spoken **at once**,
  - the same kind again is spoken only if **>15 s** have passed since the last spoken advice.

  `lastAdviceKind` is reset each time the setup check starts, so a new preview speaks its first advice.

### 3.7 Other spoken lines

| When | Words | Mode |
|---|---|---|
| Serve spotted | `Got it.` | flush |
| Picture check failed after 10 tries | `Picture check failed. Watching anyway; send the report.` | flush |
| Ready after calibration, or after the 30 s timeout | `Ready.` | queued, id ready → chime |
| After an answer | `<answer> Ready.` | queued, id ready → chime |
| Video test, no serve | `No serve found in this video.` | flush |
| Late answer (turn already closed) | `<answer>` without "Ready.", only if no newer serve was spotted (or not in LIVE) | queued |

Nothing is spoken at Start (`MainActivity.kt:445-446`).

Video test "no serve" explanation (`MainActivity.kt:711-718`). Map the tracker's rejection counts, take the top 3 by
count, and format them as `"<words>: <n>"` joined by `"; "`:

```
slow (a body?) → slow movements (a body?)        too big (a body?) → large moving shapes (a body?)
wandering (a body?) → people moving about (not a ball)   picture moving → moments with the picture moving
toss only → tosses with no flight after          upward (toss) → balls going up (tosses)
straight drop → balls dropping straight down     short flight → flights too short to measure
```

The text is `What it saw instead: <list>.` or, when none apply, `Nothing moved like a ball in flight.` Either way it is
followed by ` The ball has to fly across the picture, over the table, from a phone that stays still.`

### 3.8 Errors in the player's words (`MainActivity.kt:1188-1220`)

Every logged event goes to the report. An error additionally:
- shows a LONG toast with its raw text **only when the developer row is open**, and
- if it maps to a player line, calls `screen.problem(line)` (red box, 15 s hold).

`again` = `Press Start to try again.` when idle, else `Press Stop, then Start.`

The Android keys are log-text prefixes. **On iOS, use named error categories** with these lines:

| Category (Android prefix) | Player line |
|---|---|
| Camera failed while serving (`camera error`, `the camera refused`, `request:`, `session:`) | The camera stopped. Press Start to try again. |
| No frames from the recording stream after 3 s (`no frames from the camera`) | No picture from the camera. {again} |
| Encoder failed to start or failed while recording (`encoder`) | Recording failed. {again} |
| Decoder or tracker thread died (`decoder:`, `tracker:`) | Serve watching stopped. {again} |
| Engine failed to load (`the engine didn't load`) | The spin measurer didn't load. Close Serve Coach and open it again. |
| Picture check gave up (`picture check failed`) | Picture check failed: readings may be off. Watching anyway. |
| Setup check failed to start (`setup check:`) | Setup check is off. Line up the table by eye. |
| 30 s turn timeout (`#N: no answer in 30 s`) | That serve wasn't measured. Serve again. |
| Clip or file save failed (`save `) | Couldn't save a clip. Is the phone's storage full? |
| Everything else (shaders, chip queue, pose, report write, AI) | report only |

Other visible failures:
- The preview failed to open (after one retry without the setup check): problem
  `The camera didn't open. Close other camera apps, then open Serve Coach again.` (`MainActivity.kt:216`).
- Live camera error: the status line shows `Camera problem: <msg>`, then the app stops at once, so the status line reads
  `● STOPPED` and the problem box carries the player line.
- No recording frames after 3 s: status `No frames from the camera: stop and send the report`, RED (`:422`).
- Pipeline load note `too slow to watch every frame (N% lost). Move the phone back.` (once per session): a LONG toast,
  only when no serve is being measured or waiting (`MainActivity.kt:486-492`, `LivePipeline.kt:364-367`).
  `falling behind again (N% lost)` and `keeping up again` are logged only.

---

## 4. State machine

### 4.1 Modes (`MainActivity.kt:84-87`)

`IDLE` (preview and setup check), `LIVE` (serving session), `REPLAY` (video test reading frames). A flag `previewing` marks the
idle camera preview being on. `starting` guards against double Start.

```
launch ─▶ permission? ─no─▶ "The coach needs the camera"
             │yes
             ▼
        checkCamera: cfg? ─no─▶ "This phone doesn't offer slow motion to apps"
             │yes  state "Ready"
             ▼
   IDLE + preview + setup check (+ AI assistant if on) ──START──▶ stop preview ("Starting…")
             ▲                                                          │
             │                                                          ▼
     stopAll ("Stopped", report, share prompt, preview again)   LIVE "Starting: wait for Ready…"
             ▲                                                          │ picture check OK (or gave up after 10)
             │ STOP / app paused / camera error                         ▼
             └───────────────────────────────────────────── READY ◀─────────────┐
                                                               │ serve spotted  │ answer / 30 s timeout
                                                               ▼                │
                                                           MEASURING ───────────┘
```

### 4.2 The turn (live) (`MainActivity.kt:1064-1107`)

- **setReady(spoken)**: tell the pipeline to `resume()` (⇄ PIPELINE: it stops taking serves after each one it spots),
  set `turnId = 0`, tone CYAN, status `Ready: serve`, `readyCue()` flash, then speak `spoken` queued with id `ready`
  (the chime follows).
- First Ready: after Start, once the pipeline's picture check succeeds (⇄ PIPELINE `calibrate`, retried every 500 ms, up to
  10 times). If it never succeeds, the app speaks the failure line, watches anyway and calls `setReady("Ready.")`.
- **onSpotted(id)** (⇄ PIPELINE callback, after the pose veto, §6): set `turnId = id`, tone AMBER,
  status `Got it: measuring serve #id…`, hold the ball, say `Got it.` (flush), and start a **30 s** timer. If the turn is
  still open when it fires: log the error (which shows the problem `That serve wasn't measured. Serve again.`) and call
  `setReady("Ready.")`.
- While a serve is measured, further serves are **ignored** by the pipeline: one serve at a time.
- Engine progress: status `Measuring serve #id…`, then `Measuring serve #id… NN%`.
- **Answer** → `show(result)` → **turnDone(spoken, id)**: release the ball hold. If LIVE and id == turnId:
  `setReady("<spoken> Ready.")`. Otherwise (a late answer after the timeout) speak it alone, only if no newer serve was spotted.
- Readings are queued, up to 12 live (30 in a test) and 1.5 GB. The oldest are dropped with a log entry. ⇄ PIPELINE.

### 4.3 Buttons and gestures

| Control | Action |
|---|---|
| START | IDLE: `startLive()`. If the preview is on it is closed first (status `Starting…`), then the session opens. In LIVE or REPLAY it acts as STOP. |
| STOP | `stopAll()`: write counters into the report, stop camera, tap, pipeline, pose and recorder, mode IDLE, `turnId = 0`, START button back on the right, tone CYAN, status `Stopped`. If it was LIVE: `finishSession()` (§7.3). Then restart the preview. |
| SETUP ASSISTANT: OFF/ON | Toggles the AI assistant (§5). |
| long-press stats text | Show or hide the developer row. |
| TEST VIDEO (dev) | IDLE only: document picker for `video/*` → video test (§4.5). |
| REPORT (dev) | Copy the full report (all sessions' results since launch) to the clipboard as plain text, label `Serve Coach report`. Toast (LONG) `Report copied (it is also in the session's folder)`. |
| CHIP WINDOWS (dev) | Toggles the persisted `windowRing` (⇄ PIPELINE option). It also sends video tests through the GPU path. |
| OFFER TO SHARE CLIPS (dev) | Toggles the persisted `shareClips`, which controls the share prompt at Stop. |

On Start (`MainActivity.kt:436-446`): new session folder name, `tips.reset()`, `noReads.reset()`, `latestSpotted = 0`,
`source = "SERVE"`, mode LIVE, button STOP, tone CYAN, status `Starting: wait for Ready…`.

### 4.4 Setup assistant on/off (`MainActivity.kt:284-335`)

- **OFF → ON**:
  - If no key is saved, show the key dialog (§5.4).
  - Otherwise set `ai = true` and change the button to `SETUP ASSISTANT: ON`. Toast (SHORT)
    `Setup assistant on, key ••••<last 4>` (four U+2022). If the preview is running, start the coach now. Otherwise it
    starts when the preview reports running.
- **ON → OFF**: set `ai = false`, stop the coach, change the button text.
- The coach runs **only while idle with the preview on**. Start, pause or a preview stop closes it. It reopens with the next
  preview if `ai` is still true.

### 4.5 Video test (`MainActivity.kt:600-780`)

1. On a picked file:
   - stop the preview, set mode REPLAY, show the button as STOP (left),
   - `testStarted(name)`, where name is the last path segment after `/` and `:`, or `the video`,
   - tone AMBER, status `Video test: reading the video…`, reset the counters.
2. Frames are fed to a blocking pipeline (⇄ PIPELINE). Progress every 60 frames: `Video test: looking for serves… N%`, or
   via the chip path `Video test on the chip: N%`.
3. At the end: `stopAll()` (mode IDLE, status `Stopped`), then:
   - **0 serves**: `testFoundNone(why)`, `source = SERVE`, CYAN, `Video test done: no serve found`, say `No serve found in this video.`
   - **N serves**: AMBER, `Video test: measuring N serve(s)…`. As answers come in, they show as `VIDEO TEST · SERVE 0k`.
     When all N are answered: CYAN, `Video test done: <clear> of <N> serve(s) read`, `source = SERVE`.
4. Error: RED, `Video test failed: <msg>`.

In the test, readable answers are **spoken** (without "Ready."), NoRead and net answers are not, and nothing is saved to a
session folder or report.json.

---

## 5. AI setup assistant: Gemini Live (`GeminiCoach.kt`)

### 5.1 Transport

- WebSocket: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>`
  (`GeminiCoach.kt:26, 43`). It sends a ping every 20 s and has no read timeout. Use `URLSessionWebSocketTask`.
- Models, tried in order: **`gemini-3.8-live`**, then **`gemini-3.1-flash-live-preview`** (`:27`). Copy them verbatim.
- On open, send the setup message (`:64-71`):

```json
{"setup":{"model":"models/gemini-3.8-live","responseModalities":["AUDIO"],
  "systemInstruction":{"parts":[{"text":"<INSTRUCTIONS>"}]},
  "outputAudioTranscription":{}}}
```

### 5.2 Messages

- **Frame** (`:74-78`), only after `setupComplete`: `{"realtimeInput":{"video":{"data":"<base64 JPEG>","mimeType":"image/jpeg"}}}`.
  Send one about **every 1 s**: a JPEG at quality 70 of the setup-check stream (Android: the smallest 16:9 YUV size between
  480 and 960 wide, else 640×360). ⇄ PIPELINE supplies the frames.
- **Ask** (`:81-86`), called with the advice text each time the on-device advice changes (§3.6). It is skipped if the coach
  is not ready, **is still speaking**, or was asked **< 5 s** ago:
  `{"realtimeInput":{"text":"The phone's own check says: \"<advice text>\". Look at the latest picture: what one change should I make now?"}}`
- **Receiving** (`:88-102`):
  - `setupComplete` → ready (status `AI coach on (<model>)`).
  - `goAway` → status `AI coach: the session is ending`.
  - `serverContent.modelTurn.parts[].inlineData.data` → base64 **PCM 16-bit mono 24 kHz**. Set speaking = true and play it.
  - `parts[].text` and `serverContent.outputTranscription.text` → the text callback.
  - `turnComplete` or `interrupted` → speaking = false, then the text callback receives `"\n"` (end of turn).
- **Close / failure** (`:53-62`):
  - If it closes before it was ready and the reason contains "model" (case-insensitive), try the next model once
    (status `AI coach: trying <model>`).
  - Else, if it closes before ready with transcripts on and the reason contains "transcription", "unknown" or "invalid",
    reconnect once **without** `outputAudioTranscription`.
  - Else status `AI coach: the API key was refused` if code 1008 or the reason contains "API key", otherwise
    `AI coach stopped: <reason>`. There is no reconnect after that.
- Statuses are **logged only**, except: a status containing `refused` deletes the saved key, sets `ai = false`, sets the button
  to OFF, stops the coach and shows the status as a LONG toast (`MainActivity.kt:326-329`).
- Stop: close with code 1000, reason `setup done`.

### 5.3 INSTRUCTIONS (verbatim, `GeminiCoach.kt:105-107`, three lines)

```
You help a table tennis player set up a phone that films their serves at 240 frames a second, to measure the ball's spin. You see what the phone's camera sees, about once a second.
A good setup: the phone in landscape, still (a tripod), level, beside the table, facing its side, roughly level with the net, 1 to 1.5 metres from where the ball flies. The server's half of the table low in the picture, with room above it for the toss; the ball must fly across the picture, not toward the camera. Even, bright light; nobody walking between the phone and the table; a plain background (a wall) behind the ball's path helps. The dot-marked ball reads best: a half-black ball can keep its black side to the camera, and then its spin can't be seen. Once the framing is good, remind the player once of the dot-marked ball, bright light and a plain background.
Speak in English, calmly, one short instruction at a time (at most 12 words), the most important first: for example "Move the phone back a little" or "Tilt it down so the whole table shows". When the setup is good, say "Looks good, press Start." Talk about nothing else.
```

### 5.4 Key entry dialog (`MainActivity.kt:296-313`)

- Alert title `AI setup coach`.
- Message: `While you set the phone up, it sends what the camera sees, about one picture a second, to Google's Gemini and plays its spoken advice. Nothing is sent once you press Start. It uses your own Gemini API key (aistudio.google.com/apikey), kept on this phone only.`
- One **secure text field**: placeholder `Gemini API key`, single line, autofill off
  (`textContentType = .none`, `isSecureTextEntry = true`, no autocorrect).
- Buttons: `Turn on` (trims the input. If non-empty, saves it and toggles on) and `Cancel`.
- Storage: **Keychain**, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, so it is never in backups and never migrates.
  Android stores it in private prefs with backup and device transfer disabled.
- Display: only ever masked as `••••` + its last 4 characters.

### 5.5 Audio and on screen

- Assistant audio uses a streaming player (`AVAudioEngine` + `AVAudioPlayerNode`, Int16 mono at 24000 Hz, converted as needed),
  with usage "assistant speech". TTS is stopped when the coach starts.
- Text (`HudScreen.kt:231-238`): fragments are appended to a buffer, and the box shows `ASSISTANT  <buffer trimmed>` (two
  spaces) unless a problem is being held. On `"\n"`, the buffer is cleared and "talking" ends. The box keeps the last text.
  While talking, setup advice still updates the status line but not the box.

---

## 6. PoseWatcher: "is the player walking?" (`PoseWatcher.kt`)

- Model: MediaPipe Tasks **Pose Landmarker lite**, asset `pose_landmarker_lite.task` (`app/src/main/assets/`, stored
  uncompressed). Options: running mode **VIDEO**, **numPoses 1**. Android uses `tasks-vision:0.10.35`. On iOS use
  `MediaPipeTasksVision` (CocoaPods) `PoseLandmarker` with the same `.task` file, or Apple Vision
  `VNDetectHumanBodyPoseRequest` with the equivalent joints, if exact parity is not required.
- It runs on its own serial queue. The model is created at pipeline start. A load failure is logged as `pose model: …` and
  is not shown to the player.
- **Input**: the tracker's small picture (a quarter-size YUV "Quarter"), converted to RGB with BT.709
  (`R = Y + 1.5748V`, `G = Y − 0.1873U − 0.4681V`, `B = Y + 1.8556U`, clamped). The timestamp is
  `frameNo · 1000 / fps` ms. ⇄ PIPELINE calls `offer(q, frameNo)` on every 16th tracker sample, and the tracker samples
  every 2nd frame (`LivePipeline.kt:73, 330`). So it is offered every **32 camera frames**, which is **7.5 looks/s at 240 fps**.
  (The class comment says "about 15 a second". The code gives 7.5.) An offer is dropped if the previous one is still running.
- **Per result**: if landmarks exist (≥ 25), it records hip-centre x = mean of landmarks **23, 24** x, and torso length =
  distance from the hip centre to the shoulder centre (landmarks **11, 12**), in small-picture pixels. It keeps the last 600.
- **veto(from, to)** (`:89-103`), called by the pipeline when a serve is found, **before** `onSpotted`
  (`LivePipeline.kt:1101`). It takes the points with frame number in [from − fps/2, to]:
  - with fewer than 3 points, or a median torso < 5 px, there is no veto,
  - otherwise travel = the largest |Δ hipX| between any two points ≤ fps frames (1 s) apart,
  - if travel / torso > **1.0**, it **vetoes**: the movement is not treated as a serve. It is not spotted, not spoken
    and not measured, and **no clip is saved**. Only the note
    `not a serve: the player was walking (hips moved X.X torso lengths in a second)` is logged (kind `skipped`).
  - No person seen means no veto.
- Report counters: `pose {ready, looked, found, ms (running avg over ≤60), notServes}`.

---

## 7. Persistence and files

### 7.1 Settings (`SharedPreferences "coach"`, so `UserDefaults` on iOS, except the key)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `ai` | Bool | false | Setup assistant on |
| `key` | String | none | Gemini API key → **Keychain** on iOS |
| `windowRing` | Bool | false | CHIP WINDOWS (dev) |
| `shareClips` | Bool | false | OFFER TO SHARE CLIPS (dev) |

The developer row's visibility is **not** persisted: it is hidden at every launch.

### 7.2 Session folder

- Android: `Download/ServeCoach/Session_yyyyMMdd_HHmmss/` (local time, en-US digits), via MediaStore.
  iOS suggestion: `Documents/ServeCoach/Session_…/` with `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`, so the
  folders are visible in the Files app.
  Mark them `isExcludedFromBackup` (Android disables all backup).
- Files:

| File | When | Contents |
|---|---|---|
| `serveNN.mp4` | **always, in LIVE**, for every captured serve (`MainActivity.kt:800`) | the serve's slow-motion clip (⇄ PIPELINE produces it) |
| `skipped<n>_<why-slug>.mp4` | a movement the **tracker** rejected (not the pose veto), up to 30 a session, not while a serve is measured (⇄ PIPELINE `onSkippedClip`) | its clip. The slug is the reason with every run of characters outside `a-z` replaced by `-` and the ends trimmed (no lowercasing) |
| `serveNN.json` | each answer in LIVE (not in a video test) | `{app, session, serve, clip:"serveNN.mp4", time:<epoch ms>, capture:<capture header JSON or null>, result:<engine result minus data>, data:<engine data or null>}` |
| `report.json` | see §7.3 | session report |

  NN is the capture id, zero padded to 2 digits.
- Scratch: `cache/captures/` (`cap_<id>.bin/.json`) is wiped at launch and each pair is deleted after its answer. ⇄ PIPELINE.
- **Retention** (`MainActivity.kt:58-59, 842-872`): at launch, delete every session folder whose newest file is older than
  **30 days**, never touching the current session.

### 7.3 report.json (`MainActivity.kt:1222-1297`)

- Written: **after every answer in LIVE**, **every 30 s while LIVE** (a timer tied to the session name), and **at Stop**.
  At Stop it waits first for pending measurements: it checks every 500 ms, up to 60 tries (30 s).
  It is never written in REPLAY or before the first session. The file is created once per session and then
  **overwritten in place** (truncate and write the whole text). Writes run one at a time on a low-priority background queue.
  The JSON is pretty-printed with 1-space indent.
- Top-level keys:
  - `device`: `{maker, model, android, sdk}` (iOS: `{maker:"Apple", model:<hw id>, ios:<version>}`)
  - `app`: version name
  - `camera` (the probe), `chosen` `{camera, size:"WxH", fps, codec, encoder}` or null
  - `engine` `{webCodecs, mp4box, engine}`
  - `watchingBy`, `watching {…}`, `tap {…}`: ⇄ PIPELINE counters, defined in the companion spec
  - `pose {…}` (§6)
  - `events`: **this session's** log entries `[{t:<uptime ms>, kind, text}]`. Kinds include camera, setup, ai, error,
    skipped, load, capture, unclear, saved, files, replay.
  - `results`: this session's engine results. Each is the engine JSON plus `capture`, `wallMs`, `captureFrames`, `writeMs`,
    `answerMs` (spotted → answer) and `crossed`.
- The events of a new session start where the last session's report ended, so they include the setup events in between.
- The **REPORT** button copies the same structure, but with `results` from **all** sessions since launch.

### 7.4 Share at Stop (opt-in) (`MainActivity.kt:929-962`)

Only if `shareClips` is on and the session has more than one file. It shows **1.5 s after** report.json is written:
- Title `Share this session's clips?`
- Message: `This sends {what}, with each serve's data and report.json ({files} files, {MB} MB, in Download/ServeCoach/{session}), to wherever you pick next.\n\nOnly share them if the player agreed, and a parent or guardian too if the player is under 18.`
  `{what}` = `1 video clip that shows the player` or `N video clips that show the player`. MB is the integer bytes/1,000,000.
  On iOS, replace the path with the iOS location.
- Buttons `Choose where to send` (share sheet with all of the session's file URLs, chooser title `Send the session`) and
  `Don't share`.

---

## 8. Permissions, lifecycle, platform

- **Permissions** (`AndroidManifest.xml`): **CAMERA** and **INTERNET** only. There is **no microphone permission**: clips have
  no audio. iOS needs `NSCameraUsageDescription` and no `NSMicrophoneUsageDescription`. Camera is requested at launch.
  If denied, the status line reads `● THE COACH NEEDS THE CAMERA` and nothing else happens. Android re-checks only
  through the permission callback, so on iOS, also re-check on foreground.
- **Keep screen on**: always while the app is open (`FLAG_KEEP_SCREEN_ON`), so set `isIdleTimerDisabled = true`.
- **Orientation**: fixed `landscape` (Android's single landscape, the top of the device to the left). On iOS use
  **`landscapeRight`** only (`UISupportedInterfaceOrientations` = LandscapeRight), so the camera geometry and the tilt
  sign match. Full screen, no status bar.
- **App name** `Serve Coach Live`. Bundle id analogue `com.monti.servecoach`. Android has no custom icon (the system camera icon).
- **Pause** (app to background, `onPause`, `MainActivity.kt:574`): stop the preview, setup check and assistant. If LIVE
  **or a video test** is running, `stopAll()` (so a live session finishes: report and the share prompt flow). Pause the
  engine, but only once nothing is waiting or being measured: the remaining answers finish first (⇄ PIPELINE).
- **Resume**: resume the engine (and measure anything queued), then restart the idle preview. The app always comes back
  **idle**, never live.
- **Destroy**: stop everything, shut down TTS and release the chime.
- **Audio session (iOS)**: TTS, the chime and the 24 kHz assistant audio all play while the camera runs. Use
  `.playback` (or `.playAndRecord` without the mic if the capture spec needs it) with `.mixWithOthers` and `.duckOthers`
  as preferred. Android plays the chime on the music stream at volume 90 and the assistant with usage ASSISTANT.

---

## 9. Implementation checklist (traps)

1. Follow the code, not the snapshots: no NEXT SERVE, "SETUP ASSISTANT", dev row hidden, headline with a strength word.
2. Kerning = em × pt. Auto-shrink the status line (20→12) and the headline (28→16).
3. Only the status line changes colour. The HUD frame never does. The only border is the cyan ready flash.
4. SpinBall: transpose M (`Mᵀ·p`), y down, angle continues across serves, hold while measuring, 55× slow, 30 fps.
5. The tip is spoken even when the "move closer" line replaces it on screen. A bounce-only slow read leaves the screen unchanged.
6. NO_TABLE counts as ok (cyan). MOVING is never spoken. Repeats of the same advice wait 15 s.
7. A problem box holds 15 s against advice. A reading replaces it at once.
8. The chime plays only after the "ready" utterance finishes, in LIVE mode, with no open turn.
9. Keychain for the key, masked display, a secure field.
10. Clips are always saved live. Only the share prompt is opt-in.
