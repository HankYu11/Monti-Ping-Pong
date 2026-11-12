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

python3 test/capsanity.py  Session_*/serve*.json                  # do the headers agree with themselves?
python3 test/capfromclip.py serve01.json serve01.mp4 serve01.cap   # a real serve, off the phone
node test/capcheck.mjs  serve01.json serve02.json ...             # does it still read what the phone read?
node test/capcheck.mjs --ext .win.cap --split 4 serve01.json ... # ... and does the windowed cut of it?
node test/capprof.mjs   serve01.cap 3                             # where the engine's time goes
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

## gyrodir.mjs -- when the gyro's direction is worth showing

`gyrosign.mjs` checks the sign is right. This checks when it can be *trusted*, which is a
different question. A half-marked ball can be seen mirrored front to back; that negates the spin
axis's depth, so gyro moves by `2*w_z*f_z`. Side on, `f_z` is nil and the mirror cannot touch the
sign; down the table it can reverse it. `gyroDirKnown` on each reading is the engine's verdict,
and this holds it to account over 36 spins at eight camera angles -- that at 0 degrees nothing
moves, that far off side-on plenty flips so the rule isn't vacuous, and that a spin whose sign a
mirror would reverse is never called known.

It takes no clips on purpose: spinsim renders only flights across the picture, so every synthetic
clip is side on and none of them can produce the case the rule is for.

## capfromclip.py — a real serve, measurable off the phone

A session saves `<serve>.mp4` (the whole ring, full frame) and `<serve>.json` (the capture's
header and the live tracker's track), but not the `.cap` the engine actually measured. This
rebuilds it. The clip holds more frames than the capture and nothing records where the capture
starts, so the offset is found by scoring every candidate on how far the pixels differ from the
background inside the discs the track predicts: at the right offset the ball is in them and
nowhere else. The peak is sharp — one frame either side scores a third lower, a wrong offset
twenty times lower — and the rebuilt capture reproduced the phone's own reading to the digit on
every serve tried (39.80, 57.28, 61.71 rev/s).

This matters because until now only rendered clips could be re-measured away from a phone, and
rendered clips have no mains flicker, no motion blur, no player and no real marking.

`--rect X Y W H` cuts a different window out of the same clip. That is how the engine's cost was
shown to scale with the area it is handed and with nothing else.

## capsanity.py — the headers against themselves

One second, no clips and no browser, on a session's `serve*.json`. It checks that the track in a
capture's header lands inside that capture, and that the crop fits the frame on even pixels.

It exists because nothing else could catch that class of fault. App 0.10.4 began trimming the
capture to the tracked frames and went on numbering the track from the untrimmed start, so every
track shipped shifted by however far the trim had moved it; the engine looked for the ball where
the ball was not, measured what it found there, got nothing, and searched the whole crop instead.
Session 20261112_215446 had 23% to 88% of each track outside its own capture and searched on 8 of
10 serves, at about 9 s each. The two sessions before it: 0%, every serve, every track.

The rebuild harness below is blind to it by construction. `capfromclip.py` finds a clip's offset
by scoring the track's own predicted discs, so a shifted track is matched at a shifted offset and
comes out looking perfect. The one thing it cannot see is the track being wrong relative to its
capture, because it uses the track to place the capture.

## capcheck.mjs — real serves as a regression test

A session records its own reading for each serve; `capfromclip.py` rebuilds the capture that
reading came from. Together they make a real serve — with real mains flicker, real motion blur, a
real player in the picture and a real marking on the ball — into a test with an answer. Rendered
clips cannot be that, however good the renderer is.

`--ext` measures a different cut of the same serves against the same answers, which is how the
windowed capture is held to the reading the phone gave for the whole crop. Build those with the
app's own writer:

```bash
gradle -p ../../04-live-coach/android :desktop:installDist
java -cp "../../04-live-coach/android/desktop/build/install/desktop/lib/..." \
     com.monti.servecoach.desktop.WinCapKt serve01.cap serve01.win.cap
```

The rule is not "the same number" for every serve. A serve the engine could not read is held only
to saying so again: comparing one unreadable answer to another compares noise. Session
20261112's serve 2 fits 0.09 where a serve the engine can read fits 0.7 to 0.9, and any change to
which pixels are looked at moves that number freely. Those serves print with `~`. For every serve
the engine called clear, the reading, the spin split, the marking and the gyro's direction must
all still agree.

A serve the engine measured over a *different stretch* of the flight is held only to the things
that do not depend on which frames were used — the rate, the kind of spin, the marking, whether
the gyro's direction is claimed. The spin split over different frames is a different measurement,
not a drifted one. Those print with `s`. It happens when the engine refuses the live track and
searches the crop itself, and a capture cut down to windows is a cleaner picture to search:
session 20261112's serve 4 gave the search 44 frames of flight where the whole crop gave 23, and
it fitted them better.

`--split` sets how far the three numbers the spin is split into may move. 1 rev/s for a cut that
holds the same pixels; 4 for the windowed form, which does not — measured against the whole crop
on the four serves of session 20261112 that the engine measured along the track, the rate moves
by at most 0.15 rev/s and the split by at most 3.4, the worst being the serve flying most
directly at the camera, where the split is most sensitive to the flight's angle.

## capfile.mjs — one reader for a .cap

The parser the three harnesses share, as a string, because it has to run inside the page: a
capture is tens or hundreds of megabytes of typed arrays and handing that back across the
Playwright boundary would cost more than the measurement.

It was three copies before, and two of them silently read a windowed capture as a whole-crop one.
The header says `windows`, the bytes are the background, the row brightness and the windows, and
reading them as frames gives noise that still parses and still measures.

## capprof.mjs — where the engine's time goes

Chromium's sampling profiler around `SpinMeter.measureCapture`, through the same headless path
the WebView uses: no instrumentation, no guessing. Prints self time (which function burns the
CPU) and inclusive time (which stage owns it).

It was written because the engine's share of a serve's wait had been argued about for months
without anyone looking. The answer: the spin maths is 6% of it, and per-pixel work on pixels that
are not the ball is nearly all the rest.

## What these cannot tell you

A rendered ball is sharper and more evenly lit than a real one, its background is plain, and
its track is exact. These harnesses catch arithmetic going wrong, a reading drifting, and work
being done twice. They cannot stand in for the 30 labelled real serves (T, U, V, C, L, M, N, P)
or for a phone: frame delivery, the camera, and the club's lighting are only testable there.
