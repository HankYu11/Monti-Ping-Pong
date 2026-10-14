# Later phase: forehand rally coach

> **Status:** postponed. The first prototype teaches the backspin serve; see
> [`../PLAN.md`](../PLAN.md). This document covers rally strokes (forehand drive/loop)
> for after the serve coach works. Its findings on pose FPS, target lock, coaching cadence
> and "no pro player needed" still apply to the serve coach.

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
| 8 | Torso coil and hip rotation | Rotation is hard to measure with one camera and depends heavily on where the camera sits. | Fix the camera placement (section 6) and measure **2D** angles from that view: knee flexion, elbow–torso gap, follow-through wrist height relative to the nose. Label them as rough. |
| 9 | `numPoses = 1` plus a size filter | With `numPoses = 1` the detector can **jump** to whoever is most salient. You can't filter out a person you never received. | Use **`numPoses = 2–3`**. Each frame, keep the pose closest to the previous locked pose (centre distance plus torso-height similarity). Reject poses under 50% of the calibrated torso height. Lock onto the player with a tap or auto-centre during calibration. |
| 10 | **Gemini 2.5 Flash**, Live WebSocket | As of Oct 2026, 2.5 models are **legacy with limited access**. The Live API is unnecessary here: you send one request per coaching moment. | One **REST** call per coaching moment to a current Flash model (the docs list `gemini-3.8-flash` as stable; check the models page before coding). Speak the result with Android **`TextToSpeech`**. How often to speak is decided by the cadence rules in section 4. |
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
        ▼
CoachPolicy (section 4: decides WHEN to speak and WHICH fault to focus on)
        │  at a coaching moment: pick the stroke that best shows the focus fault
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

**Fault flags** per stroke: each metric is compared with a threshold from coaching rules
(for example, knee angle > 160° means "legs too straight"). Each flag is a named fault
category, not a single overall score. CoachPolicy counts flags across the round and
picks the stroke that shows the focus fault most clearly for the 3-panel image.

**Coach prompt** (system):
> You are a table-tennis coach speaking into the player's earbuds during a short break.
> You get the current focus fault, how often it appeared in this round compared with the
> last round, rough pose metrics, and a 3-panel image (load, contact, follow-through) of
> one representative forehand. Give ONE physical cue in under 12 words about the focus
> fault only. If it improved, say so briefly. No greetings. Prefer body feel ("sink into
> your right knee before swinging"). If the image is unclear, say "Keep going."

---

## 4. Coaching cadence: copy the human coach

The coach should **not** speak after every rally. A human coach watches several shots,
picks one thing, says it during a natural break, then watches whether it changes. Motor
learning research points the same way: feedback on every attempt can make learners rely
on it and over-correct (the "guidance hypothesis"). The evidence is mixed on *how* much to
reduce it, so treat the numbers below as starting values to replace with what you observe
from a real coach (Step 0b).

**Starting rules for CoachPolicy:**
- **Round** = a block of strokes, by default 10–15 detected forehands or about 60–90 s of
  play. Speak only at the end of a round, during a pause (rally ended and no stroke for 3 s).
- **One focus at a time.** Pick the fault that appeared in the most strokes this round
  (minimum, say, 40% of strokes). Keep the same focus for following rounds until it drops
  below a threshold or 3 rounds pass, then move on.
- **Silence is allowed.** If no fault is frequent, say nothing, or give occasional praise.
- **Confirm improvement.** When the focus fault rate drops noticeably, say so
  ("Better knee bend that round"). Coaches do this often, and it tells the player what to keep.
- **Priority order** when several faults are frequent: fix the base first (footwork and
  stance, then legs, then body rotation, then arm, then follow-through). Confirm the order
  with the coach in Step 0b.

These rules also cut LLM calls to about one per minute or less.

**Learning from a human coach (Step 0b):** record 1–2 sessions where a coach trains a
player (you can be the player). Put a phone recording video plus audio on the side-on spot.
Afterwards, transcribe the coach's speech (any speech-to-text works) and tag each
remark with:

| Field | Example |
|---|---|
| Time, and which stroke(s) it refers to | 03:42, strokes 31–38 |
| Strokes since the previous remark | 12 |
| When it was said | Ball pickup / mid-rally / between drills |
| Fault category | Legs too straight |
| Exact words | "Lower, sit down into it" |
| Type | Correction / praise / demonstration |

This table gives you the real cadence, the fault categories a coach actually uses, their
priority, and the wording style. Use the wording as examples in the LLM prompt.

---

## 5. Do you need a professional player as a model?

**No.** Nothing in this design is trained by imitating a reference player:

- **Pose estimation** is MediaPipe's pre-trained model, so no training is needed.
- **Stroke detection** is calibrated on the person using the app (5 practice swings).
- **Fault rules** come from standard coaching knowledge (bend your knees, keep the elbow
  near the body, finish high on a loop), not from measuring a pro.
- **Progress** is measured against the player's own earlier rounds.
- **The LLM** already carries general table-tennis coaching knowledge.

What you need instead:

1. **A coach, not a pro player.** A club coach's judgement ("that stroke had a straight
   leg") is the ground truth for whether the fault flags are right. Even a strong amateur
   who knows the textbook form can help label clips.
2. **Players of different levels**, especially beginners and intermediates, who are the
   target users. A pro's stroke is shorter, faster and more compact, so tuning on a pro
   would make the trigger and thresholds *worse* for your users. A small study of a
   ping pong pose-based coach found it overfit to its recorded players and broke on new ones.
3. **About 20–50 labelled strokes** to check each fault rule. Have the coach mark
   "fault / no fault" on clips, then check how often the rule agrees with the coach.

A pro reference would only matter if you later want a "how close are you to ideal form"
score. Public pro training videos could give rough ranges for that, but camera angles
won't match yours. It isn't needed for this prototype.

---

## 6. Camera setup (part of the product, not an afterthought)

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

### Step 0b — Observe a human coach (half a day)
- Record 1–2 coaching sessions and build the remark table from section 4.
- Have the coach label 20–50 strokes as fault / no fault for each fault category.
- Use the result to set the round length, fault priority, thresholds and cue wording.

### Step 1 — Android skeleton
- CameraX + PoseLandmarker in `LIVE_STREAM` mode, skeleton overlay, on-screen **actual FPS**
  and inference ms.

### Step 2 — Lock + trigger
- TargetLock + calibration screen.
- Port the tuned StrokeDetector from Python. Tick sound on each T₀, plus a stroke counter.

### Step 3 — Per-stroke capture + rally tracking
- FrameRing, StrokeRecord snapshots, metrics, fault flags, rally-end detection.
- Debug screen: the last rally's strips stored on the device so you can eyeball the
  T₀ frames.

### Step 4 — Coach
- CoachPolicy (rounds, focus fault, praise on improvement).
- StripBuilder (crop to player, draw skeleton, labels), Gemini REST call, TextToSpeech.
- Timeout (say nothing if the call fails), API key from `local.properties`
  for the prototype only.

### Step 5 — Field test
- A 30-minute session at the venue. Log FPS, triggers, false triggers, LLM latency and the
  cues. Review the logs against video.
- If possible, have the coach watch alongside and note where they would have said
  something different, or nothing at all.

**Rough timeline:** steps 1–4 ≈ 1–1.5 days. Steps 0, 0b and 5 plus tuning ≈ 2.5–3.5 days.

---

## 7. Risks and fallbacks

| Risk | Fallback |
|---|---|
| Pose FPS too low on the target phone | Lite model, lower input resolution, turn off the preview. |
| Trigger misses or false-fires too often | Add a "load before apex" requirement, raise the threshold, or add a ball-sound check as a *secondary* confirmation in quiet venues only. |
| LLM cues are generic or wrong | Generate cues with rules from the metrics; use the LLM only to phrase them. |
| Cues are annoying | Round-based cadence, one focus fault, silence when nothing is frequent (section 4). |
| Fault rules disagree with the coach | Re-tune the thresholds on coach-labelled clips; drop rules that can't reach agreement from the side-on view. |
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
- Reduced feedback frequency and the guidance hypothesis (2024) — https://pmc.ncbi.nlm.nih.gov/articles/PMC10933749/
- Bandwidth feedback in a complex gymnastics skill (2013) — https://johk.pl/wp-content/uploads/2023/02/10.2478_hukin-2013-0039.pdf
- Joint contributions to racket speed in TT forehands (JSSM) — https://www.jssm.org/jssm-17-330.xml-abst
