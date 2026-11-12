#!/usr/bin/env python3
"""Is the ball actually inside the windows? One second, no browser.

    python3 test/wincheck.py serve01.win.cap ...

A windowed capture keeps a small square round where the track says the ball was. If the track
and the pixels disagree, the windows hold background and nothing else -- and the capture is
worthless in a way nothing downstream can recover from, because the pixels that held the ball
were never written.

It shows as contrast. A window with the ball in it differs from the background by 130 to 180
levels at its peak; a window with only background differs by about 20, which is noise. Over the
real sessions measured, every capture that read gave a median peak of 133 to 165 with essentially
every window above 60, and the one that did not read gave 23, with 11 of its 37 windows above 60.

That one was a capture rebuilt from a saved clip whose alignment was the worst of the set, not a
phone's own capture: a phone takes the track and the pixels from the same frames. This is here so
that if it ever does happen on a phone it is one command to see, rather than a puzzle.
"""
import json, struct, sys
import numpy as np

BALL_PEAK = 60          # a window holding a ball clears this; one holding background does not

bad = 0
for path in sys.argv[1:]:
    raw = open(path, "rb").read()
    head = json.loads(raw[4:4 + struct.unpack(">i", raw[:4])[0]])
    name = path.split("/")[-1]
    if not head.get("windows"):
        print(f"  {name}: not a windowed capture"); continue
    o = 4 + struct.unpack(">i", raw[:4])[0]
    w, h = head["rect"][2], head["rect"][3]
    frame = w * h + 2 * (w // 2) * (h // 2)
    bg = np.frombuffer(raw[o:o + frame], dtype=np.uint8)[:w * h].reshape(h, w).astype(np.int16)
    o += frame + head["n"] * h * 4
    side = head["winW"]
    win_bytes = side * side + 2 * (side // 2) * (side // 2)
    peaks = []
    for at in head["windows"]:
        if not at: continue
        y = np.frombuffer(raw[o:o + side * side], dtype=np.uint8).reshape(side, side).astype(np.int16)
        o += win_bytes
        patch = bg[at[1]:at[1] + side, at[0]:at[0] + side]
        if patch.shape == y.shape: peaks.append(float(np.abs(y - patch).max()))
    if not peaks:
        print(f"! {name}: no windows at all"); bad += 1; continue
    p = np.array(peaks)
    hit = int((p > BALL_PEAK).sum())
    ok = np.median(p) > BALL_PEAK and hit > 0.7 * len(p)
    if not ok: bad += 1
    print(f"{'  ' if ok else '! '}{name}: {len(p)} windows, peak against the background "
          f"median {np.median(p):.0f}, {hit} of {len(p)} over {BALL_PEAK}"
          f"{'' if ok else '  — the windows look like background: is the track aligned with the capture?'}")
print()
print(f"{bad} of {len(sys.argv)-1} captures do not look like they hold a ball" if bad else
      f"all {len(sys.argv)-1} captures hold a ball where the track said it was")
sys.exit(1 if bad else 0)
