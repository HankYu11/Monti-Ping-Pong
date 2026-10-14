# Tools

## `balltrack.py` — ball tracker for a fixed camera

Finds the ball in every frame, links it into trajectories, labels each trajectory
(toss, flight, other), groups them into serves, and detects bounces. Classic computer
vision only (OpenCV), so it runs on a laptop CPU faster than real time for 480p video and
can later be ported to the phone.

```bash
python3 -m venv .venv && .venv/bin/pip install -r experiments/01-spin-from-contact/tools/requirements.txt
.venv/bin/python experiments/01-spin-from-contact/tools/balltrack.py VIDEO.mp4 --out out/ \
    --serve-dir +x            # direction a serve travels in the image for this camera
    [--start 29 --end 39]     # seconds
    [--fps 240]               # real capture rate, for slow-motion files with scaled timestamps
    [--table table.json]      # optional table corners in pixels, marks bounces on/off the table
```

Outputs in `out/`: `tracks.csv`, `serves.csv`, `bounces.csv`, `candidates.csv`,
`summary.json`, `tracks.png`, and `overlay.mp4` (skip with `--no-video`).

### How it works

1. **Moving pixels only:** background subtraction (MOG2), since the camera is fixed.
2. **Ball colour:** white (bright, low saturation) or orange.
3. **Ball shape:** small compact blob; motion streaks up to 4:1 are allowed.
4. **Not the player:** blobs that are part of a large moving blob (body, arm) are dropped,
   unless they are unmistakably the ball (very white, ≤ 300 px, high contrast). This keeps
   the ball on short serves, when it stays close to the player's hand.
5. **Brighter than surroundings:** removes grey shirt and wall patches.
6. **Linking:** nearest neighbour to a constant-velocity prediction, up to 3 missed frames.
7. **Camera cuts:** if a large share of the image changes at once, all tracks end and the
   background model re-settles.
8. **Labels:** *toss* = mostly vertical rise; *flight* = mostly sideways travel.
9. **Serves:** flight pieces less than 0.5 s apart are merged, flights moving against
   `--serve-dir` are dropped (balls being picked from the basket), and the toss up to
   1.5 s before is attached.
10. **Bounces:** a sudden drop in the ball's downward image speed (the table pushes it up).
    "Lowest point on screen" doesn't work, because under perspective a ball flying toward
    the camera keeps moving down the image even on a flat path.

### First results

Clip: [BV1Znaw6EEEZ](https://www.bilibili.com/video/BV1Znaw6EEEZ) (Bilibili), 852×480,
30 FPS, fixed camera, one player serving repeatedly for 3.5 minutes. Not committed;
download it with `yt-dlp`.

| Window | Serves by eye | Found | False alarms | Notes |
|---|---|---|---|---|
| 29–39 s (used for tuning) | 4 | 4 | 0 | First bounce (server's side) and second bounce (receiver's side) both found on one serve |
| 100–130 s (held out) | 5 | 3 | 0 | Missed 105 s and 121 s |
| Whole clip | ≈ 40–50 (estimated) | 40 | — | Roughly 75–85% found |

See [`examples/BV1Znaw6EEEZ_29-39s_tracks.png`](examples/BV1Znaw6EEEZ_29-39s_tracks.png)
and [`examples/BV1Znaw6EEEZ_serves.csv`](examples/BV1Znaw6EEEZ_serves.csv).

### Known limits

- **Contact is not visible at 30 FPS** (the ball is hidden by the racket and body for
  1–3 frames). The tracker sees the toss and the flight, not the contact. Racket angle needs
  120–240 FPS video.
- **Bounce timing is ±1 frame** (±33 ms at 30 FPS), and some bounces are missed when the
  ball is lost for a frame around them.
- **Positions are in pixels.** To measure speed and spin from the flight (Q5 in the
  experiment), positions need converting to table coordinates with a table calibration
  (4 clicked corners → homography). That's the next step.
- Thresholds were tuned on one clip. Expect to re-tune `--min-contrast`,
  `--max-area-near-player` and `--max-fg-blob` for a new camera position or resolution.

### Next steps

1. Table calibration (click 4 corners) → ball position on the table at each bounce,
   bounce on own side vs far side, short vs long.
2. Spin indicator from the flight: speed change at the far-side bounce (the method used in
   the SPIN dataset paper).
3. Run on your own 240 FPS recording (`--fps 240`) and compare with the marked-ball spin.
