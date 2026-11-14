#!/usr/bin/env python3
"""The whole app, end to end, on serves whose spin is known exactly.

`sweep.py` measures the spin meter on its own, on a ball flying across grey. This runs the
SHIPPING PIPELINE on whole serves rendered by `servesim.py`: the real tracker finds the
serve, the real window ring cuts it, the real capture writer writes it, and the real engine
reads it. Nothing here knows what the answer is supposed to be except this file, at the end,
when it compares.

    servesim.py  ->  .mp4  ->  desktop (ServeTracker, RingSim, Windows.Builder)  ->  .cap
                                                                                      |
                              web/test/cap.mjs (engine.js) <--------------------------+

What is left out is the shader's own arithmetic, the chip's timing, and the camera. Those
need a phone and a room; everything between them is tested here, with an answer to mark it
against, on serves nobody can actually play -- a pure sidespin, a pure gyro, a float.

Usage:
  python servecheck.py --workdir /tmp/servecheck            the standard set
  python servecheck.py --workdir /tmp/sc --only sidespin    one case
  python servecheck.py --workdir /tmp/sc --ring             the window ring as well
  python servecheck.py --workdir /tmp/sc --keep             do not re-render what is there
"""
import argparse
import concurrent.futures as cf
import csv
import json
import math
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
DESKTOP = os.path.join(HERE, "..", "04-live-coach", "android", "desktop")
CAPJS = os.path.join(HERE, "web", "test", "cap.mjs")

# What a serve can be, including the four no player can produce. The rates span the range
# real serves live in (the README's references put serves at 14-63 rps) and then past it,
# up to the 120 rps limit where half the frame rate makes the measurement alias.
CASES = [
    ("backspin 10 rps",      dict(spin="backspin", rps=10)),
    ("backspin 25 rps",      dict(spin="backspin", rps=25)),
    ("backspin 40 rps",      dict(spin="backspin", rps=40)),
    ("backspin 60 rps",      dict(spin="backspin", rps=60)),
    ("backspin 90 rps",      dict(spin="backspin", rps=90)),
    ("topspin 40 rps",       dict(spin="topspin", rps=40)),
    ("topspin 70 rps",       dict(spin="topspin", rps=70)),
    ("pure sidespin 40",     dict(spin="sidespin", rps=40)),
    ("pure sidespin 40, other way", dict(spin="sidespin-r", rps=40)),
    ("pure gyro 40",         dict(spin="gyro", rps=40)),
    ("side-backspin 40",     dict(spin="side-backspin", rps=40)),
    ("side-topspin 40",      dict(spin="side-topspin", rps=40)),
    ("float (no spin)",      dict(spin="none", rps=0)),
    ("head-on, backspin 40", dict(spin="backspin", rps=40, toward=45)),
    ("head-on, sidespin 40", dict(spin="sidespin", rps=40, toward=45)),
    ("far camera, 3.6 m",    dict(spin="backspin", rps=40, distance=3.6)),
    ("dim room",             dict(spin="backspin", rps=40, gain=0.55, noise=6)),
    ("full blur, 1/240 s",   dict(spin="backspin", rps=40, exposure=1.0)),
]


def slug(name):
    return "".join(c if c.isalnum() else "_" for c in name).strip("_").lower()


def render(workdir, name, opts, keep):
    out = os.path.join(workdir, slug(name) + ".mp4")
    truth = out[:-4] + ".json"
    if keep and os.path.exists(out) and os.path.exists(truth):
        return name, out, json.load(open(truth))
    cmd = [PY, os.path.join(HERE, "servesim.py"), "--out", out]
    for k, v in opts.items():
        cmd += ["--" + k.replace("_", "-"), str(v)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        return name, None, {"error": (r.stderr or r.stdout).strip().splitlines()[-1:] or ["failed"]}
    return name, out, json.load(open(truth))


def captures(clip, outdir, ring):
    """Run the shipping tracker and capture writer over one clip."""
    env = dict(os.environ, JAVA_OPTS="-Xmx6g")
    if ring:
        env["RING"] = "1"
    exe = os.path.join(DESKTOP, "build", "install", "desktop", "bin", "desktop")
    r = subprocess.run([exe, clip, outdir], capture_output=True, text=True, env=env)
    serves = [json.loads(l) for l in r.stdout.splitlines()
              if l.startswith("{") and '"serve"' in l]
    if not serves:
        return [], (r.stderr or r.stdout).strip().splitlines()[-1] if r.returncode else "no serve found"
    return [s["file"] for s in serves], None


def measure(caps):
    """One browser for the lot: cap.mjs starts playwright, which is not cheap."""
    if not caps:
        return {}
    r = subprocess.run(["node", CAPJS, "--json", *caps], capture_output=True, text=True)
    out = {}
    for line in r.stdout.splitlines():
        if line.startswith("{"):
            d = json.loads(line)
            out[d["file"]] = d
    if not out:
        print(r.stderr[-2000:], file=sys.stderr)
    return out


def err(got, want):
    return None if got is None else got - want


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--workdir", required=True)
    p.add_argument("--only", help="run only the cases whose name contains this")
    p.add_argument("--ring", action="store_true", help="also build each capture from the window ring")
    p.add_argument("--keep", action="store_true", help="reuse clips already rendered")
    p.add_argument("--jobs", type=int, default=4, help="clips to render at once")
    p.add_argument("--csv", default=os.path.join(HERE, "servecheck_results.csv"))
    a = p.parse_args()
    os.makedirs(a.workdir, exist_ok=True)
    cases = [c for c in CASES if not a.only or a.only.lower() in c[0].lower()]
    if not cases:
        sys.exit(f"no case matches {a.only!r}")

    exe = os.path.join(DESKTOP, "build", "install", "desktop", "bin", "desktop")
    if not os.path.exists(exe):
        print("building the desktop runner...", file=sys.stderr)
        subprocess.run(["gradle", "--no-daemon", "-q", ":desktop:installDist"],
                       cwd=os.path.join(DESKTOP, ".."), check=True)

    print(f"rendering {len(cases)} serves ({a.jobs} at a time)...", file=sys.stderr)
    rendered = {}
    with cf.ThreadPoolExecutor(a.jobs) as pool:
        for name, clip, truth in pool.map(
                lambda c: render(a.workdir, c[0], c[1], a.keep), cases):
            rendered[name] = (clip, truth)
            print(f"  {name}: {'rendered' if clip else truth.get('error')}", file=sys.stderr)

    allcaps, owner, failed = [], {}, []
    for name, _ in cases:
        clip, truth = rendered[name]
        if not clip:
            failed.append((name, "did not render: " + str(truth.get("error"))))
            continue
        caps, why = captures(clip, os.path.join(a.workdir, "out"), a.ring)
        if not caps:
            # The app never got as far as measuring. That is a result, not an absence of
            # one, and a sweep that printed only what it could read would be flattering.
            failed.append((name, why))
            continue
        for c in caps:
            allcaps.append(c)
            owner[os.path.basename(c)] = name
            if a.ring and os.path.exists(c[:-4] + ".ring.cap"):
                allcaps.append(c[:-4] + ".ring.cap")
                owner[os.path.basename(c[:-4] + ".ring.cap")] = name + " (ring)"

    print(f"measuring {len(allcaps)} captures...", file=sys.stderr)
    got = measure(allcaps)

    hdr = ("case", "form", "rps true", "rps got", "d rps", "axis err",
           "lift true", "lift got", "curve true", "curve got", "gyro true", "gyro got", "note")
    print()
    print(f"{'case':<32}{'form':<7}{'rps':>7}{'got':>8}{'err':>7}{'axis':>7}"
          f"{'lift':>8}{'got':>8}{'curve':>8}{'got':>8}{'gyro':>8}{'got':>8}")
    print("-" * 112)
    out_rows = []
    for cap in allcaps:
        base = os.path.basename(cap)
        who = owner[base]
        clean = who.replace(" (ring)", "")
        truth = rendered[clean][1]
        m = got.get(base)
        form = "ring" if who.endswith("(ring)") else "crop"
        if not m or m.get("error"):
            print(f"{who:<32}{form:<7}{truth['rps']:>7.1f}   could not read: {m and m.get('error')}")
            out_rows.append([who, form, truth["rps"], "", "", "", "", "", "", "", "", "",
                             str(m and m.get("error"))])
            continue
        e = truth["expected"]
        # the angle between the spin we rendered and the spin that came back
        tv = (e["gyro"], e["lift"], e["curve"])
        gv = (m["gyro"], m["lift"], m["curve"])
        tn = math.hypot(*tv) or 1e-9
        gn = math.hypot(*gv) or 1e-9
        cos = sum(x * y for x, y in zip(tv, gv)) / (tn * gn)
        ax = math.degrees(math.acos(max(-1.0, min(1.0, cos)))) if truth["rps"] > 1 else float("nan")
        print(f"{who:<32}{form:<7}{truth['rps']:>7.1f}{m['rps']:>8.2f}{m['rps'] - truth['rps']:>+7.2f}"
              f"{ax:>6.1f}°{e['lift']:>8.1f}{m['lift']:>8.1f}{e['curve']:>8.1f}{m['curve']:>8.1f}"
              f"{e['gyro']:>8.1f}{m['gyro']:>8.1f}")
        out_rows.append([who, form, truth["rps"], round(m["rps"], 2), round(m["rps"] - truth["rps"], 2),
                         round(ax, 1), e["lift"], round(m["lift"], 1), e["curve"], round(m["curve"], 1),
                         e["gyro"], round(m["gyro"], 1), ""])

    for name, why in failed:
        truth = rendered[name][1]
        rps = truth.get("rps", "")
        print(f"{name:<32}{'-':<7}{rps:>7}   {why}")
        out_rows.append([name, "", rps, "", "", "", "", "", "", "", "", "", why])

    with open(a.csv, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(hdr)
        w.writerows(out_rows)
    print(f"\nwritten to {a.csv}")

    good = [r for r in out_rows if r[4] != ""]
    if good:
        rate = max(abs(r[4]) for r in good)
        axes = [r[5] for r in good if r[5] == r[5]]
        print(f"{len(good)} of {len(out_rows)} read · worst rate error {rate:+.2f} rev/s · "
              f"worst axis error {max(axes):.1f}° · median axis {sorted(axes)[len(axes) // 2]:.1f}°")
    if failed:
        print(f"{len(failed)} serve{'s' if len(failed) > 1 else ''} the app never found: "
              + ", ".join(n for n, _ in failed))


if __name__ == "__main__":
    main()
