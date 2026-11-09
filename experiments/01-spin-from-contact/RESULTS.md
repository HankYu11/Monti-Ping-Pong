# Results: a hand-measured check (Nov 2026)

The experiment as planned (§4: ~80 serves recorded for it) was never run: the spin meter
(experiment 02) measured spin directly from marked balls, accurate to about 5%, and the contact
physics went into the coaching instead (experiment 03 works backwards from spin and launch).
This is a quick check on serves already recorded, to see whether spin could come from the
contact for plain balls.

## The relation

For a ball with no spin before the hit, mechanics alone (no rubber model) gives: the spin's
surface speed is 1.5 times the ball's change of velocity along the paddle face, so

    spin (rev/s) = 1.5 x |launch - toss| x cos(beta) / (2 pi x 0.02 m)

with beta the angle between that change of velocity and the face. The engine already measures
the launch (and often the toss) on any ball; the unknown is the face's angle at contact.

How much the angle matters, from the marked readings of 30 clear serves (`beta` worked back):
brushed serves leave 15-50 degrees off the face, where 5 degrees of error in the face angle is
2-4 rev/s (5-10%); pushed serves leave 70-80 degrees off, where 5 degrees is a factor of two.
8 of 30 read "more spin than the launch allows" (ratio over 1.5) without the toss: the toss
wasn't measured on them, or the launch speed reads low.

## The check

15 side-on home serves with clear marked readings (T, U, V, C, N; the camera in front of the
server, the ball crossing the picture). Frames around each contact were cut out, enlarged with
a pixel grid, and the paddle's long edge read by eye at contact.

| Serve | Face angle read (along the flight) | Predicted | Marked | |
|---|---|---|---|---|
| V3 | 5 deg (+-5) | 42-45 rev/s | 45.3 | within 0-7% |
| T1 | 7 deg (+-5) | 34-37 rev/s | 28.4 | 20-30% high |
| U2 | unreadable: black rubber on a black shirt, the hand over it | | 43.8 | |
| T2, T4 | unreadable: the paddle face-on to the camera (its outline doesn't show the tilt along the flight), 5-10 px of blur | | 32.7, 38.6 | |
| the rest | the contact at the frame's edge or behind the hand | | | |

## What it says

- The relation is sound mechanics, and where the angle could be read it is in the right range
  (one serve within 7%, one 25% high).
- **Measuring the paddle angle is the blocker, not the physics.** In these clips (and the club
  clips, angled further) the paddle is seen near face-on, often against dark clothes, partly
  behind the hand, blurred 2-6 cm a frame, and the 1 ms contact falls between frames. Two of 15
  serves gave a usable angle by eye; an automatic detector would do no better from this view.
- What would make it measurable: a camera that sees the paddle edge-on (behind or beside the
  server, along the blade), or a mark on the blade (coloured tape on its edge), or a second
  phone. Each is a new setup to film and test; nothing to build in the app before that.

Decision (for now): keep the marked-ball measurement; the contact physics stays in the coaching
(why a serve had little spin). Revisit with clips filmed for it (§3-§4 above), a taped blade
edge and a camera along the blade, if plain-ball readings become a priority.

## Follow-up: the contact from the arm (Nov 2026)

The question: we can't see the paddle at contact, but we see the whole arm. Can the arm give the
contact frame and the paddle angle?

**Method** (`handpose/`): MediaPipe Pose (heavy) on the frames before each flight. Per frame, the
serving hand's wrist, index and pinky knuckles in 3D, which give the palm's plane. The contact frame
is where the serving wrist moves fastest, 12-40 frames before the flight track starts. The face
angle is the palm's angle to the ball's launch plus a fixed grip offset (the paddle sits tilted in
the hand). Then the spin comes from the relation above. MediaPipe's hand model found no hand
here: a hand holding a paddle doesn't look like a hand to it. The body model does see the wrist
and knuckles.

**Contact frame.** On the 5 serves read by eye, the wrist-speed peak landed on the same frame for
2, 1-2 frames off for 1 and 5 frames (20 ms) early for 2. So the arm finds the moment, even
when the contact itself is hidden or between frames.

**Tuned on the 5 eye-read serves** (T1, T2, T4, U2, V3): the palm sat 7-12 degrees flatter than the
face the spin implies (mean 9.4 degrees). Leaving each serve out in turn gave the spin within 1-5%
with the contact read by eye and 1-9% with it found automatically. A fixed face angle with no hand
gave 2-27%.

**Blind, on 8 more serves** (U1, U3, V1, C1, N1, N4, N12, N15; all automatic, offset 9.7 degrees):

| Serve | Predicted | Marked | Error |
|---|---|---|---|
| U1 | 39.8 | 35.5 | +12% |
| U3 | 38.5 | 26.7 | +44% |
| V1 | 36.6 | 45.7 | -20% |
| C1 | 27.9 | 28.3 | -1% |
| N1 | 19.4 | 31.4 | -38% |
| N4 | 38.7 | 45.2 | -15% |
| N12 | 20.9 | 30.9 | -32% |
| N15 | 42.7 | 47.1 | -9% |

Median error 17%. A fixed face angle with no hand gave the same median (17%). The hand helped on
the worst case (U3: +145% without it) and hurt on N1. T3 and V2 couldn't be tried: their contact
is before the clip starts.

**What it says**
- The arm does give the contact frame, within about 5 frames, with no paddle in view.
- The palm isn't the paddle. The palm's plane turns 10-20 degrees within 2 frames of contact
  (the wrist snaps), so a frame off in timing is a big error in the angle. The wrist can also
  bend the blade away from the palm, and that varies by serve. The tuned result (1-9%) didn't
  hold on new serves (17%), so the palm angle is not yet a measurement.
- An AI agent choosing between possibilities wouldn't fix this. The missing piece is a
  measurement of the blade itself: a model trained to find the paddle's edge, or a taped blade
  edge. The arm's contact frame would tell either one which frame to look at.
- Still useful now: the contact frame from the wrist (for coaching: the "contact" moment in the
  replay), and the palm angle as a rough "open/closed face" cue, not as a spin number.
