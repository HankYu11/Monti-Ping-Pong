# iOS port: status

Goal: Serve Coach Live on iPhone, the same as the Android app (0.13.24).

## Done, and how it was checked
- **Shared core** (`shared/`): the Android `core` module compiled for iOS from the same sources. 94 of 94 Android
  core tests pass on the iPhone simulator.
- **Video test pipeline**: `15 Nov 23:04 serve 5` (`live-clips/Session_20261115_230448/serve05.mp4`), iPhone 16 Pro simulator:
  - Unprimed, the tracker's per-frame picture sums and candidates match the desktop reference (ffmpeg decode) on all
    149 tracker frames.
  - Primed, as the app does: the 5 priming pictures match the reference decode exactly. Result: 2712 → **2710 RPM, strong
    backspin, after the bounce**, 42 / 11 / 14 rev/s. Repeatable.
  - The Android screenshot of this clip (42 / 9 / 13, off the bat) came from the **GPU ("chip") replay path**, which
    runs only with the CHIP WINDOWS developer option on. It is not a reference for the default path.
  - **Open:** the desktop tool primed the same way (`PRIME=0,72,144,216,291`) gives different candidates from frame 0
    than primed iOS, although unprimed they match. Cause not found. Android's default path, primed, on this clip has
    never been run, so "same answer as Android" is not measured for the primed case. Settle it on the Pixel with
    CHIP WINDOWS off.
- **Screen** (`Hud.swift`, `HudScreen.swift`, `SpinBall.swift`): layout, fonts, colours, ball and bars as `HudScreen.kt`.
  Compare `docs/shots/ios-video-test-serve05.png` with `app-screenshots/result-backspin-full.png`.
- **Self-tests** (`-selftest`): the Android `SpinBallTest` ported, plus outcome words, qualifier, tips and the
  no-serve text. 31 of 31 pass.
- **Controller** (`Coach.swift`): modes, turns, speech and chime, the result rules of `MainActivity.show`, the engine
  queue, error lines, report copy, video test from the developer row.
- **Live session** (`Camera.swift`, `LiveCoach.swift`), compiled for device but **not yet run on an iPhone**:
  1080p240 back camera, H.264 encoder (keyframe every 0.1 s, 5 s store), frames numbered by time, captures cut from
  the store, `serveNN.mp4` / `serveNN.json` / `report.json` in Documents/ServeCoach/Session_…, skipped-movement
  clips, 30-day cleanup, share prompt, setup check with tilt and motion, Gemini setup assistant (Keychain key).
- **Walking veto** (`PoseWatcher.swift`): Apple Vision body pose instead of MediaPipe, on the same small picture and
  with the same rule. The simulator can't run Vision's body-pose model. The same request on the Mac finds the
  player's hips and shoulders in this clip's converted picture.

## Differences from Android, by design
- No GPU tap. The first "Ready" waits for the tracker's background instead of the GPU picture check.
- CHIP WINDOWS is kept as a stored switch but has no GPU path to turn on.
- report.json is pretty-printed with 2-space indent (Android: 1 space).
- Not ported: the load guard (thinning when the phone falls behind) and its "too slow" note.

## Guesses to check by ear or on a device
- Speech rate 0.5 (Android uses its default rate).
- The chime: 400 + 1200 Hz, 100 ms on, 100 off, cut at 220 ms (from the spec, unverified against Android).
- The walking veto has never produced a result in the app (the simulator can't run the model).

## Not yet verified (needs a physical iPhone)
- Whether the encoder keeps up with 1080p at 240 fps in real time, and the "behind" / frames-missed numbers.
- Camera lock, preview orientation, tilt sign, speech, chime and assistant audio.
- A full live session: spotting, captures cut from the live store, clips saved.
