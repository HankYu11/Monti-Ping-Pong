#!/usr/bin/env python3
"""Accuracy sweep: render synthetic clips with known spin and measure them.

Each row varies one condition from the baseline, so the table shows which conditions
break the meter. Writes sweep_results.csv and prints a Markdown table.

Usage: python sweep.py --workdir /tmp/sweep [--csv sweep_results.csv]
"""
import argparse
import csv
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable

BASE = dict(rps=30, axis="0 0 1", fps=240, diameter=40, speed=8, exposure=0.25,
            pattern="half+line", noise=4, frames=30)

CASES = [
    ("baseline", {}),
    ("slow spin 5 rps", dict(rps=5)),
    ("15 rps", dict(rps=15)),
    ("60 rps (top-player serve)", dict(rps=60)),
    ("100 rps", dict(rps=100)),
    ("130 rps (above the 120 rps limit)", dict(rps=130)),
    ("backspin -30 rps", dict(rps=-30)),
    ("ball 20 px", dict(diameter=20)),
    ("ball 14 px", dict(diameter=14)),
    ("ball 70 px", dict(diameter=70)),
    ("full blur (1/240 s)", dict(exposure=1.0)),
    ("full blur, 60 rps", dict(exposure=1.0, rps=60)),
    ("fast flight 20 px/frame", dict(speed=20)),
    ("axis tilted 20°", dict(axis="0 0.342 0.940")),
    ("axis tilted 45°", dict(axis="0 0.707 0.707")),
    ("axis tilted 70°", dict(axis="0 0.940 0.342")),
    ("pure sidespin (axis vertical)", dict(axis="0 1 0")),
    ("side-backspin, axis 40° (typical serve)", dict(rps=-30, axis="0 -0.643 0.766")),
    ("side-backspin, full blur", dict(rps=-30, axis="0 -0.643 0.766", exposure=1.0)),
    ("pattern: half only", dict(pattern="half")),
    ("pattern: dots", dict(pattern="dots")),
    ("noise 12", dict(noise=12)),
    ("120 FPS, 30 rps", dict(fps=120)),
    ("120 FPS, 70 rps", dict(fps=120, rps=70)),
]


def run_case(name, over, workdir, idx):
    cfg = dict(BASE, **over)
    vid = os.path.join(workdir, f"case{idx:02d}.mp4")
    cmd = [PY, os.path.join(HERE, "spinsim.py"), "--out", vid, "--rps", str(cfg["rps"]),
           "--axis", *cfg["axis"].split(), "--fps", str(cfg["fps"]), "--diameter", str(cfg["diameter"]),
           "--speed", str(cfg["speed"]), "--exposure", str(cfg["exposure"]), "--pattern", cfg["pattern"],
           "--noise", str(cfg["noise"]), "--frames", str(cfg["frames"]), "--seed", str(idx)]
    subprocess.run(cmd, check=True, capture_output=True)
    import numpy as np
    axis = np.array([float(v) for v in cfg["axis"].split()])
    axis /= np.linalg.norm(axis)
    true_axis = axis * np.sign(cfg["rps"])           # positive rotation about this axis
    row = dict(case=name, true_rps=abs(cfg["rps"]),
               true_axis=" ".join(f"{v:.2f}" for v in true_axis))
    # v1 (2D): sees only the component about the viewing axis
    out2 = json.loads(subprocess.run([PY, os.path.join(HERE, "spinmeter.py"), vid, "--fps", str(cfg["fps"]),
                                      "--method", "2d"], check=True, capture_output=True, text=True).stdout)
    if "rps" in out2:
        signed = out2["rps"] if out2["direction_in_image"] == "clockwise" else -out2["rps"]
        row["v1_visible_true"] = round(cfg["rps"] * axis[2], 1)
        row["v1_measured"] = round(signed, 1)
    else:
        row["v1_visible_true"], row["v1_measured"] = round(cfg["rps"] * axis[2], 1), None
    # v2 (3D): full rate and axis
    out3 = json.loads(subprocess.run([PY, os.path.join(HERE, "spinmeter.py"), vid, "--fps", str(cfg["fps"]),
                                      "--method", "3d"], check=True, capture_output=True, text=True).stdout)
    if "rps" in out3:
        m_axis = np.array(out3["axis_camera_xyz"])
        cosang = float(np.clip(m_axis @ true_axis, -1, 1))
        row["v2_rps"] = out3["rps"]
        row["v2_rps_error"] = round(out3["rps"] - abs(cfg["rps"]), 1)
        row["v2_axis_error_deg"] = round(float(np.degrees(np.arccos(cosang))), 1)
        row["v2_fit"] = out3["fit_score"]
    else:
        row.update(v2_rps=None, v2_rps_error=None, v2_axis_error_deg=None, v2_fit=None)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", required=True)
    ap.add_argument("--csv", default=os.path.join(HERE, "sweep_results.csv"))
    a = ap.parse_args()
    os.makedirs(a.workdir, exist_ok=True)
    rows = [run_case(n, o, a.workdir, i) for i, (n, o) in enumerate(CASES)]
    with open(a.csv, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print("| Case | True rps | v1 (2D) true→measured, in-image part | v2 (3D) measured rps | v2 rps error | v2 axis error ° |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['case']} | {r['true_rps']} | {r['v1_visible_true']} → {r['v1_measured']} | "
              f"{r['v2_rps']} | {r['v2_rps_error']} | {r['v2_axis_error_deg']} |")


if __name__ == "__main__":
    main()
