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
