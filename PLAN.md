# Monti Ping Pong — Backspin Serve Coach (first prototype)

A phone on a tripod beside the table watches a beginner practise the **forehand backspin
serve**. After every serve it tells the player **what happened**: did it go in, how low,
and was there real backspin. Every few serves it gives **one technique cue** about the
most common fault.

The rally-stroke (forehand loop) plan is postponed to
[`docs/later-forehand-plan.md`](docs/later-forehand-plan.md). Its findings on pose FPS,
target lock, coaching cadence and "no pro player needed" still apply here.

---

## 1. Why the serve is a better first step

| | Forehand loop (old plan) | Backspin serve (this plan) |
|---|---|---|
| Timing | Continuous rally, opponent decides timing | One self-paced action, then a natural pause to pick up the next ball |
| Detecting the stroke | Hard (wrist-speed trigger, false triggers) | Easy: the toss (ball rising from the free hand) starts each serve |
| Did it work? | Hard to judge from the ball | **Visible from the ball's flight**: bounces, height over the net, and what the ball does after landing |
| Feedback latency | Must be fast, between rallies | 2–3 s is fine; the player is picking up the next ball |
| Typical setting | Needs a partner | **Practising alone with a basket of balls**, so usually one person in view |
| Rules to check | None | Toss rules (16 cm, open palm, behind end line) are concrete |

This targets exactly the beginner problem you described: **they can't tell whether the
serve had backspin or why it failed.** The app can fill that gap mostly from the ball's
flight, which is more reliable than judging body pose.

---

## 2. The honest catch: spin and the racket face aren't directly visible

- **Spin can't be seen** on a 40 mm ball at phone frame rates. It has to be **inferred
  from the flight**: backspin slows the ball's forward speed when it bounces, and strong
  backspin makes it stop or come back toward the net.
- **The brushing contact and racket face angle are what make backspin**, and MediaPipe
  Pose tracks the wrist, not the racket. Contact lasts a few milliseconds and blurs at
  30–60 FPS.

So v1 judges the **outcome** reliably and the **cause** only roughly (contact height,
toss, wrist path, plus a slow-motion contact frame shown to the LLM). That's still a big
help for a beginner: knowing "that one had no spin" after each serve is feedback they
don't get at all today.

A spin-quality note from players: a ball that comes back isn't automatically a great
serve. The best backspin serve also stays **low** over the net. The app should reward
"low + short + spin", not just "came back".

---

## 3. What the app measures

### Camera setup
- Tripod **beside the table, level with the net**, about 3 m back, lens just above table
  height, landscape. From there the camera sees the server side-on **and** the whole
  table, so the ball's path along the table runs left-to-right in the image.
- One-time **table calibration**: tap the 4 visible table corners (or the near edge ends
  plus the net top). The table's known size (2.74 m long, net 15.25 cm high) gives a
  scale in cm and the position of the end lines and net.
- Scale is rough (±20–25%), because the ball can be nearer or farther than the table
  edge. That's fine for coaching.

### Per serve: outcome (from ball tracking)
The ball is tracked with classic computer vision first: frame differencing + white/orange
colour + small round blob + linking positions into a trajectory. This is cheap enough for
the phone. TTNet (a published table-tennis network) can detect the ball and bounces very
accurately on 120 FPS video, but it's built for a desktop GPU; keep it as a fallback.

| Measure | How |
|---|---|
| **Toss height** | Ball's rise above the free hand before it starts falling (rule: at least 16 cm) |
| **Contact height** | Ball height above the table at the moment its path changes direction |
| **Bounce 1 (own side)** | Downward-to-upward turn at table height on the server's half; position along the table |
| **Net clearance** | Ball height when it crosses the net line |
| **Bounce 2 (far side)** | Position on the far half |
| **Spin indicator** | Forward speed just **after** bounce 2 divided by forward speed just **before** it. Lower means more backspin; below zero means the ball came back. Compare against the player's own recent serves, since the exact numbers need tuning with real data. |
| **Short or long** | Does the ball bounce a second time on the far half (short), or leave past the end line? |

**Outcome label**, spoken after each serve in 1–3 words:
`Net` · `Missed own side` · `Too high` · `Long, no spin` · `Good, low with spin` ·
`Came back!`

### Per serve: technique hints (from pose + frames)
MediaPipe Pose on a few chosen frames only (toss, just before contact, contact,
follow-through), so the phone isn't running pose on every frame.

- **Toss rule:** free hand open and flat, ball behind the end line and above table height.
- **Contact height** (from the ball track): high contact usually means a high, long serve.
- **Wrist path at contact:** the direction the wrist moves. A forward-and-down chop
  suggests hitting through; a flatter forward brush underneath is what produces backspin.
  Treat this as a rough proxy.
- **Body:** knee bend, and whether the player stands at the end line.

### Fault → cause table (starting draft, check with a coach in Step 0b)

| What happened | Likely causes to cue |
|---|---|
| Into the net | Racket face too open, too little forward motion, contact too far under the ball |
| Too high / long | Hitting the back of the ball instead of brushing underneath it; racket too flat; contact too high above the table |
| In, but no spin | Pushing the ball forward instead of a fine brushing contact; no wrist acceleration at contact |
| Bounce 1 lands near the net | Usually fine for a short serve; if too close it may not clear the net |
| Toss illegal | Toss too low, or not from a flat open palm |

These match common coaching advice: heavy backspin comes from contacting the bottom of
the ball with a fine brushing contact, and a common beginner error is trying to push the
ball forward instead of brushing it.

---

## 4. Feedback cadence

- **After every serve:** the short outcome label only. This is the "did it work?" answer
  beginners are missing. Allow a setting to switch it to a tone, or off.
- **Every 5–8 serves:** **one** technique cue about the most frequent fault in that
  block. Keep the same focus until it improves, and say so when it does
  ("More spin on those last five"). Stay quiet if nothing stands out.
- These numbers are starting values. Replace them with what you see a real coach do
  (Step 0b, and section 4 of the forehand plan).

---

## 5. Architecture (Android / Kotlin)

```
CameraX ImageAnalysis, 60 fps target (see note below)
        │
        ├─► BallTracker (downscaled frames, cheap CV) ─► Trajectory
        │                                                 │
        ├─► FrameRing (~2 s of small frames)              │
        ▼                                                 ▼
ServeSegmenter: toss detected → contact → bounces → ball gone/stopped
        │  ServeRecord {trajectory, key frames}
        ▼
PoseLandmarker (IMAGE mode, only on ~4 key frames)
        ▼
ServeAnalyzer: outcome label + measures + fault flags
        ├─► TextToSpeech: outcome label (every serve)
        ▼
CoachPolicy (every 5–8 serves, focus fault)
        ▼
Rules → cue text (optionally Gemini with contact frames + measures to phrase / check it)
        ▼
TextToSpeech → earbuds or phone speaker
```

**Frame rate note:** CameraX 1.5 can record at 120/240 FPS for slow motion, but
high-speed sessions are meant for recording, so live per-frame analysis at those rates is
unlikely. Plan v1 on **60 FPS live analysis**. In Step 0, record at 240 FPS and drop
frames to simulate 120/60/30 FPS; that tells you exactly what frame rate the tracker
needs before writing any Android code.

**Target lock:** usually only one player is in view, but balls from neighbouring tables
can fly through. Only accept trajectories that start with a toss from the locked
player's free hand, and filter by table area.

**LLM:** the rules handle most of the coaching here. Gemini (a current Flash model; check
the models page) is optional, used to phrase cues naturally and to look at the contact
frames for things the rules can't see, like the racket face angle.

---

## 6. Build plan

### Step 0 — Record serves (half a day)
1. Record **50–100 forehand backspin serves** at 240 FPS (slow motion) from the tripod
   position: yours, plus ~20 from a decent club player (not necessarily a pro).
2. Label each by eye from the slow motion: net / long / short / came back, and roughly
   "spin: none / some / lots".

### Step 0b — Ask a coach (half a day, can be in parallel)
- Have a coach watch 20–30 serves (live or on video) and say what they'd tell the player
  and when. Use this to fix the fault → cause table, the cue wording and the cadence.

### Step 1 — Offline prototype in Python (1–2 days)
- Table calibration, ball tracker, bounce detection, outcome label, spin indicator.
- Test at 240/120/60/30 FPS by dropping frames.
- Target: **outcome label agrees with your eye labels on ≥ 90% of serves** at 60 FPS, and
  the spin indicator clearly separates "none" from "lots".
- Add pose on key frames and the fault rules; print the cue each serve would get. Show it
  to the coach.
- Even this offline version is already useful: record a practice session, run the
  script, read the report.

### Step 2 — Android app (1–2 days)
- CameraX 60 FPS analysis, table calibration screen, BallTracker, ServeSegmenter.
- Speak the outcome label after each serve.

### Step 3 — Technique cues (about 1 day)
- Pose on key frames, fault flags, CoachPolicy, rule-based cues, optional Gemini call.

### Step 4 — Field test
- A 20–30 minute serve practice. Log every serve (outcome, measures, cue) and keep the
  clips. Compare with your own labels and, if possible, the coach's opinion.

**Rough timeline:** about 4–6 days in total. The offline step tells you early whether ball
tracking from one phone is good enough, before any Android work.

---

## 7. Risks and fallbacks

| Risk | Fallback |
|---|---|
| Ball too small or blurred at 60 FPS | Move the camera closer, add more light, or use the Step 0 results to pick a higher FPS (lower resolution) mode. |
| Spin indicator too noisy | Rely on simpler signals: "came back", "second bounce on the table" (short), net clearance. |
| White ball lost against a white wall or lines | Use orange balls for practice, or change the camera angle. |
| Cause rules wrong | The outcome label still works on its own; show the slow-motion contact clip instead of a cue. |
| Neighbouring balls confuse the tracker | Require the trajectory to start with a toss from the locked player. |

---

## 8. Later

1. Other serves (sidespin, topspin, backhand serves): same tracker, new rules.
2. Receive and push (the next discrete skills).
3. Rally strokes: [`docs/later-forehand-plan.md`](docs/later-forehand-plan.md).

---

## Sources

- ITTF service rules summary (toss ≥ 16 cm, open palm, behind end line) — https://www.tabletennis365.com/Aberdeen/News/2017/11/How_to_serve_correctly_according_to_ITTF/18700
- PingSkills: backspin serve contact and the "come back off the floor" drill — https://pingskills.com/table-tennis-forum/my-topspin-and-my-backspin-serve
- PingSkills: how to generate backspin — https://www.pingskills.com/table-tennis-forum/how-to-generate-a-backspin
- TableTennisDaily: brushing vs hitting through, contact point — https://www.tabletennisdaily.com/forum/goto/post?id=169591
- TableTennisDaily: ghost serve, low vs "comes back" — https://www.tabletennisdaily.com/forum/goto/post?id=167989
- TTNet: real-time ball detection and bounce spotting (CVPRW 2020) — https://openaccess.thecvf.com/content_CVPRW_2020/html/w53/Voeikov_TTNet_Real-Time_Temporal_and_Spatial_Video_Analysis_of_Table_Tennis_CVPRW_2020_paper.html
- CameraX 1.5 high-speed and slow-motion capture — https://developer.android.com/blog/posts/high-speed-capture-and-slow-motion-video-with-camera-x-1-5
- MediaPipe Pose Landmarker — https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker
- Gemini models — https://ai.google.dev/gemini-api/docs/models
