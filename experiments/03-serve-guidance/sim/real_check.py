#!/usr/bin/env python3
"""The guidance on real clips already run through the engine: python real_check.py NAME=CLIP.mp4 ..."""
import sys, json
import numpy as np
from diagnose import diagnose
for arg in sys.argv[1:]:
    nm, p = arg.split("=", 1)
    e = json.load(open(p + ".json"))
    reads = [r for sv in e[:-1] for r in sv if r.get("rps") and r.get("flight")]
    r = next((r for r in reads if r["phase"] != "bounce" and r["clear"]), None) or next((r for r in reads if r["clear"]), None)
    if not r: print(f"{nm:8} no clear reading"); continue
    f = r["flight"]; d = diagnose(r["rps"], f)
    toss = np.linalg.norm(f["tossVel"]) if f.get("tossVel") else float("nan")
    print(f"{nm:8} {r['phase']:6} {r['rps']:5.1f} rps  launch {f['launchSpeed']:.2f} m/s (fix {f.get('scaleFix', 1):.2f}, toward {f['towardCameraDeg']:+.0f}°)"
          f"  toss {toss:.2f}  spin/speed {d['ratio']:.2f}  grip {'-' if d['grip'] is None else round(d['grip'], 2)}  -> {d['kind']}{'/' + d['alt'] if d['alt'] else ''}")
