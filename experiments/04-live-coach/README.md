# Serve Coach Live (Android)

Practise serves with the phone on a stand: it records slow motion all the time, sees each serve
fly, measures it with the spin meter's engine and says how it went. (0.1–0.2 listened for the
hit instead; see 0.3 for why that changed and how it works now.)

Why an app: browsers give pages the camera at 30–60 fps; the spin meter needs 120–240 fps, which
Android offers apps through a "constrained high-speed" camera session (the one the camera app
uses for slow motion).

## How it works

1. **Camera**: a high-speed session at a fixed 240 fps (else 120), 1080p if the phone's encoder
   keeps up, else 720p. Such a session can only feed a preview and a video encoder, so frames go
   to the encoder (H.264, else HEVC; a keyframe every 0.1 s) and the last 5 s of the encoded
   stream stay in memory.
2. **Ear**: the microphone listens for the hit's click: 5 ms blocks of high-passed sound, a click
   when one is 10× the room's recent background (Ear − / Ear + change that), then 1.5 s of quiet
   (the bounces). "Serve now" does the same by hand.
3. **Clip**: from 0.6 s before the hit to 1.2 s after, cut from memory into an MP4.
4. **Engine**: the spin meter's own `engine.js` (and mp4box.js) in a hidden WebView, through the
   same `SpinMeter.analyze` and `SpinMeter.coach` as the web app: spin, type, and the coaching.
5. **Say it**: the result on screen and spoken ("42 revs per second, backspin. Strong backspin").

## Version 0.1: the feasibility check

Things only the phone can answer, all in **Report** (copies JSON to paste back):

- `camera.cameras[].modes`: the slow-motion sizes and rates the phone opens to apps;
  `encoders`: which of them its encoders can take;
- `chosen`, `measuredFps`: what was used and what really arrived;
- `events`: each hit heard (level, clock check), each clip (frames, time to cut);
- `results`: each serve's reading, coaching, and how long the engine took (`wallMs`).

Checked here: the APK builds; `engine.html` runs the engine on a real clip (V2: 42 rev/s
backspin, "strong backspin", 7 s on a 4-core machine). Not checkable here: the camera, the
encoder at 240 fps, the ear in a real room, the engine's time on the phone.

## What 0.1 showed (Pixel 8, Android 17)

- 1920×1080 at a fixed 240 fps is open to apps; H.264 keeps up: 240 frames a second arrived.
- The ear heard the hits (5 of them, 7–10,000× the background); clips were cut 0.1–0.9 s later.
- The frames' timestamps are not on the boot clock the camera reports but on the uptime
  clock; the arrival-time check caught it and the clips were cut in the right place.
- The engine ran in the WebView (WebCodecs, 7 workers) but took 9–16 s for a 1.8 s clip (about
  30 frames a second, as in the web app on this phone), and only 1 of 4 clips read clearly
  (31.6 rev/s backspin); one read a 16 px ball at 15.7 m/s toward the camera.

## 0.2

- clips from 0.3 s before the hit to 0.8 s after (about 260 frames, was 440): faster;
- every measured clip saved to the phone's videos (Movies/ServeCoach), named in the report, so a
  wrong reading can be looked at;
- a queue (up to three serves waiting), 2.5 s of deafness after a hit (0.1 took a second click
  1.6 s after one), the last six serves listed on screen;
- a ball under 24 px across: "move the phone closer".

## 0.3: watching instead of listening

- **One Start / Stop button.** No microphone (a court is noisy and other tables click too).
- **Seeing the serve**: the encoded stream is decoded again as it is recorded (the phone's
  hardware decoder); every 2nd frame (120 a second), at a quarter of its size, goes to a serve
  tracker (`core/ServeTracker.kt`): a background learned over the last seconds, what moved,
  ball-sized round blobs, linked into tracks; a track that flies far enough, steadily enough,
  and isn't a toss or a drop is a serve. About 6–9 ms a frame on a busy 4-core machine.
- **Measuring only what matters**: the serve's frames are decoded again, all of them (240 a
  second), cropped to where the ball flew (a "capture"), and the engine measures the crop
  (`SpinMeter.measureCapture`): no MP4 to cut and parse again, and fewer pixels to search.
- **Test with a video**: plays a saved slow-motion video through exactly the same pipeline as the
  camera (decoder, tracker, capture, engine), so the app can be tried without a table.
- Readings the engine isn't sure of are listed but not spoken.

Checked here, without a phone (no emulator can run here, and none has a 240 fps camera): the
tracker and capture code (`core/`) run on a PC in `desktop/` over the test clips. On all 10
wall clips the serve is found and the crop measures the same as the whole clip did (T1 28.3,
T2 32.8, T3 44.4, T4 38.6, U1 35.5, U2 43.8, V1 45.7, V2 42.0, V3 45.4 rev/s); captures of
other movement are rejected by the engine ("found in too few frames") and not spoken.
Not checkable here: the decoder's speed next to the camera at 240 fps (Report: `watching`
`msPerFrame`, `lagMs`, `dropped`) and the engine's time on a crop (`results` `wallMs`).

## What 0.3 showed (Pixel 8)

- The serve tracker couldn't keep up: 10.7 ms a frame at 120 frames a second is 1.3 s of work
  each second, so 22% of the frames were dropped, and more were skipped waiting for the next
  keyframe. Tracks broke, crops missed part of the flight (#1: the ball in 3 of 37 frames).
- 8 captures, 2 clear readings (32.7 rev/s backspin, 44.6 topspin); the engine took 2–13 s a
  capture, most of it its own search for the flight (2–11 s), and 1–4 s to write a capture.

## 0.4

- **Keeping up**: finding blobs on 3 threads at once (each frame on its own), linking in order on
  another; the decoder only shrinks the frames, reading rows in bulk. The blob finding itself is
  about twice as fast (tighter loops, no per-frame clearing). On the PC the parallel version
  finds exactly the serves the one-thread version does.
- **No second search**: the capture carries the tracker's tracks; the engine fills them in for
  every frame and measures along them (`measureCapture` with `useTrack`), picking among them by
  its own flight rules (`flightRule`, shared with its search) and scoring. On the 13 test
  captures: the same readings as with its search (within 0.7 rev/s), 1.3–4× faster; captures
  of other movement are turned down in 1 ms.
- The tracker waits for a flight still in the air before reporting (V1: the toss came out as a
  serve of its own, the real one in a second capture).
- A capture reaches the engine in one request, not one per frame.
- Report: `encoded` (frames the camera gave), `skipped` (frames the tracker let go to keep up),
  `shrinkMs` / `findMs` / `linkMs`, and per result `fetchMs`, `usedTrack`.

## 0.4.1

- Serve clips are saved again (Movies/ServeCoach). 0.3 cut each clip from memory only after its
  capture was written, by when the serve had dropped out of the 5 s kept, so none were saved and
  nothing said so. Now the clip is cut from the same frames as the capture, the moment the serve
  is found, with 0.5 s more before it and 0.3 s after; a failure is logged.

## What 0.4.1 showed (Pixel 8), and 0.4.2

- The blob finders kept up (`skipped` 0), but 8373 frames were encoded and 5422 decoded: the
  decoder's thread fell behind (shrinking took 3.3 ms of it a frame, and writing a capture, 4.2 s,
  decoded at the same time). Clip C1 (the two saved clips, in the scratch set as C1/C2): here the
  tracker finds its flight and the engine reads 28.4 rev/s backspin + gyro (the web app: 28.38);
  on the phone, with frames missing, the crop missed the flight's right half and a 13 px blob
  was read instead. C2 is the moments before a serve (bouncing the ball, the toss): rightly no
  serve; the serve after it wasn't caught.
- 0.4.2: the decoder's thread only copies the rows the small picture is made from (bulk); the
  finders make it. A capture's crop copies rows in bulk (a byte at a time before). Report:
  `waitingForKey` (frames passed over after a drop), `decoderFull` (waits for the decoder).

## Build

`android/build.sh` (Android SDK 35, Gradle 8.9+, JDK 17+): copies the current engine in and
writes `release/serve-coach-live-<version>.apk`, signed with a debug key (sideloading only).
`gradle :desktop:installDist`, then `desktop/build/install/desktop/bin/desktop clip.mp4 outDir`
(needs ffmpeg) runs the serve tracker on a clip and writes its captures (`.cap`).
mp4box.js (BSD-3-Clause, gpac) is bundled in `assets/`.
