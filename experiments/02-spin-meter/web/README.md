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
