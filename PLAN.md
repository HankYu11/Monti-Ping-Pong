# Monti Ping Pong — Phone Coach Prototype Plan

A phone on a tripod watches one player. Pose estimation runs on the phone, and the phone
detects strokes from wrist motion. When a rally ends, it sends a 3-panel snapshot of the
weakest stroke to an LLM and speaks one short coaching cue into the player's earbuds.

This plan reviews the original "Video-In, Voice-Out" proposal and corrects the parts that
won't hold up in practice.

---

## 1. Verdict

**Doable as a prototype.** The core idea is sound: on-device pose, a kinematic trigger
instead of an audio trigger, and a few keyframes plus numbers sent to an LLM instead of
video. A research prototype for tennis took the same approach: MediaPipe world landmarks,
velocity-based stroke segmentation and a rule-based coaching layer (arXiv 2606.15992).

**"One day" is optimistic.** In one day you can get the pipeline running end to end, from
camera to speech. Making the stroke trigger reliable for a real player at a real venue
will take another 2–4 days of recording and tuning. The trigger is the hard part; the LLM
part is easy.

---

## 2. Corrections to the original proposal

| # | Original claim | Problem | Fix |
|---|---|---|---|
| 1 | Pose at 60 FPS | Many phones won't deliver 60 FPS to `ImageAnalysis`. The camera HAL caps the rate at the slowest stream's minimum frame duration, and Pose Landmarker (Full, GPU) on mid-range Android usually runs at roughly 20–40 FPS. `LIVE_STREAM` mode drops frames when it is busy. | Design for **30 FPS**. Use frame **timestamps** for every Δt, never frame counts. Ask for 60 FPS through `Camera2Interop` `CONTROL_AE_TARGET_FPS_RANGE`, and log the rate you actually get. Use the **Lite** model if Full can't keep up. |
| 2 | Wrist velocity in m/s from `worldLandmarks`, threshold > 3.5 m/s | World landmarks are estimated from a single camera, and the **z axis is noisy**. A table-tennis coach prototype found z too unreliable to train on. A fixed m/s threshold won't transfer between players or camera positions. | Compute speed from **2D image landmarks, normalised by torso length** (shoulder midpoint to hip midpoint, in pixels), giving units of *torso-lengths per second*. Set the threshold from a **5-swing calibration** (for example, 50% of the median peak) instead of hard-coding it. |
| 3 | Wrist blur and peak timing | At 30–60 FPS a fast forehand blurs the wrist, and MediaPipe can lose it or lag it for a few frames. MediaPipe's built-in temporal smoothing also **flattens and delays** the velocity peak. | Ignore frames where wrist `visibility < 0.5`. Use a central difference over ±1 frame and take the peak *frame*, not the peak *value*. Expect T₀ to be off by ±1–2 frames, which is fine for coaching form. |
| 4 | Peak wrist speed = ball contact | Close, but there is no ball check. A shadow swing triggers too. **Pushes, blocks and short serves barely move the wrist** and will be missed. | Scope v1 to **drives and loops only** (forehand first). Accept that the coach critiques *form*, not *outcome*. |
| 5 | 45-frame (0.75 s) ring buffer, comic strip built at rally end | **Bug.** The rally-end decision comes about 3 s after the last stroke, so a 0.75 s buffer has already overwritten the frames you need. | On every T₀, **copy** the frames at T₀−250 ms, T₀ and T₀+200 ms (once that frame arrives) into a per-stroke record. Keep the records for the current rally only. |
| 6 | Store a JPEG for every frame | Encoding a JPEG on every frame costs CPU time that pose estimation needs. | Keep small downscaled `Bitmap`s (480×360) in the ring buffer, roughly 1.5 s worth. JPEG-encode only the 3 panels you send. |
| 7 | SoundPool gives "sub-30 ms" feedback in earbuds | **Bluetooth audio adds about 100–300 ms.** A mid-rally judgement of "good weight transfer" from single-camera pose is also unreliable and distracting. | v1: one short tick per detected stroke (a debugging aid, so you can *hear* the trigger working), switchable off. No mid-rally quality chimes until the metrics are validated. |
| 8 | Torso coil and hip rotation | Rotation is hard to measure with one camera and depends heavily on where the camera sits. | Fix the camera placement (section 4) and measure **2D** angles from that view: knee flexion, elbow–torso gap, follow-through wrist height relative to the nose. Label them as rough. |
| 9 | `numPoses = 1` plus a size filter | With `numPoses = 1` the detector can **jump** to whoever is most salient. You can't filter out a person you never received. | Use **`numPoses = 2–3`**. Each frame, keep the pose closest to the previous locked pose (centre distance plus torso-height similarity). Reject poses under 50% of the calibrated torso height. Lock onto the player with a tap or auto-centre during calibration. |
| 10 | **Gemini 2.5 Flash**, Live WebSocket | As of Oct 2026, 2.5 models are **legacy with limited access**. The Live API is unnecessary here: you send one request per rally. | One **REST** call per rally to a current Flash model (the docs list `gemini-3.8-flash` as stable; check the models page before coding). Speak the result with Android **`TextToSpeech`**. Rate-limit to one cue every N rallies. |
| 11 | Uploading frames from a public gym | Frames may contain bystanders. | Crop each panel to the locked player's bounding box (plus padding) before upload. |
| 12 | Not mentioned | Camera, GPU and screen running for 30+ minutes **thermally throttles** the phone, and FPS drops. | Dim or turn off the preview after calibration. Show an FPS/thermal warning. Test a 30-minute session. |

---

## 3. Architecture (Android / Kotlin)

```
CameraX ImageAnalysis (RGBA_8888, ~30–60 fps, STRATEGY_KEEP_ONLY_LATEST)
        │  bitmap + timestampMs
        ▼
PoseLandmarker (tasks-vision, LIVE_STREAM, numPoses=3, GPU delegate)
        │  async result listener
        ▼
TargetLock ──► picks the player's pose, rejects others
        │
        ├──► FrameRing (≈1.5 s of {ts, 2D landmarks, small bitmap})
        ▼
StrokeDetector (state machine: IDLE → LOAD → APEX(T₀) → FOLLOW → COOLDOWN)
        │  on T₀ (+200 ms): StrokeRecord {3 frames, metrics}
        ▼
RallyTracker (rally ends after 3 s with no stroke)
        │  on rally end: pick weakest stroke
        ▼
StripBuilder (1440×360 JPEG, 3 labelled panels with skeleton overlay)
        ▼
CoachClient (Gemini REST: image + metrics JSON → ≤12-word cue)
        ▼
TextToSpeech → Bluetooth earbuds
```

### Key components

**TargetLock**
- Calibration: the player stands in the ready position for 2 s. Record the pose closest to
  the frame centre: centre `(cx, cy)` and torso height `H` (pixels).
- Per frame: among the poses returned, choose the one minimising
  `dist(centre, lastCentre)/H + |torsoH − H|/H`. Reject it if `torsoH < 0.5·H`, or if the
  centre jumps more than 1.5·H in a single frame.

**StrokeDetector** (all speeds in torso-lengths per second, `L/s`)
- `v(t) = |p(t+1) − p(t−1)| / (ts(t+1) − ts(t−1)) / torsoLen`, using the dominant wrist
  (landmark 16 for right-handed players, 15 for left-handed).
- `LOAD`: the wrist moves back behind the hip line on the dominant side for ≥ 80 ms.
- `APEX`: `v` crosses `vThresh` (from calibration) and then falls for 2 consecutive frames.
  T₀ = the frame with the maximum `v`. That's about 1–2 frames of extra delay, which is fine.
- `FOLLOW`: wait until `ts ≥ T₀ + 200 ms`, then snapshot that frame.
- `COOLDOWN`: 450 ms, so the recovery back to the ready position doesn't count as a stroke.
- Calibration: 5 shadow forehands, `vThresh = 0.5 × median(peaks)`.

**Metrics per stroke** (2D, rough)
- Knee angle at LOAD (hip–knee–ankle). Straight legs > ~160°.
- Elbow–torso gap at T₀ (elbow-to-hip-line distance / torsoLen).
- Follow-through wrist height relative to the nose at T₀+200 ms.
- Peak wrist speed (L/s), compared with the player's own median.

**Weakest stroke** = the lowest simple score combining those metrics relative to the
player's own calibration baseline. Keep the score easy to change.

**Coach prompt** (system):
> You are a table-tennis coach speaking into the player's earbuds between rallies. You get
> a 3-panel image (load, contact, follow-through) of one forehand stroke and rough pose
> metrics. Give ONE physical cue in under 12 words. No greetings. Prefer body feel
> ("sink into your right knee before swinging"). If the image is unclear, say "Keep going."

---

## 4. Camera setup (part of the product, not an afterthought)

- **Side-on to the player**, on the playing-arm side, roughly level with the end line,
  2.5–3.5 m away, lens at hip height. The table blocks the legs from a front view; a side
  view shows knee bend, elbow gap and follow-through height best.
- Whole body in frame, with headroom for the follow-through.
- Even lighting, and avoid a bright window behind the player. Pose accuracy drops sharply
  in poor lighting.
- Landscape orientation, phone on a tripod (the trigger assumes a static camera).

---

## 5. Build plan

### Step 0 — Record data first (half a day, highest value)
1. Record 5–10 minutes of real play at the target venue at the phone's best FPS, from the
   side-on position. Include rallies, pushes, walking around, and other players in the
   background.
2. Write down stroke times by hand for 2–3 minutes of footage (a CSV of timestamps).
3. Run MediaPipe Pose in **Python on the laptop** over the clips and build the
   StrokeDetector there first. Iterating offline takes minutes; iterating on the device
   takes hours.
4. Target: **≥ 85% recall on drives/loops, ≤ 1 false trigger per minute** while walking
   or idle.

### Step 1 — Android skeleton
- CameraX + PoseLandmarker in `LIVE_STREAM` mode, skeleton overlay, on-screen **actual FPS**
  and inference ms.

### Step 2 — Lock + trigger
- TargetLock + calibration screen.
- Port the tuned StrokeDetector from Python. Tick sound on each T₀, plus a stroke counter.

### Step 3 — Per-stroke capture + rally tracking
- FrameRing, StrokeRecord snapshots, metrics, rally-end detection.
- Debug screen: the last rally's strips stored on the device so you can eyeball the
  T₀ frames.

### Step 4 — Coach
- StripBuilder (crop to player, draw skeleton, labels), Gemini REST call, TextToSpeech.
- Rate limit, timeout (say nothing if the call fails), API key from `local.properties`
  for the prototype only.

### Step 5 — Field test
- A 30-minute session at the venue. Log FPS, triggers, false triggers, LLM latency and the
  cues. Review the logs against video.

**Rough timeline:** steps 1–4 ≈ 1–1.5 days. Steps 0 and 5 plus tuning ≈ 2–3 days.

---

## 6. Risks and fallbacks

| Risk | Fallback |
|---|---|
| Pose FPS too low on the target phone | Lite model, lower input resolution, turn off the preview. |
| Trigger misses or false-fires too often | Add a "load before apex" requirement, raise the threshold, or add a ball-sound check as a *secondary* confirmation in quiet venues only. |
| LLM cues are generic or wrong | Generate cues with rules from the metrics; use the LLM only to phrase them. |
| Cues are annoying | One cue per N rallies; repeat only when the same fault recurs. |
| Bystander privacy | Crop to the player, never store uploads, and say so on screen. |

---

## Sources

- MediaPipe Pose Landmarker guide — https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker
- BlazePose Lite/Full/Heavy trade-offs — https://blog.tensorflow.org/2021/05/high-fidelity-pose-tracking-with-mediapipe-blazepose-and-tfjs.html
- MediaPipe Holistic (tracker loses fast motion) — https://research.google/blog/mediapipe-holistic-simultaneous-face-hand-and-pose-prediction-on-device/
- Table Tennis AI Coach student project (z-noise, false triggers, overfitting) — https://cs1.txwes.edu/~jmmcdonald3/
- Tennis stroke biomechanics with MediaPipe world landmarks (arXiv 2606.15992) — https://arxiv.org/pdf/2606.15992
- Table-tennis stroke recognition with MediaPipe + Transformer — https://www.mdpi.com/2073-431X/13/12/332
- CameraX image analysis — https://developer.android.com/training/camerax/analyze
- AE FPS range limited by stream min frame duration (AOSP) — https://gerrit.omnirom.org/plugins/gitiles/android_frameworks_av/+/60d00de58e24e0f91d0193db592a6c41879735d7%5E%21
- Gemini models list — https://ai.google.dev/gemini-api/docs/models
- Gemini API changelog — https://ai.google.dev/gemini-api/docs/changelog
- Joint contributions to racket speed in TT forehands (JSSM) — https://www.jssm.org/jssm-17-330.xml-abst
