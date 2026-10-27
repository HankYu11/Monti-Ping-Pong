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

## What 0.4.2 showed (Pixel 8), and 0.5

- Replaying the saved clip C1 on the phone: 28.42 rev/s backspin + gyro, as here (28.4) and in
  the web app (28.38). The measuring is right when no frames are missing.
- Live, 8 serves: 14216 frames encoded, 7844 decoded, 4154 passed over after drops, the decoder
  full 484 times. The phone can't decode 1080p at 240 frames a second for watching and keep up;
  of 7 saved clips only one (L1) held a serve's flight, the rest the moments around serves.
  L1 here: 22.95 rev/s backspin (web app 22.93).
- Measuring along the live tracker's tracks was faster but not sure enough: on L1 it measured a
  hand moving along the bottom; smoothing the tracks fixed that and lost V3. **0.5 goes back to
  the engine's own search in the crop** (all serves read as the whole clip does).
- **0.5 watches without decoding**: the camera's preview stream (every frame, as the recording)
  goes to the graphics chip (`GlTap`), which draws each frame at a quarter of its size (each
  pixel the mean of its 4x4 block) as brightness and colour, and reads that back: 0.5 MB a
  frame instead of decoding 2 million pixels. It also draws the preview on the screen. The
  decoder is used only for a serve's own frames. Which way round the chip's picture is against
  the recording is checked once at the start (a recorded keyframe decoded, compared 4 ways:
  `pictureCheck`). On the PC, pictures made that way (4x4 means) find every serve and measure
  them the same.
- A reading only "after a bounce" under 10 rev/s isn't announced (walking off to fetch the ball
  read 4.8).
- Report: `watchingBy`, `tap` (frames, tapped, missed, tapMs), `pictureCheck`.

## 0.5.1

0.5 on the Pixel 8: the preview was turned a quarter, and the tap got 324 frames of 2581 (30 a
second): Android's slow-motion burst (`createHighSpeedRequestList`) sends the preview only the
first frame of each 8. Its first request targets both streams, and a burst is accepted if made of
its requests, so 0.5.1 repeats that one 8 times (`everyFrameToPreview` in the log; Android's own
list if refused). The preview is drawn as the recording is, not by the camera's transform (meant
for a portrait screen), turned by the picture check; the check now decodes any tapped frame from
its keyframe, not only keyframes.

## 0.5.2

0.5.1 on the Pixel 8: every frame went to the preview (accepted), the preview was the right way
round, but the tap kept 481 of ~1240 frames and the picture check matched none. The tap waited
for each read-back (4 ms) and frames arriving meanwhile were replaced; now each picture is
copied into one of 3 buffers while the chip goes on and taken out two pictures later (OpenGL ES
3; `tap.readBack`). The check failed because the preview's times count the phone's sleep and the
recording's don't (762,430 s apart): the offset is estimated from the two clocks and set exactly
from the matched frame (`pictureCheck` says by how much the estimate was off).

## 0.5.3

A session on 0.5.1 (8 serves) watched nothing: its picture check never matched a frame (the
clocks, fixed in 0.5.2), and the tracker waits for the check. Now after 5 s of failed checks it
watches anyway (as the picture comes, the clocks' estimate) and says so; the screen shows the
app's version and whether the check is done.

## 0.5.4

0.5.3 (no serves yet): the picture check passed (flip 0: mean difference 0.8 against 35-36 the
other ways; clocks 762,426 s apart, estimated within 41 us), but the tap kept 1115 of ~2260
frames: taking a picture out of its buffer still cost 3.9 ms on the frames' thread, and the
camera keeps only its newest preview frame. Now a second thread (a context sharing the buffers)
waits for the chip (a fence) and copies the picture out; the frames' thread only starts it.
Report: `tap.latchMs` / `showMs` / `readMs` / `busySkips`.

## 0.6: watching through the recording stream

0.5.4: the tap's thread was nearly idle (taking a frame in 0.43 ms, starting a read 0.68 ms),
yet 42% of frames never arrived. Android's camera service (Camera3OutputStream) gives a preview
consumer (a texture) a frame spacer and a queue that may drop: a queued frame not yet taken is
replaced by the next, and in slow motion frames come in batches. Only the recording stream
(an encoder) gets a lossless queue, and slow motion allows only preview or encoder outputs.

So the camera's recording output is now a queue of ours (an ImageReader made to look like an
encoder, 12 frames); the chip takes one frame at a time from it (ImageWriter into the texture,
the next once this one is in), shrinks it for the tracker, and draws it whole into the encoder
with the camera's own time (so recording and tap share one clock). The preview is the camera's
own again. If that can't be set up: the preview stream (0.5.4), else decoding (0.4).
Report: `watchingBy`, `tap.queued` / `encodeMs` / `stalls`.

## A session at the club on 0.5.4, and 0.6.1

16 captures, 15 clips saved (M1-M15). The phone at the receiver's end of the table, low,
filming the server head-on: the ball flies toward the camera (45-62 degrees). M1-M5 the phone
being set up; M6-M15 serves. Where phone and PC both read clearly they agree: #7 44.5 / 31.9
backspin (PC 44.4 / 32.0), #12 42.9 side-topspin (42.7), #6 28.5 / 23.5 (28.5 / 23.4, top/back
mirrored: head-on). #9-#11 were never measured: the app kept 3 serves waiting and some readings
took 13-15 s. 0.6.1 keeps up to 12 (and 1.5 GB of frames); a dropped one is logged.

## 0.6.2

The 13-15 s readings (#2, #8, #15, #16) were the captures over 160 MB: those were read into the
engine a frame at a time, again on every pass (their `fetchMs` was 5-7 ms: only the header). Now
every capture up to 600 MB is fetched whole, in chunks of 24 frames. On the PC, those serves
measure in 2-5 s from memory, as the phone read them (M7 44.35 / 31.9, M12 43.0, M6 28.5 / 23.5,
M14 51.0 / 48.8).

## 0.6.3

0.6/0.6.2 froze after Start: frames passed on with an ImageWriter evidently didn't come back
to the camera's queue, and in slow motion a stalled stream stalls the camera (and its preview).
Now the chip reads each queued frame's buffer directly: a few lines of native code
(`cpp/hwtex.c`, built with the NDK into `jniLibs/arm64-v8a/libhwtex.so`) make it an OpenGL
texture without a copy (EGLImage from the HardwareBuffer, which Java can't do). After the
shrinking and the drawing into the encoder (and a fence), the buffer goes back to the camera.
Setting up never blocks the screen for more than 3 s (then the preview stream, then decoding),
and no frames from the queue within 3 s is said on screen. Report: `tap.queued`, `bindFailures`.

## 0.6.4

0.6.3 worked (no freeze, frames from the recording queue, the picture check exact: clocks 0 us
apart), but the camera sent only 756 frames in ~12 s, about 60 a second: it sends a frame only
when a buffer comes back, and each came back only after waiting for the chip, plus 1.5 ms to
make its EGLImage. Now each buffer's EGLImage is made once and kept (the camera cycles 12), and
up to 2 frames stay in flight: a buffer goes back when its fence says the chip is done.
Report: `tap.fenceWaitMs`.

## 0.6.5

0.6.4: ~82 frames a second (685 in ~8 s), though the measured steps add to ~3 ms a frame
(EGLImage 0.25, shrink 0.95 every 2nd, into the encoder 2.1, fence 0.22). The encoder's
surface now stays current (no switching each frame) with swap interval 0, and the report says
where the rest goes: `tap.swapMs` (handing the encoder a frame), `busy` (share of time the
frames' thread works), `framesPerWake` and `maxBurst` (how the camera delivers).

## 0.6.6

0.6.5: the frames' thread was busy 28% of the time and found one frame each time it woke, yet
886 of ~2470 frames arrived: the camera dropped them. The queue was marked "read by the chip"
(GPU_SAMPLED_IMAGE), and the camera service treats any such consumer as a texture: no dequeue
timeout, so a frame not yet taken is replaced by the next. Now the queue asks for an encoder's
usage only (VIDEO_ENCODE), as the real encoder in 0.4 did (which got every frame). If the chip
can't read such a buffer, the app starts again with the mark (`tap.queue` says which).

## 0.6.7

0.6.6: the chip couldn't import buffers marked for an encoder alone, so the app fell back to
"sampled by the chip" (drops: 1225 of ~3620 frames). Now it first tries "drawn into by the chip"
(GPU_COLOR_OUTPUT), which the camera service doesn't treat as a texture (so no drops), then
falls back. Since the recording is drawn from the buffer the chip reads, the picture check
also checks the picture itself (brightness, contrast, rows changing smoothly) and falls back
if it looks wrong. `tap.queue` and `pictureCheck` say what was used and found.

## What 0.6.7 showed, and 0.6.8

Every frame watched: 2584 of 2602 reached the chip (28 missed), 2526 encoded; the queue marked
"drawn into by the chip" stayed lossless and the chip read it right (brightness 108, contrast
59, rows smooth; the recording matched within 0.8). The frames' thread was busy half the time.
62 pictures were skipped with all 4 read-back buffers busy (once 56 frames came at once after a
hiccup): 0.6.8 has 8.

## What 0.6.8 showed (home, side-on, ~7 serves), and 0.6.9

Every frame watched (13072 of 13074). 17 captures: every serve (N1, N4, N7, N10, N12, N15, and
one more unsaved) and 8 times walking to fetch the ball (rejected by the engine, not spoken).
Phone and PC agree where both read clearly: N1 31.35 / 31.40 backspin, N4 45.25 / 45.25
backspin (both "good"). The PC also reads N12 30.85 backspin, N15 46.75 topspin (the report
was cut off before them). N7 and N10 the phone called clear and the PC low (different crops).
#4 took 29 s: its capture was 600.4 MB, just over what the engine takes at once, so it was read
a frame at a time. 0.6.9 caps a capture at 300 MB (the run-up before the flight trimmed to
0.1 s, then the end; `trimmed` in the report).

## 0.7: telling walking from serving (pose)

In the 0.6.8 session 8 of 17 captures were the player walking to fetch the ball. Now MediaPipe's
pose model (`pose_landmarker_lite`, on the phone, no network) looks at the tracker's small
picture about 15 times a second (`PoseWatcher`), and a ball movement isn't a serve when the
player's hips travelled more than one torso length sideways within any second, from half a
second before it to its end. Measured on the clips (`pose/feat.py`, 480x270 at 15 a second):
all 21 serves recorded so far (club M6-M15, home N1/N4/N7/N10/N12/N15, L1, C1, T1, T4, U1,
V1, V2) 0.04-0.68; walking (N2, N3, N5, N6, N8, N9, N11, N13, N14, L2, L5) 1.37-3.32. No one
seen: no say. Also in "Test with a video". Report: `pose` (looked, found, ms, notServes) and a
"skipped" event for each. Any Android phone with slow motion for apps (and arm64) can run it.

## 0.7.1: turn by turn

Answers take a few seconds, longer than between serves, so it wasn't clear which serve an answer
was about. Now: "Ready" (green) -> a serve spotted: "Got it" (amber, "measuring serve #n"), and
serves meanwhile are ignored (`ignoredWhileMeasuring`) -> its answer, or "Couldn't read that
one", then "Ready" again. Never stuck: no answer in 30 s and it's ready again. Walking (pose)
doesn't start a turn.

## Build

`android/build.sh` (Android SDK 35, Gradle 8.9+, JDK 17+; the NDK to rebuild the native part, else the committed .so is used): copies the current engine in and
writes `release/serve-coach-live-<version>.apk`, signed with a debug key (sideloading only).
`gradle :desktop:installDist`, then `desktop/build/install/desktop/bin/desktop clip.mp4 outDir`
(needs ffmpeg) runs the serve tracker on a clip and writes its captures (`.cap`).
mp4box.js (BSD-3-Clause, gpac) is bundled in `assets/`.
