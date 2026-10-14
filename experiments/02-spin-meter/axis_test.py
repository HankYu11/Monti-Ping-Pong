#!/usr/bin/env python3
"""Axis accuracy on synthetic serves: pole method vs whole-marking consistency ("marks").
Realistic real-footage conditions: 25 px ball, motion blur, noise, light from above."""
import json, os, subprocess, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
CASES = [
    ("side-backspin 40°", ["--rps", "-30", "--axis", "0", "-0.643", "0.766"]),
    ("pure backspin", ["--rps", "-32", "--axis", "0", "0", "1"]),
    ("pure topspin", ["--rps", "17", "--axis", "0", "0", "1"]),
    ("pure sidespin", ["--rps", "30", "--axis", "0", "1", "0"]),
    ("random axis A", ["--rps", "36", "--axis", "0.4", "0.6", "0.7"]),
    ("random axis B", ["--rps", "25", "--axis", "-0.5", "0.3", "0.81"]),
    ("axis 15° from black pole", ["--rps", "30", "--axis-near-pole", "15"]),
    ("axis 30° from black pole", ["--rps", "30", "--axis-near-pole", "30"]),
]
COMMON = ["--fps", "240", "--diameter", "25", "--speed", "12", "--exposure", "0.6", "--noise", "6",
          "--frames", "40", "--size", "900", "300", "--pattern", "half+line+dot"]


def ang(a, b):
    return float(np.degrees(np.arccos(np.clip(np.dot(a, b) / np.linalg.norm(a) / np.linalg.norm(b), -1, 1))))


def main(work):
    os.makedirs(work, exist_ok=True)
    print("| Case | True rps | Pole: rps / axis error | Marks: rps / axis error | Marks consistency |")
    print("|---|---|---|---|---|")
    for k, (name, extra) in enumerate(CASES):
        vid = os.path.join(work, f"ax{k}.mp4")
        subprocess.run([PY, os.path.join(HERE, "spinsim.py"), "--out", vid, "--seed", str(100 + k)] + COMMON + extra,
                       check=True, capture_output=True)
        truth = json.load(open(vid[:-4] + ".json"))
        ta = np.array(truth["axis"]) * np.sign(truth["rps"])
        out = {}
        for m in ("pole", "marks"):
            r = json.loads(subprocess.run([PY, os.path.join(HERE, "spinmeter.py"), vid, "--fps", "240", "--method", m],
                                          check=True, capture_output=True, text=True).stdout)
            out[m] = r
        row = [name, f"{abs(truth['rps']):.0f}"]
        for m in ("pole", "marks"):
            r = out[m]
            row.append(f"{r.get('rps')} / {ang(r['axis_camera_xyz'], ta):.0f}°" if "rps" in r else r.get("error"))
        row.append(str(out["marks"].get("consistency")))
        print("| " + " | ".join(row) + " |", flush=True)


if __name__ == "__main__":
    main(sys.argv[1])
