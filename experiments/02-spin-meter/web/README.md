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
