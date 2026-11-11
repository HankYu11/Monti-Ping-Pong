# Measuring the engine: ground truth and a clock

The engine had no way to say whether a change made readings better or worse. Real serves have
no true spin to compare against, so a change could only be judged by eye, one clip at a time.
That is how a 27% error from reading full-range video as limited range lived for months
(live coach 0.7.3).

Rendered serves do have a true spin. These harnesses run **the real `engine.js`**, in headless
Chromium through WebCodecs and workers, exactly as the app's WebView runs it, and score it.

```bash
bash test/make_clips.sh /tmp/clips          # render the clips (needs ffmpeg)
node test/bench.mjs      /tmp/clips/k_*.mp4 # a whole clip: reading vs truth, and the time
node test/capbench.mjs   /tmp/clips/k_*.mp4 # a live capture: searching vs the tracker's track
node test/guard.mjs      /tmp/clips/k_*.mp4 # what may be measured along a handed-in track

bash test/make_gyro_clips.sh /tmp/gyro      # pairs that differ only in which way they drill
node test/gyrosign.mjs       /tmp/gyro/*.mp4 # the gyro's direction, not just its size
```

`make_clips.sh` writes `<clip>.json` beside each clip with the true spin, the axis and the
ball's track — the track a perfect live tracker would hand the engine.

## bench.mjs — accuracy and time on a whole clip

Runs `SpinMeter.analyze`, the path "Test with a video" uses. Prints the reading, its error
against the truth, the axis error and the wall clock; `--json` writes the rows for comparing
two engines.

## capbench.mjs — the live path, three ways on identical pixels

Crops the clip to the flight and feeds raw frames to `SpinMeter.measureCapture`, as the app
does after cutting a capture. Three runs:

| mode | what it does |
|---|---|
| `search` | the engine finds the ball in the crop itself |
| `track` | it measures along the track it was handed (`useTrack`) |
| `badtrack` | it is handed a *hand* sliding along the bottom, and must refuse it |

`badtrack` is the one that matters for safety: measuring along a handed-in track was turned off
in 0.5 because on clip L1 the engine measured a moving hand. It must now say so and search for
itself, keeping the reading.

## guard.mjs — the ball-in-flight check on its own

Runs `ballisticRule` over real tracks (from the rendered clips, with tracker-like jitter) and
over made-up ones: a hand sliding back and forth, an arm climbing, a ball merged with the
paddle, and a track too jittery to be a ball. Real ones must pass, the rest must not.
Exits non-zero on any disagreement, so it can gate a change.

## gyrosign.mjs — which way the ball drills

The engine reports `gyro` signed: positive means the spin points along the flight, away down the
table. That sign is the whole answer to whether the ball will skid right or left at the bounce,
and until 0.9.6 it was thrown away (`gyro: Math.abs(c.gyro)`), so nothing downstream could have
noticed it going wrong. This renders each spin twice, once each way round, and checks the
measured sign against the truth — the true spin projected on the true flight direction, both from
spinsim's own JSON. A sign dropped or flipped anywhere between the fit and the result shows up.

It cannot check the colour-dot path. spinsim renders one grey channel, so every clip reads with
the half marking, and on a half-marked ball the engine can misjudge front from back (`mirrored()`
in engine.js) — the sign goes with that misjudgement. Only the dots settle it, and only real
footage has them. So this proves the sign is carried, not that a half-marked reading's sign is
right; the app draws the gyro's direction only when the dots were read.

## What these cannot tell you

A rendered ball is sharper and more evenly lit than a real one, its background is plain, and
its track is exact. These harnesses catch arithmetic going wrong, a reading drifting, and work
being done twice. They cannot stand in for the 30 labelled real serves (T, U, V, C, L, M, N, P)
or for a phone: frame delivery, the camera, and the club's lighting are only testable there.
