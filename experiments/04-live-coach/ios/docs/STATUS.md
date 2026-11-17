# iOS port: status

Goal: Serve Coach Live on iPhone, the same as the Android app (0.13.24).

## Done and how it was checked
- **Shared core** (`shared/`): the Android `core` module compiled for iOS from the same sources. 94 of 94 Android
  core tests pass on the iPhone simulator. The Java format stand-in matches Java on 180,057 cases.
- **Video test pipeline** (`Pipeline.swift`, `VideoDecoder.swift`, `FrameStore.swift`, `Engine.swift`, `VideoTest.swift`):
  `15 Nov 23:04 serve 5` (`live-clips/Session_20261115_230448/serve05.mp4`) on the iPhone 16 Pro simulator:
  - Unprimed, the tracker's per-frame picture sums and candidates match the desktop reference
    (`android/desktop`, ffmpeg decode) on all 149 tracker frames. Result: 147-frame capture, flight phase, 45.1 rev/s
    (2710 RPM), lift 42.0, curve 9.4, gyro 13.5.
  - Primed (as the app's video test does): the 5 priming pictures match the reference decode exactly. Result:
    145-frame capture, read after the bounce, 45.2 rev/s (2710 RPM), lift 41.7, curve 10.9, gyro 13.6.
    Runs are repeatable (3 of 3 identical).
  - The Android screenshot of this clip (42 / 9 / 13, off the bat) came from the **GPU ("chip") replay path**, which runs
    only with the CHIP WINDOWS developer option on (101-frame capture, other rejections). It is not a reference for the
    default decoder path.

## Not yet verified
- Anything needing a camera: the live 240 fps path, the encoder, tilt, audio. The simulator has no camera, so these need
  a physical iPhone.

## Next
1. Pure logic with tests: outcome words, qualifier, tips, NoRead text, SpinBall maths.
2. HUD screen driven by the video test; compare with `app-screenshots/result-backspin-full.png`.
3. Turn and mode state machine.
4. Live camera, encoder, setup check, pose, Gemini assistant and chime.
