#!/usr/bin/env python3
"""Rebuild the .cap a phone measured, from the clip and the serve JSON it saved beside it.

    python3 test/capfromclip.py serve01.json serve01.mp4 serve01.cap [--rect X Y W H]

A session writes <serve>.mp4 (the whole ring, full frame) and <serve>.json (the capture's
header: the rect, the frame count and the live tracker's track). The .cap the engine actually
measured is not kept, so until now a real serve could not be re-measured off the phone -- only
rendered ones could, and rendered ones have no mains flicker, no motion blur and no player.

The clip holds more frames than the capture and nothing says where the capture starts. The
offset is found by scoring every candidate on how far the pixels differ from the background
inside the discs the track predicts: at the right offset the ball is in them and nowhere else.
The peak is sharp -- on the sessions tried, one frame either side scores a third lower, and a
wrong offset twenty times lower -- and the rebuilt capture reproduces the phone's own reading
to the digit (39.80 and 57.28 rev/s on the two checked).

--rect cuts a different window out of the same clip, which is how the engine's cost was shown
to scale with the area it is given rather than with anything else.
"""
import json, struct, subprocess, sys, os
import numpy as np

a = sys.argv[1:]
rect_override = None
if "--rect" in a:
    i = a.index("--rect"); rect_override = [int(v) for v in a[i + 1:i + 5]]; a = a[:i] + a[i + 5:]
if len(a) != 3:
    sys.exit("usage: capfromclip.py serve.json serve.mp4 out.cap [--rect X Y W H]")
meta_path, clip, out = a

cap = json.load(open(meta_path))["capture"]
rect = rect_override or cap["rect"]
x, y, w, h = rect
if w % 2 or h % 2 or x % 2 or y % 2:
    sys.exit("rect must be even on every side: the chroma planes are half size")

raw = out + ".yuv"
subprocess.run(["ffmpeg", "-v", "error", "-i", clip, "-filter:v", f"crop={w}:{h}:{x}:{y}",
                "-pix_fmt", "yuv420p", "-f", "rawvideo", raw, "-y"], check=True)
fb = w * h * 3 // 2
n_clip = os.path.getsize(raw) // fb
frames = np.memmap(raw, dtype=np.uint8, mode="r", shape=(n_clip, fb))

Y = frames[:, :w * h].reshape(n_clip, h, w).astype(np.float32)
bg = np.median(Y[::20], axis=0)
D = np.abs(Y - bg)
track = [(p[0], p[1] - x, p[2] - y, p[3]) for p in cap["track"]]
yy, xx = np.mgrid[0:h, 0:w]
best = None
for k in range(0, max(1, n_clip - cap["n"] + 1)):
    tot = 0.0; used = 0
    for f, tx, ty, r in track:
        j = k + f
        if not 0 <= j < n_clip: continue
        x0, x1 = int(max(0, tx - r)), int(min(w, tx + r + 1))
        y0, y1 = int(max(0, ty - r)), int(min(h, ty + r + 1))
        if x1 <= x0 or y1 <= y0: continue
        m = (xx[y0:y1, x0:x1] - tx) ** 2 + (yy[y0:y1, x0:x1] - ty) ** 2 <= r * r
        if m.sum() < 10: continue
        tot += float(D[j, y0:y1, x0:x1][m].mean()); used += 1
    if used >= 20 and (best is None or tot / used > best[0]):
        best = (tot / used, k)
if best is None:
    sys.exit("no offset scored: the track and the clip do not overlap")
score, k = best

head = {f: cap[f] for f in ("W", "H", "rect", "n", "full", "fps", "track", "near") if f in cap}
head["rect"] = rect
hb = json.dumps(head).encode()
with open(out, "wb") as f:
    f.write(struct.pack(">i", len(hb))); f.write(hb)
    for i in range(k, k + cap["n"]):
        f.write(frames[i].tobytes())
os.remove(raw)
print(f"{out}: frames {k}..{k + cap['n'] - 1} of {n_clip}, rect {rect} "
      f"({w * h} px/frame), match score {score:.1f}")
