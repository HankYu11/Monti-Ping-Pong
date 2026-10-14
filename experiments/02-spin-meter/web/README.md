# Serve Spin Meter (browser version)

A phone-friendly page that runs the spin meter on a slow-motion video, entirely in the
browser: the video is analysed on the device and never uploaded.

- Published (private until shared): https://claude.ai/artifact/8pUoA3jJDFb1UMSwSBsnTp
- Build: `python3 build.py` → `dist/serve-spin-meter.html` (inlines `engine.js` and the
  example filmstrip into `page.html`).

## What it does

`engine.js` is a port of `../spinmeter.py --auto --method pole`:
1. Reads the MP4 metadata (capture frame rate from Android's `com.android.capture.fps`).
2. Decodes the video **once, front to back** with WebCodecs + mp4box.js. Seeking frame by
   frame instead re-decodes from the last keyframe every time: about 1 s per frame, roughly
   9 minutes per clip. Browsers without WebCodecs fall back to seeking.
3. Finds the serve's flight, the ball in each frame, the black half's pole, scans every
   spin rate, and splits the spin into back/top, side (with curve direction) and gyro.
4. Detects which way is down from gravity, so upside-down or rotated videos read correctly.

The page keeps a session log in the browser (intended spin vs. measured) that can be
copied as tab-separated text, for comparing against what the server meant.

## Checked against the Python meter

Same four serves (VP9 in MP4, headless Chromium, no GPU):

| Serve | Python | Browser | Time |
|---|---|---|---|
| w1 | 35.9 rps, side-backspin + gyro, low quality | 36.1 rps, side-backspin + gyro, low quality | 64 s |
| w2 | 31.7 rps, side-backspin | 31.7 rps, side-backspin | 59 s |
| w3 | 16.9 rps, topspin | 17.1 rps, topspin | 28 s |
| w4 | 36.1 rps, backspin | 36.1 rps, backspin | 51 s |
| w2 upside down | – | 31.7 rps, side-backspin | 58 s |

Test: `node test/run_page.mjs dist/serve-spin-meter.html clip1.mp4 …` with `MP4BOX` (local
copy of mp4box.all.min.js) and `SHOT` (screenshot path) set. Headless Chromium here has no
H.264 decoder, so test clips are VP9 in MP4; phones decode H.264 in hardware.
