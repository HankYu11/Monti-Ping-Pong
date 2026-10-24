#!/usr/bin/env python3
"""Pick serves for the end-to-end test, the same number of each kind (thick, good, slip, slow),
and render them: python make_batch.py OUTDIR [per_kind] [seed].
A server sets the face so the ball flies a playable path: each serve gets a launch angle
(-10 to +25 degrees) and the face tilt that gives it."""
import sys, os, subprocess
import numpy as np
from concurrent.futures import ThreadPoolExecutor
from contact import hit
from robust4 import truth
out = sys.argv[1]
per = int(sys.argv[2]) if len(sys.argv) > 2 else 10
rng = np.random.default_rng(int(sys.argv[3]) if len(sys.argv) > 3 else 11)
os.makedirs(out, exist_ok=True)
def launch(h): return np.degrees(np.arctan2(h["v"][1], h["v"][0]))
def tilt_for(V, a, fall, kp, want):        # launch rises with tilt: bisect
    lo, hi = -80.0, 20.0
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        if launch(hit(V, a, fall, kp, mid)) < want: lo = mid
        else: hi = mid
    return 0.5 * (lo + hi)
want = {k: per for k in ("thick", "good", "slip", "slow")}
jobs = []
while any(want.values()):
    V, a, fall, kp = rng.uniform(2, 10), rng.uniform(0, 60), rng.uniform(0.5, 2.0), rng.uniform(0.45, 0.80)
    tilt = tilt_for(V, a, fall, kp, rng.uniform(-10, 25))
    h = hit(V, a, fall, kp, tilt)
    k = truth(h)
    if want[k] and h["speed"] >= 1.6:
        want[k] -= 1
        jobs.append(dict(name=f"{k}{per - want[k]:02d}", paddle=V, angle=a, fall=fall, kp=kp, tilt=tilt,
                         height=rng.uniform(120, 300), seed=len(jobs)))
here = os.path.dirname(os.path.abspath(__file__))
def go(j):
    subprocess.run([sys.executable, os.path.join(here, "render_serve.py"), "--out", f"{out}/{j['name']}.mp4"] +
                   [x for k in ("paddle", "angle", "fall", "kp", "tilt", "height", "seed") for x in (f"--{k}", str(j[k]))],
                   check=True, capture_output=True)
with ThreadPoolExecutor(4) as ex: list(ex.map(go, jobs))
print(len(jobs), "clips in", out)
