# Getting a serve's answer under three seconds

The target is the wait a player has between finishing a serve and hearing the answer: the app's
own `answerMs`, from "Got it" to the spoken reading. Six serves on a Pixel 8 at 0.10.2 measured
3.27, 8.26, 3.62, 11.69, 3.27 and 4.05 seconds.

Everything below is measured. Where a plan was built on reasoning instead, it says so, and the
reasoning has mostly turned out to be wrong.

## Where the wait goes

`answerMs` is `fetchMs` plus the engine plus about 35 ms of dispatch, and `writeMs` sits *inside*
`fetchMs` — the WebView's fetch is almost entirely the wait for the writer. Queue time,
`answerMs` minus `wallMs`, is 5–20 ms on every serve: nothing waited behind anything. The event
log splits the write further, into decoding the stored frames and cutting the crop out of them.

| serve | answer | decode | cut | write | parse | engine | track |
|---|---|---|---|---|---|---|---|
| 1 | 3.27 s | 741 | 475 | 425 | 41 | 1538 | yes |
| 2 | 8.26 s | 2059 | 771 | 69 | 520 | 4819 | **refused** |
| 3 | 3.62 s | 1040 | 622 | 87 | 124 | 1716 | yes |
| 4 | 11.69 s | 1112 | 4196 | 70 | 981 | 5298 | **refused** |
| 5 | 3.27 s | 977 | 833 | 85 | 62 | 1274 | yes |
| 6 | 4.05 s | 907 | 780 | 73 | 185 | 2070 | yes |

On the four tracked serves, averaging 3.55 s: decode 916 ms (26%), cut 678 (19%), write 168 (5%),
parse 103 (3%), **engine 1650 (46%)**, dispatch 36 (1%).

The two slow serves are the engine refusing the tracker's handed-in track and searching for the
flight itself, at 3.5 s and 4.6 s of pure search. Serve 4's track explains the refusal: its first
three points have radius 52, 45 and 8 where the ball's is 18 — it had locked onto a paddle — and
it then has two six-frame gaps. The engine was right. Nothing on the capture side touches this.

## Inside the engine

A real capture was rebuilt off the phone for the first time (`test/capfromclip.py`, below) and
profiled with Chromium's sampling profiler (`test/capprof.mjs`). Desktop runs about 1.25–1.5×
faster than the phone.

| part | ms | % | what it does |
|---|---|---|---|
| `findBall` × 60 frames | 236 | 24% | erode/dilate, connected components, min enclosing circle |
| `measurePole` | 228 | 23% | the marking measurement itself |
| `grabColor` (luma + chroma crop) | 164 | 16% | pulling the ROI out of each frame |
| `deflickerOne` / `deflickerRows` | 121 | 12% | per-row brightness against the background |
| the camera-shake check | ~100 | 10% | every 2nd pixel of the ROI, every frame, to see if the tripod moved |
| `medianFrames` | 57 | 6% | the background, a per-pixel median of 15 frames |
| **`fitRotatingVector` + `scan`** | **64** | **6%** | **the spin maths** |

**The spin maths is 6% of the engine. The other 94% is per-pixel JavaScript, nearly all of it on
pixels that are not the ball.** The ROI is 844×250 = 211,000 px. The ball is r≈19, about 1,162 px
— **0.55% of it**. Sixty frames are measured, so each full-ROI pass is 12.7 M pixel visits, and
there are several.

## The engine's cost is its area, and nothing else

The same capture was cut from the same clip at five different rectangles and measured each time:

| ROI px/frame | engine (desktop) | reading |
|---|---|---|
| 211,000 (what the phone gave it) | 1010 ms | 39.80 rev/s |
| 141,100 | 750 ms | 39.80 |
| 99,600 | 527 ms | 40.05 |
| 74,700 | 383 ms | 40.45 |
| 53,120 | 286 ms | 40.20 |

Serve 6 agrees: 347,745 px → 1653 ms (57.28 rev/s), 218,228 px → 1073 ms (57.05).

That is a straight line through both serves at about **4.5 µs per thousand ROI pixels**, roughly
75 ns per pixel visit, over a fixed cost of 40–100 ms. The engine has no other significant term.
And the reading barely moves: 39.80 to 40.45 across a four-fold cut, down to a strip only 3.7 ball
radii tall.

So the engine's time is a choice about how many pixels it is handed. Give it a box round the ball
— 115×115 for serve 1 — instead of the whole flight's bounding strip, and the line predicts
**~103 ms on the desktop, 130–160 ms on the phone, against 1,538 ms today.** About tenfold.

## The capture is recovering pixels it already had

The decode and the cut — 1,594 ms, 45% of the wait — exist only because the full-resolution
pixels are thrown away at capture time and then recovered from H.264 afterwards.

`GlTap.kt` says what actually happens live. Every frame at 240 fps arrives as a **GPU texture**
from an ImageReader in `ImageFormat.PRIVATE` with `USAGE_VIDEO_ENCODE`. The graphics chip reads
that buffer with no copy, draws it shrunk 4× to 480×270 as brightness and colour in the tracker's
units, reads back 518 KB every second frame, and draws the full-size frame into the encoder.

**The full-resolution pixels are on the GPU, 240 times a second, while the serve happens. Nobody
keeps them.** Half a second later the app asks the hardware decoder for them back, at 5.3–12.4 ms
a frame.

So the architecture is: **ring the windows, not the frames.**

- In the GPU pass that already runs, add a draw that crops a box round the ball's predicted
  position at full resolution and reads back ~56 KB instead of nothing.
- Ring those windows in RAM uncompressed. At 196×196 that is 56 KB a frame and 13.8 MB for a
  240-frame ring — against 435 MB for raw full frames, which is why the ring is compressed today.
- Reduce each frame to per-row mean brightness in the same pass and read back 1080 floats: the
  flicker correction the windowed format already needs.
- Keep a rolling full-resolution background on the GPU and read back only the flight's rectangle
  of it, once, when the serve ends.
- At serve end there is then nothing to decode and nothing to cut. Hand it over in memory.

### How big the window has to be

The ball's position is known from the tracker's readback, which is deliberately two frames late
and taken every second frame, so the window must be cut from a prediction. Measured on all six
serves' own tracks, predicting each point linearly from the three points at least two frames
older, and ignoring points whose radius is far from the track's median (which is how serve 4's
paddle is excluded):

| serve | px/frame | ball r | median error | 90th | worst |
|---|---|---|---|---|---|
| 1 | 12.3 | 17.4 | 3 | 5 | 7 |
| 2 | 13.3 | 13.5 | 4 | 8 | 16 |
| 3 | 17.0 | 16.1 | 4 | 10 | 17 |
| 4 | 25.2 | 18.3 | 6 | 15 | 41 |
| 5 | 14.9 | 13.6 | 5 | 8 | 19 |
| 6 | 19.8 | 18.9 | 5 | 11 | 12 |

**A median of 4 px and a worst case of 41 px.** A box of 3r plus that worst case either side is
196×196. Quadratic extrapolation is *worse* than linear at every lag — it fits the noise — so the
prediction should stay linear. At a six-frame lag the worst is still 56 px.

The H.264 ring stays as the safety net. The encoder runs anyway, so it costs nothing, and if the
ball ever leaves its window the old path still has the pixels.

## What was proposed and did not survive

Gemini was asked to pick the plan apart. Two of its recommendations were already true and one of
its numbers was wrong by a factor of four, which is worth recording.

- **"Your I-frame interval is a second, so the decoder grinds through hundreds of useless frames
  to seek."** The clips say the keyframe interval is 24 frames — 0.1 s at 240 fps, uniform — and
  `ClipRecorder.kt` already sets `KEY_I_FRAME_INTERVAL = 0.1f`. Worst case is 23 extra frames on a
  140-frame span, 16%. The decode is throughput, not seeking.
- **"A serve at 15 m/s moves 1.5 ball diameters a frame, so your prediction will drift 180 px."**
  These serves launch at 3.6–7.3 m/s and move 12–25 px a frame, about half a diameter, and the
  measured drift is 4 px typical and 41 px worst. The window is a fifth of the size that argument
  demanded.
- **"Replace the WebView with QuickJS."** QuickJS is an interpreter; 64 ms of maths in V8 could
  become several hundred in it. Once the per-pixel work leaves JavaScript the host hardly matters,
  and the 36 ms of dispatch is not worth a rewrite.
- **"Keep the H.264 ring as a fallback"**, **"pack the readbacks into one texture rather than
  adding a second and a third"**, and **"prove the window-native engine before touching the GPU"**
  are all good, and are in the plan below.

## Order of work

1. **Make the engine window-native.** It reads the windowed format today by *rebuilding the full
   crop* (`windowsInto`) and then running the same full-ROI passes, which is why that format won
   only 1.13×. Every expensive stage — `findBall`, `grabColor`, the deflicker, the shake check,
   `medianFrames` — should work in the box round the ball, with the background patch supplying
   what is outside it. The line above predicts roughly tenfold. This needs no phone and no GPU
   work: the harness can prove it on rebuilt real captures and on the rendered clips with ground
   truth.
2. **Cut the windows on the phone from the existing capture**, so the engine gets the format it
   now wants. Decode and cut stay, but write and parse shrink with the bytes. Expected answer:
   about 1.9 s on serve 1 and 2.2 s on serve 6 — the three-second target, met, without touching
   the camera path.
3. **Ring the windows on the GPU** and stop decoding. One packed readback per frame, a rolling
   background, the H.264 ring kept as the fallback. Expected answer: a few hundred milliseconds.
4. **The refused-track path.** Untouched by all of the above, worth 3.5–4.6 s when it fires, and
   possibly an artefact of serving into open air with no bounce. It needs a session on a table
   before it is worth designing for.

Steps 1 and 2 reach the target. Step 3 is what makes the number small enough to stop thinking
about, and step 1 is a prerequisite for it either way.

## What would break it, and the test that catches it

**The background.** The engine's background is a per-pixel median across 15 frames precisely
because a ball or a player crossing a pixel must not pull it. A GPU accumulator gives an
exponential moving average cheaply and a median expensively, and at 240 fps a ball crosses a pixel
in about two frames, so an average will smear a trail behind it exactly where the measurement
looks. The cheap test is to add the average to the renderer's own path and see whether the
ground-truth harness still scores. Gemini's alternative is worth trying if it fails: copy every
*k*-th frame into a 15-deep texture array, and take the median in one compute pass at the end.

**The row alignment.** The windowed capture reconstructs a frame from a background, a row
brightness ratio and a window. If the row Kotlin measures and the row the engine applies are off
by one, the correction fights the banding instead of removing it, and the spin reads wrong while
everything still looks plausible. The test is one phone session writing both forms of the same
serve and measuring both; a drift past the 0.2% the format was validated at means the rows are
misaligned.

**The window itself.** If the prediction is ever wrong the pixels are gone, where today they are
not. Hence the H.264 fallback, and hence a window sized by the measured worst case rather than the
typical one.

## The tools this needed

- `test/capfromclip.py` rebuilds the exact `.cap` a phone measured, from the clip and the serve
  JSON saved beside it, by finding the offset that puts the ball inside the discs its own track
  predicts. It reproduced the phone's reading to the digit on all three serves tried — 39.80,
  57.28 and 61.71 rev/s. Before this, only rendered clips could be re-measured off the phone, and
  rendered clips have no mains flicker, no motion blur and no player.
- `test/capprof.mjs` CPU-profiles `measureCapture` on a `.cap` through the same headless Chromium
  path the WebView uses, and prints self and inclusive time per function.
