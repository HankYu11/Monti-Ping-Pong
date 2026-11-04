# Spin from the flight (physics), first test

Can the ball's path alone tell the spin, on the club's angled views where the engine's own
flight geometry failed? Offline test on 22 club serves (setups M, P, R, S: 0.5.4, 0.7.4, 0.7.9,
0.8.0 sessions), compared with the marked-ball readings.

1. `calib.py setups.json poses.json`: the camera from the table. The server's half (1.37 x 1.525 m),
   its centre line and the net's top tape (15.25 cm) fitted to the white lines (started from
   rough hand-picked points, then snapped to the white pixels): focal length, rotation, position.
   Lines fit to 1-2 px; field of view 68-71 degrees (the engine assumes 68); cameras 1.4-2.0 m
   beside the table near the net, 0.5-0.8 m above it. `table_fits.jpg`: the fitted lines.
2. `two.mjs` with the engine patched by `engine_eval.patch` (the measuring pass's ball positions,
   full size, per frame) on each capture from the desktop tool.
3. `fit3d.py poses.json club.jsonl out.json`: each measured stretch fitted in table coordinates:
   start position, velocity, backspin and sidespin, with gravity, drag (0.126/m) and the Magnus
   push at the strength found on the home clips (0.0173 per rev/s per m/s, independent of these),
   to the ball's position (1-2 px) and size (its distance) in every frame. Gyro spin (along the
   flight) doesn't bend the path and can't be seen this way.

Results (clear stretches of 20+ frames, 17):

| | backspin part: sign | backspin part: median error | sidespin part: median error |
|---|---|---|---|
| half-size search positions | 12/15 | 16.9 rev/s | 24.6 rev/s |
| full-size positions | 14/15 | 10.7 rev/s | 35.2 rev/s |

The engine's own flight geometry (depth from the ball's size only, no table) on these angled views
was off by hundreds of percent. Sidespin turns the ball toward or away from a camera beside the
table: seen only through the ball's size, too weak. Next: fit the stretches before and after the
bounce together, the bounce on the table's plane (z = ball radius) pinning the depth; then the
whole flight in one piece, with a bounce model.
