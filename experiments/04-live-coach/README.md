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

## 0.7.3: captures cut on the graphics chip

Writing a capture took 1.4-6.5 s: every decoded frame of the serve copied out by the processor
from the decoder's slow memory. Now (`CaptureGl`) the decoder renders the serve's frames into
graphics buffers, two shaders (`assets/shaders/crop_y.frag`, `crop_c.frag`) cut out the crop as
the capture's planes, and the read-back is the capture, while the next frame decodes. The chip
sees colour converted its own way, so the first capture of a session also decodes one frame the
old way and fits the mapping back to the decoder's bytes (`ColourFit`; which matrix, gain and
offset per plane): captures stay what the engine was checked with. That mattered: the phone's
videos are full range and have always been read as limited; given true full range, a 10-frame
paddle stretch read 32 rev/s instead of 44. On any failure: the old way (twice: off).

Tested without a phone (`gltest/`): the shaders on Mesa against NumPy, the fit, and the engine's
readings on captures remade through the shaders (same within 0.1-0.6 rev/s). Not testable here:
the phone's decoder rendering into the chip's buffers, and the time it takes; "Test with a
video" compares the first 20 frames of each capture both ways on the phone (`chipCheck`).
Report: `captureBy` (how, timings, the fit), `chipCaptureFailures`.

Crops are now a multiple of 8 wide (all serve clips read as before). Found on the way, not yet
fixed: in N12 and N15 the ball merges with the paddle at the hit, the tracker's crop follows
the merged blob and the engine then takes the paddle (the whole clip reads 30.9 and 46.8).

## What 0.7.3 showed ("Test with a video", clip #1 of the 0.6.8 session), and 0.7.4

The capture was cut on the chip: 202 frames decoded and cut in 1.1 s (copying took 2-4 s), and
it read 31.45 rev/s backspin (31.35 before). But the colour fit was off by 7 levels (here 0.46):
a decoder pads a 1080-row frame to 1088 rows, and the shaders were told 1080, so the picture was
stretched by 8 rows over its height. On Mesa: 10.7 that way, 0.26 by the buffer's own size.
0.7.4 uses the buffer's size, and refuses the chip (old way) if a fit is worse than 3 levels;
"Test with a video" logs the 20-frame comparison as an event.

## 0.7.5: walking past the phone isn't a serve

The 0.7.4 session (phone low on the table, next to where the player walks) made 34 captures,
about 30 of them the player's body; pose saw a whole body in only 61% of looks, so its veto
missed most. The tracker now checks a serve's best track before saying "Got it": every real
serve in the labelled clips (30, from T, U, V, C, L, M, N, P) flies 5.1+ px a tracker frame at
its fastest (480-wide picture, 120 a second), 2.0+ at its median, with a ball of 6.1 px or less
and under a tenth of the picture moving. Bodies, arms and a handled camera are slower, bigger,
or much of the picture moves. Rejected: fastest under 4.5 or median under 1.5 ("slow (a body?)"),
ball over 7 ("too big (a body?)"), over a fifth of the picture moving ("picture moving"), all
scaled to the picture's width and rate.

On the clips: all 30 serves kept, with the same frames and crops as without the check (so the
same readings); of the walking and handling clips (L2-L7, M1-M5, N2-N14 without serves, P30,
P31, P1, P3) none gives a serve now, except two real ball flights at the start of N2 and N13.
The report's `rejected` counts the new reasons.

## 0.7.6: the toss isn't a serve

The 0.7.5 test (phone low on the table by the net post, the server at the far end): 2 serves
gave 3 captures, none read. One was the player standing still, touching his face (it doesn't
come back from its saved clip); the other two were the toss. The real flights went mostly
toward the phone, small and slow in the picture over the blue table, and were never tracked.
The toss, coming down (Q2) or going up and away (Q3), was the only good track. Now a group of
only falling tracks isn't a serve ("toss only": a real serve has a flight across the picture
too, though its falling toss may be its best track, as in N4 and N10), and "upward (toss)"
takes anything climbing steeper than about 27 degrees (it was 45; no serve flight in the
labelled clips climbs over 15). All labelled clips: the same 31 serves with the same frames;
Q1-Q3 give none. The phone needs the ball to cross the picture: side-on, level with the table.
Also: a reading with no error logged "#2: null"; it now gives its first issue.

## 0.7.7: the picture before Start

While nothing runs, the screen shows what the recording will see: the same slow-motion camera
mode (same size, same field of view), with only the screen fed (30 frames a second). Start
closes it and opens the full session; Stop, a finished replay and coming back to the app open
it again. A camera that finishes opening after it was stopped is closed at once.

## 0.7.9: Start froze with the setup preview (0.7.7)

"It kept saying ANR after starting." 0.7.7's preview was the slow-motion session with only the
screen fed (240 frames a second to it), closed on the main thread when Start was pressed. Now
the preview is an ordinary 30-a-second session at the same size, it is closed on the camera's
thread, and the slow-motion session opens only after that close is done (Start shows
"Starting…" meanwhile). 0.7.8 logged "preview running"; still there.

## 0.8.0: one spin number, and clips of what was skipped

0.7.9 at the club worked (the preview, then 16.8 rev/s side-backspin + gyro on a serve filmed
along the table, the same 16.8 on the desktop from the same clip), but "some serves were
skipped", and "two spin numbers" (rev/s and RPM) were confusing. Now the screen, the history and
the voice give RPM only, to the nearest 10. A skipped serve left nothing to look at; now each
movement the last checks turn down ("slow (a body?)", "too big (a body?)", "picture moving",
"toss only") and each track taken for a toss going up is saved as a clip,
`ServeCoach_<time>_skipped<n>_<reason>.mp4`, up to 30 a session (not while a serve is being
measured). Every serve's toss can show up among them; the point is to see which check takes
real serves. Labelled clips: the same serves as before.

## 0.8.1: a ball rolling back took the serve with it

0.8.0 at the club: 7 captures, 6 read (31-47 rev/s), and its skipped clips showed what went
missing. Of 8 groups turned down, 5 were bodies (walking past the phone, K1, K3, K4, K5, K8) and
3 were serves (K2, K6, K7, one into the net). The group was judged by its best track by score
(frames x ball size), and in those it was the ball rolling back along the table: 140 frames,
slow, so the whole group went as "slow (a body?)". Now, when the best track doesn't fly like a
ball, the best of the tracks that do (and cross the picture rather than drop: P31, a short fall
beside a walking body) is taken instead; if there is none the group is turned down as before.
`desktop selftest` builds that case from made-up tracks (the old rule skips it, the new keeps it).
All clips: the same serves as before; K2, K6, K7 read 50.1, 40.2, 43.0 rev/s; the bodies and
the 0.7.4 walking clips still give none.

## 0.8.2: the engine reads a capture while it is cut

Each serve had to wait for: cutting its frames on the chip (1.3-2.3 s, the saved clip written
first), then the engine fetching them all (0.5-1.9 s), then the engine (1.5-3.9 s), one after the
other. Now the capture is announced as it starts (a first header, its frame count estimated
from the encoded frames), the engine's reads of 24-frame chunks wait for those frames to be
written (each frame flushed as it is done), then it takes the last header (count, colour range);
the saved clip is written after the capture. The same bytes reach the engine, so the readings
can't change: a stand-in for the app's reads (frames arriving while read, the estimate right,
low and high) gives the engine exactly the frames of the finished file. The report's results
gain `answerMs`, from "Got it" to the answer.

Profile of the engine (desktop, one thread): the search for the ball is about 80% (scanFrame
28%, the background's medians 12%, deflicker 10%, morphology 9%, halving 11%); the spin itself
about 2%. On the phone the per-frame scan already runs on up to 7 worker threads.

## 0.8.3: each session's data in one folder, sent in one share

For the spin model (research/spin_ai.md: spin from the flight and bounce, trained on the marked
balls' readings), every serve is kept as training data without anything to do by hand. Each
session (Start to Stop) gets `Download/ServeCoach/Session_<time>/`, with `serveNN.mp4` (the clip),
`serveNN.json` (the reading, each measured stretch with the ball's position and size in every
frame, the flight's launch speed and direction, the tracker's header and tracks: about 2 KB),
`skippedNN_<reason>.mp4`, and `report.json` at Stop (the report, this session's results only;
the per-stretch data stays in the serve files). At Stop, once the last answer is in, the app
offers to send the folder: one share (Drive, any folder) instead of picking files one by one.

## 0.8.4: the measuring step timed

After the physics test (research/physics: from one phone beside the table, the flight gives the
backspin/topspin part to about 10 rev/s and not the sidespin), the work stays with marked balls:
speed and reliability. The engine's report now has `time.measure` (seconds measuring the spin,
after the search) next to `background` and `search`, so a live report shows where each serve's
wait goes: `writeMs` (cutting), `fetchMs` (now overlapping the cut), `time`, `answerMs` (from
"Got it" to the answer).

## 0.9.0: the cockpit HUD, and how strong a spin is

The look chosen from six mocked directions: the camera view full height with cyan line art over
it (corner brackets, the state as a status line, amber while measuring, red on a problem), the
reading on the right in light wide numerals (Oxanium), labels and lists in IBM Plex Mono (both
OFL, in `assets/fonts`), Start/Stop as a cut-corner button. `HudScreen.kt` builds it; `Hud.kt`
draws the frame, the scale and the button.

Under each reading, a reference: four bands, no spin (under 600 RPM), weak (600-1,500), medium
(1,500-2,400), strong (2,400 and up), with marks at the world-class average and fastest serve
(2009 World Championships quarterfinals, Tamaki et al.: men 46 rev/s on average, about 2,760 RPM;
fastest 62.5 rev/s, 3,750 RPM), and this serve's marker. The voice says the band too ("2450 RPM,
strong spin, backspin").

Without a phone: `gradle :app:recordPaparazziDebug` draws the screen on the desktop (Paparazzi,
a Pixel 8 in landscape) in its states; `app/src/test/snapshots/images/` holds them.

## 0.9.1: setup guidance, a video test that answers, no camera corners

- The corner brackets are gone ("the phone is already a camera"); the HUD keeps its shading.
- "Test with a video" shows its own states: reading the video (with progress), measuring N
  serves, then "N of M read"; with no serve, a big NONE and what it saw instead (the tracker's
  turned-down movements in words). Before, only the status line changed, and a timer kept
  writing "Replaying…" over it.
- Setting up, instead of a fixed paragraph: `SetupCheck` (core) on a small copy of the preview
  (an extra 640x360 camera output, read a few times a second) with the phone's tilt and movement
  (accelerometer, gyroscope): one piece of advice at a time, most important first (hold still,
  too dark, level the phone, tilt down/up, move closer/back, face the side), shown and spoken
  once it holds for 0.9 s, then "Looks good, press Start". The table is the largest blue patch;
  the rules come from the clips that read well (club, angled: the table 11-28% of the picture,
  its top at 38-54% of the height, 2.5-3.7 times as wide as high) and the net-post view that
  didn't. `desktop setup frame.yuv 320 180 [tilt]` runs it on a picture: the club setups say
  "looks good", Q2 "tilt down", reframed copies of them "move back", "move closer", "tilt up".
  At home, with a plain wall and no table (which read well), it says so, without blocking.
- Optional AI setup coach (`GeminiCoach`, the AI COACH button, the player's own Gemini API key,
  kept on the phone): a Gemini Live session over its WebSocket while setting up only, a JPEG about
  once a second and, when the on-device advice changes, a question with it; it answers by voice
  (24 kHz PCM, played) with a transcript on screen. Closed at Start. Tested against a stand-in
  server (`GeminiCoachTest`: setup message, frames, question, audio, transcript); not yet against
  Google's (the model is `gemini-3.8-live`, then `gemini-3.1-flash-live-preview` if refused).

## 0.9.2: NEXT SERVE, and only the latest serve spoken

With more than one ball and a helper at the phone: NEXT SERVE (beside STOP while a session runs)
ends the turn now, ready for the next serve without waiting for this one's answer. A serve still
being measured gets its answer on screen and in the list; it is spoken only if no serve came
after it (only the latest serve is spoken), and it no longer sets "Ready" for a turn already
ended. The tools are shorter (TEST VIDEO) to make room.

## 0.9.3: the spin is measured along the tracker's own track

The engine no longer searches the crop for a ball the live tracker has already found: it
measures along the track it is handed. About **3x faster** over the 21 recorded captures
(97.7 s to 33.0 s). 0.4 did this and 0.5 took it out again, because on clip L1 it measured a
hand moving along the bottom of the picture. What was missing was a way to give up, and there
are now three, each one added because a real capture needed it:

- a track that doesn't fly like a ball is refused (`ballisticRule`: its size steady, its path a
  parabola between bounces, its fall downward). It is scale-free, since a ball is 40 mm across
  and gravity is then about 0.0085 of its radius per frame squared at 240 a second;
- a tracked measurement that fails or reads unclear is abandoned (clip L1, which barely moves
  and so passes every rule, then measures to nothing);
- a tracked run that only ever sees the ball after the bounce is abandoned, because the serve's
  own spin is the stretch off the paddle (capture M6, which read 23.3 rev/s against 28.5 and
  called it clear). `serveBuilder` also looks backwards now, and marks such a stretch "bounce"
  rather than "flight".

Each one falls back to the engine's own search, so the worst case per serve is what the app did
before. Five of the 21 captures take that path (L1, M6, N12, N15, T4_2) and all five then read
what the search alone reads; the other 16 are unchanged. `report` carries `debug.trackRefused`
for the serves that fell back.

Measuring is faster too: it took four per-pixel medians across every frame of the flight and
corrected flicker on every row, which a profile showed as a third of its time in quickselect.
A spread of 15 frames gives the same background, and the row ratio is smoothed over 9 rows.

Still to watch: M7, M12 and M14 differ from the search by -3% to +2%, all of them paddle
stretches that start where the track starts, earlier than the search starts them, where the
paddle is still beside the ball. No ground truth either way, and inside the meter's usual
spread. And this is one phone's footage.

New: `../02-spin-meter/web/test/` scores the engine against rendered serves with a known spin
(`bench.mjs`, `capbench.mjs`, `guard.mjs`, `make_clips.sh`), and
`research/speed-and-accuracy.md` records the review this came out of.

## 0.9.4: the report says whether the track was used

0.9.3's report still said `usedTrack: false` for every serve, a leftover from 0.4, so there was
no way to tell which serves measured along the tracker's track and which fell back to the
engine's search. Now `usedTrack` says what happened and `trackRefused` says the track was
handed in and turned down. That is the number to watch at the table: it was 5 of 21 on the
recorded captures, and how often it happens live decides how much of the speed is real.

## 0.9.5: the track is kept when the engine's own search has nothing better

The first session with 0.9.4 at the table used the tracker's track on **1 serve in 10** and
searched the crop on the other nine, so almost none of the speed arrived. Re-measuring those ten
captures on a desktop (rebuilt from each `serveNN.json` against its clip) gave the reasons: six
"only after the bounce", two "read unclear", one "no track flies like a ball".

Six of the nine were a structure the M6 rule was never meant to catch. The bounce is *inside*
the track, but the stretch between the hit and the bounce is under 8 frames, so only the
after-bounce part is built. That is not M6, where the bounce came before the track began and
only the other tracks showed it — there the engine's search can reach frames the track cannot.
Here it cannot: on five of the six its own answer is also an after-bounce reading, and on one it
finds nothing at all while the track reads 35.5 rev/s clear. So a stretch now carries *why* it
was called after-bounce, and the reading is only given up when the bounce came first.

And the hit itself was splitting the flight. The tracker's crop follows the ball, which merges
with the paddle at the hit: that point is far too big and sits low, which reads as a lowest
point followed by a rebound. Serve 3 measured the stretch off the paddle correctly (52.5 rev/s
against the search's 53.4) and called it "bounce". Such points are dropped — before the frames
between the tracker's points are filled in, since the fill drags their neighbours toward them
and those come out ball-sized, where nothing later can see the problem.

On that session's ten captures the track now carries seven. Engine time over them, one desktop
thread: 14.9 s against 26.7 s searching every one; on the phone expect a mean near 4.5 s against
the 6.4 s it did. The 21 club captures are unchanged, and M6 still refuses and still reads 28.5.

**Known, and not from this change:** a ball that comes back off the net is read and spoken as
the serve. One serve of the ten went into the net and was given as 40.9 rev/s, clear — that is
the ball dropping back off the net toward the camera, and nothing in the reading says so.

## Build

`android/build.sh` (Android SDK 35, Gradle 8.9+, JDK 17+; the NDK to rebuild the native part, else the committed .so is used): copies the current engine in and
writes `release/serve-coach-live-<version>.apk`, signed with a debug key (sideloading only).
`gradle :desktop:installDist`, then `desktop/build/install/desktop/bin/desktop clip.mp4 outDir`
(needs ffmpeg) runs the serve tracker on a clip and writes its captures (`.cap`).
mp4box.js (BSD-3-Clause, gpac) is bundled in `assets/`.
