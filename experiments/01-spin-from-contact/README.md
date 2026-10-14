# Experiment 01 — Can a phone camera tell the spin from the contact?

**Question:** from a phone's slow-motion video of a serve, can we predict how much
backspin the ball gets, using only what happens at contact: racket angle, racket speed and
direction, where the racket meets the ball, and the ball's speed off the racket?

If yes, the coach can explain **why** a serve had no spin ("your racket was too flat",
"you pushed instead of brushing"). If no, the coach can only report the result.

This is an offline experiment: phone video, a laptop and Python. No app yet.

---

## 1. Why the serve is a clean test case

- **The toss has no spin** (the rules require tossing without spin), and the ball is
  almost still when the racket meets it. So all the spin comes from this one contact,
  with no incoming spin or speed to confuse things.
- **Backspin spins around a horizontal axis, sideways to the flight.** A camera at the
  side looks straight down that axis, so the rotation is seen flat, in the image plane.
  That's the easiest possible view for measuring it.
- **Physics gives a starting hypothesis.** Spin comes from friction between rubber and
  ball, driven by the racket's speed **along** its face (tangential speed), not into it.
  For a hollow ball that starts sliding and ends rolling, a simple model gives
  `spin × radius ≈ 0.6 × tangential racket speed`. Real inverted rubber deforms and
  springs back, so the true factor will differ. The experiment fits it from data.
  Robotics papers model the racket–ball bounce with exactly these tangential and normal
  speeds.

**Hypothesis:** spin is mostly explained by the tangential racket speed at contact, which
depends on racket face angle × swing direction × racket speed. "Pushing" (speed into the
face) adds ball speed but little spin.

---

## 2. Ground truth: measuring the real spin

You need to know the real spin of each serve, or there's nothing to compare against.

**Method: marked ball + 240 FPS slow motion.**
- Draw a **thick black line all the way around** a ball, plus **one dot** beside it so you
  can tell which way it turns. Make several marked balls.
- After contact, track the line's angle over 4–8 frames → degrees per frame → revolutions
  per second (rps).
- **Limit:** at 240 FPS, anything above **120 rps** (half a turn per frame) becomes
  ambiguous. That's fine here: serves by top players at the 2009 World Championships
  measured roughly 28–63 rps, and beginners spin much less. If needed, a ball with several
  dots works at higher spin: one study measured up to 175 rps at 350 FPS that way.
- Professional systems do this with the ball's printed logo. The T-League system reports
  a median error under 1 rps. Motion blur is the main enemy.

**Second, independent check (free from the same video):** backspin bends the ball's
flight (it falls slower than gravity alone would make it), and it slows the ball's forward
speed when it bounces. A robot table-tennis study found estimating spin from the
trajectory (Magnus effect) more accurate than reading the logo. If this works, the app may
not need the marked ball at all.

---

## 3. Setup

| Item | Setting |
|---|---|
| Camera | Phone slow-motion **240 FPS** (record the original high-FPS file, not the re-encoded slow-motion one, if the phone allows) |
| Position | Side-on to the server, **perpendicular to the serve direction**, ~1.5 m away, lens at table height. Frame only the contact zone + first ~1 m of flight, so the ball and racket are as large as possible. |
| Light | **As bright as possible** (daylight or LED floodlights). This shortens the exposure and reduces blur on the ball and racket. Main risk of the whole experiment. |
| Background | Plain and dark if possible (a dark cloth behind the table), so the white ball and the racket edge stand out. |
| Serve direction | Straight down the table, parallel to the side line, so the swing stays in the plane the camera sees. |
| Reference scale | A ruler or a printed 10 cm square taped at the contact distance, for cm/pixel. |

---

## 4. Protocol (~80 serves, one or two sessions)

Deliberately **vary** the serves. A regression needs a wide range, not 80 similar serves.

| Block | Serves | Instruction |
|---|---|---|
| A | 20 | Flat push: racket nearly vertical, push the ball forward (little spin expected) |
| B | 20 | Light brush: racket half open, brush under the ball gently |
| C | 20 | Heavy brush: racket very open, fast brush under the ball |
| D | 10 | Mixed: whatever comes naturally |
| E | 10 | Controls: no-spin "dead" ball and topspin serves |

Use **2–3 people**, ideally one who can already serve real backspin (a club player is
fine). Note the player and block for each clip.

---

## 5. What to measure per serve

**Stage 1 — by hand.** Answers "is the information in the video at all?" Write a small
Python/OpenCV click tool that steps through frames.

| Measurement | How (clicks) |
|---|---|
| **Spin (ground truth)** | Line angle on the ball in 4–8 frames after contact → rps |
| **Contact frame** | First frame where the ball moves with the racket |
| **Racket face angle** | Two clicks on the blade edge in frames −3 to +2 around contact |
| **Racket velocity** | Blade centre position across those frames → speed and direction |
| **Contact point** | Ball centre relative to the blade line: how far under the ball, and where on the blade |
| **Ball out** | Ball centre in 5 frames after contact → speed and launch angle |
| **Wrist (automatic)** | MediaPipe Pose wrist speed and direction at contact |

**Derived features**
- `v_tangential` = racket velocity along the face; `v_normal` = into the face
- `brush_ratio` = `v_tangential / |v_racket|` (1 = pure brush, 0 = pure push)
- `ball_speed_out`, `launch_angle`
- `wrist_speed` (pose only)

**Stage 2 — automatic.** Answers "can an app do it?" Replace the clicks with the ball
tracker (colour + motion blob) and an automatic racket detector (red/black rubber colour
segmentation, then fit a line to the blade). Compare with the hand measurements.

---

## 6. Analysis: four questions and how to decide

| # | Question | How | "Yes" if |
|---|---|---|---|
| Q1 | Is the spin predictable from the contact at all? | Fit spin vs `v_tangential` (and a small model with all hand-measured features). Test on a player left out of fitting. | Correlation **r ≥ 0.8**, or **≥ 80%** correct on 3 classes (none / light / heavy) |
| Q2 | Which features matter? Is pose enough? | Compare models: wrist-only vs racket features vs ball-out features | Shows whether racket tracking is required or the wrist proxy is enough |
| Q3 | Can it be automated? | Stage 2 features vs the hand-measured ones | Automatic features keep most of the Q1 accuracy |
| Q4 | What frame rate is needed? | Drop frames: 240 → 120 → 60 FPS, repeat Q1–Q3 | Tells the app what camera mode to use |
| Q5 (bonus) | Can the ball's flight alone give the spin? | Flight curvature and the speed lost at the bounce vs ground truth | If good, the app can always report spin, even when racket tracking fails |

### What each outcome means for the product

| Result | Next step |
|---|---|
| Q1 yes, Q3 yes at 60–120 FPS | Build the serve coach with "why" cues from racket angle and brush ratio |
| Q1 yes, Q3 no | The information exists, but automatic racket tracking needs work (for example a small trained racket detector). Keep the hand tool for coaching research meanwhile. |
| Q1 no, Q5 yes | The app reports spin from the ball's flight. Cues come from rules, a coach's input and the LLM looking at contact frames. |
| Q1 no, Q5 no | Re-check the ground truth (blur? lighting?). If it can't be measured cleanly, fall back to the simple "did it come back / bounce twice" serve test. |

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| Motion blur hides the line on the ball and the racket edge | More light, slower practice serves first, dark background |
| Contact (~1 ms) falls between frames | Expected. Find contact by intersecting the racket path and ball path, not from one frame. |
| Racket face not edge-on to the camera | Serve straight down the table; keep the camera perpendicular. Drop clips where the blade isn't visible as a thin shape. |
| Too few serves for a model | Keep models tiny (1–3 features). Add more serves only after Q1 looks promising. |
| Phone saves only the re-encoded 30 FPS slow-motion file | Timestamps are scaled, but every frame is still there. Just treat each frame as 1/240 s. |

---

## 8. Deliverables

1. `clips/` — raw serve videos (not committed if large; keep a list with player and block).
2. `annotate.py` — frame-stepping click tool that writes one JSON per serve.
3. `features.py` — computes derived features from the annotations.
4. `analysis.ipynb` — Q1–Q5 plots and numbers.
5. A short `RESULTS.md` with the answer and the decision from section 6.

**Effort:** about 1 day to record and annotate ~80 serves by hand, plus about 1 day for
the tools and analysis. Stage 2 (automatic) adds about 1–2 days, only if Q1 says yes.

---

## Sources

- Tamaki, Yamagata & Hashizume (2024), serve spin from the ball logo in T-League matches — https://reference-global.com/article/10.2478/ijcss-2024-0003
- Serve spin rates of 2009 World Championship quarter-finalists (Yoshida thesis abstract) — https://jair.repo.nii.ac.jp/record/2003238/files/SDO0012-engabs.pdf
- Spin detection in robotic table tennis (logo at 380 Hz vs Magnus trajectory method) — https://cs.paperswithcode.com/paper/spin-detection-in-robotic-table-tennis
- Dotted-ball spin measurement up to 175 rps at 350 FPS — https://arxiv.org/abs/2303.03879v1
- Spin estimation with an event camera (CVPRW 2024) — https://openaccess.thecvf.com/content/CVPR2024W/CVsports/papers/Gossard_Table_Tennis_Ball_Spin_Estimation_with_an_Event_Camera_CVPRW_2024_paper.pdf
- Racket–ball bounce dynamics across rubbers (tangential/normal friction model) — https://arxiv.org/pdf/2604.11349
- Robotic table-tennis serves, velocity-dependent rubber model — https://arxiv.org/pdf/2608.08157
- ITTF service rules (toss without spin) — https://www.tabletennis365.com/Aberdeen/News/2017/11/How_to_serve_correctly_according_to_ITTF/18700
- CameraX 1.5 high-speed capture — https://developer.android.com/blog/posts/high-speed-capture-and-slow-motion-video-with-camera-x-1-5
