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
    # Gyro from three different starting orientations. The meter reads the inked pole's path,
    # so a ball whose pole lies along its own spin axis has a marking that does not move and a
    # rate that is not there to be read. Gyro is where that can happen by accident, because the
    # axis points along the flight and so does a good deal of the picture -- one orientation is
    # not a test of gyro, it is a test of one orientation.
    ("pure gyro 40",         dict(spin="gyro", rps=40)),
    ("pure gyro 40, turned", dict(spin="gyro", rps=40, orient=2)),
    ("pure gyro 40, turned again", dict(spin="gyro", rps=40, orient=5)),
    # and the blind spot on purpose, so the sweep says what happens there instead of finding out
    # by accident: the pole 8 degrees off the axis, where no method can read the rate
    ("pole on the axis (blind spot)", dict(spin="gyro", rps=40, pole_at=8)),
    ("side-backspin 40",     dict(spin="side-backspin", rps=40)),
    ("side-topspin 40",      dict(spin="side-topspin", rps=40)),
    # Floats from several orientations. A no-spin serve is a real serve and must come back as
    # "no spin", not as a refusal and not as a rev/s or two of spin that is not there -- and
    # which of those happened used to depend on which way the marking faced.
    ("float (no spin)",      dict(spin="none", rps=0)),
    ("float, turned",        dict(spin="none", rps=0, orient=2)),
    ("float, turned again",  dict(spin="none", rps=0, orient=5)),
    # Spinning balls that LOOK as still as a float: the pole put near the axis on purpose. These
    # are what stop "it looked still" from being read as "it was not spinning", and they are the
    # reason the no-spin test is the dots' and not the pattern's or the pole's.
    ("25 rps, pole 10 deg off", dict(spin="backspin", rps=25, pole_at=10)),
    ("40 rps, pole 12 deg off", dict(spin="sidespin", rps=40, pole_at=12)),
    ("15 rps, pole 5 deg off",  dict(spin="gyro", rps=15, pole_at=5)),
    ("head-on, backspin 40", dict(spin="backspin", rps=40, toward=45)),
    ("head-on, sidespin 40", dict(spin="sidespin", rps=40, toward=45)),
    # Speed. A serve faster than about 10 m/s used to produce no track at all -- see the first
    # link's gate in ServeTracker -- and these are what keep that from coming back. 18 m/s is
    # the most this framing can hold: at 20 the flight is 37 frames and the engine wants 40.
    ("slow, 3 m/s",          dict(spin="backspin", rps=30, speed=3)),
    ("fast, 12 m/s",         dict(spin="backspin", rps=30, speed=12)),
    ("fast, 16 m/s",         dict(spin="backspin", rps=30, speed=16)),
    ("fast, 18 m/s",         dict(spin="backspin", rps=30, speed=18)),
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
    p.add_argument("--chunk", type=int, default=6,
                   help="captures to hold on disk at once (a whole-crop one is about 370 MB)")
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

    # A whole-crop capture is about 370 MB, so a sweep of twenty cannot have them all on disk
    # at once. Built, measured and deleted a chunk at a time; the clips they come from are
    # small and are kept.
    allcaps, owner, failed, refused, got = [], {}, [], [], {}
    chunk, pending = [], []
    def drain():
        if not pending:
            return
        print(f"  measuring {len(pending)}...", file=sys.stderr)
        got.update(measure(pending))
        for c in pending:
            try:
                os.remove(c)
            except OSError:
                pass
        pending.clear()

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
        mine = []
        for c in caps:
            mine.append(c)
            owner[os.path.basename(c)] = name
            if a.ring and os.path.exists(c[:-4] + ".ring.cap"):
                mine.append(c[:-4] + ".ring.cap")
                owner[os.path.basename(c[:-4] + ".ring.cap")] = name + " (ring)"
        allcaps.extend(mine)
        pending.extend(mine)
        if len(pending) >= a.chunk:
            drain()
    drain()

    hdr = ("case", "form", "rps true", "rps got", "d rps", "axis err",
           "lift true", "lift got", "curve true", "curve got", "gyro true", "gyro got", "note")
    print()
    print(f"{'case':<32}{'form':<7}{'pole':>6}{'rps':>7}{'got':>8}{'err':>7}{'axis':>7}"
          f"{'lift':>8}{'got':>8}{'curve':>8}{'got':>8}{'gyro':>8}{'got':>8}")
    print("-" * 118)
    out_rows = []
    for cap in allcaps:
        base = os.path.basename(cap)
        who = owner[base]
        clean = who.replace(" (ring)", "")
        truth = rendered[clean][1]
        m = got.get(base)
        form = "ring" if who.endswith("(ring)") else "crop"
        if not m or m.get("error"):
            print(f"{who:<32}{form:<7}{'':>6}{truth['rps']:>7.1f}   could not read: {m and m.get('error')}")
            out_rows.append([who, form, truth["rps"], "", "", "", "", "", "", "", "", "",
                             str(m and m.get("error"))])
            continue
        # The app refuses a reading it is not sure of and says "Couldn't read that one." The
        # engine hands back a rate anyway, next to the flag. Print the app's answer, not the
        # engine's: a number the player never sees is not a result, and reporting one as though
        # it were is how this sweep once had the app "answering 0.21 rev/s" in its blind spot
        # when the app had in fact refused it.
        if m.get("appClear") is False:
            why = (m.get("issues") or ["no clear reading"])[0]
            print(f"{who:<32}{form:<7}{truth.get('pole_axis_deg', float('nan')):>5.0f}°"
                  f"{truth['rps']:>7.1f}{'refused':>8}   {why[:60]}")
            out_rows.append([who, form, truth["rps"], "refused", "", "", "", "", "", "", "", "",
                             why])
            refused.append((who, why))
            continue
        e = truth["expected"]
        # the angle between the spin we rendered and the spin that came back
        tv = (e["gyro"], e["lift"], e["curve"])
        gv = (m["gyro"], m["lift"], m["curve"])
        tn = math.hypot(*tv) or 1e-9
        gn = math.hypot(*gv) or 1e-9
        cos = sum(x * y for x, y in zip(tv, gv)) / (tn * gn)
        ax = math.degrees(math.acos(max(-1.0, min(1.0, cos)))) if truth["rps"] > 1 else float("nan")
        pole = truth.get("pole_axis_deg", float("nan"))
        print(f"{who:<32}{form:<7}{pole:>5.0f}°{truth['rps']:>7.1f}{m['rps']:>8.2f}"
              f"{m['rps'] - truth['rps']:>+7.2f}"
              f"{ax:>6.1f}°{e['lift']:>8.1f}{m['lift']:>8.1f}{e['curve']:>8.1f}{m['curve']:>8.1f}"
              f"{e['gyro']:>8.1f}{m['gyro']:>8.1f}" + ("   (blind spot)" if truth.get("blind_spot") else ""))
        out_rows.append([who, form, truth["rps"], round(m["rps"], 2), round(m["rps"] - truth["rps"], 2),
                         round(ax, 1), e["lift"], round(m["lift"], 1), e["curve"], round(m["curve"], 1),
                         e["gyro"], round(m["gyro"], 1),
                         "blind spot: pole %.0f deg off the axis" % pole if truth.get("blind_spot") else ""])

    for name, why in failed:
        truth = rendered[name][1]
        rps = truth.get("rps", "")
        print(f"{name:<32}{'-':<7}{'':>6}{rps:>7}   {why}")
        out_rows.append([name, "", rps, "", "", "", "", "", "", "", "", "", why])

    with open(a.csv, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(hdr)
        w.writerows(out_rows)
    print(f"\nwritten to {a.csv}")

    blind = {n for n, _ in cases if rendered.get(n, (None, {}))[1].get("blind_spot")}
    good = [r for r in out_rows if r[4] != "" and r[0].replace(" (ring)", "") not in blind]
    if blind:
        print(f"\n(the blind-spot case is left out of the summary: its rate is not there to be read)")
    if good:
        rate = max(abs(r[4]) for r in good)
        axes = [r[5] for r in good if r[5] == r[5]]
        print(f"{len(good)} of {len(out_rows)} read · worst rate error {rate:+.2f} rev/s · "
              f"worst axis error {max(axes):.1f}° · median axis {sorted(axes)[len(axes) // 2]:.1f}°")
    if failed:
        print(f"{len(failed)} serve{'s' if len(failed) > 1 else ''} the app never found: "
              + ", ".join(n for n, _ in failed))
    if refused:
        print(f"{len(refused)} the app found and refused rather than guess: "
              + ", ".join(n for n, _ in refused))


if __name__ == "__main__":
    main()
