#!/usr/bin/env python3
"""Score the coaching answer the engine's readings give against the simulator's truth.
python score_e2e.py DIR  (DIR holds NAME.mp4, NAME.json = truth, NAME.mp4.json = engine output)"""
import sys, json, glob, os
import numpy as np
from contact import R, hit
from robust4 import truth, TARGET
from diagnose import diagnose, grip_used
rows, labs = [], ("thick", "good", "slip", "slow")
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.mp4"))):
    t = json.load(open(f[:-4] + ".json")); eng = json.load(open(f + ".json"))
    want = truth(t)
    h = hit(t["paddle"], t["angle"], t["fall"], t["kp"], t["tilt"])
    g_true = grip_used(h["rps"], h["v"] * [1, -1, 1], [0, t["fall"], 0])
    reads = [r for sv in eng[:-1] for r in sv if r.get("rps") and r.get("flight")]
    r = next((r for r in reads if r["phase"] != "bounce" and r["clear"]), None)
    d = diagnose(r["rps"], r["flight"]) if r else None
    said = (d["kind"] if want != d["alt"] else d["alt"]) if d else "none"   # "between": right if either is
    rows.append((os.path.basename(f), want, said, t, r, d, g_true))
for n, want, said, t, r, d, g in rows:
    m = ""
    if r:
        fl = r["flight"]
        m = (f"rps {r['rps']:5.1f}/{t['rps']:5.1f}  speed {fl['launchSpeed']:4.2f}/{t['speed']:4.2f}  hit {fl['hitFrame'] or 0:5.1f}"
             f"  toss {np.linalg.norm(fl['tossVel']) if fl.get('tossVel') else float('nan'):4.2f}/{t['fall']:4.2f}  grip {d['grip'] if d['grip'] is not None else float('nan'):4.2f}/{g:4.2f}")
    alt = f"/{d['alt']}" if d and d["alt"] else ""
    print(f"{n:12} truth {want:5} said {(d['kind'] if d else 'none') + alt:11} {'  ' if want == said else '!!'} {m}  ratio {t['ratio']:.2f}")
ok = [w == s for _, w, s, *_ in rows]
ans = [w == s for _, w, s, *_ in rows if s != "none"]
between = sum(1 for *_, d, g in rows if d and d["alt"])
print(f"right {sum(ok)}/{len(ok)}; when answered {sum(ans)}/{len(ans)}; {between} answered 'between two'")
print("truth \\ said  " + "  ".join(f"{l:>6}" for l in labs + ("none",)))
for a in labs: print(f"{a:13} " + "  ".join(f"{sum(w == a and s == l for _, w, s, *_ in rows):6d}" for l in labs + ("none",)))
