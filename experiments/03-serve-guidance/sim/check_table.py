#!/usr/bin/env python3
"""Check the landing model on real serves on a table, with the stretch off the paddle and the one
after the first bounce both measured: does the model call them legal, does it get the time to the
first bounce, and what do the real bounces say about the table's restitution and friction?
python check_table.py NAME=clip.mp4 ..."""
import sys, json
import numpy as np
import landing
from landing import fly, legal, R

FPS = 240.0
for arg in sys.argv[1:]:
    nm, p = arg.split("=", 1)
    try: e = json.load(open(p + ".json"))
    except Exception: print(f"{nm}: no output"); continue
    rd = e[0]
    pad = next((r for r in rd if r.get("phase") == "paddle" and r.get("clear") and r.get("flight")), None)
    bnc = next((r for r in rd if r.get("phase") == "bounce" and r.get("rps") and r.get("flight")), None)
    if not pad:
        print(f"{nm}: no clear reading off the paddle ({[(r.get('phase'), r.get('clear')) for r in rd]})"); continue
    f = pad["flight"]; v = np.array(f["launchVel"])
    across, up = float(np.hypot(v[0], v[2])), float(-v[1])
    speed, ang = float(np.linalg.norm(v)), float(np.degrees(np.arctan2(-v[1], np.hypot(v[0], v[2]))))
    w = 2 * np.pi * max(pad["lift"], 0)
    line = f"{nm:6} {pad['rps']:5.1f} rps (back {pad['lift']:5.1f}) {speed:4.2f} m/s {ang:+4.0f} deg toward {f['towardCameraDeg']:+3.0f}"
    hit = f.get("hitFrame")
    if bnc:
        bf = bnc["start"] - 2                       # the stretch after the bounce starts 2 frames after it
        t_meas = (bf - hit) / FPS if hit is not None else None
        g = bnc["flight"]; va = np.array(g["launchVel"])
        line += f" | after bounce {bnc['rps']:5.1f} rps, {np.hypot(va[0], va[2]):4.2f} across, {-va[1]:+5.2f} up" + (f", hit->bounce {t_meas * 1000:.0f} ms" if t_meas else "")
    else: t_meas = None
    print(line)
    for h in (0.03, 0.10, 0.20):
        b, c = fly(-0.05, h, across, up, w)
        # model's time to the first bounce
        t, y, vy = 0.0, h, up
        while True:
            t += 1e-4; vy -= 9.81 * 1e-4; y += vy * 1e-4
            if y <= R: break
        print(f"        hit {h * 100:2.0f} cm up: {legal(b, c)}  bounces {[round(x, 2) for x in b]}  model hit->bounce {t * 1000:.0f} ms")
