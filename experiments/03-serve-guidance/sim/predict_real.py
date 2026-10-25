#!/usr/bin/env python3
"""Would the measured serves land legally? Each serve's spin, launch speed and direction from the
engine, hit at the end line and about table height, flown through landing.py; repeated over the
model's uncertain numbers (table friction and restitution, lift) to see which answers hold.
python predict_real.py NAME=clip.mp4 ..."""
import sys, json, itertools
import numpy as np
import landing
from landing import fly, legal

SPREAD = dict(mu=(0.15, 0.25, 0.35), e=(0.85, 0.9, 0.93), cl=(0.7, 1.0, 1.3))
HEIGHTS = (0.03, 0.08, 0.15)          # hit height above the table, m
X0 = -0.05                            # just behind the end line
NAMES = {"S": "short", "H": "half-long", "L": "long", "n": "net", "2": "dies on your side", "o": "misses your side"}

for arg in sys.argv[1:]:
    nm, p = arg.split("=", 1)
    e = json.load(open(p + ".json"))
    r = next((r for r in e[0] if r.get("clear") and r.get("flight") and r["phase"] != "bounce"), None)
    if not r: print(f"{nm}: no reading off the paddle"); continue
    f = r["flight"]; v = np.array(f["launchVel"])
    speed = float(np.linalg.norm(v)); ang = float(np.degrees(np.arctan2(-v[1], np.hypot(v[0], v[2]))))
    w = 2 * np.pi * max(r["lift"], 0)                      # backspin part, rad/s
    print(f"\n{nm}: {r['rps']:.1f} rev/s (backspin {r['lift']:.1f}), {speed:.2f} m/s, {ang:+.0f} deg")
    for h in HEIGHTS:
        out = []
        for mu, et, cl in itertools.product(SPREAD["mu"], SPREAD["e"], SPREAD["cl"]):
            landing.MU_T, landing.E_T, landing.CL_SCALE = mu, et, cl
            b, c = fly(X0, h, speed * np.cos(np.radians(ang)), speed * np.sin(np.radians(ang)), w)
            out.append(legal(b, c))
        landing.MU_T, landing.E_T, landing.CL_SCALE = 0.25, 0.9, 1.0
        b, c = fly(X0, h, speed * np.cos(np.radians(ang)), speed * np.sin(np.radians(ang)), w)
        mid = legal(b, c)
        counts = {k: out.count(k) for k in dict.fromkeys(out)}
        print(f"  hit {h * 100:2.0f} cm up: {NAMES[mid]:18} bounces at {', '.join(f'{x:.2f}' for x in b)} m"
              f"   over 27 model variants: " + ", ".join(f"{NAMES[k]} {n}" for k, n in counts.items()))
