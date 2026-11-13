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

1. ~~**Measure.**~~ Done: six serves, broken down above.
2. ~~**Tighten the capture rect.**~~ Done in 0.10.3. It padded the serve's box by 4r + 40, sized
   when the engine read the whole crop and wanted room for a background; the engine now reads a
   box of 3r + 20 round the ball, so the crop takes 3r + 24. About 1.4x fewer pixels to cut,
   write, serve and parse, with the reading unmoved (39.80 and 57.35 through the desktop runner
   on two real clips, against the phone's 39.80 and 57.28).
3. ~~**Attack the engine's measure.**~~ Done in 0.10.3, and it was the largest stage. The profile
   is above; the fix is the box. 1,010 -> 613 ms and 1,653 -> 873 on two rebuilt real captures,
   and 357 and 404 when the capture itself is windows.
4. **Have the phone write windows.** The engine is ready for them and the format is now correct
   (below). What stands in the way is the ORDER. A windowed capture is background, then the row
   brightness, then the windows — and the background is a median of frames, so it cannot be
   written until frames exist. The phone currently writes the whole crop *while the engine reads
   it*, which is worth 0.5-1.9 s a serve on its own (0.8.2). So the background has to come from
   somewhere else, and the obvious somewhere is the plan's own answer: frames from BEFORE the
   serve, on a tripod, with the ball not yet crossing them, computed on another thread while the
   serve happens. That is the next piece of work and it is a real one.
5. **Ring the windows on the GPU** and stop decoding, as above. Nothing has changed about this.
6. **The refused-track path**, still worth 3.5-4.6 s when it fires, still untouched by any of the
   above, still possibly an artefact of serving into open air.

### Where that leaves the wait

Using the measured engine times and the 1.25-1.5x the phone runs behind this desktop:

| | serve 1 | serve 6 |
|---|---|---|
| measured, 0.10.2 | 3.27 s | 4.05 s |
| 0.10.3 (box reading + tighter crop) | about 2.2 s | about 2.6 s |
| with windows written on the phone | about 1.6 s | about 1.7 s |
| with the windows ringed on the GPU | a few hundred ms | a few hundred ms |

The first of those is shipped. The second needs step 4, the third step 5.

## What the windowed format got wrong, and how it was caught

The format was validated at 0.9.5 on 29 real captures — on the spin RATE. The spin split into
lift, curve and gyro came later. Comparing a real serve both ways, with `test/capfromclip.py` to
rebuild the capture and the app's own writer to cut it down, found two faults in it. Neither
shows in the rate.

**The row brightness used the wrong estimator.** The writer took median(frame) ÷ median(background)
for a row; the engine's `deflickerRows` takes median(frame ÷ background) pixel by pixel. A median
of a row's values is the median of whatever that row crosses — table, floor, a player — so on a
row the two pictures cross differently the two medians come from different things and their ratio
is off by a few percent. The error depends on the row; the ball changes rows as it flies; so it
lands as a drift in the ball's measured SIZE over the flight. Size against time is how the engine
works out whether the serve comes toward the camera, and that angle is how the spin is split.
Serve 3 read 36 degrees toward the camera from windows against 13 from the whole crop, moving its
gyro from -35 to -52 rev/s while the rate stayed within 0.1.

**The background came from the whole capture.** A capture starts before the flight and holds the
toss and the swing, and a median across those has the arm in it wherever the arm went — which is
where the ball goes a moment later. It now comes from the frames the ball was tracked through.

With both fixed, a capture a fiftieth of the size reads within 0.15 rev/s of the whole crop and
within 3.4 on the split, the worst being the serve flying most directly at the camera, where the
split is most sensitive to the angle.

A third thing was tested and was fine: the writer samples rows at every 16th column, which the
code's own comment flagged as untested on real footage. Strides of 2, 4, 8 and 16 on real serves
move the reading by less than 0.1 rev/s. The estimator was the problem, not the stride.

## The GPU window ring: what has been settled, and what has not

The decode is the last large stage, 0.6 to 0.8 s of a two-second wait, and it exists only because
the full-resolution pixels are thrown away while the serve happens and bought back from H.264
afterwards. Cutting the ball's window as the frame goes past removes both the decode and the cut.
Four things had to be true. Three are now measured.

**The live pass has the room.** The graphics thread reports itself 55% busy of the 4.17 ms a
frame gets at 240 a second, and its readback — 480x270 of RGBA every second frame — is 2.8 ms of
that, which prices a readback at 5.5 us per KB. A 192 px window read back every frame is 144 KB,
which takes the thread to about 74% busy *while a ball is up*, and that is a tenth of a session.
Worth watching rather than assuming: the 0.10.5 session dropped 6.7% of its frames at 60% busy,
where 0.11.0 dropped 0.2%.

**A row sample taken over the whole frame is the same correction.** The live pass sees frames,
not the serve's crop, so the row brightness it could measure spans 1920 columns where the
capture's spans the crop's width. Over three real captures, taking the sample over the whole row,
60% of it and 35% of it moves the reading by at most 0.03 rev/s and the gyro by 1.1. The
correction does not care which columns it is read from, which is the same common-mode argument
that made the stride free.

**The window can be cut before anyone knows where the ball is.** It has to be: the tracker finds
the ball in a readback that is two frames late and taken every second frame. `Predict` draws a
straight line through the last three points that are old enough, and `PredictTest` scores it
against every flight of the four recorded sessions — seventeen tracks, 618 points, real serves
with the tracker's own noise and gaps:

| | median | 90th | worst |
|---|---|---|---|
| two frames behind | 4.3 px | 11.4 | **45.4** |
| four frames behind | 5.4 px | 14.8 | 97.3 |
| six frames behind | 7.4 px | 18.9 | 149.2 |

A 192 px window leaves 96 px round the 47 the measurement needs, so two frames behind has twice
the margin it uses and four frames behind does not. **The lag is the thing to hold, not the window
size**: keeping the readback prompt is cheaper than reading a bigger square every frame.

Three things that look right and are not, each caught by that test rather than by argument:

- *Fitting a curve.* A flight is a parabola, so a quadratic should beat a line. It is worse at
  every lag, because over the handful of points in reach it fits the tracker's noise and not
  gravity. Five points on a line are worse than three for the same reason: 102 px at worst
  against 45.
- *Trusting the first points of a track.* At the hit the ball merges with the bat and the blob
  comes out two or three times too big. A line through those misses by 177 px. The engine throws
  the same points away (`dropMerged`) and so must this.
- *Taking the upper middle value as the ball's size.* With four points, two of them the paddle, a
  median that leans high **is** the paddle, and the filter then keeps the paddle and throws the
  ball away: 106 px. Leaning to the lower of the two middle values leans toward the ball, because
  what spoils a young track makes blobs bigger and never smaller. That one change takes the worst
  miss from 106 px to 45.

A window is placed for 89% of tracked frames. The rest are the first few of each track, where
there are not yet four points to draw a line through — and the engine skips the frames right after
the hit anyway.

**What is not settled is the pass itself.** Cropping on the chip, packing the window and the row
sample into the one readback the tap already does, and ringing the result cannot be tried here:
there is no phone, and no emulator can run this app. That part ships behind `windowRing`, off,
with the frame counters to judge it by.

### The one thing that does not change

The background still wants whole frames, and the ring does not hold any. It keeps coming from the
H.264 ring — but only the fifteen frames the median needs, which is 100 ms of decoding rather than
the 600 to 800 ms of decoding all of them. The ring stays for that reason as well as as a fallback.

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

## 0.12.0: the GL pass, written

The window pass is in. `GlTap` now draws, for every frame while a serve is in the air, three more
things into one buffer and reads it back once: the ball's window as the capture lays it out
(brightness packed four pixels to a texel, then the two colour planes through the same two
shaders `CaptureGl` already uses for a decoded frame), and the whole picture's row sample. The
read-back is 65 KB for a 208 px window and 65 KB for the rows, against the small picture's 259 KB
a frame, and it only happens while a ball is up. It goes into the same PBO-and-fence machinery as
the small picture, on the same reader thread.

### What the writing changed about the design

**The rows cannot come from the small picture.** That was the cheapest idea — the tap already
reads a quarter-size picture back, every second frame, so the row brightness could be worked out
on the processor for nothing. It fails on the frames, not the rows: at 240 frames a second with
100 Hz mains flicker the phase advances 0.42 of a cycle between frames, so sampling every second
frame is above Nyquist and interpolating between them is not an approximation but an alias. The
rows have to be read every frame, which puts them on the chip.

**But they can be coarse in the row direction**, which is what makes them affordable. A frame's
rows span well under half a mains cycle at 240 fps — about a quarter of one across a typical
crop — so the row profile is a smooth arc. `WindowsPartsTest` measures the cost of interpolating:
a step of 2 moves the row brightness by 0.002 on average and 0.017 at worst, 4 by 0.004 and 0.021,
8 by 0.012, where the row-to-row noise on the captures recorded is itself 0.0066. A step of 1 is
exactly zero, which is also the test that the columns and rows line up at all. The step shipped is
2: it is the only one below the noise, and the row brightness is the one number here that has
already gone wrong once, by a few percent, and moved a serve's flight angle by 23 degrees.

**The crop has to be nudged.** The chip samples the whole picture's columns at a fixed stride
because the crop is not known while the serve is happening; the capture samples the crop's. They
are the same columns only if the crop's left edge is a multiple of the stride, so it is rounded
down to one (and the top to the row step). A few pixels of crop, and the two samples are the same
set of pixels rather than merely a similar one — which matters because the ratio only cancels its
own bias when both sides read the same columns.

**The live window is cut big and the capture's is cut out of it afterwards.** The window has to
carry the prediction's miss, which makes it two to three times the area of the window the capture
wants; shipping that in the capture would have put back about 300 ms of the writing, serving and
parsing the format exists to avoid. So the chip's window is re-cut round where the ball really was
once the flight is over. That also turns the prediction's accuracy into something the capture
measures rather than assumes: it reports how many windows fitted and how far off centre the worst
one came.

**The prediction must not be asked for a lag.** `Predict.at` takes a lag because that is how its
accuracy was measured against finished tracks. Live, the track handed to the chip is already as
new as the tracker has and the tracker is itself a few frames behind, so asking for a lag on top
discards the newest points and doubles the delay — at four tracker frames the worst miss is 97 px
where at two it is 45. Live, the lag is zero.

**A place named in the header must have bytes behind it.** The reader takes the windows one after
another and skips a frame whose place says null, so a place named without its window shifts every
window after it. That never happened while every crop was handed in whole; it happens as a matter
of course when the windows are cut live, where the first few frames of a flight have no prediction
yet and some later one may be missed. `headerExtra` now names a place only where a window follows.

### What the phone has to answer

Everything above is measured, but none of it is measured on a phone. Three things can only be
answered there, and 0.12.0 reports each:

- **Does the chip keep up?** `tap.windows.skips` counts frames where every buffer was still being
  read, `missed` the camera frames that never arrived at all, and `busy` the share of the time the
  frames' thread was at work. The budget says 55% to about 72% while a ball is up.
- **Do the chip's bytes match the decoder's?** The camera's frame before it is encoded is not the
  frame the background is decoded from, so the ring carries its own `ColourFit` (`ringCalib`),
  fitted once a session from a serve cut the old way, and its rms is reported every serve. Until
  there is one, no capture is built from the ring.
- **Where does the ball actually come out?** `worst px off centre` is the real prediction miss,
  the number the 72 px margin was guessed at. If it is small the window can shrink; if it is large
  the margin was the right call and the fallback did its job.

The flag is `CHIP WINDOWS` on the HUD, off by default, and every serve it cannot serve is cut
exactly the way 0.11 cut it.

## What the first phone session said (0.12.0, session 20261113_020003)

Four serves, the flag on. Three read (26.05, 51.45, 51.20 rev/s), one found no flight.

**The chip keeps up, comfortably.** 9856 frames arrived and 17 went missing, 0.17%. The window
pass cut 6418 windows with no errors at 256 px a side, which is 159 KB a frame read back on top
of the small picture's 259 KB every second frame. It cost 0.92 ms a frame on the frames' thread
and 1.16 ms on the reader's, and took the frames' thread from 55% busy to 61%. That was the
budget's question and the answer is yes.

**But not one capture used the ring**, and the report could not say why, because every early
refusal returned without writing a reason. Reading the code against the numbers gives three
faults, all of them things only a phone could have shown:

- **The ring was thrown away whenever the window changed size.** 6418 windows were cut and 376
  were still held. The size comes from the ball's apparent radius, which drifts frame by frame
  and as the track grows, so it moved constantly — and a ring built for one size was replaced
  each time. Fixed twice over: each window now carries its own size, so a mixed ring is fine and
  the capture (which cuts a smaller window out of a bigger one anyway) does not care; and the
  size itself is now held steady, growing when it must and shrinking only when the ball is under
  six tenths of what it was.
- **The colour fit could never happen.** It compared a live window against the same window cut
  out of a decoded frame, and required the window to lie inside the crop. A live window is round
  the ball; the crop is round the whole flight. Of the four serves, none had one that fitted —
  serve 2's crop is 276 px tall against a 256 px window. It now fits over the overlap, with 64 px
  square the least it will work with. Without the fit there is no map from the camera's own
  frames onto the decoder's bytes, and without that the ring is never used at all: that one fault
  was enough on its own.
- **The fit would not have reached the shader even once it existed.** The plan is only rebuilt
  when the window's size changes, so a fit arriving between serves would have sat unused for as
  long as the size happened to hold — which, after the fix above, is most of a session.

Two smaller things from the same numbers: 15% of frames found every read-back buffer still busy
(the camera delivers in bursts — 96 frames at once here — so four buffers is too few; now eight),
and every refusal now records what it was, so the next report answers this in one line instead of
needing the code read against it.

None of this touches a reading: all four serves were cut exactly as 0.11 cut them, which is what
the fallback is for. The cost of the whole experiment so far has been 6% of the frames' thread.

## Running the ring without a phone

The emulator was the wrong thing to fix, for a reason that has nothing to do with the emulator:
**"Test with a video" never touches the chip**. It feeds a file's frames to the pipeline's own
decoder; GlTap, and with it the whole window pass, only exists on the camera's path. An emulator
that booted perfectly would have exercised none of this — and it would not boot anyway, since
without /dev/kvm there is no 240 fps camera, no GLES 3 external images and no arm64 native part.

So the ring is reproduced instead, on the JVM, from a clip the phone recorded: `RingSim` in the
desktop runner (`desktop/ringcheck.sh`). The same tracker, the same `Predict`, the same
`WindowRing`, the same `Windows.Builder` — with the window and the row sample cut by the
processor from the decoded frame rather than by the chip from the camera's. What that leaves out
is the shader's arithmetic, the chip's timing, and the camera's frames against the decoder's.
What it covers is everything else, which is where all four faults so far have been.

### What it found at once

Of three recorded serves, **one was refused outright and the other two lost a fifth of their
windows**, and the per-frame dump said why — in two different ways:

- **The track the window follows was chosen the way a finished track is scored**, by length times
  travel. That picks an arm that has been alive for a hundred frames over a flight that is ten
  frames old — and the first part of the flight is exactly where the window is wanted. A dump of
  serve 2 shows the window sitting at x≈1350 while the ball ran from 1326 to 1106. It is now
  chosen by how fast a track is going *now* (2 px a tracker frame at quarter size; a served ball
  runs 8 to 25), and among those by length times travel.
- **Coverage was counted over the whole capture**, including the near tracks and the frames
  `offsets()` fills in at either end, where a missing window costs nothing. Counting only the
  flight, less ten frames after the hit (the ball is merged with the bat there and the engine
  throws them away itself), serve 2 goes from 73 of 101 to 82 of 91.

With both: 21/27, 81/91, 63/63 of the measured flight, and the captures read

| serve | phone | whole crop | from the ring | bytes |
|---|---|---|---|---|
| 1 | 26.05 | 26.00 | **26.00** | 25x fewer |
| 2 | 51.45 | 51.50 | **51.45** | 74x fewer |
| 3 | 51.20 | 51.20 | **51.10** | 69x fewer |

The split moves by up to 4.2 rev/s, which is what the windowed format already moves it by on its
own (serve 3's curve: −26.0 whole, −22.9 windowed, −21.8 from the ring).

### Two numbers the sim can give and the phone cannot

**How far behind the live track may run.** On the phone the delay is whatever the tracker, the
finders and the linker add up to; here it is a dial. Coverage of the measured flight, per serve:

| lag (tracker frames) | 0 | 2 | 4 | 6 | 8 |
|---|---|---|---|---|---|
| serve 1 | 23/27 | 21/27 | 21/27 | 21/27 | 21/27 |
| serve 2 | 88/91 | 81/91 | 73/91 | 61/91 | 53/91 |
| serve 3 | 63/63 | 63/63 | 59/63 | 55/63 | 51/63 |

So it degrades gracefully to about four tracker frames (33 ms) and starts refusing serves past
six. That is the headroom the design has, measured rather than hoped for.

**Whether the margin is worth its bytes.** At a lag of 4, margins of 48, 72, 104 and 144 px give
serve 2 70, 73, 75 and 76 of 91, and change serves 1 and 3 not at all. The window is not what
loses frames — being on the wrong object, or having no prediction yet, is. 72 stays because it
costs little and the phone's real lag is still unknown, but there is nothing to buy by raising it.

## The crop two pixels too wide (0.12.0 → 0.12.3)

The second phone session on 0.12.0 came back slower and with more serves the app would not read,
and the report's first line said why in a way it took a simulation to recognise:

    capture #1 on the chip: the chip's frames don't match the decoder's (rms 40.0):
    BT.709, Y -0.041x+152.6 (rms 40.04)

A colour fit of gain −0.04 and rms 40, where 0.3 is normal, is not a colour problem. It is what
you get when the two pictures are not the same pixels at all. The crop was 1870 wide. The chip
packs four pixels into a texel and reads a row back as **width/4** texels, so 1870 comes back as
1868 pixels, and every row after the first lands two bytes early: the plane skews, progressively,
and nothing says so. `ServeTracker.cropFor` has always masked the width to a multiple of 8 for
exactly this reason. `alignRect`, which 0.12.0 added to line the row sample's columns up with the
chip's, moved the left edge and added the shift to the width **without masking it again**.

Reproduced from the session's own clip: reading one decoded frame back at 1870 wide gives gain
−0.054, offset 159.4, rms 46.8 — the phone's −0.041, 152.6, 40.04. At 1864 it is exact.

Six of that session's seven crops were two pixels wide of a multiple of four. The first one's fit
caught it and, under the old rule, a fit that "doesn't match" turned the chip off for the whole
session: every capture after it was copied out by the processor, 1.2 to 3.3 s each. So the
readings stayed right — the copied-out path is the reference one — and everything got slow. The
session before it was luckier: its first crop was 940 wide, which is a multiple of 4 so the
brightness plane was exact and the fit passed, and only the fourth serve's 906 went through the
chip skewed. That serve is the one that came back "No serve flight found", which had been written
off as a tracker problem.

Three things, so this cannot happen again quietly:

- `alignRect` masks the width back to a multiple of 8 and the height to even, after clipping.
  `AlignRectTest` sweeps every left edge and width a picture can produce.
- `CaptureGl` refuses a crop it cannot read, by shape, before it decodes anything — the skew is
  now a thrown error rather than a plane that looks plausible.
- A single failed colour fit no longer turns the chip off for the session. Two failures do.

## 0.12.3 on a phone: the ring runs

Eight serves, session 20261113_18. The ring served five of them and the numbers it was built for
came back:

- **The colour fit works.** `Y 0.856x+16.8 (rms 1.01), over 288x248 of a 288px window` — the
  camera's own frames map onto the decoder's bytes to about one grey level. The overlap fix was
  what it needed; there were no chip failures all session.
- **The cut all but disappears.** A ring serve decodes in 832 ms and cuts 15 frames in **94 ms**,
  against 394 ms of cutting on the serve that made the fit and 683 ms on the longest copied one.
- **The wait: a median of 2381 ms the session before, 1364 ms here**, and 1315 ms over the five
  serves the ring served. The decode is now the whole of what is left, which is the next thing to
  take (the background from between serves, so no frame of the span need be decoded at all).
- The chip kept up: 10032 windows cut, no errors, 763 of 10795 attempts skipped for want of a
  buffer (15% before eight buffers, 7% now).

Coverage of the measured flight ran 30/37, 33/37, 44/45, 44/55, 61/63 — the three worst are the
three with the most windows missed, not the ones with the worst prediction. Worst off centre was
261 to 496 px against a 288 px window, so the margin is doing its job at the tail while the
median sits far inside it.

### The spin types

The player reported some side spins read as backspin. That is not the new path: re-measuring the
original clips **whole crop, at full size**, gives the same type on every serve whose rebuild is
sound (1, 2, 3, 5, 7, 8). The capture is not losing the side component — the engine is reading
the ball that way.

What the numbers do show is a labelling edge. `spinLabel` says "side" only when the curve
component is at least 35% of the total, so:

| serve | lift | curve | gyro | side share | label |
|---|---|---|---|---|---|
| 7 | 32 | 10 | −16 | 26% | backspin |
| 8 | 56 | −15 | 21 | 24% | backspin |
| 5 | −16 | −38 | 32 | 73% | sidespin + gyro |

A serve with a quarter of its spin sideways is announced as plain backspin, with nothing on the
screen to say the rest is there. Whether that is the whole of what the player noticed needs their
own account of which serves were which.

One ring serve of the five moved: serve 8's curve read −7.9 on the phone against −15.0 whole
crop. The other four matched to about a rev/s. That is the split drift the windowed format has
always had, and it is enough to tip a serve across a threshold at 35%.

### What "I tried to make it a side spin" turned out to mean

The two serves were 5 and 8. Serve 5 the app already called "side-topspin (curves left) + gyro".
Serve 8 it called, flatly, "backspin" — and the reading behind that is:

    lift 54.3    curve −7.9    gyro 27.6    (of 61.4 rev/s)

The side part really is small: 13% of the total, a tilt of 8°. What is not small is the **gyro**
— 45% of the spin is along the ball's own flight line. And spin along the flight bends nothing,
because the Magnus force is the spin crossed with the velocity and that is zero when they point
the same way. A player brushing round the side of the ball while the bat travels down the ball's
own line puts plenty of spin on and gets no curve for it. The label said nothing about any of
that, because it only named gyro above half.

Six of the eighteen serves recorded so far were in that position: announced as pure back or
topspin with 35% to 49% of their spin along the flight. So the shares the label uses moved —
side from 0.35 to 0.25, gyro from 0.5 to 0.30 — which names those six and changes none of the
twelve that were already right. `test/labels.mjs` holds all eighteen with the label each should
get, so the shares can't drift back on taste.

Gyro sits at 0.30 rather than 0.35 for a reason worth recording: serve 8 reads 45% of the total
from the phone's own capture and 34% from the same serve measured whole crop at full size. At
0.35 the label changed with the capture path rather than the ball. The gyro's own error is 1.9
rev/s typically and 7.0 at worst, which on a 60 rev/s serve is eleven points of share, so a bar
anywhere near a serve's value is soft by construction — better to put it where the known error
cannot straddle it.

The serve also now carries a note saying where the spin went: *"45% of this spin is along the
ball's own path (a corkscrew), which doesn't curve it. To make it curve, brush across the ball
rather than around it."* That is the actual coaching answer to what the player was trying to do,
and it is the one thing neither the rate nor the type was telling them.

## 0.12.4: every read right, and the ring served 3 of 12

Twelve serves, all read correctly bar one the player hit at the ceiling, which the app declined
and which nobody wants read. The labels did their job: serve 5 came back "gyro (corkscrew)" at
51 rev/s, which is what a serve meant as sidespin and struck down its own line actually is.

But the ring served only three of the twelve, and the median wait went **up**, to 3364 ms — 1748
ms over the three it served, 3395 ms over the nine it did not. The refusals all said the same
thing: *"the chip's windows covered 42 of 65 frames"*.

RingSim does not reproduce it. The same four clips, the same tracker, the same prediction, give
**94% to 98%** of the flight covered, and `held 150 of 150` — nothing missing from the ring at
all. The difference between the two is the only thing the simulation does not have: a ring that
gets overwritten.

The arithmetic says the same. The ring holds 360 frames, a second and a half. A serve is only
reported once the grouping has waited 0.4 s for a better track, which is 96 frames; the longest
span this session was 213. That is 309 before the capture thread has even started, against 360 —
and the camera handed its frames over in bursts of up to **400** this session, where the last one
peaked at 96. The oldest frames go first, and the oldest frames are the start of the flight.

So the ring now stops taking anything from the moment a serve is reported until that serve's
capture has had what it needs. Nothing arriving in that window is wanted: a serve during a
measurement is ignored anyway, and the chip gets the work back for free while the engine runs.
The refusal note now also says how many frames the ring still held, so the next report tells the
two failures apart without a simulation: windows that did not fit, against windows that were
no longer there.

The window's size needs nothing: the simulation reaches 94–98% with sides of 224 to 288 chosen by
the same rule, because the ring holds a mixture and the big entries cover the big misses (one
miss of 121 px was re-cut out of a 384 px window).
