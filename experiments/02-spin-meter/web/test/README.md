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

`make_gyro_clips.sh` renders them in two sets. The grey ones use the old marking and read as
`half`. The colour ones use `--pattern half+dots` — the black half plus the three 8 mm dots the
Serve Coach actually reads — and read as `dots`.

That second set matters more than it looks. spinsim rendered one grey channel until 0.9.7, so
every synthetic clip ever made read with the half marking, which meant **the colour-dot path had
never once been checked against a known spin** — and it is the only path whose gyro sign the
engine trusts, and the only one the app will draw a direction from. It now reads 8 of 8 signs
right, magnitudes within 1 rev/s of truth, at 40 and 64 px.

The second colour set separates the three suspects for why the club never reads dots:

| clip | what it changes | marking read |
|---|---|---|
| `g_dots_small` | 30 px ball instead of 40 | dots |
| `g_dots_blue` | a blue table behind the ball | dots |
| `g_dots_blur` | exposure 0.9 instead of 0.25 | dots |
| `g_dots_worst` | heavy blur **and** a blue table | **half** |

So neither a small ball nor a blue background alone loses the dots; heavy motion blur is what
tips it, and blur over a blue table is what falls back to half. Blur also drags the split while
leaving the rate alone: `g_dots_blur` reads 30.1 rev/s against a true 30, but its gyro comes out
18.3 against a true 25.3. The rate survives smearing; which way the axis points does not.

What none of this reaches is a real ball. A rendered dot is a clean disc of known colour; a
painted one is ink on gloss under club light. These clips prove the arithmetic and the detector
agree with each other, not that a hand-painted ball reads the same way.

## What these cannot tell you

A rendered ball is sharper and more evenly lit than a real one, its background is plain, and
its track is exact. These harnesses catch arithmetic going wrong, a reading drifting, and work
being done twice. They cannot stand in for the 30 labelled real serves (T, U, V, C, L, M, N, P)
or for a phone: frame delivery, the camera, and the club's lighting are only testable there.
