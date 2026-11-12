#!/usr/bin/env python3
"""Check a session's capture headers against themselves. No clips, no browser, one second.

    python3 test/capsanity.py Session_.../serve*.json

A capture's header carries the live tracker's track, with frames counted from the capture's own
first frame. Nothing downstream checks that those frames land inside the capture, and when they
do not the engine looks for the ball where the ball is not, measures whatever is there, finds
nothing, and searches the whole crop instead -- the right answer, several seconds late.

That is not hypothetical. App 0.10.4 began trimming the capture to the tracked frames and kept
numbering the track from the untrimmed start, so every track shipped shifted by however far the
trim had moved it. Session 20261112_215446 had 23% to 88% of each track outside its own capture
and searched on 8 of 10 serves, at about 9 s each. The sessions before it: 0%, every serve.

The rebuild harness could not catch it. test/capfromclip.py finds the clip offset by scoring the
track's own predicted discs, so a shifted track is simply matched at a shifted offset and comes
out looking perfect -- the one thing it cannot see is the track being wrong relative to the
capture, because it uses the track to place the capture.
"""
import json, sys

bad = 0
for path in sys.argv[1:]:
    d = json.load(open(path))
    cap = d.get("capture") or {}
    n, rect = cap.get("n"), cap.get("rect")
    name = path.split("/")[-1]
    notes = []
    if not n or not rect:
        print(f"! {name}: no capture header"); bad += 1; continue
    x, y, w, h = rect
    if x % 2 or y % 2:
        notes.append(f"rect starts at an odd pixel ({x},{y}): the half-size chroma planes will not line up")
    if w <= 0 or h <= 0 or x + w > cap.get("W", 1 << 30) or y + h > cap.get("H", 1 << 30):
        notes.append(f"rect {rect} does not fit in {cap.get('W')}x{cap.get('H')}")
    # Points past the end can simply be the 300 MB cap cutting a long track short, which costs
    # the engine nothing it could have used. Points before 0, or most of a track gone, means the
    # header is numbered from a different frame than the capture starts at.
    soft = []
    tracks = [("track", cap.get("track") or [])] + [(f"near {i+1}", t) for i, t in enumerate(cap.get("near") or [])]
    for label, t in tracks:
        if not t: continue
        before = [p for p in t if p[0] < 0]
        after = [p for p in t if p[0] >= n]
        if not before and not after: continue
        where = (f"{len(before)} before frame 0" if before else "") + (" and " if before and after else "") + \
                (f"{len(after)} past frame {n-1}" if after else "")
        line = (f"{label}: {len(before)+len(after)} of {len(t)} points "
                f"({100*(len(before)+len(after))//len(t)}%) outside the capture — {where}; "
                f"the track spans {min(p[0] for p in t):.0f}-{max(p[0] for p in t):.0f}")
        serious = bool(before) or (label == "track" and (len(before) + len(after)) > 0.1 * len(t))
        (notes if serious else soft).append(line)
    if soft and not notes:
        print(f"  {name}: {n} frames, {rect[2]}x{rect[3]}")
        for s2 in soft: print(f"    (note) {s2}")
        continue
    notes += [f"(note) {x}" for x in soft]
    if notes:
        bad += 1
        print(f"! {name}")
        for s in notes: print(f"    {s}")
    else:
        t = cap.get("track") or []
        span = f"{min(p[0] for p in t):.0f}-{max(p[0] for p in t):.0f}" if t else "no track"
        print(f"  {name}: {n} frames, {rect[2]}x{rect[3]}, track {span} — fits")
print()
print(f"{bad} of {len(sys.argv)-1} headers are inconsistent" if bad else
      f"all {len(sys.argv)-1} headers are consistent with their captures")
sys.exit(1 if bad else 0)
