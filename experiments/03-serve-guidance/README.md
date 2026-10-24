# Serve guidance: what to work on, from a serve video

Why a backspin serve is weak, worked out from what the spin meter measures, and checked on
simulated serve videos before it went into the app (spin meter 3.0, "What to work on").

## The physics

The paddle changes the ball's velocity by the contact impulse. With the impulse model for
inverted rubber (normal restitution falling with impact speed; tangential impulse proportional
to how fast the ball slides on the rubber, capped by friction; after Nakashima et al., as used
by Gossard 2026, arXiv 2604.11349):

- **Spin ÷ speed** (the spin's surface speed over the ball's speed) is 1.5 × cos β, β the
  angle the ball leaves at from the face. It doesn't depend on the rubber: it says how thin
  the contact was. Under 0.9 the ball was pushed.
- **Grip used**: the impulse's part along the face sets the spin (surface speed = 1.5 × that
  part, since I = 2/3 m r²); the rest pressed the ball into the rubber. Their ratio, from the
  spin and launch velocity minus toss velocity, reaches the rubber's friction (~1.2) when the
  ball slid. Then a faster swing adds nothing; pressing harder (a faster-falling toss, brushing
  slightly into the ball) does.
- Otherwise the rubber held, and the spin is set by the paddle's speed along the face.

Kinds: thick (pushed) / good (30 rev/s or more) / slip / slow, with "between two" near a line.

## Files (sim/)

- `contact.py`: the contact model; `hit()` gives a serve's outcome from paddle speed, brush
  angle, toss speed, rubber grip and face tilt.
- `render_serve.py`: renders a serve as a 240 fps side-on clip (marked ball, toss, paddle,
  table, one bounce) plus the truth as JSON.
- `make_batch.py OUT per_kind seed`: picks serves evenly over the four kinds, each with the
  face tilt that gives a playable launch (−10 to +25°), and renders them.
- `diagnose.py`: the rules and the coaching text (ported to `SpinMeter.coach` in the app;
  identical answers on all 80 test clips).
- `score_e2e.py DIR`: the engine's answers against the truth.
- `real_check.py NAME=clip.mp4 …`: the rules on real clips already run through the engine.
- `fit_real.py`, `robust*.py`: the earlier fits and noise studies that led here.

## Results

Engine run on rendered clips (spin within 0.1 rev/s; speed at the hit within about 6% after
the scale fixes; toss within about 0.15 m/s): of 80 serves, 55 read clearly and 53 got the
right finding (10 of them as "between two"). Most of the 25 without a reading had the black
half's pole facing the camera, so the marking hardly changed; the app says so.

Real clips: the weak backspin serve (H, 15.6 rev/s, spin ÷ speed 0.46) reads as pushed, which
matches what the video showed (upright face, contact near the handle); the strong ones read as
strong. The toss was rarely in view in those clips, so grip used is untested on real video.

Not modelled: air drag and lift during the flight (the simulated ball flies in a vacuum), so the
gravity-based scale check is one-sided (backspin only lifts) and capped at 15%.
