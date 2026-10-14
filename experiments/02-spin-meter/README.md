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

`spinsim.py` renders a 240 FPS clip of a marked ball (default: one half black plus one
black line) flying across the frame with a known spin, with motion blur, sensor noise and
lossy MP4 compression. `sweep.py` varies one condition at a time.

Baseline: 30 rps, axis straight at the camera, 40 px ball, 8 px/frame flight, exposure
1/960 s, noise σ = 4. Full results: [`sweep_results.csv`](sweep_results.csv).

| Case | True rps | v1 (2D): in-image part true → measured | v2 (3D) measured | v2 error | v2 axis error |
|---|---|---|---|---|---|
| baseline | 30 | 30.0 → 29.8 | 30.0 | 0.0 | 2.6° |
| slow spin | 5 | 5.0 → 3.2 | 4.92 | −0.1 | 3.6° |
| 15 rps | 15 | 15.0 → 14.9 | 15.0 | 0.0 | 2.6° |
| 60 rps (top-player serve) | 60 | 60.0 → 59.0 | 60.0 | 0.0 | 0.0° |
| 100 rps | 100 | 100.0 → 100.2 | 98.7 | −1.3 | 0.0° |
| 130 rps (above the limit) | 130 | aliased | 109.3 | aliased | — |
| backspin | 30 | −30.0 → −30.0 | 30.0 | 0.0 | 2.6° |
| ball 20 px | 30 | 30.0 → 29.8 | 30.0 | 0.0 | 3.6° |
| ball 14 px | 30 | 30.0 → 26.1 | 29.5 | −0.5 | 6.3° |
| ball 70 px | 30 | 30.0 → 29.7 | 30.0 | 0.0 | 0.0° |
| full motion blur (1/240 s) | 30 | 30.0 → 26.9 | 29.7 | −0.3 | 3.6° |
| full blur, 60 rps | 60 | 60.0 → 63.4 | 58.8 | −1.2 | 4.4° |
| fast flight, 20 px/frame | 30 | 30.0 → 29.3 | 29.7 | −0.3 | 2.6° |
| axis tilted 20° | 30 | 28.2 → **1.4** | 30.6 | 0.6 | 1.6° |
| axis tilted 45° | 30 | 21.2 → **14.5** | 29.7 | −0.3 | 3.2° |
| axis tilted 70° | 30 | 10.3 → **1.9** | 29.0 | −1.0 | 1.9° |
| pure sidespin | 30 | 0.0 → 1.3 | 28.7 | −1.3 | 2.6° |
| side-backspin, axis 40° (typical serve) | 30 | −23.0 → **−2.6** | 29.8 | −0.2 | 3.1° |
| side-backspin, full blur | 30 | −23.0 → **−14.7** | 30.3 | 0.2 | 5.6° |
| pattern: half black only | 30 | 30.0 → 29.8 | 30.0 | 0.0 | 2.6° |
| pattern: 8 dots | 30 | 30.0 → 24.1 | 30.0 | 0.0 | 3.6° |
| heavy noise (σ = 12) | 30 | 30.0 → 28.5 | 31.2 | 1.2 | 8.1° |
| 120 FPS, 30 rps | 30 | 30.0 → 27.6 | 30.0 | 0.0 | 3.6° |
| 120 FPS, 70 rps (above its limit) | 70 | aliased | 50.0 | aliased | — |

**Conclusions from synthetic data:**
- **v2 (3D) is accurate to about ±1.3 rps and ±6° of axis** across ball sizes from
  14 px, full motion blur, any axis, and three marking patterns.
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

## Recording real serves (next step)

| Item | Setting |
|---|---|
| Ball | Half the ball coloured black with a permanent marker, plus one black line across the white half (like the simulation). Or Spinsight balls, or SpinDOE-style dots. |
| Camera | Slow-motion **240 FPS**, 1080p if available. Keep the original file. |
| Position | Side-on to the serve's flight, about 1–1.5 m from the ball's path, so the ball is **at least 20 px across** (about 40 px at 1080p and 1.5 m). |
| Light | As bright as possible (Spinsight asks for ≥ 300 lux). Short exposure = less blur. |
| Background | Plain and not white, so the ball stands out. |
| Clip | Trim each serve to the flight after contact, before the first bounce (≈ 0.1 s, 20–30 frames). |

```bash
.venv/bin/python experiments/02-spin-meter/spinmeter.py serve01.mov --fps 240 --start 120 --end 150
```

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
