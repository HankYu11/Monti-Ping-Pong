# Serve Coach Live: pipeline porting spec (camera → tracker → capture → engine)

Source: `experiments/04-live-coach/android`. Paths are abbreviated:

| Short | Path |
|---|---|
| `LP` | `app/src/main/java/com/monti/servecoach/LivePipeline.kt` |
| `GT` | `app/src/main/java/com/monti/servecoach/GlTap.kt` |
| `CG` | `app/src/main/java/com/monti/servecoach/CaptureGl.kt` |
| `HSC` | `app/src/main/java/com/monti/servecoach/HighSpeedCamera.kt` |
| `CR` | `app/src/main/java/com/monti/servecoach/ClipRecorder.kt` |
| `FD` | `app/src/main/java/com/monti/servecoach/FrameDecoder.kt` |
| `AF` | `app/src/main/java/com/monti/servecoach/Frames.kt` (app, not core) |
| `MA` | `app/src/main/java/com/monti/servecoach/MainActivity.kt` |
| `EH` | `app/src/main/assets/engine.html` |
| `core/X` | `core/src/main/kotlin/com/monti/servecoach/core/X.kt` (shared with iOS through KMP, unchanged) |

Every section tags what it describes:

- **REQUIRED**: behaviour or output the engine or tracker depends on. iOS must reproduce it.
- **ANDROID WORKAROUND**: there only because of Android or Camera2 limits (HardwareBuffer, EGLImage, the preview/recording streams, the clock mismatch). Reproduce the *output*, not the mechanism.
- **EXPERIMENT (off by default)**: the "window ring" (`windowRing`). It is off unless the user taps the version number five times (`MA:513-515`, pref `windowRing`, default `false`). Every capture measured so far was made with it off. **Port the default path first.**

Notes in the *Port note* voice are suggestions for iOS. Nobody has run them on a device, so treat them as unverified.

---

## 0. The default path in one picture

```
LIVE (Android default)
 camera 240 fps ──► GlTap (GPU, "gl-tap" thread)
   │  every frame: onFrame(tsNs, frameNo) ──► LP.offerTapTime      (frame-time table)
   │  every frame: whole frame drawn into the H.264 encoder ──► ClipRecorder (5 s of encoded frames)
   │  every 2nd frame: 480x270 RGBA small picture ──► "gl-read" thread ──► LP.offerTapPicture
   ▼
 LP.offerTapPicture ──► finder pool (3 threads): Detector.find/findFromAlpha ──► jobs queue
 "serve-tracker" thread: ServeTracker.pushFound(index, candidates) ──► Serve?
   ▼ onServe
 "capture-N" worker: decode the span again from ClipRecorder ─► crop each frame (GPU CaptureGl or CPU Capture.crop)
   ─► Windows.Builder (windowed capture) ─► cap_N.bin + cap_N.json
   ▼
 WebView engine.html analyseCapture('https://appassets.androidplatform.net/capture/N', N)
   fetch /head, /head/final, /all (or /frames/k/24, /k) ─► SpinMeter.measureCapture ─► Android.onResult
```

```
TEST VIDEO (Android default, windowRing off) -- build this first on iOS
 FileFrames(uri): demux every sample (pts, key, bytes spilled to a cache file)
 LP.prime(frames) : 5 keyframes decoded alone ─► ServeTracker.prime
 LP.offer(frame) for every frame (blocking queue, 64)
 "decoder" thread: FrameDecoder ─► every 2nd picture: Downsample.rows ─► finder: Downsample.quarter + Detector.find
 "serve-tracker": pushFound … onServe ─► capture worker (same as live) ─► engine (same as live)
 LP.finish(): END ─► 120 empty tracker frames ─► join
```

There is **no ring of raw full frames**. The only frame memory is the **encoded** store (`ClipRecorder`, the last 5 s), which has a keyframe every 0.1 s. A capture is made by **decoding the span again** from the keyframe before it (`AF:69-76`, `LP:1444`, `LP:1526-1543`). The only raw pixels kept are the experimental window ring's small windows (§2.6).

---

## 1. Camera configuration (live)

### 1.1 Mode choice: `HSC:89-116` (REQUIRED in intent)

- Back camera only, and only one with `CONSTRAINED_HIGH_SPEED_VIDEO`.
- It considers only fixed fps ranges (`lower == upper`), with an upper of at least 120 (`HSC:100`).
- Score: `+1000` if fps ≥ 240, `+100` if height ≥ 1080, else `+50` if ≥ 720, `+1` if H.264 rather than HEVC (`HSC:103-104`). The encoder must support that size at that rate (`HSC:76-83`).
- In practice this means **1920x1080 @ 240 fps, H.264**, and the rest of the app assumes it. The small picture is then 480x270, and the mask and row shaders hard-code 120 columns (`rows.frag`: `for (i < 120)`, i.e. 480/4).

*Port note:* choose an `AVCaptureDevice.Format` on the back wide camera with `videoSupportedFrameRateRanges` max ≥ 240 at 1920x1080. Prefer 420 biplanar full-range (`420f`). Set `activeVideoMinFrameDuration = activeVideoMaxFrameDuration = 1/240`. If the phone has no 1080p240, fall back to 720p240, then to 120. The core's `ServeTracker(w, h, fps/2)` follows whatever size you pass.

### 1.2 Request settings: `HSC:134-185`

- `TEMPLATE_RECORD`, `CONTROL_AE_TARGET_FPS_RANGE = [fps,fps]`, `CONTROL_MODE_AUTO` (`HSC:135-138`).
- **After 1500 ms** the camera is held still (`HSC:175-185`). The first choice is AE lock + AWB lock + `AF_MODE_OFF`. If the device refuses that, it tries AE+AWB lock with focus left to the camera, and if that fails too, nothing is locked. The result is kept in `lockNote`. **Why (REQUIRED):** the motion mask compares against a median background. An exposure, white-balance or focus change moves the whole picture, so every pixel reads as moving (`HSC:160-171`). The row-brightness ratio cancels exposure changes. Nothing cancels a white-balance change or focus breathing.
- No stabilisation key is set. A constrained high-speed session has none.
  - *Port note:* set `connection.preferredVideoStabilizationMode = .off` on every output. Stabilisation warps or crops the frame and breaks the background.
  - *Port note:* after 1.5 s, lock exposure (`.locked`), white balance (`.locked`) and focus (`.locked`).
- Android sends a "burst" request list. `allToPreview` (`HSC:146-157`) is an ANDROID WORKAROUND that sends every frame to the second stream. It only matters for the preview-stream fallback.

### 1.3 Streams (ANDROID WORKAROUND, `MA:389-426`)

- First choice: camera outputs `[screen preview surface, GlTap.surface]` (`MA:410`). `GlTap.surface` is an `ImageReader` (PRIVATE, 12 buffers, usage `VIDEO_ENCODE | GPU_COLOR_OUTPUT`, `GT:376-380`) that pretends to be the encoder. GlTap reads each buffer on the GPU (EGLImage via `hwtex.c`), draws it whole into the real encoder's input surface with the camera's timestamp (`GT:511-527`), and releases the buffer. The screen preview is the camera's own, at 30 fps.
- Fallback 1: preview stream into a `SurfaceTexture` (`GT:359-365`, `MA:393-396`). This loses frames.
- Fallback 2: no GPU. The encoder gets the camera directly, and the pipeline decodes its output (`MA:399`, `rec.onFrame = { p.offer(it) }`). This is the same path TEST VIDEO uses.
- *Port note:* none of this is needed on iOS. Use one `AVCaptureVideoDataOutput` (every frame, `alwaysDiscardsLateVideoFrames = false`) feeding both (a) the analysis and (b) a `VTCompressionSession` / `AVAssetWriter`-style encoder into the in-memory store. `AVCaptureVideoPreviewLayer` gives the preview.

### 1.4 Encoder and frame store: `CR` (REQUIRED in behaviour)

- Codec: H.264 (else HEVC) at the camera size, `COLOR_FormatSurface`.
- Bitrate `min(W*H*fps/14, 120 Mbit/s)` (`CR:37`). At 1080p240 that is ≈ 35.5 Mbit/s, about 0.07 bit/px, and keeps the ball's marking.
- `KEY_FRAME_RATE`, `OPERATING_RATE` and `CAPTURE_RATE` are all set to fps. **`I_FRAME_INTERVAL = 0.1 s`** (`CR:41`), so every 24th frame is a keyframe, which lets a cut start close to where it should. Priority 0 (real time).
- Each output sample becomes an `EncodedFrame(ptsUs, key, bytes)` (`AF:6-9`). Codec-config buffers are skipped. `outputFormat` (it carries csd-0/1) comes from `onOutputFormatChanged` (`CR:69`).
- **Store:** an `ArrayDeque` that keeps only `keepSeconds = 5.0` (`CR:14,56-57`). A frame is evicted once it is more than 5 s older than the newest. At 35 Mbit/s that is ≈ 22 MB.
- `between(fromUs, toUs)` (`CR:94`, `AF:69-76`): runs in **decoding order**, from the last keyframe at or before `fromUs` (else the first keyframe) up to, but not including, the first keyframe whose pts is after `toUs`. It returns a copy of the list.
- pts are the encoder's presentation times in µs. On Android they come from the camera timestamp passed through `eglPresentationTimeANDROID` (`GT:523`).
- *Port note:* `VTCompressionSession` with `kVTCompressionPropertyKey_RealTime`, `MaxKeyFrameIntervalDuration = 0.1`, `AllowFrameReordering = false` (this keeps decode order = display order and simplifies `between`), and `AverageBitRate ≈ W*H*fps/14`. Keep `CMSampleBuffer`s or (pts, key, Data) in a 5 s deque. Whether a given iPhone's encoder sustains 1080p240 in real time has to be measured.

### 1.5 Field of view and exposure → capture header (REQUIRED fields)

- `HSC:240-246`: each capture result stores the latest `SENSOR_EXPOSURE_TIME` (ns), `SCALER_CROP_REGION` and `LENS_FOCAL_LENGTH`.
- `HSC:255-271` `optics(cfg)` computes `hfov = Optics.horizontalFov(physW_mm, pixelArrayW, crop.w, crop.h, streamW, streamH, focal_mm)` and returns `Optics.headerFields(hfov, exposureNs, detailJson)` (`core/Optics:44-51`).
  - The returned string is appended to the header JSON: `,"hfov":66.12,"hfovFrom":"camera","exposureUs":4166,"optics":{…}`.
  - Without an hfov the string is `,"hfovFrom":"assumed"` and the engine keeps 68° (`Optics.FALLBACK_HFOV`).
  - `horizontalFov` returns null outside 30–120°.
- Wiring: `p.optics = { camera.optics(c) }` (`MA:362`). It is evaluated each time a header is written (`LP:1471`, `LP:1555`). On TEST VIDEO it stays `""`, so the engine falls back to 68°.
- The engine uses `hfov`, `hfovFrom` and `exposureUs` (`EH:112`) for speed and smear.
- *Port note:* `hfov = device.activeFormat.videoFieldOfView` (horizontal degrees for the format), and `exposureUs = device.exposureDuration` in µs, read at header time. Build `detail` from whatever you used. Call `Optics.headerFields(hfov, exposureNs, detail)` from the shared core so the string format matches.

### 1.6 Preview before Start (UI only)

`HSC:203-232`: a regular session at 30 fps, same size, used to set the phone up. It also feeds SetupGuide's small copy. It is closed before the 240 fps session opens (`MA:350-356`).

---

## 2. Per-frame paths

### 2.1 Frame numbering (REQUIRED invariant)

- **Whole frame number** `n` (240/s) and **tracker index** `index = n / step`, with `step = 2` (`LP:73`). The tracker runs at `captureFps/step` = 120 Hz (60 Hz for a 120 fps video), and `ServeTracker(w, h, captureFps / step)`.
- **`ptsRing`** (`LP:249`): a `LongArray(8192)` of each whole frame's pts (µs, *recording* clock), indexed `n % 8192`. That is ≈ 34 s at 240 fps. A serve's frame span is converted to `[fromUs, toUs]` through it (`LP:1139-1140`).
- **Decoder path:** `n` counts decoded pictures in arrival order (`LP:307-309`).
- **Tap path:** `n = round((tsNs - t0Ns) * fps / 1e9)` (`GT:477`). Frames that never arrived leave gaps, and their ptsRing slots are filled with extrapolated times (`LP:395`). A frame with `n <= lastNo` is a repeat and is dropped (`GT:479`).
- *Port note:* on iOS, derive `n` from the `CMSampleBuffer` pts exactly like `GT:477`. Use the same pts for the encoder, so the store and the tracker share one clock and `clockOffsetUs = 0`.

### 2.2 Decoder path: TEST VIDEO, and live without GPU (REQUIRED, the iOS-first path)

Thread "decoder" runs `LP:257-283`:

1. `queue.poll(20 ms)`. Queue capacity is **64 when `blocking` (replay)** and **720 live** (`LP:69`). Live `offer` drops when the queue is full and counts `dropped` (`LP:243-246`). Replay `put` blocks.
2. A `FrameDecoder(store.outputFormat)` is created lazily (`LP:268-271`). After any drop it waits for the next keyframe (`LP:272,277`).
3. `feed(fr, 5 ms)` is retried while draining (`LP:274`). Then `drain(0)`, which calls `onPicture(pts, yuv)` per picture (`LP:300`).

`FrameDecoder` (`FD`):
- A MediaCodec configured `YUV420Flexible` that hands out `Yuv(w, h, Plane y, Plane u, Plane v, fullRange)`.
- The planes wrap the codec's buffers: `Plane(buf, 0, rowStride, pixelStride)` (`FD:78-84`). They are valid only inside the callback.
- `fullRange` comes from the output format's `COLOR_RANGE` (`FD:59-61`). Phones record video range, so it is usually `false`.

`onPicture` (`LP:306-337`), on the decoder thread:
1. Record `ptsRing[n] = pts`, `decoded = n+1`, `lastPts`. Return unless `n % 2 == 0`.
2. Load guard (§3.3). Live only: if `jobs` is full, skip the frame (`skipped++`). Replay never skips.
3. `rows = Downsample.rows(yuv)`: copies just the rows the quarter picture needs, because the decoder wants its buffer back.
4. Create `ServeTracker(rows.w, rows.h, captureFps/2)` lazily. `rows.w = W/4`, `rows.h = H/4`.
5. If `tr.needsFrame(index)` (every 24th tracker index, i.e. 0.2 s at 120 Hz): `q0 = Downsample.quarter(rows)` and `tr.sample(q0, index)` (background sample). This runs *on the producer thread*.
6. `bg = tr.background()`. If it is null (fewer than 3 samples), the job is submitted with `found = null`.
7. Otherwise submit to the finder pool (3 threads, `LP:76`):
   - `q = q0 ?: Downsample.quarter(rows)`
   - every 16th index: `pose?.offer(q, n)` (optional pose model; can be skipped on iOS)
   - `Detector(w,h)` is thread-local, one per finder thread
   - `c = d.find(q, bg)`, which returns `Found` (a list of `[x, y, r]` in quarter-pixels, plus `.moving` = the share of pixels moving)
8. `jobs.put(Job(index, future))`. `jobs` capacity is 16 in replay and 24 live (`LP:89`).

### 2.3 GPU tap path: live Android (ANDROID WORKAROUND in mechanism, REQUIRED in output)

Thread "gl-tap" runs `GT:473-506`, `handleFrame(ts)`, for **every** camera frame:

- `onFrame(tsNs, n)` → `LP.offerTapTime` (`LP:391-400`). This fills ptsRing using `pts = tsNs/1000 - clockOffsetUs`.
- If there is a window plan: `cutWindow` (§2.6, experiment).
- If `n % 2 == 0`: `tap(ts, n)` draws the **small picture** (`GT:566-607`):
  - **480x270 RGBA8**, `qw = W/4`, `qh = H/4`. Each output pixel is the mean of a 4x4 block (4 bilinear reads at ±1 texel, `GT:989-1003`).
  - R = Y = dot(rgb, BT.709 weights). G = U + 128 with U = (B−Y)/1.8556. B = V + 128 with V = (R−Y)/1.5748. Everything is full-range and scaled ×255.
  - **A** = the mask byte when the mask passes ran (255 = moved, 0 = still), else 255.
  - Row 0 is the buffer's first row, with no transform.
  - Read back asynchronously (8 PBOs, fence) on the "gl-read" thread (`GT:698-720`). It is delivered about two frames late as `onPicture(ts, n, rgba, maskGen)`.
- Encode the frame (`GT:511-527`).

`LP.offerTapPicture(tsNs, n, rgba, w=480, h=270, maskGen)` (`LP:407-488`), on the gl-read thread:
1. Until `calibrated` (§2.4), keep the last 48 taps for the flip check and return.
2. Run the load guard. If `jobs` is full, `skipped++` and return. Otherwise `jobs.offer` (non-blocking).
3. Create the tracker lazily as `ServeTracker(480, 270, fps/2)`.
4. If `tr.needsFrame(index)`: `Downsample.fromRgba(rgba, w, h, flip)` then `tr.sample`.
5. `offerBackground` sends the tracker's background to the GPU for the mask (§2.5).
6. Finder, by trust state:
   - `maskTrust == 1`: `d.findFromAlpha(rgba, flip)`
   - `maskTrust == 0` and checkable: `d.findChecked(...)` compares the CPU and GPU masks
   - otherwise: `d.find(Downsample.fromRgba(rgba, w, h, flip, scratch), bg)`

**What iOS must deliver to the tracker:**
- A `Quarter` of `W/4 x H/4` floats per tracker frame: `y` in 0..255, `u` and `v` centred on 0, in the orientation the recording has.
- Either build it with `Downsample.quarter(Yuv)` from the camera's YUV (decoder-path equivalent; handles video range with `lumaVideo`), or produce the RGBA layout above and call `Downsample.fromRgba`.

*Port note:* the simplest approach is the decoder-path recipe applied to the live `CVPixelBuffer`:
- wrap the planes as `Yuv(W, H, Plane(yBytes,0,rowStride,1), Plane(cbcrBytes,0,rowStride,2), Plane(cbcrBytes,1,rowStride,2), fullRange)`
- call `Downsample.rows`, then `quarter` on a finder thread

The iOS `java.nio.ByteBuffer` stand-in only has `wrap(ByteArray)` (`ios/shared/src/iosMain/kotlin/java/Nio.kt:7-8`), so the planes must be **copied into a Kotlin `ByteArray`**. That is about 3 MB per frame × 120/s. If it costs too much, a Metal compute kernel that writes the 480x270 RGBA (R=Y, G=U+128, B=V+128, A=255) is the drop-in alternative. Then use `Downsample.fromRgba`, which is 518 KB per tracker frame.

### 2.4 Picture check: flip and clock offset (ANDROID WORKAROUND)

- `LP.calibrate()` (`LP:664-724`) is called from `MA:450-471`, 500 ms after the camera runs, retried up to 10 times. It decodes the recorded frame matching a kept tap and compares all 4 flips by mean |ΔY|.
  - It sets `flip` (bit0 = mirror x, bit1 = mirror y), `clockOffsetUs` (preview clock vs recording clock) and `calibrated = true`. It also rejects black, flat or noisy pictures (`flipBest = 99`).
  - On success the app calls `tap.setFlip(f)` and `setReady("Ready.")`.
  - After 10 failures it calls `assumeDefault()` (flip 0) and still says Ready.
  - If `flipBest > 15` on the recording stream, the app restarts with the other buffer usage (`nextQueueMode`, `MA:77-82`).
- *Port note:* the analysis buffer and the encoded buffer are the same `CVPixelBuffer` with the same pts, so flip = 0 and offset = 0. Pipeline startup still has to produce the **Ready** transition (§7). Treat iOS as calibrated from the first frame.

### 2.5 GPU mask in the alpha byte (ANDROID OPTIMISATION, optional)

- `GT:614-672`, shaders `mask_body.glsl`, `rows.frag`, `mask.frag`. This is a pixel-exact GPU copy of `Detector.makeMask`. The CPU pipeline trusts it only after `maskChecksWanted = 60` frames where the CPU mask had 50..20000 moving pixels and the two masks differed on **zero** pixels (`LP:601-612`, `LP:447-476`). One differing pixel means `maskTrust = -1` for the rest of the session.
- The background goes to the GPU through `offerBackground` (`LP:629-652`):
  - only when `tr.bgSamples >= 5` and `tr.bgVersion` has changed
  - RGBA layout R=y, G=u+128, B=v+128, A=255, flipped back into the camera's orientation
  - the last 4 generations are kept for matching against `maskGen`
- *Port note:* skip this at first. `Detector.find(q, bg)` alone is the reference behaviour. Port it only if the finders cannot keep up (target: find ≤ 25 ms per tracker frame on 3 threads).

### 2.6 Window ring, scene background, row samples (EXPERIMENT, `windowRing` off by default)

When `windowRing` is on, the GPU cuts a square window round the predicted ball **on every frame** while a live track exists, plus a whole-picture row sample, so a capture can be built without decoding the span again.

**Plan** (`LP:893-934`), on the tracker thread after every `pushFound`:
- `best = LiveAim.pick(tr.liveTracks, tr.w)`
- `side = ringSide(medianR * 4)`, which is multiples of 32 in 128..384 (`core/LiveWindows:32-33`). It only grows, and shrinks only below 60%.
- The plan is `GlTap.WindowPlan(rect=[0,0,W,H], side, rowStep=2, rowStride=Windows.ROW_STRIDE=16, bt601, y/u/v gain+offset (ringCalib, else [1,0]), at = { n -> LiveAim.place(track, n/2f, side, 4f, W, H) })` (`LP:929-933`, `LP:938-939`).
- `null` tears the plan down. The plan is not sent while `ringHeld > 0`.

**Bytes per frame** (`GT:742-759`, `GT:832-893`):
- `Y side*side`, then `U (side/2)^2`, `V (side/2)^2`, all planar.
- Then the **row sample**: rows `ceil(H/2)` (every 2nd row of the **whole picture**), each `rowPitch = ceil(ceil(W/16)/4)*4` bytes of Y at every 16th column. At 1080p that is 540 rows × 120 bytes = 64.8 KB.
- Colour is mapped with the plan's gain/offset (`crop_y.frag`, `crop_c.frag`, `crop_rows.frag`).
- Read back via 8 PBOs on gl-read, then `onWindow(n, at=[x,y], bytes)`.

**`LP.offerWindow`** (`LP:1071-1095`):
- Works the window side out from the byte count: `win = bytes - rowBytes`, `side = sqrt(win/1.5)`, and checks `side % 8 == 0`.
- Calls `WindowRing.put(n, x, y, side, winBytes, rowSample)`.
- The ring holds `ringFrames = 1.5 × fps = 360` slots indexed `n % 360` (`LP:845`, `core/LiveWindows:126-160`).
- While a capture holds the ring (`ringHeld > 0`), only frames ≤ `heldFrom` (= the serve's last frame) are accepted.

**Scene background** (`LP:956-1028`):
- Runs on a worker, every 4 s until complete, then every 30 s.
- Skipped while a serve is held or planned, or while `behindNow`.
- Decodes up to 15 keyframes from the last 6 s and calls `SceneBackground.addWhole(Capture(W,H,[0,0,W,H],fullRange,fps).crop(yuv), nowMs)`.
- `SceneBackground(W, H, stripsFor(H))`: 9 strips at 1080.
- `crop(rect)` is called from the capture worker. `add`, `crop` and `reset` are `@Synchronized`. **The app never calls `reset()`.**

**`cutFromRing`** (`LP:1243-1348`):
1. Needs `CaptureGl.calib` and `CaptureGl.ringCalib`. Both are colour fits made by the first capture of the session, which is always cut the old way (`fitRing`, `LP:1389-1430`).
2. Takes `WindowRing.take(firstFrame, n)`.
3. Requires ≥ 75% of flight frames (main track from frame `+10` (`FLIGHT_GRACE`) to its end) to have a window that the capture's own window fits inside.
4. `Windows.reCut`s each frame and feeds `Builder.addParts(Windows.ringRows(rowSample, rect, W, H, 2), window, bgCropOrNull)`.
5. Uses the scene background (`b.given`) when it is complete and its window edges match within a median of 30 levels (`LP:1042-1068`). Otherwise it decodes the span to get the 15 background frames.

With the ring on, the crop rect is aligned with `Windows.alignRect(r, W, H, 2)` (`LP:1433-1434`) so its x is a multiple of 16.

*Port note:* leave this out until the default path matches Android. It only saves capture time (§4.6). Nothing the engine needs depends on it.

---

## 3. Threads, queues, drop policies

### 3.1 Thread map (Android)

| Thread | Work | Core calls |
|---|---|---|
| main | UI, engine bridge results, turn state, `next()` | none of the tracker |
| "camera" HandlerThread (`MA:63`) | Camera2 callbacks, `results` (exposure, crop, focal) | — |
| "encoder" HandlerThread (`MA:65`) | MediaCodec encoder callbacks, store append, `rec.onFrame` (decoder fallback) | — |
| "gl-tap" (`GT:81`) | every camera frame: latch, window cut, small picture draw, encode | — |
| "gl-read" (`GT:198`) | PBO map/copy → `offerTapPicture`, `offerWindow` | `ServeTracker.sample/background/needsFrame`, `Downsample.fromRgba`, `WindowRing.put` |
| "decoder" (`LP:71`) | decoder path: decode, `onPicture` | `Downsample.rows/quarter`, `ServeTracker.sample/background/needsFrame` |
| "finder" ×3 (`LP:76`) | `Detector.find/findFromAlpha/findChecked` (thread-local `Detector`) | `Downsample.quarter/fromRgba` |
| "serve-tracker" (`LP:72`) | `link()`: `pushFound`, plan, scene trigger, `onServe` | `ServeTracker.pushFound`, `.liveTracks`, `.cropFor`, `LiveAim.pick` |
| "pipeline-work" pool (`LP:159-160`) | capture-N, skipped-N clip, scene-bg | `Windows.Builder`, `Capture.crop`, `WindowRing.take`, `SceneBackground.addWhole/crop` |
| WebView IO thread | `shouldInterceptRequest` → `captureResponse` (**blocks up to 20 s**) | — |

**`ServeTracker` is touched from two threads.** `sample` / `background` / `needsFrame` / `bgVersion` run on the producer (decoder or gl-read). `pushFound` / `liveTracks` / `cropFor` run on serve-tracker. The Android code relies on the core being safe for that split (`@Volatile` fields). Keep exactly this split on iOS. Don't add a third caller.

Thread-safe core objects: `WindowRing.put/take` and `SceneBackground.add/crop/reset` are `@Synchronized` (`core/LiveWindows:139,147`, `core/SceneBackground:76,181,205`). On iOS the `Synchronized` annotation is a no-op stand-in (`ios/shared/src/iosMain/kotlin/core/Jvm.kt:19`). **Port note:** guard those calls with your own lock, or call them from one serial queue.

### 3.2 Queues and their policies

| Queue | Capacity | Live | Replay |
|---|---|---|---|
| encoded `queue` (`LP:69`) | 720 live / 64 replay | `offer`; full means drop + `dropped++`, then resume at the next keyframe | `put` (blocks the feeder) |
| `jobs` (`LP:89`) | 24 live / 16 replay | skip the tracker frame when full (`skipped++`, counted by the guard) | decoder path: `put` (blocks); tap path: `offer` |
| GPU small-picture PBOs (`GT:537`) | 8 | busy slot → `busySkips++`, picture dropped | same |
| GPU window PBOs (`GT:784`) | 8 | busy → `winSkips++` | same |
| camera ImageReader (`GT:376`) | 12 buffers; ≤ 2 frames in flight on the GPU before waiting on a fence (`GT:425`) | — | — |
| engine `waiting` (`MA:784-792`) | 12 live / 30 replay, and ≤ 1.5 GB of frame bytes; the oldest is dropped (its .bin deleted) | | |
| `pipeline-work` | unbounded threads (`SynchronousQueue`, min priority) | | |

The linker polls `jobs` every 50 ms and **blocks on each job's future in index order** (`LP:740-747`). Finder results are linked strictly in order.

### 3.3 Load guard (`LP:113-370`, live only)

- Window: 240 tracker frames. If the share skipped is > 10%, then `behindNow = true`, and the note "too slow to watch every frame (N% lost). Move the phone back." is spoken once per session and shown as a toast.
- It returns to calm after 8 windows in a row under 2%.
- It **does not thin frames**. It only reports, and suppresses the optional scene-background work.

### 3.4 Stop and finish

- `stop()` (`LP:189-195`): interrupt and join the decoder and linker (1 s each), `finders.shutdownNow`, then `retireWorkers`.
  - Captures already being written get `captureGraceMs = 30 000` ms. After that they are abandoned and throw at their next frame.
  - `whenWorkersDone` callbacks run afterwards. Replay uses one to close the spill file (`MA:777`).
- `finish()` (replay, `LP:198`): `queue.put(END)`, then the decoder flushes (EOS and drain; `LP:290-298`), then pushes `Job(end=true)`. The linker then pushes **120 empty tracker frames** so a serve still grouping can come out (`LP:742-745`). Join both, then `finders.shutdown()`.

---

## 4. Serve spotted → capture

### 4.1 `onServe` (`LP:1097-1155`), on the serve-tracker thread

`Serve` (`core/ServeTracker:22-47`):
- `first`, `last` (tracker indices; already include 0.25 s before and ≤ 0.05 s after, capped at 1.5 s)
- `box` (quarter px) and `ballRadius`
- `track` (`[index, x, y, r]` quarter px) and `near` (other tracks)
- `why`

Steps:
1. `servesFound++`. `first = serve.first*2`, `last = serve.last*2 + 1` (whole frames).
2. `pose?.veto(first, last)`: walking, not serving. It adds a note and returns (optional on iOS).
3. **Turn gate** (live only, `onSpotted != null`). If `paused`, `ignoredWhileMeasuring++` and the serve is dropped with a note. Otherwise `paused = true`.
4. **Trim to tracks:** `first = max(first, minTrackFrame - fps/20)`, `last = min(last, maxTrackFrame + fps/20)`, over `track + near`. That is ±12 frames at 240.
5. **300 MB cap:**
   - `per = cropW*cropH + 2*(cropW/2)*(cropH/2)` and `maxN = max(60, 300e6/per)`.
   - If the span is longer: `first = max(first, flightStart - fps/10)`, and if still too long, `last = first + maxN - 1`. Then `trimmed++`.
6. Bail out if `decoded - first > 8192` or `first >= decoded` (no longer in ptsRing).
7. `fromUs = ptsRing[first] - 1`, `toUs = ptsRing[min(last, decoded-1)] + 1`.
8. `rect = tr.cropFor(serve, W, H)` (`core/ServeTracker:559-569`): the box ± (3·rMax·4 + 24) px, x and y even, **w a multiple of 8, h even**.
9. `id = ++captures`. Call `onSpotted(id)`. If the ring is on, hold it.
10. `work("capture-$id") { writeCapture(id, serve, first, fromUs, toUs, rect, W, H, held) }`.

### 4.2 Capture files (REQUIRED format; the app does **not** use `Capture.write` or `Windows.write`)

Two files per capture are written in `captureDir` (`cacheDir/captures`, wiped at app start, `MA:117`).

**`cap_N.json`**
- The header. It is always swapped in whole: written to `cap_N.json.tmp`, then renamed (`LP:1573-1577`).
- The base is `Capture(W,H,rect,full,fps).header(extra, n)` = `{"W":1920,"H":1080,"rect":[x,y,w,h],"n":N,"full":false,"fps":240` + extra + `}` (`core/Frames:172-173`).

| | First (partial) header (`LP:1470-1471`) | Final header (`LP:1555-1557`) |
|---|---|---|
| `n` | `nGuess` = number of store frames with pts in [fromUs, toUs] | real count |
| `full` | always `false` | the decoder's real colour range |
| extra | `,"id":N,"partial":true` + (`,"windowed":true` if windowed) + `optics()` | `,"id":N,"fromUs":…,"toUs":…,"why":"…"` + `optics()` + `Builder.headerExtra()` (windowed only: `,"winW":S,"winH":S,"windows":[[x,y]\|null,…]`, x and y in crop pixels) + `serve.tracksJson(2, 4.0, firstFrame, n)` = `,"track":[[f,x,y,r],…],"near":[[…],…]` |

- Track coordinates are **whole-picture pixels** (quarter × 4). `f` is counted from the capture's first frame (`firstFrame`, *after* trimming), and points outside `[0,n)` are dropped (`core/ServeTracker:40-47`).
- **`"windowed":true` is only in the first header.** The engine branches on the first header (`EH:77`).

**`cap_N.bin`** has no length prefix.
- **Plain** (not windowed): `n` frames back to back, each `per` bytes. Each frame is the crop's Y (`w*h`), then U (`w/2*h/2`), then V. Planar, raw decoder bytes (video range if `full:false`). This is exactly `Capture.crop(yuv)` (`core/Frames:151-170`). It is written frame by frame through `FrameCountingOut`, which flushes and bumps `Writing.frames` after each whole frame (`LP:1595-1603`).
- **Windowed** (`Windows.Builder.writeBody`, `core/Windows:430-480`): written in one go at the end.
  1. a background frame (`per` bytes)
  2. `n × h` floats, **big-endian** (`DataOutputStream.writeInt(floatToIntBits)`): each frame's row brightness against the background
  3. one window (`winW*winH + 2*(winW/2*winH/2)` bytes, planar YUV) for each non-null entry of `windows`, in order

`windowed = windowCaptures (default true, LP:1161) && tracks.isNotEmpty()` (`LP:1469`). `tracks = (serve.track + serve.near)` converted to whole frames relative to `firstFrame` and whole pixels, keeping those with ≥ 2 points (`LP:1464-1468`).

### 4.3 `writeCapture` order of work (`LP:1436-1570`), on a pipeline-work thread

1. `rect = alignRect(rect0)`; this only changes it when the ring is on. `frames = store.between(fromUs, toUs)`. Live: `clipFrames = store.between(fromUs - 0.5 s, toUs + 0.3 s)`, snapshotted now because the store keeps only 5 s.
2. `writing[id] = Writing(per)`, a static map that the engine's HTTP reads wait on (`LP:254`, `LP:1580-1592`).
3. Write the **first header**, then `onCaptureStarted(Info(id, nGuess, per, "", 0, fromUs, toUs))` → main thread, `waiting.addLast`, `next()`. The engine can start fetching now.
4. If windowed: `builder = Windows.Builder(rect, nGuess + fps/4, tracks)`, or from the ring (§2.6).
5. **Cut the frames.** Each crop is handed to `sink` → `builder.add(crop)` (windowed) or `out.write(crop)` (plain). `checkAbandoned()` runs per frame.
   - GPU (`CaptureGl`, ANDROID WORKAROUND): taken if `rect.w % 8 == 0 && rect.h % 2 == 0` and fewer than 2 GPU failures so far. The first capture of a session also decodes one reference frame on the CPU and fits gain/offset per plane, BT.709 vs BT.601 (`CG:196-212`, rms ≤ 3 required). **Output must equal `Capture.crop(yuv)` within that fit.**
   - CPU fallback (`LP:1526-1543`): decode `frames` with `FrameDecoder` and keep pictures with `fromUs ≤ pts ≤ toUs`. The first one sets `fullRange`. `sink(Capture(W,H,rect,fullRange,fps).crop(yuv))`.
6. Windowed: `n = builder.count` and `builder.writeBody(DataOutputStream(bin))`.
7. Write the **final header**, then `prog.finish()` (wakes `/head/final` and `/all`).
8. Live: write `clip_N.mp4` (`writeMp4(clipFrames, fmt, toUs + 0.3 s)`, `AF:82-99`). Then `onCapture(Info(id, n, frameBytes, head, writeMs, fromUs, toUs), clip)` → main (log, save clip).
9. `finally`: `writing[id]?.finish()`, release the ring hold.

*Port note:* the CPU path is the reference. On iOS:
- Decode with `VTDecompressionSession`, output `kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange` (or full range, and record it).
- Crop Y directly. De-interleave CbCr into U then V.
- That gives byte-identical planar crops with no colour fit, so skip `CaptureGl` entirely.
- `full` in the final header must be the true range of the bytes you wrote.

### 4.4 Memory and timing

- Live store: ≈ 22 MB encoded (5 s). ptsRing: 64 KB.
- Plain capture: up to 300 MB on disk (the cap). A windowed capture is about 1/50 of that, around 6 MB.
- Builder memory: 15 background crops (`BG_FRAMES`) + `h × ceil(w/16)` row samples per frame + windows. That is ≈ 24 MB + 12 MB + 4 MB on a 287 MB-equivalent serve (`core/Windows:160-166`).
- Measured on Android: a serve is reported about 0.4 s after the flight ends (the tracker's grouping wait). Decode + cut took 1.2–3.3 s on the CPU path and 2.2–3.0 s with GPU cut and decode. Engine wall time is around 2 s.

### 4.5 Skipped-movement clips (live only, optional)

`tr.onSkipped(first, last, why)` → up to 30 per session, while not paused. A worker writes `skipped_N.mp4` from `store.between(from - 0.5 s, to + 0.3 s)` (`LP:794-814`).

### 4.6 Ring hold timing (experiment)

The ring hold goes up in `onServe` and comes down at the end of `writeCapture`. While it is up the ring accepts only windows for frames ≤ the serve's last frame, which the GPU reads back late (`LP:1082`).

---

## 5. The engine (WebView)

### 5.1 Setup (`MA:152-153`, `MA:965-984`)

- A `WebView` added at 2x2 px (invisible), with JavaScript enabled, `addJavascriptInterface(Bridge(), "Android")`, and `loadUrl("https://appassets.androidplatform.net/engine.html")`.
- `shouldInterceptRequest` serves only that host:
  - `/capture/...` → `captureResponse`
  - anything else → the app asset at that path, with mime `text/html` / `application/javascript` / `application/octet-stream`
  - any exception → 404
- Assets: `engine.html`, `engine.js` (copied from `experiments/02-spin-meter/web/engine.js` by the build), `mp4box.all.min.js`.
- The engine uses WebCodecs (`VideoDecoder`) only for the `analyse(url)` video path. The capture path reads raw planes.
- Lifecycle (`MA:586-596`): on pause, `web.onPause()`, and `pauseTimers()` only once nothing is measuring or waiting. On resume, `resumeTimers()` then `next()`.

*Port note:*
- Use a `WKWebView` with a `WKURLSchemeHandler` for a custom scheme such as `app://local/engine.html` and `app://local/capture/N/...`. The engine builds every capture URL from the `base` string it is passed, so the scheme does not matter.
- Bridge: `WKScriptMessageHandler`. Inject a shim `window.Android = { onReady:(a,b,c)=>webkit.messageHandlers.onReady.postMessage([a,b,c]), onProgress:…, onResult:… }` at document start so `engine.html` runs unchanged.
- **Blocking:** the Android handler blocks a WebView IO thread for up to 20 s. On iOS, never block in `webView(_:start:)`. Answer from a background queue when the data is ready, and handle `stop` (task cancelled).
- Check whether `fetch` from a custom scheme accepts `arrayBuffer()` of 300 MB and whether `VideoDecoder` exists in WKWebView. `onReady` reports both.

### 5.2 How the app calls it

`next()` (`MA:803-811`), on main:
- Only when `engineReady && !engineBusy && !timersPaused`.
- Pops `waiting`, sets `measuring`, `engineBusy = true`, then:
  `web.evaluateJavascript("analyseCapture('https://appassets.androidplatform.net/capture/${id}', ${id})")`

`next()` runs after `onReady`, after each `onCaptureStarted` (so measuring starts while the capture is still being cut), after each `onResult`, and on resume.

### 5.3 URL contract (`MA:987-1014`), base = `…/capture/N`

| Request | Waits for | Returns |
|---|---|---|
| `${base}/head` | nothing; the file exists before `onCaptureStarted` | current `cap_N.json` (the first or final header), `application/json` |
| `${base}/head/final` | `writing[N].awaitDone(20 s)` | `cap_N.json` (final if done) |
| `${base}/all` | `awaitDone(20 s)` | the whole `cap_N.bin`, octet-stream |
| `${base}/frames/k/c` (`c = 24`) | `await(k + c, 20 s)` (frames on disk ≥ k+c, or done) | `min(c, frames_on_disk - k)` frames × `per`, possibly **0 bytes** |
| `${base}/k` | `await(k + 1, 20 s)` | frame k, `per` bytes |

`per` is recomputed from the header's `rect` on each request. After a timeout the handler returns whatever is on disk.
- If `writing[N]` is missing (finished and removed, or never existed), nothing waits.
- `Writing.frames` only advances for **plain** captures (`FrameCountingOut`). For windowed captures only `done` matters.

### 5.4 What engine.html does (`EH:61-137`)

1. `head = GET /head`. `per = cw*ch + 2*(cw>>1)*(ch>>1)`.
2. **Windowed** (`head.windowed`): `head = GET /head/final`, `b = GET /all`. Parse the background (`per` bytes), then `head.n` × `ch` big-endian Float32 row values, then for each `i` with `head.windows[i]` non-null a window of `winW*winH + 2*((winW>>1)*(winH>>1))` bytes. Frames with null get `null`.
3. **Plain, ≤ 600 MB**: fetch `/frames/k/24` for k = 0, 24, … up to `head.n` (the guess), stopping at a 0-byte reply. Then `GET /head/final`, then keep fetching chunks while `frames.length < head.n` and the last chunk was full. Truncate to `head.n`.
4. **Plain, > 600 MB**: `GET /head/final`, then fetch lazily per frame with `/k`.
5. Call:

```
SpinMeter.measureCapture({W, H, rect, n, frames, frame, full, fps, track, near,
                          hfov, hfovFrom, exposureUs, ...(windowed: background, rowBright, windows, winW, winH)},
                         {useTrack: true, single: true, serveDir: "any", robust: true, onProgress})
```

6. `Android.onResult(id, JSON)` (on exception: `{ms, error: "engine: …"}`).

Bridge calls JS → app:
- `Android.onReady(webCodecs: Bool, mp4box: Bool, engine: Bool)` at page load (`EH:138`). The app sets `engineReady = engine`, records it in the report, then calls `next()`, or logs "the engine didn't load" (`MA:1017-1021`).
- `Android.onProgress(id, step: String, frac: Double)`. The app shows "Measuring serve #id… NN%" (`MA:1022-1024`).
- `Android.onResult(id, json: String)` (`MA:1025-1055`).

### 5.5 Result JSON (from `analyseCapture`) and what the app does with it

Fields:
- `ms`, `error` (null if a serve was read)
- `serve` = `pick(sv)` (`EH:9-25`): `{phase, rps, rpm, type, clear, error, lift, curve, gyro, marking, gyroDirKnown, fit, speed (m/s), launchSpeed, hfov, toward, frames, issues[], ballPx}` plus `phases[]` (same shape) and `notes[]`
- `coach` (`{kind, …}` from `SpinMeter.coach`)
- `frames`, `crop`, `fetchMs`, `optics{hfov, from, shutter}`, `net`, `skipped`
- `data` (training data per phase, `EH:29-35`)
- `usedTrack`, `trackRefused`, `trackSaid`, `trackUnclear`, `engineSeconds`, `time`, `search`

`onResult` on main (`MA:1025-1055`):
1. `engineBusy = false`; `measuring = null`.
2. `data` is removed and saved separately. Added: `capture=id`, `wallMs`, `captureFrames`, `writeMs`, `answerMs` (since "Got it").
3. `crossed = NoRead.crossed(header.track.map{x}, header.W)` (how far across the picture the track went).
4. Live: save `serveNN.json` (header + result + data).
5. `writing.remove(id)`; delete `cap_N.bin` and `cap_N.json`.
6. `show(r)` (`MA:1109-1181`):
   - `net` → "Into the net." (unread).
   - `serve == null || !serve.clear` → unread. The cause comes from `NoRead.of(marking, toward, issues, error, crossed)`, and the player hears `noReads.next(cause)`.
   - No clear phase other than `"bounce"` and `rps < 10` → unread (probably someone walking).
   - Otherwise: `rpm = round(rps*6)*10`, `type`, tip from `coach.kind` (TipPicker), "move closer" if `ballPx ≤ 24`, the spin ball from `lift/curve/gyro/gyroDirKnown`, `afterBounce = phase=="bounce"`, `km/h = speed*3.6` (if > 0.1). The player hears `"<SpinLevel.outcome(rpm,type)>, <rpm>. [Tip: …]"`.
   - Each branch calls `turnDone(spoken, id)` (live).
7. TEST VIDEO: count `testRead` / `testGood` (`clear`) and run `finishTest()`.
8. Live: `writeReport()`. Then `next()`. If backgrounded, `pauseEngine()`.

---

## 6. TEST VIDEO, end to end (build this first)

Trigger: the "Test" button → `ACTION_OPEN_DOCUMENT video/*` → `onActivityResult(requestCode == 7, RESULT_OK, uri)` (`MA:600-603`, `MA:721-780`).

1. Main: `stopPreview()`, `mode = REPLAY`, UI "Video test: reading the video…", reset the test counters, `firstId = firstFreeCaptureId()` (`MA:478-479`, which is greater than any capture still in flight). Then a new background thread:
2. **`FileFrames(uri)`** (`AF:113-157`):
   - `MediaExtractor` on the first `video/*` track, with `outputFormat = trackFormat` (it carries csd).
   - Every sample's bytes go to an unlinked temp file in `cacheDir/replay`. Each frame keeps `SpilledFrame(ptsUs, key=SYNC flag, channel, offset, size)` in memory (`AF:21-46`). This avoids OOM on 130–180 MB videos.
   - `captureFps = captureFpsOf(pts)`: the median positive gap; 120 if it is in [6 ms, 12 ms], else 240 (`AF:52-56`). Slow-motion files stored "stretched" for playback are still treated as 240, which the gap would not show.
   - *Port note:* `AVAssetReader` + `AVAssetReaderTrackOutput(outputSettings: nil)` gives compressed `CMSampleBuffer`s in decode order. Key = `!kCMSampleAttachmentKey_NotSync`. The format description replaces csd. Use PTS for `ptsUs`. Spilling to disk matters less on iOS, but keep the same `FrameStore.between` semantics (decode order, keyframe before fromUs → first keyframe after toUs).
3. `p = newPipeline(ff, blocking = true, fps = ff.captureFps, firstId)` (`MA:481-505`). Replay-specific settings:

| | Live | Replay |
|---|---|---|
| `blocking` | false | true (queues 64/16, `put`, no load guard, no skipping) |
| `onSpotted` / pause | yes (one serve per turn) | `null`: never paused, every serve captured |
| `clipDir`, skipped clips, `clip_N.mp4` | yes | none |
| `optics` | camera's | `""` → engine assumes 68° |
| `fps` | 240 | `captureFps` of the file (240 or 120) |
| engine `waiting` cap | 12 | 30 |
| end | `stop()` | `finish()` (+120 empty tracker frames), then `stopAll()` |
| results saved to session files | yes | no |

   - `CaptureGl.calib` and `CaptureGl.ringCalib` are reset for each new pipeline (`MA:505`).
4. **`p.prime(ff.frames)`** (`LP:221-240`): picks 5 keyframes spread evenly over the whole file (`keys[i*(K-1)/4]`). Each is decoded alone with a fresh `FrameDecoder` (feed, EOS, drain up to 400 tries). `qs = Downsample.quarter(yuv)` and `tracker = ServeTracker(q.w, q.h, fps/2).prime(qs)`. This gives the tracker a background before the serve, which may be in the first frames.
5. `p.start()`.
6. If `windowRingOn`: `replayThroughChip` (§6.1). Else (**default**): `for (fr in ff.frames) p.offer(fr)`. That is a blocking `queue.put`, so the feeder runs at decoder speed, and it stops if the mode changes. Progress "looking for serves… N%" every 60 frames.
7. `p.finish()` blocks until the decoder has drained and the tracker has flushed (§3.4). Serves found on the way have started capture workers. Each worker writes its first header, then `onCaptureStarted` → engine `next()`. **Engine measurement overlaps the replay.**
8. Main: log a summary, `whyNoServe(tracker.rejected)`, `stopAll()` (→ `p.stop()`, captures still in progress get the 30 s grace), `testSpotted = servesFound`.
   - 0 serves → "Video test done: no serve found" + why.
   - Otherwise "measuring N serve(s)…"; `finishTest()` runs after each result until `testRead == testSpotted` → "Video test done: G of N serve(s) read".
9. `finally`: the spill file is closed once the pipeline's workers are done (`p.whenWorkersDone { ff.close() }`).

### 6.1 `replayThroughChip` (experiment, `MA:623-697`)

Only used when `windowRingOn`.
- MediaCodec decodes into `GlTap.surface` with render time = pts × 1000 ns. GlTap numbers frames from those times.
- Pacing: a semaphore released by `onFrame`. It waits up to 200 ms per frame and counts `stalls`.
- After 30 frames it calls `p.calibrate()` (flip and offset come out as 0).
- Exercises the small-picture, mask, window and ring paths as live does, but slower than real time.

---

## 7. Turn and timing state the pipeline drives (live)

State machine (`MA:1064-1107`):

```
Start ─► camera running ─► calibrateSoon OK (or 10 failures → assumeDefault) ─► setReady("Ready.")
Ready ── LP.onServe (paused=false) ─► paused=true, onSpotted(id) ─► "Got it." (AMBER), turnId=id
      ── 30 s with no answer for turnId ─► log "#id: no answer in 30 s", setReady("Ready.")
      ── onResult(id) ─► show ─► turnDone(spoken,id):
             id == turnId  → setReady("<spoken> Ready.")   (pipeline.resume(): paused=false)
             else (timed-out turn) → spoken only if id >= latestSpotted
```

- `setReady` (`MA:1070-1079`): `pipeline.resume()`, `turnId = 0`, CYAN, "Ready: serve", flashes the border, speaks the text (queued, utterance id `"ready"`), then a chime when the speech ends (`MA:125`) if no turn has started meanwhile.
- While paused, `onServe` ignores serves (`ignoredWhileMeasuring`). Tracking itself never stops, so the background and tracks stay current.
- `onSpotted` happens on the tracker thread before the capture is written. "Got it" comes about 0.4 s after the ball lands.
- **Decoder fallback has no Ready.** `calibrateSoon` runs only when a GPU tap exists (`MA:418`). Without one, `setReady` is first called after the first answer. `paused` starts false, so serves are still caught.
  - *Port note:* on iOS, call `setReady("Ready.")` once the camera runs and the tracker has a background (≥ 3 samples, about 0.5 s), or after a fixed ~1 s. Use the same text.
- After 3 s with no frames from the recording stream, the app shows an error (`MA:421-423`).
- `stopAll` (`MA:525-569`): writes the report, then `camera.stop`, `tap.stop`, `pipeline.stop`, `recorder.stop`, `mode = IDLE`, `turnId = 0`.
  - `finishSession` waits up to 30 s (60 × 500 ms) for `measuring` and `waiting` to empty before writing the report (`MA:930-933`).
  - `onPause` calls `stopAll()` for both LIVE and REPLAY.

---

## 8. Constants

| Name | Value | Where |
|---|---|---|
| capture fps | 240 (120 if a file's median gap is 6–12 ms) | `HSC:103`, `AF:52-56` |
| resolution | 1920x1080 preferred, else 720p | `HSC:103` |
| tracker step | 2 (tracker at 120 Hz) | `LP:73` |
| small picture | W/4 x H/4 = 480x270 | `GT:79-80`, `LP:368` |
| encoder bitrate | min(W·H·fps/14, 120 Mbit/s) | `CR:37` |
| keyframe interval | 0.1 s | `CR:41` |
| encoded store | 5 s | `CR:14` |
| AE/AWB/AF lock delay | 1500 ms | `HSC:185` |
| encoded queue | 720 live / 64 replay | `LP:69` |
| jobs queue | 24 live / 16 replay | `LP:89` |
| finder threads | 3 | `LP:76` |
| ptsRing | 8192 frames | `LP:249` |
| load guard | window 240, on > 10 %, off after 8 windows < 2 % | `LP:113, 352-356` |
| background sample | every 24 tracker frames, median of last 5, usable at ≥ 3 | `core/ServeTracker:51-52,130` |
| pose look | every 16th tracker frame | `LP:330` |
| trim margin | fps/20 = 12 frames | `LP:1118` |
| capture cap | 300 MB, ≥ 60 frames; run-up trimmed to fps/10 | `LP:1129-1132` |
| crop | box ± (3·rMax·4 + 24) px, w % 8, h % 2, x,y even | `core/ServeTracker:559-569` |
| builder frames bound | nGuess + fps/4 | `LP:1480` |
| BG_FRAMES / DEFAULT_RADII / ROW_STRIDE | 15 / 3.0 / 16 | `core/Windows:28,30,143` |
| capture grace after Stop | 30 000 ms | `LP:163` |
| HTTP read wait | 20 000 ms | `MA:992-1007` |
| engine chunk | 24 frames; whole in memory if ≤ 600 MB | `EH:95-104` |
| engine waiting cap | 12 live / 30 replay; ≤ 1.5 GB | `MA:789` |
| turn timeout | 30 000 ms | `MA:1096` |
| GPU colour-fit acceptance | Y rms ≤ 3 (decode), ≤ 6 (ring) | `CG:210`, `LP:1429` |
| GPU failures before CPU-only | 2 | `LP:1524` |
| mask trust | 60 checked frames with 50..20000 moving px, 0 differing | `LP:602-607` |
| ring (exp.) | 360 frames, row step 2, side 128..384 step 32, FLIGHT_GRACE 10, coverage ≥ 75 % | `LP:845,864,873,1286,1344` |
| scene bg (exp.) | every 4 s → 30 s when complete, 15 keyframes of last 6 s, edge median ≤ 30 | `LP:976-978,1001,1317` |
| skipped clips | ≤ 30 per session, −0.5 s / +0.3 s | `LP:800,809` |
| serve clip | −0.5 s / +0.3 s around [fromUs, toUs] | `LP:1450,1564` |
| hfov fallback | 68° (accept 30–120°) | `core/Optics:17,36` |
