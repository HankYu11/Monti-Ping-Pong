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

---

# On real footage (Nov 2026, after the above)

The session holding the recorded clips ran the change over **21 real captures** (`.cap` files
from the desktop runner: the crop plus the live tracker's track and near tracks, the same thing
the app hands `engine.html`), three ways — the engine before, the engine after with the track
off, and the engine after with the track on.

**It is much faster than the rendered clips suggested.** 97.7 s for the 21 captures before,
**25.3 s measuring along the track: 3.9×.** The 1.5× measured here was an underestimate because
a rendered flight is small and clean, so the search costs little; on a real capture the search
is most of the work, as the original 80% profile said. The medians change showed no gain on real
captures for the same reason — the search dominates, and it does not run the measuring step's
medians.

**17 of 21 agreed within 0.3–1.4 rev/s. Four did not, and they are the point.**

| Capture | search | along the track | |
|---|---|---|---|
| L1 (the hand) | 23.0, clear | **no reading** | the hand's track passed every rule, then failed to measure |
| N12 (paddle merge) | 39.7 | **no reading** | same: accepted, then "found in too few frames" |
| N15 (paddle merge) | no reading | no reading | unchanged |
| T4_2 | no reading | no reading | refused outright, as intended |
| **M6** | 28.5 | **23.3, clear** | **−18%, and nothing catches it** |

**What was wrong, and what is fixed.** The fallback only covered a track that produced *no
segment at all*. A track that was accepted and then failed to measure fell through it and lost
the reading. `ballisticRule` does not refuse L1's hand: it runs flat along the bottom of the
picture, y 195 → 192 over frames 60–166, which is a straight enough path and a steady enough
size to pass. The check was built to catch a hand that *moves like an arm*; this one barely
moves at all, and `flightRule`'s own "too slow" test did not catch it either.

So the fallback now also abandons a tracked measurement that **errors or comes back unclear**,
not only one that yields no segment. That covers L1, N12 and T4_2, and keeps the speed on the
common case.

**M6, explained.** It is not another object, and not a worse measurement. The tracked run reads
the stretch *after the bounce*, and it agrees with the search's own after-bounce reading
(23.3 against 23.5). The bounce really does cut the spin; 23.x is right for that stretch and
wrong as the serve's.

The search finds two stretches — off the paddle [52-68], 28.5 clear, then after the bounce
[70-132], 23.5 clear — and puts the paddle one up as the headline. With the track there is only
one stretch, [94-154], and the engine labels it "flight", because labelling depends on whether a
bounce *follows* inside the same track. Nothing follows, so it reads as a plain flight, and the
headline logic prefers any non-bounce reading. The tracks hold only frames 60-68 before the
bounce, too few to measure, and the search's paddle stretch starts at 52, before any track data.

Two changes, both on the track path only:

- `serveBuilder` now also looks *backwards*. When the tracks before a stretch show the ball
  reaching its lowest point and coming back up, the stretch is marked `bounce`, not `flight`.
- A tracked run whose only clear reading is after the bounce is abandoned, and the engine
  searches — which can reach frames before the tracks begin, where the serve's own spin is.

So M6 now costs a search and should read 28.5. Captures whose tracks do cover the paddle stretch
(M7 among them) are untouched.

**Confirmed on the real captures, and the switch is on.** M6 now refuses the track, searches, and
reads 28.5 (paddle [52-68] clear, then bounce [70-132] clear) — identical to the old engine, for
2.7 s. M7, which does have the stretch before the bounce, is untouched and does not refuse. No
other capture pays a search: the five that take that path are L1, M6, N12, N15 and T4_2, and all
five read what the search alone reads. 33.0 s for the 21 against 97.7 s before, about **3×**.

### What to watch

Three captures still differ a little from the old search's headline, and all three are paddle
stretches that **start where the track starts, earlier than where the search starts them**:

| | along the track | search | |
|---|---|---|---|
| M7 | 43.0 | 44.4 | −3%, [64-76] vs [67-76] |
| M12 | 43.9 | 43.0 | +2% |
| M14 | 51.8 | 51.0 | +1.6% |

The other 16 are within 0.3 rev/s. Nothing here says which side is right — there is no ground
truth for these — and all three are inside the meter's usual few-percent spread, so they are not
blockers. But the direction is worth remembering: the engine skips `skipAfterContact` frames
after the hit precisely because the paddle is still beside the ball there, and a stretch that
starts earlier has more of those frames in it. If a bias shows up later, this is the first place
to look.

And all of it is **one phone's footage, 21 captures**. Frame delivery, the camera and the club's
lighting are still only testable on a phone.

**Why M6 mattered.** It was a *confident* wrong number — marked clear, from a track that passes
every check — so no fallback could catch it. That breaks the promise the whole design rests on:
that the worst case of measuring along the track is the behaviour we already have.

**A correction to the claim above.** "Readings within 0.05 rev/s" was measured on rendered
clips only. On real captures the medians change moved M12 by 0.4 rev/s and N12 from an unclear
0.5 to 39.7. Neither is obviously wrong — N12 was never readable before — but 0.05 rev/s is the
rendered figure, not the real one.

---

# At the table (10 Nov 2026): the track is refused nine times in ten

First session on a phone with the change live: 0.9.4, Pixel 8, 10 serves measured.

**Frame delivery was perfect**, which is the risk that could have invalidated everything else:
`watchingBy` "graphics chip, recording stream" — the good path, not one of the two lossy
fallbacks — with **0 dropped and 0 skipped** of 52,990 frames encoded.

**But the speed is not there.** The track was used on **1 of 10** serves; the other 9 refused it
and searched. Offline it was 5 of 21. On the one serve that used it the answer came in 3.5 s
against a 6.8 s mean for the nine that didn't, so the gain is real when it happens — it just
almost never happens.

| | |
|---|---|
| serves measured | 10 |
| track used | 1 |
| track refused | 9 (90%) |
| answer, track used | 3.5 s |
| answer, track refused | 6.8 s mean |
| engine search time when refused | 3.1 s mean |

**It is not the ball-in-flight rules.** Serve 3's capture was reconstructed from `serve03.json`
and its track run through the real `flightRule` and `ballisticRule`: the tracker's pick passes
both (93 points after filling in every frame, median radius 7.5). So the track is being accepted
and then put aside afterwards, by the test added for M6: *a tracked run is kept only if it has a
clear reading of a stretch that is not after the bounce.*

That test is almost certainly too strict. On serve 3 the engine's own search read the stretch
off the paddle at fit **0.752**, where 0.75 is the line between clear and unclear. A reading
that marginal flips to unclear on any small difference in which frames are measured — and the
tracked run measures a slightly different set. So the test meant to catch M6 (*no* pre-bounce
stretch at all) is instead firing on *marginal* pre-bounce stretches, which are common.

Whether that is really what happened on all nine is not recorded: `trackRefused` was a plain
true. It now carries the reason — "no track flies like a ball", "measured to nothing", "only
after the bounce", or "the serve's own stretch read unclear" — which separates the structural
case (M6, worth refusing) from the quality case (probably not worth refusing, since the engine
would then be searching to turn an unclear reading into a marginal one).

**The next step costs no table time.** Each `serveNN.json` carries the capture's rect, frame
count and the tracker's track and near tracks, and the clips are beside them, so all 10 can be
re-measured on a desktop with the instrumented engine. The counts per reason decide whether the
test should be narrowed to the structural case alone.

## Why it refused: not what I guessed

The session's 10 captures were rebuilt from `serveNN.json` (the phone's own rect, frame count,
track and near tracks) and re-measured on the desktop against the saved clips. The counts:

| reason the track was put aside | serves |
|---|---|
| only after the bounce | 6 (1, 3, 5, 7, 9, 10) |
| the serve's own stretch read unclear | 2 (4, 6) |
| no track flies like a ball | 1 (8) |
| *track used* | *1 (2)* |

**The marginal-fit guess was wrong.** "Unclear" fired twice, and neither was marginal: serve 4
reads 54.05 at fit 0.485 along the track against the search's 54.4 at 0.561 — both unclear, same
answer, so the search buys nothing; serve 6 is a net ball (below).

**The real cause was a second structure that looks the same from outside.** In all six, the
bounce is *inside* the track and `bouncedBefore` never fires — the stretch between the hit and
the bounce is simply under 8 frames, so only the after-bounce part gets built. That is not M6.
In M6 the bounce happened *before* the track began and only the near tracks showed it, so the
engine's own search could reach frames the track could not. Here the search has nothing better:
on 5 of the 6 its own headline is also an after-bounce reading, and on serve 1 it finds nothing
at all while the track reads 35.45 clear.

So "only after the bounce" now refuses only when the label came from `bouncedBefore`, which is
M6's case. A track that holds the bounce but too few frames before it keeps its reading.

**And one mislabelling.** Serve 3 is the single capture where the search did find the stretch off
the paddle (53.4, fit 0.817). The track measured the same stretch — 52.35, fit 0.833, the right
number — but called it "bounce". Its first point is the ball merged with the paddle at the hit:
radius 28 against a median of 13, sitting low, which reads as a lowest point followed by rising,
so the flight was split as though it had already bounced. Points over 1.6× the median radius are
now left out before the kinks are looked for, the same test `ballisticRule` already uses.

Together these take the track from 1 of 10 to **7 of 10** on this session, by the re-run's count.

## Open, and not from this work: a net ball reads as a serve

Serve 6 went into the net. Capture frames 0–50 are the serve crossing to the net; frame 60 on is
the ball dropping back off the net toward the camera. The tracker picked the after-net ball, and
the engine's search read it 40.9 at fit 0.958 — clear — and the phone spoke it. That is not the
serve's spin, and nothing in the reading says so. A ball that comes back off the net should
probably give no reading at all.
