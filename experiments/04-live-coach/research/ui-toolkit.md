# Which UI toolkit the next Serve Coach Live UI is built on

The product review of 16 Nov left one decision open: the UI toolkit. For the investor demo the
current Views were adjusted; this note is the decision for after it.

**Recommendation: Jetpack Compose, added inside the existing activity as an overlay on the
current screen, one widget at a time, with the camera `SurfaceView` and the engine `WebView`
left exactly as they are.** Write the composables so they could move to Compose Multiplatform
later, but don't set up Kotlin Multiplatform now. A web UI in the WebView is the one option to
rule out.

Nothing here was measured on a phone. Every statement about frame rate, memory or APK size
below is reasoning from the code and the docs, and is marked as such. The prototype in the last
section is the measurement, and it decides.

## What the UI is today

About 1,000 lines of hand-built Views, all in code (no XML layouts, no AndroidX at all):

| file | lines | what it is |
|---|---|---|
| `HudScreen.kt` | 302 | The one screen: a `FrameLayout` with the camera `SurfaceView`, a shading view, a "ready" flash, the state line, the right-hand readout column (label, big RPM, outcome word, qualifier, scale, ball, split, coaching box), START / NEXT SERVE, and a bottom-left block (stats, Setup assistant, a hidden developer row opened by long-pressing the version line). Everything is reached through methods: `state()`, `tone()`, `reading()`, `noRead()`, `advice()`, `problem()`, `aiText()`, `running()`, `readyCue()`, `testStarted()`… |
| `Hud.kt` | 185 | Colours and fonts (Oxanium variable font, IBM Plex Mono from assets), `SpinLevel` (pure logic), and three custom-drawn views: `HudFrame` (gradient), `SpinScale` (bar + pro-average tick), `SpinSplit` (three bars), plus `HexDrawable` (the cut-corner button face). |
| `SpinBall.kt` | 330 | `Spin`, `SpinMath` (pure, tested), and the `SpinBall` view: a per-pixel shaded sphere rendered into a bitmap and redrawn about 30 times a second, with a `hold()` that stops it while a serve is measured, because it competes with the tracker and engine for CPU. |
| `SetupGuide.kt` | 139 | Not UI: a camera `ImageReader` and sensor listener that produces advice. It only calls back with text. |

Outside those files, `MainActivity` (1,293 lines, a plain `android.app.Activity`, theme
`Theme.DeviceDefault.NoActionBar.Fullscreen`) adds two `AlertDialog`s (Gemini key, share clips),
a few `Toast`s, the system file picker and share sheet. It also keeps raw references to five of
`HudScreen`'s widgets (`stateText`, `bigText`, `coachText`, `statsText`, `startBtn`) and writes
`statsText.text` directly every 400 ms. Everything else goes through `HudScreen`'s methods
(about 30 `screen.` calls).

**What touches the camera, GL and WebView:**

- The camera preview is a `SurfaceView` owned by `HudScreen`. `MainActivity` sets its fixed
  size and hands `holder.surface` to Camera2 (setup preview) or to `GlTap.showOn()` (live).
  Its frames go straight to the compositor; the UI thread never touches them.
- The capture/GL pipeline (`LivePipeline`, `GlTap`, `CaptureGl`) runs on its own threads and
  handlers. It reports to the activity through callbacks the activity posts to the main thread.
  It never touches a view. `LivePipeline` has no reference to `screen`.
- The engine `WebView` is a **2×2 px view** added to `HudScreen.root` by `MainActivity`. It is a
  headless compute engine, not UI. It loads `engine.html` and `engine.js` (copied from
  `experiments/02-spin-meter/web`), serves capture bytes through `shouldInterceptRequest`, and
  calls back through `@JavascriptInterface Bridge` (`onReady`, `onProgress`, `onResult`), each of
  which posts to the main thread. `pauseTimers()` is used to rest it in the background.

**What the tests cover:**

- `HudShot`: 10 Paparazzi 1.3.5 snapshots of `HudScreen.root` on a 2400×1080 landscape device:
  idle, setup advice, AI coach, video test with nothing found, a series of readings, measuring,
  and four spin cases (gyro, after the bounce, no dots, plain backspin). Committed under
  `app/src/test/snapshots/images/`.
- `HudFilm`: measures, lays out and `draw(Canvas)`s `HudScreen.root` into its own bitmap, to
  write a raw RGB still and a 36-frame film of the ball turning.
- `SpinBallTest` checks `SpinMath` against the drawn ball. `SpinLevelTest` and `TipPickerTest`
  are pure logic.
- Nothing runs on a device: the review notes the WebView crashes on emulators.

**What the UI/UX phase is going to ask for** (from the review): the HUD keeps changing (it was
reworked in 0.9, 0.10 and 0.13.22). A session-review screen is a candidate. Placement and net
height are in scope, which means an overlay registered to the camera picture (the table outline,
bounce marks). Toasts need to become HUD messages, and the developer row needs a menu. So the
phase means several new screens and an overlay, not a restyle of one HUD. That is what makes
"stay on Views" expensive.

## The options

### A. Stay on Views

- **Effort to move:** none.
- **Risk to 240 fps / memory:** none new.
- **APK and startup:** unchanged (about 24.2 MB for 0.13.0; most of it is MediaPipe's native
  library and the 5.5 MB pose model).
- **Testing:** unchanged; Paparazzi works today.
- **iOS:** nothing carries over.
- **Iteration speed:** this is the cost. Every layout is `LinearLayout` + `LayoutParams` + `dp()`
  by hand; `running()` swaps gravity and margins in code to move one button. The 16 Nov
  Android reviewer's words: "a UI built in code will slow a redesign". There are no previews.
  A layout change means running Paparazzi, or a phone and a table.

Right for the demo (and that was done); wrong for a phase that adds screens.

### B. Jetpack Compose on Android, inside the current activity (recommended)

- **Effort to move:** moderate, and it can be done in steps. `ComposeView` is a View, so it goes
  into `HudScreen.root` above the `SurfaceView` and below nothing, and takes over widgets one at
  a time. The camera stays a `SurfaceView` (no `AndroidExternalSurface` and no CameraX
  viewfinder needed). The `GlTap.showOn(holder.surface)` contract doesn't change. The engine
  WebView stays a 2×2 View.
  - Prerequisite: `ComposeView` needs a `ViewTreeLifecycleOwner` (and a saved-state owner) on
    its window. `MainActivity` is a plain `Activity`, so it either becomes
    `androidx.activity.ComponentActivity`, which sets these up and doesn't need an AppCompat
    theme, or sets the owners by hand. The usual "ViewTreeLifecycleOwner not found" crash comes
    from exactly this. That requirement is stated in the interop docs and the activity release
    notes; the plain-`Activity` failure mode is from forum reports.
  - Build: Kotlin 2.0.21 is already Kotlin 2, so the Compose compiler is the
    `org.jetbrains.kotlin.plugin.compose` Gradle plugin at the same version. Add a Compose BOM
    and `androidx.activity:activity-compose`. AGP 8.7.3 and compileSdk 35 are within what
    current Compose needs, but confirm the exact BOM against compileSdk 35 when adding it.
- **Risk to 240 fps / memory (reasoning):** low, for structural reasons. The 240 fps work never
  runs on the UI thread: the preview is a `SurfaceView` composited outside the app's view
  drawing, and capture/GL/encode run on their own threads. Compose can only cost the pipeline
  through CPU and GPU contention: the same cores and the same GPU that `GlTap` uses. The app
  already knows this matters; `SpinBall.hold()` exists for it. The risks to watch:
  1. Recomposition storms, where a fast-changing value (progress %, the 400 ms stats string)
     invalidates a big tree. Keep state granular and read hot values in draw/layout lambdas.
  2. A `ComposeView` layered over a `SurfaceView` is the same arrangement as today's View
     overlay. It doesn't add a composition pass over the camera.
  3. The first composition and class loading cost some ms at startup. Most of it is a one-time
     JIT/AOT cost, which baseline profiles reduce.
  4. Memory: the Compose runtime and UI libraries are a few MB of heap and dex. That is small
     next to the frame ring and MediaPipe, but measure it.
- **APK and startup:** Google's own measurement (the Sunflower migration) was +782 KB to the
  download size on adding Compose, **with R8 full mode**. This app ships with
  `isMinifyEnabled = false`, so the unminified cost will be larger; expect a few MB on 24 MB
  (reasoning, not measured). Turning on R8 is worth doing, but as its own step: it needs keep
  rules for `@JavascriptInterface Bridge`, the JNI entry points of `libhwtex`, and MediaPipe.
  Don't let it ride along with the UI change.
- **Testing:** Paparazzi has supported Compose since 1.0, and 1.3.5 (the version here) renders
  composables and Views. The question is a `ComposeView` inside the existing `HudScreen.root`
  View tree (Paparazzi has to provide the lifecycle owners), and `HudFilm`'s own
  `draw(Canvas)` into a bitmap. Both should work, but the prototype has to prove it, because the
  current tests depend on it. Compose `@Preview` gives a design loop without a phone.
- **iOS:** the composables are the part of the UI that could later move to Compose
  Multiplatform with little change, if they're written without `android.*` types (see the next
  option).
- **Iteration speed:** the main gain. Layouts become declarative (`Row`/`Column`/`Box`, state in,
  UI out). There are previews and Live Edit, and Paparazzi snapshots of single composables.
  `SpinScale`, `SpinSplit` and `HexDrawable` are already `Canvas` drawing and port to Compose
  `Canvas`/`drawBehind` almost line for line.

### C. Compose Multiplatform now

- **Effort to move:** B plus a KMP restructure: a `:ui` (or `:shared`) module with
  `commonMain`/`androidMain`/`iosMain`, the Compose Multiplatform Gradle plugin, fonts moved to
  Compose resources, and an Xcode project to keep building.
- **Risk to 240 fps / memory and APK:** on Android, Compose Multiplatform *is* Jetpack Compose
  (it publishes the AndroidX artifacts for the Android target), so the same as B.
- **Testing:** Paparazzi in a KMP library module needs the new Android-KMP library plugin. Its
  support for that is in the 2.0 alphas (2.0.0-alpha05 is the latest found, Nov 2026), not in
  1.3.5. So the test setup moves to an alpha, or the snapshot tests stay in `:app`.
- **iOS:** Compose for iOS is stable since Compose Multiplatform 1.8.0 (May 2025); the latest
  stable found is 1.11.0 (May 2026), with 1.13 in beta. But be honest about what the UI is worth
  to an iOS port. It is about 1k of the app module's 6.2k lines. The bulk is Camera2 high-speed
  capture, GL ES shaders over `HardwareBuffer`, the NDK `hwtex` library, MediaPipe and the
  WebView engine, and none of it is shared code. iOS needs AVFoundation 240 fps capture, Metal
  or CoreVideo for the GPU path, and JavaScriptCore or a WKWebView for `engine.js`. The real
  KMP candidate is `:core`, which is already pure Kotlin/JVM (serve tracking, windows, setup
  check) and could become `commonMain` with little change. That is worth doing when iOS is
  real, and it's independent of the UI toolkit.
- **Iteration speed:** the same as B, plus KMP build overhead.

Paying the KMP cost now buys an iOS UI that is the smallest part of an iOS app. You already know
KMP from other apps, so this is a matter of when, not whether you can. B, written the right way,
keeps C one module move away.

### D. A web UI in the existing WebView

- **Effort to move:** a full rewrite in HTML/CSS/JS. The WebView goes from 2×2 to full-screen and
  transparent over the `SurfaceView`, and every state change crosses the JS bridge
  (`evaluateJavascript` in, `@JavascriptInterface` out).
- **Risk to 240 fps / memory:** the worst of the four, for a structural reason. Android gives an
  app **one WebView renderer process for all its WebViews** (Android's WebView memory guide).
  The UI's page would share that renderer with the spin engine, and `engine.js` does its search
  in Workers but runs the rest of its work on the page's main thread. During a measurement
  (1.3–5 s per serve, `under-three-seconds.md`), UI animation competes with or waits on the
  engine. `pauseTimers()` is process-wide (`MainActivity.pauseEngine()` says so), so resting the
  engine would freeze the UI too. A second WebView doesn't help, because there is still one
  renderer. A full-screen transparent WebView over a `SurfaceView` also adds a large GPU layer
  to composite every frame, on the GPU `GlTap` uses.
- **APK and startup:** about the same size. Startup waits for the WebView to load.
- **Testing:** Paparazzi can't render a WebView. The HTML could be tested in a desktop browser
  (Playwright), but the existing 10 snapshots would be thrown away. On-device tests are already
  blocked by the WebView crashing on emulators.
- **iOS:** the HTML would run in a WKWebView, which is the one real attraction.
- **Iteration speed:** fast in a desktop browser (hot reload), slow anywhere it touches the
  bridge or the camera.

Ruled out: it couples the UI to the engine's thread, and the app's whole design keeps the
measurement away from anything else.

### Side by side

| | A. Views | **B. Compose (Android)** | C. Compose Multiplatform | D. Web UI in WebView |
|---|---|---|---|---|
| Effort to move | none | moderate, in steps | B + KMP restructure | full rewrite + bridge |
| Risk to 240 fps / memory | none | low (UI thread only; measure) | as B | high (shares the engine's renderer) |
| APK / startup | unchanged | + a few MB unminified (measure); small first-composition cost | as B | ≈ same; waits on WebView |
| Testing | Paparazzi as now | Paparazzi 1.3.5 + Compose; prove `ComposeView` in `HudScreen` | Paparazzi 2.0 alpha for KMP modules | Paparazzi lost; browser tests |
| Path to iOS | none | composables portable if kept Android-free | UI ready; capture/GL/engine still to write | HTML reusable |
| UI/UX iteration | slow | fast (previews, Live Edit) | fast, slower builds | fast in a browser, slow on device |

## Migration plan

Each step ships a working app and keeps the snapshot tests green. No step touches `LivePipeline`,
`GlTap`, `CaptureGl`, the camera or the engine WebView.

0. **Baseline.** On the Pixel 8, record before any change: release APK size, cold start to the
   first preview frame, and from a live session's `report.json` the pipeline numbers
   (`trackMsAvg`, `lagMs`, dropped + skipped frames) and `answerMs` per serve. The stats line
   already shows the first three live.
1. **Prototype (de-risk). Done = one Compose element on the live screen, the numbers unchanged.**
   Add the Compose plugin, BOM and `activity-compose`. Make `MainActivity` a `ComponentActivity`.
   Add a `ComposeView` to `HudScreen.root`, layered like the current widgets, and move one
   self-contained, frequently updated widget into it: `SpinSplit` (custom `Canvas` drawing,
   changes every serve) and the coaching box (text that changes often and swaps style for
   problems). Then check four things:
   - Paparazzi: `HudShot` still renders the whole screen with the composable in it. `HudFilm`'s
     `draw(Canvas)` captures the composable.
   - A live session on the phone: `trackMsAvg`, `lagMs` and missed frames match step 0 within
     noise, and `answerMs` doesn't grow.
   - APK size delta, unminified; cold start delta.
   - Nothing goes wrong around `onPause`/`onResume` (preview restarts, the WebView pauses).

   If any of these fails, stop and stay on Views. This step is cheap to throw away.
2. **Put the screen's state in one place.** Introduce a `HudState` (an immutable data class: state
   line, tone, the reading, the coaching message with its problem hold, running, the buttons'
   on/off) that `HudScreen`'s existing methods update. Its logic (the 15 s problem hold, held
   advice, the AI line) moves out of the views into plain Kotlin, unit-tested like `TipPicker`.
   Replace `MainActivity`'s five direct widget references with calls on `HudScreen`
   (`statsText.text = …` becomes `screen.stats(…)`). `HudScreen` keeps its method API as a
   facade, so the activity barely changes. This is the step that makes a later Compose
   Multiplatform move cheap: state and composables with no `android.*` in them.
3. **Move the readout column, then the buttons.** State line, label, big RPM, outcome, qualifier,
   `SpinScale`, START / NEXT SERVE (`HexDrawable` becomes a `Shape`), the bottom-left block and
   the developer row. One PR per group, each with its snapshots re-recorded and compared. Fonts:
   Oxanium's weight axis maps to `FontVariation.weight` in a `Font` loaded from assets.
4. **`SpinBall` last, and maybe never.** It is a per-pixel bitmap renderer with a frame clock and
   a `hold()` tied to engine load. Wrap it in `AndroidView` and keep it as it is. Port it to a
   Compose `Canvas` drawing the same bitmap with `withFrameNanos` only if there's a reason.
   (The review's open item to measure its cost applies either way.)
5. **New screens in Compose from the start.** These are the session review, the placement /
   net-height overlay over the preview (a `Canvas` above the `SurfaceView`, in preview
   coordinates), and HUD messages instead of `Toast`s and `AlertDialog`s. This is where the
   phase gets its speed.
6. **Separately, R8.** Turn on minification with keep rules for the JS bridge, JNI and MediaPipe,
   checked on the phone. It recovers the size Compose added, and more.
7. **When iOS is decided, not before:** move `:core` to KMP `commonMain` first. Then move
   `HudState` and the composables into a Compose Multiplatform `:ui` module, keeping `AndroidView`
   wrappers (the camera surface, `SpinBall` if not ported) in `androidMain`.

## What to prototype first, and what would change the answer

Step 1, on the phone, in a live session at the table. It answers the only questions the reasoning
above can't:

| question | pass | if it fails |
|---|---|---|
| Does the pipeline notice? | `trackMsAvg`, `lagMs`, missed frames, `answerMs` within noise of step 0 | Stay on Views; look at recomposition first (Layout Inspector counts) |
| Do the screenshot tests survive? | `HudShot` and `HudFilm` render the `ComposeView` inside `HudScreen.root` | Snapshot the composables on their own, and keep one whole-screen test that composes Views in `AndroidView` |
| What does it cost to ship? | APK delta and cold-start delta written down | If the size matters, do step 6 (R8) before step 3 |
| Lifecycle | Preview and engine pause/resume as before | Fix the lifecycle owners before going on |

## Sources

- Using Compose in Views (`ComposeView`, composition strategies), Android Developers:
  https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views
- Compare Compose and View metrics (APK size and build time on adding Compose, measured with R8):
  https://developer.android.com/develop/ui/compose/migrate/compare-metrics
- WebView and memory (one renderer process for all of an app's WebViews):
  https://developer.android.com/topic/performance/memory/guide/webview-memory
- Compose Multiplatform 1.8.0, Compose for iOS stable (May 2025):
  https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/
- Compose Multiplatform compatibility and versions:
  https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Paparazzi releases and changelog (Compose since 1.0; KMP library plugin in 2.0 alphas):
  https://github.com/cashapp/paparazzi/releases, https://cashapp.github.io/paparazzi/changelog
- CameraX Compose viewfinder on `AndroidExternalSurface` (the alternative not needed here):
  https://android-developers.googleblog.com/2024/12/whats-new-in-camerax-140-and-jetpack-compose-support.html

Versions "latest found" are as of 16 Nov 2026 web searches; check them again when adding the
dependencies.
