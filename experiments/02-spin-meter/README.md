# Experiment 02 — Spin meter

**Goal:** measure a table tennis ball's spin, **rate (RPM) and axis**, from one phone's
slow-motion video, like a baseball spin-rate tracker. This is now the first step of the
project: before coaching anything, the app must know how much spin a serve really had.

## Prior art

| System | How it measures spin | Notes |
|---|---|---|
| Baseball: TrackMan | Doppler radar | No camera |
| Baseball: Rapsodo | Radar + camera reading printed markings on special balls | Special balls needed for the axis |
| **Spinsight** (ESN, Germany, launched Feb 2025) | iPhone 13+ on a net-post holder, **special marked balls**, processed in their cloud | Rotations per second + direction, speed, placement. Needs ≥ 300 lux. Free tier, then €10/month. **Proves the idea works with one phone.** Also a possible reference to check our meter against. |
| SpinDOE (Univ. Tübingen, 2023) | Printed dot pattern, CNN finds dots, fit 3D orientation per frame | Up to 175 rps at 350 FPS, < 1% spin error. Open source (cogsys-tuebingen/spindoe). |
| T-League system (Tamaki et al., 2024) | Reads the ball's printed logo | Median error 0.78 rps on match serves |

## Method (`spinmeter.py`)

1. **Find the ball** in each frame: the region that differs from the background median.
   Both the white and the black parts of a marked ball differ from the background, so
   the whole ball is found.
2. **Normalise:** resample every frame's ball into the same 64×64 patch, centred.
3. **Remove the lighting:** the marking turns with the ball, the light doesn't. A high
   per-pixel percentile over all frames estimates "white under this light"; dividing by
   it leaves only the marking.
4. **3D rotation fit:** treat the visible half of the ball as a sphere. For a candidate
   per-frame rotation (axis + angle), map each surface point of frame *t+1* back to frame
   *t* and compare. Search all axes and angles coarsely (≈ 27,000 candidates on 10 frame
   pairs), then refine the best 8 on all frame pairs. Spin is assumed constant over the
   clip (about 0.1 s), which holds in flight.
5. **Report:** total rps / RPM, axis, and the split into backspin/topspin, sidespin and
   gyro (corkscrew) components, using the flight direction.

`--method 2d` keeps the simpler v1: in-image rotation only, via polar cross-correlation.

## Synthetic test (`spinsim.py`, `sweep.py`)

`spinsim.py` renders a 240 FPS clip of a marked ball (default: one half black, one black
line and one dot; see [Marking the ball](#marking-the-ball)) flying across the frame with a known spin, with motion blur, sensor noise and
lossy MP4 compression. `sweep.py` varies one condition at a time.

Baseline: 30 rps, axis straight at the camera, 40 px ball, 8 px/frame flight, exposure
1/960 s, noise σ = 4. Full results: [`sweep_results.csv`](sweep_results.csv).

| Case | True rps | v1 (2D): in-image part true → measured | v2 (3D) measured | v2 error | v2 axis error |
|---|---|---|---|---|---|
| baseline | 30 | 30.0 → 29.8 | 30.0 | 0.0 | 2.6° |
| slow spin 5 rps | 5 | 5.0 → 3.2 | 4.92 | -0.1 | 3.6° |
| 15 rps | 15 | 15.0 → 15.0 | 15.0 | 0.0 | 2.6° |
| 60 rps (top-player serve) | 60 | 60.0 → 59.2 | 60.0 | 0.0 | 0.0° |
| 100 rps | 100 | 100.0 → 100.0 | 100.0 | 0.0 | 0.0° |
| 130 rps (above the 120 rps limit) | 130 | 130.0 → -109.8 | 110.0 | aliased | — |
| backspin -30 rps | 30 | -30.0 → -30.0 | 30.0 | 0.0 | 2.6° |
| ball 20 px | 30 | 30.0 → 29.7 | 30.0 | 0.0 | 3.6° |
| ball 14 px | 30 | 30.0 → 26.3 | 29.5 | -0.5 | 6.3° |
| ball 70 px | 30 | 30.0 → 29.7 | 30.0 | 0.0 | 0.0° |
| full blur (1/240 s) | 30 | 30.0 → 26.9 | 29.67 | -0.3 | 3.6° |
| full blur, 60 rps | 60 | 60.0 → 60.9 | 58.0 | -2.0 | 3.6° |
| fast flight 20 px/frame | 30 | 30.0 → 29.7 | 30.0 | 0.0 | 2.6° |
| axis tilted 20° | 30 | 28.2 → 1.2 | 30.5 | 0.5 | 1.6° |
| axis tilted 45° | 30 | 21.2 → 6.9 | 30.33 | 0.3 | 2.4° |
| axis tilted 70° | 30 | 10.3 → -3.8 | 29.0 | -1.0 | 1.9° |
| pure sidespin (axis vertical) | 30 | 0.0 → 1.2 | 29.33 | -0.7 | 0.0° |
| side-backspin, axis 40° (typical serve) | 30 | -23.0 → -3.6 | 29.71 | -0.3 | 2.3° |
| side-backspin, full blur | 30 | -23.0 → -14.7 | 28.25 | -1.8 | 7.8° |
| pattern: half + line (no dot) | 30 | 30.0 → 29.9 | 30.0 | 0.0 | 2.6° |
| pattern: half only | 30 | 30.0 → 26.9 | 30.0 | 0.0 | 2.6° |
| pattern: dots | 30 | 30.0 → 27.6 | 30.0 | 0.0 | 3.6° |
| noise 12 | 30 | 30.0 → 28.3 | 30.67 | 0.7 | 3.6° |
| 120 FPS, 30 rps | 30 | 30.0 → 29.7 | 30.0 | 0.0 | 3.6° |
| 120 FPS, 70 rps | 70 | 70.0 → -49.5 | 49.33 | aliased | — |

**Conclusions from synthetic data:**
- **v2 (3D) is accurate to about ±2 rps and ±8° of axis** across ball sizes from
  14 px, full motion blur, any axis, and four marking patterns.
- **v1 (2D) fails as soon as the axis tilts**, for example on a side-backspin serve
  (−23 → −2.6). Use v2.
- **Hard limit: half a turn per frame.** 120 rps at 240 FPS (7,200 RPM), 60 rps at
  120 FPS. Top-player serves measured 14–63 rps, so **240 FPS covers serves**, and 120 FPS
  is borderline. Above the limit the answer is silently wrong (aliased). A future check
  could use the ball's flight curve (Magnus effect) to catch this.
- Speed: about 7 s per clip on a laptop CPU, unoptimised.

**What the synthetic test does *not* cover** (needs real video): imperfect hand-drawn
markings, real ball detection against a cluttered background, specular highlights,
rolling shutter, phone slow-motion encoding, and spin changing during the clip.

## Marking the ball

![How to mark a ball](docs/ball-marking.png)

1. **Find the seam:** a faint line all the way round where the two halves were joined.
   It's the equator.
2. **Colour one half completely black** with a permanent marker, edge on the seam, two
   coats.
3. **Draw one line about 5 mm wide** over the white half, seam to seam, crossing the seam at
   right angles. A rubber band makes a good guide.
4. **Add one dot about 8 mm across** on the white half, between the line and the seam, on
   one side of the line only. Without it, the pattern looks the same after a half turn
   about the white pole. The meter still copes (it fits one rotation across all frames),
   but the dot removes the ambiguity.

Make 3–5 balls and re-ink them when the marks wear off.

## Recording: no table needed

The meter only looks at the ball's flight **just after the racket contact, before the
first bounce** (about 0.1 s, 20–30 frames at 240 FPS). The table doesn't change the spin.
It only matters later, for the outcome of the serve (where it bounces, does it come back).
So an **empty floor next to a wall is fine**, and even easier:

| Item | Setting |
|---|---|
| Space | Stand about 1 m in front of a **plain, mid-tone wall or cloth** (blue, green or grey). Not white, or the white half disappears; not black, or the black half disappears. A table-tennis barrier or a blue/green bed sheet works. |
| Direction | Serve **parallel to the wall**, so the ball flies left-to-right (or right-to-left) across the camera's view. |
| Camera | Tripod about **1–1.5 m from the ball's path**, at contact height, pointing at the wall, square to it. Frame the first 50–80 cm of flight after contact. The ball should be **at least 20 px across** (about 40 px at 1080p and 1.5 m). |
| Mode | Slow-motion **240 FPS**, 1080p if available. Keep the original file. |
| Light | As bright as possible (Spinsight asks for ≥ 300 lux). Daylight by a window or a work lamp aimed at the area. Short exposure = less blur. |
| Motion | Your real serve motion (toss + brush). Start with easy ones: drop the ball and brush under it, then full serves. |
| Bonus check | On the floor, real backspin shows itself: the ball skids, stops or even rolls back toward you after bouncing. |

Don't spin the ball in place (like a top on the floor or the table): the meter finds the
ball by its movement against the background, so it needs a ball in flight.

```bash
.venv/bin/python experiments/02-spin-meter/spinmeter.py serve01.mov --fps 240 --start 120 --end 150
```

Trim each clip to the frames after contact and before the bounce (`--start`, `--end`).

**Checking against a reference:** compare a few serves with Spinsight, or spin a marked
ball at a known speed on a drill or small motor and measure that.

## Files

- `spinmeter.py` — the meter (`--method 3d` default, `2d` = v1)
- `spinsim.py` — synthetic clip renderer with ground truth
- `sweep.py` — accuracy sweep, writes `sweep_results.csv`

## Sources

- Spinsight — https://spinsight.com, App Store: https://apps.apple.com/ee/app/spinsight/id6741476615
- SpinDOE (dotted ball, open source) — https://arxiv.org/abs/2303.03879v1
- Tamaki et al. 2024, logo-based spin in the T-League — https://reference-global.com/article/10.2478/ijcss-2024-0003
- Serve spin of 2009 World Championship players (14–63 rps) — https://jair.repo.nii.ac.jp/record/2003238/files/SDO0012-engabs.pdf
- TrackMan Doppler radar for baseball — https://baseball.physics.illinois.edu/trackman.html
- Rapsodo vs TrackMan comparison — https://tht.fangraphs.com/comparing-the-rapsodo-baseball-device-to-other-pitch-trackers/
