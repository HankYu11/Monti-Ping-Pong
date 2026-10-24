#!/usr/bin/env python3
"""Score the coaching answer the engine's readings give against the simulator's truth.
python score_e2e.py DIR  (DIR holds NAME.mp4, NAME.json = truth, NAME.mp4.json = engine output)"""
import sys, json, glob, os
import numpy as np
from contact import R
from robust4 import truth, TARGET
def rule(rps, speed, slip_thr=1.25):
    ratio = 2 * np.pi * rps * R / speed
    if ratio < 0.9: return "thick"
    if rps >= TARGET: return "good"
    return "slip" if ratio >= slip_thr else "slow"
rows, labs = [], ("thick", "good", "slip", "slow")
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.mp4"))):
    t = json.load(open(f[:-4] + ".json")); eng = json.load(open(f + ".json"))
    want = truth(t)
    reads = [r for sv in eng[:-1] for r in sv if r.get("rps") and r.get("flight")]
    r = next((r for r in reads if r["phase"] != "bounce" and r["clear"]), None)
    if not r:
        rows.append((os.path.basename(f), want, "none", t, None)); continue
    said = rule(r["rps"], r["flight"]["launchSpeed"])
    rows.append((os.path.basename(f), want, said, t, r))
for n, want, said, t, r in rows:
    m = f"rps {r['rps']:5.1f}/{t['rps']:5.1f}  speed {r['flight']['launchSpeed']:4.2f}/{t['speed']:4.2f}  hit {r['hit']}" if r else ""
    print(f"{n:12} truth {want:5} said {said:5} {'  ' if want == said else '!!'} {m}  ratio {t['ratio']:.2f}")
ok = [w == s for _, w, s, _, _ in rows]
print(f"right {sum(ok)}/{len(ok)}")
print("truth \\ said  " + "  ".join(f"{l:>6}" for l in labs + ("none",)))
for a in labs: print(f"{a:13} " + "  ".join(f"{sum(w == a and s == l for _, w, s, _, _ in rows):6d}" for l in labs + ("none",)))
