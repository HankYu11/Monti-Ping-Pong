# Speed and accuracy: an outside read, then measurements (Nov 2026)

The barriers list for the serve coach named two problems: **a serve takes several seconds to
answer**, and **nothing can tell whether a change makes real readings better or worse**. This
is what came of putting them to Gemini 4 (`gemini-4-argon`), checking its answers against the
source, and measuring what could be measured here.

Everything below was run in a container with no phone and no real clips: headless Chromium runs
the real `engine.js` over rendered serves that have a true spin. See
[`02-spin-meter/web/test/README.md`](../../02-spin-meter/web/test/README.md).

## What the outside read got right, and what it got wrong

| Its claim | What the source and the measurements say |
|---|---|
| The engine is photometrically fragile: it must be matching raw pixel values, so normalise the ball patch (its top-priority fix) | **Wrong, and the fix was not made.** The engine is geometric: it fits the black half's pole per frame, over a darkness `1 - v/ref` already taken against a reference per frame. Measured: full range vs limited, a lifted black level of 40, gamma 1.35, and dim-plus-pedestal all read 30.0 rev/s against a true 30. |
| Measuring along a handed-in track needs a gravity check, a size cap, and trimming where the ball merges with the paddle | **Right, and built.** `flightRule` had no upper size bound and no test of the path's shape. |
| Interpolate the tracker's 120 a second up to 240 before measuring | Already there: `half()` has always filled in every frame. |
| Turning the track path on will not return the whole 80% the search costs, because the measuring step still does full-frame work | **Right, and this was the useful part.** Measured on these clips the search is 45% of the engine, not 80%, and the measuring step still took per-pixel medians over every frame. |

The 80% figure came from a profile of the whole engine on real captures; on a rendered 41-frame
flight the split is search 1.3 s, measure 1.6 s. Both numbers are true of their own case. The
list's "1.3–4× faster with the same readings" is the part that did not survive: see below.

## What changed

**1. The measuring step stopped taking medians it did not need.** A CPU profile put 33% of the
track path in `selectInPlace` (quickselect) and another 15% in `medianFrames` and `percentile`.
`measureSegment` took four per-pixel medians across *every* frame of the flight, and ran the
flicker correction over every row. The background is what most frames show at a pixel, so a
spread of 15 frames gives the same picture, and the row ratio is smoothed over 9 rows anyway.
Measuring is now **1.34× faster** with readings within 0.05 rev/s — the engine's own reporting
step.

**2. Measuring along the live tracker's track is on again** (`useTrack: true` in
`engine.html`), with the check that was missing. `ballisticRule` asks whether a handed-in track
flies like a ball: its size steady (a ball merged with the paddle jumps, clips N12 and N15), its
path a parabola between bounces, and its fall downward — nothing in free flight climbs harder
and harder. It is scale-free: a ball is 40 mm across, so at 240 frames a second gravity is
always about 0.0085 of its radius per frame squared, whatever the camera or its distance.

**3. A refused track costs nothing.** This is what 0.5 was missing. When a handed-in track does
not look like a ball, the engine now searches the crop itself, as it always has, and says so
(`debug.trackRefused`). Handed a hand sliding along the bottom of the picture — the shape that
caused the 0.5 revert — all 13 clips refused it, searched, and kept the right reading, in the
time the search costs today. So the worst case of turning this on is the current behaviour.

## The numbers

Per-serve engine time on a live capture, 13 rendered serves (10–60 rev/s, three ball sizes,
three flight speeds, side and tilted axes, extra noise):

| | mean per serve |
|---|---|
| searching the crop, engine before | 1.04 s |
| searching the crop, engine after | 0.83 s |
| **measuring along the track, engine after** | **0.70 s** |

On the 11 clips where both measure the same frames, the track path is **1.28× faster**
(1.11–1.48×). On the other two the search stopped short — 21 and 24 frames of a 41-frame
flight — and the track path measured all of it: slower, and on `k_tilt30` nearer the truth
(0.25 → 0.00 rev/s). Altogether, against the app as it stands, **about 1.5×**.

Accuracy over 19 clips with a true spin: median error **0.05 rev/s**, worst **0.05 rev/s**, all
read as clear. That is the engine's reporting step, so no error is visible at this resolution.

**The old claim was too generous.** 0.4 recorded the track path as "1.3–4× faster"; measured
here it is 1.1–1.5×. The 4× end is not there, because the search was never the whole cost.

## What is still open

- **None of this is validated on real serves.** The 30 labelled clips (T, U, V, C, L, M, N, P)
  are not in the repository. L1 and V3 decided this change last time and should decide it again;
  `capbench.mjs` runs on any clip once a track is available for it.
- **The capture is still cut whole before the engine reads it** (1.3–2.3 s, and a 600 MB cliff
  held off only by trimming to 300 MB). Gemini's suggestion is to send a small box per frame
  around the tracker's ball instead of one box around the whole flight — well over 90% fewer
  bytes. That is Kotlin and the graphics chip, so it needs a phone.
- **Dim light is refused, not misread.** Below about half the reference brightness the engine
  says "the marking is too faint to read". That is the safe behaviour and matches the setup
  guidance, so it was left alone.
- **No ground truth for real serves yet.** Gemini's suggestion is worth doing and costs nothing:
  count whole turns of the marking by eye across many frames of an existing 240 fps clip. Over
  10 turns even a quarter-frame error in reading the phase is about 0.4%, which is far better
  than the engine's own 5%.
