# Serve Spin Meter (browser version)

A phone-friendly page that runs the spin meter on a slow-motion video, entirely in the
browser: the video is analysed on the device and never uploaded.

- Published (private until shared): https://claude.ai/artifact/8pUoA3jJDFb1UMSwSBsnTp
- App (installable, offline, in the Share menu): https://hankyu11.github.io/Monti-Ping-Pong/
  once GitHub Pages is switched on (Settings → Pages → Source: GitHub Actions). The workflow
  `.github/workflows/spin-meter-pages.yml` rebuilds and publishes it on every push to `main`.
- Build: `python3 build.py` makes both from `page.html` + `engine.js`:
  - `dist/serve-spin-meter.html`: one self-contained file (the artifact above).
  - `dist/app/`: the same page plus `app/` (manifest, service worker, icons, install button).

## What it does

`engine.js` is a port of `../spinmeter.py --auto --method pole`:
1. Reads the MP4 metadata (capture frame rate from Android's `com.android.capture.fps`).
2. Decodes the video **once, front to back** with WebCodecs + mp4box.js. Seeking frame by
   frame instead re-decodes from the last keyframe every time: about 1 s per frame, roughly
   9 minutes per clip. Browsers without WebCodecs fall back to seeking.
   A software decoder is requested first, so each frame's brightness (Y) plane can be copied
   straight out of the decoded frame instead of going through a canvas.
3. Finds the serve's flight, the ball in each frame, the black half's pole, scans every
   spin rate, and splits the spin into back/top, side (with curve direction) and gyro.
4. Detects which way is down from gravity, so upside-down or rotated videos read correctly.
5. **Several serves per video (v2).** Every flight in the video becomes its own reading.
   Ball tracks closer than 0.5 s apart count as one flight (the longest piece is kept), so
   leave a second or two between serves. The result panel lists the serves (average and
   range of RPM, tap one to see it), and the session log gets one row per serve.

The page keeps a session log in the browser (intended spin vs. measured) that can be
copied as tab-separated text, for comparing against what the server meant.

## Checked against the Python meter

Same four serves (VP9 in MP4, headless Chromium, no GPU):

| Serve | Python | Browser | Time (before → now) |
|---|---|---|---|
| w1 | 35.9 rps, side-backspin + gyro, low quality | 35.8 rps, side-backspin + gyro, low quality | 54 → 22 s |
| w2 | 31.7 rps, side-backspin | 31.7 rps, side-backspin | 53 → 21 s |
| w3 | 16.9 rps, topspin | 17.1 rps, topspin + gyro (gyro share 0.52, cut-off 0.5) | 25 → 10 s |
| w4 | 36.1 rps, backspin | 36.1 rps, backspin | 49 → 19 s |
| w2 upside down | – | 31.7 rps, side-backspin | 54 → 21 s |

## Setup check (v2)

While a video is analysed, a checklist fills in as each item can be judged:
- straight away: slow-motion frame rate (from the file), landscape, resolution;
- after the background pass: overall light, camera steadiness (share of the picture that
  keeps changing);
- per serve: ball size, frames with the ball, marking contrast, wall brightness behind the ball.
- If no serve is found, it says whether no ball was seen at all or the ball didn't fly across.

Limits come from the test serves (light 136–145 of 255, ball 21–28 px across, marking
contrast 55–70%, w3 24%) and from deliberately bad copies of w3: darkened (brightness 41 →
"too dark"), shrunk (12 px → "too small"), rotated to portrait (→ "portrait video", no flight).

## The app (v2)

`dist/app/` is the page as an installable web app. Chrome on Android offers "Install app";
iPhone uses Share → Add to Home Screen. Installed on Android, it appears in the Share menu
(manifest `share_target`): in Photos, Share → Spin Meter opens the app and starts the analysis.
The service worker keeps the shared video only until the page picks it up, and keeps the page,
mp4box.js and fonts for offline use. Checked in headless Chromium: installable with no errors,
works offline, and a video posted to `./share` is analysed.

## Busy backgrounds and filming from the end of the table (2.2)

Real halls have no plain wall. Clips filmed there (Pixel 8 originals, 15 Oct) found no serve in
2.1. What changed, and why:
- **Colour.** The search used brightness only, so the ball's black half and the blue table
  were the same grey and half the ball vanished. Pixels now count as "not background" when
  their brightness *or colour* (the decoder's chroma planes, no extra cost) differs.
- **Linking.** Flickering lights at 240 fps leave hundreds of specks. Tracks now take their best
  match first (near the predicted spot, similar size), so a speck can't take the ball's place,
  and a flight must move steadily (≥ 1 px per frame at half size, mostly straight).
- **Any direction.** Flights toward or away from the camera count, not only across. Not a
  flight: a toss (mostly upward) or a straight drop that doesn't come closer.
- **Bounces.** A bounce changes the spin, so the flight is cut where its velocity jumps and the
  longest stretch between bounces is measured (a ball coming closer speeds up smoothly, which
  doesn't count). From the end of the table that is the spin after the first bounce.
- **Growing ball.** The ball's size is fitted per frame (1/size is linear in time).
- **Reference white.** Darkness is judged against the background when it is even around the
  ball (a plain wall, the table top); otherwise against the ball's own white half. This keeps
  every earlier result unchanged.
- **Direction and down from 3D.** The ball's distance comes from its size, so the flight
  direction and gravity are taken from its path in space. Judged from the picture alone, a
  ball coming closer drifts outward and seems to curve, which turned "down" by 90°.

Results (headless Chromium; the new clips converted to VP9 for the test browser):

| Clip | 2.1 | 2.2 |
|---|---|---|
| p4, hall, from the far end (coach: backspin with a little side) | no serve | **36.9 rps backspin, clear** (count by eye 37.5) |
| p3, same view, hand-held | no serve | wrong object, marked low |
| p1, p2, from behind the server | no serve | no serve (ball small and blurred) |
| w1 | 35.8 side-backspin + gyro, low | same, now clear (fit 0.74, next rate 0.29) |
| w2, w2 upside down, w3, w4 | | same rates and labels |
| w1+w2+w4 in one video | 3 serves | same 3 serves |

"Clear" now also means a fit ≥ 0.5 that is ≥ 0.2 ahead of the best other spin rate.
The Python meter (`../spinmeter.py`) doesn't have these changes yet.

## Off the paddle and after the bounce (2.2)

The flight is cut at its kinks. A kink where the ball suddenly flies off much faster (≥ 1.6×)
is the hit: anything before it, such as a toss falling into view, is dropped. Any other kink
is a bounce. Two stretches are then measured:
- **off the paddle**: from 4 frames after the hit (the paddle still touches the ball) to the
  first bounce: the serve's own spin;
- **after the bounce**: from 2 frames after the bounce to the next kink: what the receiver faces.

Each needs ≥ 8 frames. The headline reading is off the paddle when it reads clearly, else the
clearer one; the page shows both and either can be opened. With no bounce in view there is one
"in flight" reading. A stretch where no ball-shaped object is found in most frames is dropped
when the video has other serves.

Tested on made-up paths (toss, hit, flight, bounce: kinks found at frames 13 and 32 for true
12 and 32; a ball speeding up smoothly toward the camera gives no kink) and on the clips:
p4's stretch before the bounce is 8 frames, too short, so it reads after the bounce only
(36.95 rps backspin, clear); w1 reads 35.8 rps off the paddle (clear) and a short, low-quality
stretch after a later bounce; w2–w4 have no bounce in view and read as before.

Best camera position for both readings: beside the table at the server's half, 1–1.5 m from
the path from paddle to first bounce, 40–60 cm above the table and tilted down, so the table
is behind the ball and its size barely changes.

## Saying what went wrong, not guessing (2.2)

A stretch without a clear reading shows **no number**: "No clear reading", the frames it had,
and a "What to change in the footage" list, each item a problem found in the footage and the fix:
- ball found in under 70% of the stretch's frames (hidden, or blends with the background);
- ball under 20 px across; motion blur (ball stretched > 1.35× along its path);
- black half faint (contrast < 30%); spin rate ambiguous (another rate fits about as well);
- setup problems from the checklist (too dark, frame rate, portrait).

Stretches that couldn't be measured say why, with frame counts: "Off the paddle: not measured.
Only 7 frames from when the ball was first seen to the bounce (needs 12)"; after the bounce,
whether the ball then left the picture (which edge) or was lost; with no bounce in view, that
the reading can't be placed before or after the bounce. The session log records "no clear
reading" with the first reason instead of a number.

Tried and dropped: a whole-picture camera-shake measure (on plain walls there is nothing to
line up, so tripod clips looked shaky), and one background per section of the video (too few
samples per section; the server's body leaked into it and p4 was lost). The background samples
are now stored as bytes (about 60 MB instead of 240 MB for 40 colour samples).

## Picking the serve among noise, and troubleshooting details (2.3)

p4 read fine in the test browser but found no serve on the Pixel 8 (original HEVC file, 2.2).
The new troubleshooting details showed why this was fragile even here: about 95 candidates per
frame (light flicker, compression) and 88 noise tracks passing as flights. One flight per time
window was chosen by **length**, so a long noise track could push the real serve out and then
yield nothing to measure. Now:
- tracks whose ball would be under 5 px across are dropped ("tiny");
- every candidate is turned into stretches to measure first, and per time window the one with
  the most measurable frames of the biggest ball wins;
- with a clear serve in the video, a short unclear stretch with no bounce (a hand, a ball being
  picked up) is dropped.

Results: p3 (hand-held, far end) now reads 32.8 rps backspin after the bounce, clear; by eye one
turn per 7.3 frames, 33 rps. p2 (from behind the server) reads 34.55 rps backspin after the
bounce, clear; by eye about 36 rps. p4 and all earlier clips unchanged.

**Details for troubleshooting** (in Measure serves, after a video): decoder and pixel format,
frames decoded and unmatched, full or video range, candidates per frame, tracks rejected by
reason, the longest moving tracks, the serves chosen, and each stretch's frames, rate, fit and
problems. One tap copies it. Also checked: NV12 frames (phone hardware decoders) read the same
as I420, and full-range video gives the same result as video range.

## Fewer readings thrown away, and a check against the bounce (2.4)

Tested on the 16 Oct clips (A–H), the earlier hall clips (p2–p4) and the home clips (w1–w4).

**A clear reading needs at least one full turn of the ball.** Below that the rate can't be
told: H's after-the-bounce stretch was 15 frames, a quarter of a turn, and gave 4–5 rps. It now
shows "The ball turned only about 27% of a turn in this stretch".

**Ambiguous means a *different* rate fits as well.** Before, the runner-up could be the best
rate's own neighbour. Short stretches always have a wide best match, so every off-the-paddle
reading looked ambiguous. Now the runner-up must be a separate match. The best match's width
is shown as ± RPM.

**Blurred frames count less.** Each frame is weighted down by how stretched the ball is. Frames
far off the fitted spin are weighted down step by step, so one bad frame can't pull the rate.

**Off the paddle vs after the bounce.** The table takes some backspin away. When both stretches
read clearly as backspin, the off-the-paddle rate must be 0.9–2.2× the after-the-bounce rate.
If it isn't, the off-the-paddle reading is not shown, with the reason. If it fits, the result
says "Fits the after-the-bounce reading".

| Clip | Off the paddle | After the bounce | Ratio |
|---|---|---|---|
| A | 39.0 rps (±2.8), was "low" | 27.6 | 1.41 |
| B | 50.8 (±3.0), was "low" | 34.6 | 1.47 |
| D | 41.4 (±1.4), was "low" | 25.8 | 1.61 |
| E2 | 52.8 (±2.3), was "low" | 38.8 | 1.36 |
| H | 15.6 (±0.8), was "low" | not clear (¼ turn) | – |
| p3 | 44.3 (±4.2), was "low" | 32.9 | 1.35 |

All other rates are unchanged: p2, p4, w1–w4 and F.

## Colour dots (2.5)

The black half alone has three weak spots:
- **It needs a full turn.** A stretch shorter than one turn can't be read.
- **It fails when the spin axis runs through the black half's centre.** The black half then
  barely moves.
- **It can't read anything when the black half faces away the whole time.**

Colour dots are sharp points, so where a dot is gives the turn from a few frames.

**Marking** (optional; balls without dots read exactly as before):
- Three dots on the white half, each about 8 mm across and filled solid: red, blue and green.
- Each dot sits 1 cm (a finger-width) from the seam.
- They are evenly spread: 12, 4 and 8 o'clock, looking at the white half straight on.
- Near the seam, a dot comes into view whichever way the ball spins.
- The old black line and black dot aren't used by this meter and can be left off.

**How the dots are read:**
- **Finding a dot.** Each dot is found by its hue, measured against the ball's own neutral colour so the light's tint cancels out. Its pixels are left out of the black-half measurement.
- **Fitting the spin.** The black half's pole and every dot are fitted together as one ball turning at one rate about one axis. Turning each sighting back by the spin should bring all sightings of a mark to one spot.
- **Starting points.** The fit starts from the black half's best rates and from each dot's own circle round the axis. It is then refined.
- **How much turn is needed.** With a dot seen in 4 or more frames, a quarter turn is enough (otherwise the full-turn rule stays).
- **Black half not seen.** If the black half can't be seen at all, the dots alone are used.

**Traps found while testing:**
- **Edge of the search area.** Compression smears colour, so the background bleeds 2–3 px into the ball. Dots are looked for only inside that edge, and a dot cut by it counts less. Before this, dots near the rim read too far in and the spin too slow (8–20% low).
- **Blur.** Motion blur stretches the ball, so its radius is taken across the streak.
- **Real clips without dots.** The blue table bled into the white ball and was taken for blue dots in many frames. That made two readings unclear (p4 after the bounce, and E1).
- **The fix for that.** A dot near the rim is rejected when the background just outside the ball, in that direction, has the same colour. Patches are checked at three distances and three angles: one patch landed on the table's white edge line and missed it. With this, all real clips read as in 2.4.

**Synthetic test** (`colsim.py`, in the scratch tests):
- 16 random serves, 8–80 rps, 14–60 frames.
- Exposure up to 1/300 s, a wavy hand-drawn black edge, 28–40 px ball.

| | Rate error (median / worst) | Axis error (median) | Clear readings |
|---|---|---|---|
| With dots | 1% / 7% | 3° | 15 of 15 found |
| Black half only (same serves) | 0% / 16% | 6° | 11 of 13 found (none on 3) |

Notes on the results:
- **The ± range.** Each dot reading's stated ± range covered its true rate. The worst errors (7%) were 12–13 rps serves seen for 18–24 frames, with ± of 1.5–2.5 rps.
- **Serves only the dots could read:**
  - a 9 rps serve seen for half a turn;
  - a 16 rps serve whose black half faced away throughout (the old method said "marking too faint");
  - an axis 8° from the black half's centre (fit 0.48 → 0.98).
- **Not yet tested on real footage:** real marker colours under hall light, and the ball's printed logo (a red logo would count as a red dot; it turns with the ball, so it helps or is down-weighted).

## Same side all the way (2.6)

Home clips filmed face-on (19 Oct, I–L) showed a new way to get a wrong "clear" reading.

- **The problem.** In clip K the black half faced the camera for the whole stretch: a black disc with a sliver of white now and then. The fit turned that noise into "backspin, 1,710 RPM, clear" on a topspin serve.
- **The measure.** Each stretch now gets a "pattern change" score: how much each spot on the ball flips between black and white over the frames. The ball is split into a 6 × 6 grid for this.

| Stretch | Pattern change |
|---|---|
| Readable stretches in all labelled clips | 0.11 (w3, dim room) and up; mostly 0.3–0.4 |
| Clip K, black disc | 0.08 |

- **The rule.** Below 0.10 there is no clear reading for the black-half method. The tool says the ball showed the same side all the way, and suggests filming from the side. Readings from colour dots are exempt.
- **Result.** All labelled readings are unchanged.

## Measure only part of the video (2.7)

Decoding every frame is most of the work. A 20–60 s video with one serve in it spent most of its time on frames that don't matter, and other ball movements in it could be mistaken for the serve.

**On the page:**
- Choosing a video now opens it in a player first.
- Play or drag to just before the serve and tap **Start**, then to just after the bounce and tap **End**. The ‹ › buttons step one frame.
- **Measure this part** decodes only those frames, starting at the keyframe before Start.
- **Measure the whole video** still works as before.
- If the browser can't show the video, it is measured whole.

| Clip | Whole video | Trimmed to 3–4 s | Readings |
|---|---|---|---|
| N | 64 s | 23 s | Same |
| Q | 68 s | 24 s | Same off the paddle; after the bounce a longer stretch, 48.75 vs 49.10 rps |
| S | 52 s | 12 s | Same after the bounce; off the paddle starts earlier, same mirror problem |

Times are from the test browser on a laptop; phones are slower, so the saving there is bigger in seconds.

## Backspin vs topspin mirrors, and a faster first pass (2.8)

**Clip S (20 Oct): what went wrong.** The serve was sidespin with topspin. The tool read it as sidespin with backspin off the paddle, and sidespin with topspin after the bounce, both marked clear.

- **Sideways swing hides the direction.** Off the paddle, the black half mostly swung left↔right in the picture, about once every 4 frames (55 rps). A sideways swing looks the same whether the ball turns over the top or under the bottom.
- **The toward/away clue was skewed.** The only thing that tells those apart is whether the black half faces toward or away from the camera on the way. In these frames the ball was smeared grey by its own spin, in front of a dark wall. The toward/away part reached only +0.26 toward the camera but −0.73 away; a true circle about this axis reaches about ±0.6 both ways.
- **The data favoured the mirror.** Fitted about the after-bounce axis, the frames fit 0.25; fitted about its front-back mirror, 0.62. The fit followed the skewed data.
- **No dot was seen** in S. A dot would have settled it.

**The fix.** A bounce can weaken backspin but can't turn a strong backspin into a strong topspin, or topspin into backspin. So when the two stretches disagree (both with at least 40% back/top), one of them is mirrored:
- if one stretch has colour dots, it decides, and the other is turned round to match;
- otherwise neither is clear, and the app says so, keeping the sidespin (S: about 1,460 RPM, curving right), which holds either way.

All labelled readings are unchanged (q5 off the paddle 52.70 → 53.00 rps).

**Faster first pass.** Phone videos have a keyframe every 30 frames, and a keyframe decodes on its own. The background is now learned from up to 60 keyframes instead of decoding the whole video for it.

| Clip | Frames | Background step | Total |
|---|---|---|---|
| S | 419 | 10.2 → 2.2 s | 23.6 → 12.9 s |
| O | 1,307 | 14.9 → 7.3 s | 48.6 → 41.3 s |

Times are from the test browser with phone-like keyframes.

**Then the real cost turned up.** Each video measured on its own, on the same files:
- The old pass was about 6 s fixed plus about 5.6 ms per frame. The keyframe pass was about 150 ms per keyframe.
- So S and O saved about the same 6.5 s, although O is 3× longer: O has 44 keyframes, more than the old 40 samples.
- Timing inside: reading a frame took 14 ms. Storing it compactly took 134 ms, because `TypedArray.from` called a function for every one of the ~1.5 M values in the frame.
- Plain loops give identical bytes, 7× faster.

| Clip | Background: old | Keyframes | Plain loops | Total: old → now |
|---|---|---|---|---|
| S | 7.9–9.3 s | 2.1 s | 0.4 s | ~20 → 10.9 s |
| O | 13.1–14.1 s | 6.3–7.5 s | 1.1 s | ~45 → 32 s |

The ball search, about 22 ms a frame, is now nearly all the time. Decoding alone is about 5–6 ms of it.

**The ball search, profiled** (clip O, 1,307 frames, 3 workers on a 4-core test machine):
- Copying a frame out of the decoder takes 2.5 ms. The main thread then waits 52 ms per frame for a free worker, so the workers set the pace.
- Per frame, in a worker:

| Step | Before | After |
|---|---|---|
| Brightness to half size | 7.2 ms | 7.0 ms |
| Colour to half size | 6.5 ms | 6.9 ms |
| Even out flicker | 9.4 ms | 6.4 ms (no full corrected copy) |
| Moving mask | 14.3 ms | 14.6 ms (one plain loop; the arithmetic itself is ~10 ms) |
| Remove specks | 6.3 ms | 6.8 ms |
| Group into blobs | 12.4 ms | 3.1 ms (work arrays reused, not 2 × 2 MB a frame) |
| **Total** | **56 ms** | **45 ms** |

- **Same arithmetic.** The mask loop includes the float32 rounding the old code had, so all labelled readings are identical.
- **Totals for O:** about 45 s → 32.0 s (background) → 26.7 s (search).
- **More workers.** Up to 7 now, one per core but one: phones have 8–9 cores, so the search should scale there. That isn't tested on a phone yet.

**Automatic trimming: tried, not reliable yet.**
- *Compressed frame sizes* (free to read): motion during the serve is only 1.0–1.1× the clip's usual level, and 4–12% of frames are busier.
- *White ball-like spots in keyframes:* 18–33 during a serve, but just as many elsewhere (up to 46): the ball basket, shirts, hands.
- **So the manual Start/End trim stays** the way to skip most of a long video.

## Versions and updates

The header shows `Version <VERSION> · build <id>`. `VERSION` is bumped by hand for a release;
the build id is a hash of the built page, so every change gets a new one (the same source
gives the same id locally and in the Pages workflow). Session rows and the copied text carry
it, so a reading can be traced to the build that made it.

The installed app updates itself: the page is always fetched fresh when online (revalidated,
so GitHub Pages' 10-minute cache doesn't hold it back), the cached copy only serves offline.
An app left open checks `version.json` when it comes back on screen and every 30 minutes; if
the build differs it shows "A new version (x) is ready · Later / Reload". Tested by swapping
the served build under an open app: the note appears, Reload loads the new build, and the new
build is then what loads offline.

## Several serves in one video

Test clip: w1, w2 and w4 joined into one video (1903 frames). Each serve reads the same as
its own clip: 36.1 rps side-backspin + gyro (low), 31.7 rps side-backspin, 36.1 rps
backspin. w1 joined to itself reads 36.0 and 35.9 rps. Time grows with the video's length
(about 73 s headless for the three serves), so a long set takes minutes on a phone. The
whole file is read into memory once (no extra copy), so keep a set to roughly 20–30 s of
recording.

## Speed

Where the time went (w1, 700 frames at 1080p): copying every frame out through a canvas,
the per-row deflicker median (a full sort per row), the mask clean-up, and the per-pixel
background median. Changes:
- **Direct frame reading**: the brightness plane comes straight from the decoded frame
  (`VideoFrame.copyTo`), no canvas. Falls back to the canvas when a decoder hands out frames
  that can't be copied (some phone hardware decoders). The canvas path alone went 25 → 13 s
  on w3 and gives the same numbers as before.
- **Medians by selection** instead of sorting, and a faster 2×2 mask opening. Both give
  bit-identical output (checked against the old code on real frames and random masks).
- **Worker threads**: the per-frame ball search runs on up to 4 background threads, built
  from this same file. If the page isn't allowed to start workers, it runs on the main
  thread as before.

On the Pixel 8 (Chrome): about 60 s per clip before, **16 s** after, with direct frame
reading (the software decoder's frames are readable, so the canvas fallback isn't needed).

Small differences from the earlier numbers come from reading brightness directly (video
range Y mapped to 0–255) instead of the canvas' RGB-to-gray conversion. The result shows
"Analysed in N s · direct / canvas frame reading" and the session log has a `seconds`
column, so the time on a phone can be compared.

Test: `node test/run_page.mjs dist/serve-spin-meter.html clip1.mp4 …` with `MP4BOX` (local
copy of mp4box.all.min.js) and `SHOT` (screenshot path) set. Headless Chromium here has no
H.264 decoder, so test clips are VP9 in MP4; phones decode H.264 in hardware.
