# Bisecting the missed serves, on a real phone

0.12.8 found serves on the phone. From 0.13.0 it missed some, and from 0.13.10 it found none.
This folder runs each version's own TEST VIDEO on the same footage, changing nothing in the app
but the way the video is handed to it, so the versions can be compared by running them rather
than by reasoning about them.

## What is already established

* **Ground truth.** `PXL_20261114_161527357.mp4` (stock slow motion, 240 fps, from the spot the
  app was failing at) holds two serves, counted by eye: near frames 900 and 2150. The ball
  measures radius 5.4 to 6.0 in the small picture, the same as in clips that work, so camera
  position and distance are not the problem.
* **The core logic is not it, through 0.13.4.** The tracker and detector exactly as each version
  had them, through that version's own desktop harness, find the same serves on both stock clips
  at 0.12.8, at each of 0.13.0's three tracker changes, at 0.13.2 and at 0.13.4. (0.13.6 onwards:
  see the commit that adds the rest.)
* **An emulator cannot settle it.** A software emulator is ~1000x slower than a phone, its
  WebView crashes at start-up, and without `RIG=1` the chip replay drops frames everywhere --
  0.12.8 itself found nothing. With `RIG=1` the replay is lossless, which tests detection but
  cannot test frame loss under load or a phone's own GPU. Those are exactly what a phone tests.

## Running it on the phone

The phone needs USB debugging on; the computer needs `adb`, an Android SDK on `ANDROID_HOME`,
`gradle`, `ffmpeg`, and this repo on branch `claude/zealous-goldberg-qwboov`.

```bash
cd experiments/04-live-coach/tools/bisect
./cut.sh /path/to/PXL_20261114_161527357.mp4 700 520 s1.mp4
./cut.sh /path/to/PXL_20261114_161527357.mp4 1950 480 s2.mp4
./run.sh b3b0a71 v0.12.8 s1.mp4 s2.mp4      # must find 1 serve in each, or the setup is wrong
./run.sh e6061c2 v0.13.18 s1.mp4 s2.mp4
```

No extras for a phone: the app runs as built, apart from the launch hook and the log mirror.
If 0.12.8 finds both and 0.13.18 does not, bisect between them with the table below; each run
leaves the app's full event log in `$WORK/out` (default `/tmp/servecoach-bisect/out`).

| version | commit | what changed in the watching path |
|---|---|---|
| 0.12.8 | `b3b0a71` | last known good |
| 0.13.0 | `ee5b75f` | room background built on a thread from keyframes, every 4 s |
| 0.13.0 | `eff8643` `847aa73` `55a37b1` | tracker: ball size at p85, wider first link, refusal counts |
| 0.13.2 | `ac706d1` `a44015d` | thin frames when behind (reverted in 0.13.5) |
| 0.13.4 | `9d000c6` | halve the point bar when thinning |
| 0.13.5 | `d7f1184` | guard measures and warns only |
| 0.13.6 | `cef133d` | short tracks get a speed |
| 0.13.7 | `187bd48` | less CPU: scene refresh rarer, pose every 16th frame |
| 0.13.8 | `e57319d` | finders reuse one small picture per thread |
| 0.13.9 | `92b2f87` | detector counts without three whole-frame walks |
| 0.13.10 | `fe3f1cd` `edeab4a` `75d58f0` | mask cut on the GPU; picture read back from the mask pass |
| 0.13.17 | `b89f8da` | camera exposure, white balance and focus locked; colour drift compensated |
| 0.13.18 | `e6061c2` | mask pass textures unbound; framebuffers invalidated; frozen-picture fallback |

On a software emulator instead: `EMU=1 NOWEB=1 RIG=1 ./run.sh ...`.
