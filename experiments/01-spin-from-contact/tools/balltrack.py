#!/usr/bin/env python3
"""Classic-CV table tennis ball tracker for a fixed camera.

Pipeline per frame:
  1. Background subtraction (MOG2) keeps only moving pixels.
  2. Colour mask keeps white or orange pixels (bright, low saturation / orange hue).
  3. Small, roughly compact blobs become ball candidates.
Then candidates are linked into trajectories with a constant-velocity predictor,
and bounces are found where the ball switches from moving down to moving up
in the image.

Outputs (in --out):
  candidates.csv  every candidate blob per frame
  tracks.csv      linked ball positions: track_id, frame, t, x, y, area
  bounces.csv     detected bounces: track_id, frame, t, x, y, on_table, kink
  serves.csv      toss + flight grouped into serves, with bounce times
  summary.json    counts and settings
  overlay.mp4     video with candidates (grey), tracks (colour trails), bounces (red)
  tracks.png      x/y of each track over time

Usage:
  python balltrack.py VIDEO --out OUTDIR [--start S] [--end S] [--table table.json]

table.json (optional) restricts bounce detection to the table surface:
  {"table": [[x1, y1], [x2, y2], [x3, y3], [x4, y4]]}   # pixel corners, any order around
"""
import argparse
import csv
import json
import math
import os

import cv2
import numpy as np


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("video")
    p.add_argument("--out", required=True)
    p.add_argument("--start", type=float, default=0.0, help="start time in seconds")
    p.add_argument("--end", type=float, default=None, help="end time in seconds")
    p.add_argument("--table", help="JSON file with the table polygon in pixels")
    p.add_argument("--fps", type=float, default=None,
                   help="real capture FPS if the file's timestamps are scaled (slow-motion files)")
    p.add_argument("--min-area", type=float, default=6.0)
    p.add_argument("--max-area", type=float, default=400.0)
    p.add_argument("--max-aspect", type=float, default=4.0, help="max blob elongation (motion streaks)")
    p.add_argument("--min-track-len", type=int, default=6)
    p.add_argument("--min-track-travel", type=float, default=30.0, help="pixels")
    p.add_argument("--max-miss", type=int, default=3, help="frames a track may go unseen")
    p.add_argument("--max-fg-blob", type=float, default=600.0,
                   help="candidates inside a moving blob larger than this (px) are the player, not the ball")
    p.add_argument("--min-contrast", type=float, default=25.0,
                   help="ball must be this much brighter (grey levels) than its surroundings")
    p.add_argument("--max-area-near-player", type=float, default=300.0,
                   help="max blob area (px) for a ball candidate touching the player")
    p.add_argument("--min-contrast-near-player", type=float, default=40.0)
    p.add_argument("--cut-frac", type=float, default=0.25,
                   help="foreground fraction above which a frame is treated as a camera cut")
    p.add_argument("--serve-dir", choices=["any", "+x", "-x"], default="any",
                   help="image direction a serve travels in for this camera (filters ball pickups etc.)")
    p.add_argument("--no-video", action="store_true", help="skip overlay.mp4")
    return p.parse_args()


# ---------------------------------------------------------------- detection

def colour_mask(frame_bgr):
    hsv = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV)
    h, s, v = cv2.split(hsv)
    white = (v >= 150) & (s <= 70)
    orange = (h >= 5) & (h <= 25) & (s >= 120) & (v >= 150)
    return ((white | orange).astype(np.uint8)) * 255


def detect_candidates(frame_bgr, bg_sub, args, kernel):
    """Returns (candidates, foreground_fraction). A high foreground fraction means a
    camera cut or camera movement; the caller should reset tracking."""
    fg = bg_sub.apply(frame_bgr)
    fg = cv2.threshold(fg, 200, 255, cv2.THRESH_BINARY)[1]  # drop MOG2 shadows (127)
    fg_frac = float(np.count_nonzero(fg)) / fg.size
    # Moving blobs. The player is one big blob; a flying ball is a small one of its own.
    fg_clean = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, kernel)
    n_fg, fg_labels, fg_stats, _ = cv2.connectedComponentsWithStats(fg_clean, connectivity=8)

    mask = cv2.bitwise_and(fg, colour_mask(frame_bgr))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    mask = cv2.dilate(mask, kernel)
    n, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=8)
    gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV)
    H, W = gray.shape
    cands = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if not (args.min_area <= area <= args.max_area):
            continue
        aspect = max(w, h) / max(1, min(w, h))
        if aspect > args.max_aspect:
            continue
        fill = area / float(w * h)
        if fill < 0.3:
            continue
        cx, cy = centroids[i]
        # Part of a large moving blob (the player's body or arm)? Then only keep it if it
        # looks unmistakably like a ball: very white, small, and high contrast. This keeps
        # the ball while it is next to the hand or racket (short serves, toss, contact).
        lab = fg_labels[int(cy), int(cx)]
        in_player = lab > 0 and fg_stats[lab, cv2.CC_STAT_AREA] > args.max_fg_blob
        if in_player:
            comp = labels[y:y + h, x:x + w] == i
            px = hsv[y:y + h, x:x + w][comp]
            if area > args.max_area_near_player or px[:, 2].mean() < 200 or px[:, 1].mean() > 45:
                continue
        # brighter than its surroundings?
        pad = max(4, max(w, h))
        x0, y0 = max(0, x - pad), max(0, y - pad)
        x1, y1 = min(W, x + w + pad), min(H, y + h + pad)
        ring = gray[y0:y1, x0:x1].astype(np.float32)
        inner = labels[y0:y1, x0:x1] == i
        if inner.all() or not inner.any():
            continue
        contrast = float(ring[inner].mean() - np.median(ring[~inner]))
        if contrast < (args.min_contrast_near_player if in_player else args.min_contrast):
            continue
        cands.append({"x": float(cx), "y": float(cy), "area": int(area),
                      "aspect": float(aspect), "contrast": contrast})
    return cands, fg_frac


# ---------------------------------------------------------------- tracking

class Track:
    _next_id = 0

    def __init__(self, frame, cand):
        self.id = Track._next_id
        Track._next_id += 1
        self.points = [(frame, cand["x"], cand["y"], cand["area"])]
        self.miss = 0

    @property
    def last(self):
        return self.points[-1]

    def predict(self, frame):
        f1, x1, y1, _ = self.points[-1]
        if len(self.points) < 2:
            return x1, y1, 0.0
        f0, x0, y0, _ = self.points[-2]
        df = max(1, f1 - f0)
        vx, vy = (x1 - x0) / df, (y1 - y0) / df
        k = frame - f1
        return x1 + vx * k, y1 + vy * k, math.hypot(vx, vy)

    def travel(self):
        xs = [p[1] for p in self.points]
        ys = [p[2] for p in self.points]
        return math.hypot(max(xs) - min(xs), max(ys) - min(ys))


def associate(tracks, cands, frame, base_gate=25.0):
    """Greedy nearest-neighbour association using predicted positions."""
    pairs = []
    for ti, t in enumerate(tracks):
        px, py, speed = t.predict(frame)
        gate = base_gate + 1.5 * speed
        for ci, c in enumerate(cands):
            d = math.hypot(c["x"] - px, c["y"] - py)
            if d <= gate:
                pairs.append((d, ti, ci))
    pairs.sort()
    used_t, used_c, matches = set(), set(), []
    for d, ti, ci in pairs:
        if ti in used_t or ci in used_c:
            continue
        used_t.add(ti)
        used_c.add(ci)
        matches.append((ti, ci))
    return matches, used_t, used_c


# ---------------------------------------------------------------- bounces

def find_bounces(points, table_poly=None, min_kink=3.0):
    """Find bounces as sudden upward kinks in the image-y velocity.

    Under perspective a ball flying toward the camera keeps moving down the image even
    on a flat path, so "lowest point on screen" is not a reliable bounce. A bounce is
    instead a frame where the downward image speed drops sharply (the table pushes the
    ball up). Gravity only ever increases downward speed, so a big drop is a bounce.

    points: list of (frame, x, y, area). Returns [(frame, x, y, on_table, kink)].
    min_kink: drop in vy, in pixels per frame, needed to call it a bounce.
    """
    out = []
    if len(points) < 4:
        return out
    f = np.array([p[0] for p in points], dtype=float)
    x = np.array([p[1] for p in points])
    y = np.array([p[2] for p in points])
    vy = np.diff(y) / np.maximum(1.0, np.diff(f))  # velocity between consecutive points
    # kink at point i (between segment i-1 and segment i)
    kinks = []
    for i in range(1, len(vy)):
        before, after = vy[i - 1], vy[i]
        drop = before - after
        if before > 0.5 and drop >= min_kink:
            kinks.append((drop, i))
    # keep the strongest kink within any 4-frame window
    kinks.sort(reverse=True)
    taken = []
    for drop, i in kinks:
        if any(abs(f[i] - f[j]) <= 4 for _, j in taken):
            continue
        taken.append((drop, i))
    for drop, i in sorted(taken, key=lambda t: t[1]):
        on_table = None
        if table_poly is not None:
            on_table = cv2.pointPolygonTest(table_poly, (float(x[i]), float(y[i])), True) >= -6
        out.append((int(f[i]), float(x[i]), float(y[i]), on_table, float(drop)))
    return out


def classify_track(t):
    """'toss' if the ball mostly rises vertically, 'flight' if it mostly travels sideways."""
    xs = np.array([p[1] for p in t.points])
    ys = np.array([p[2] for p in t.points])
    dx = xs.max() - xs.min()
    rise = ys[0] - ys.min()  # image y decreases when the ball goes up
    if rise > 1.5 * dx and rise >= 30:
        return "toss"
    if dx >= 35:
        return "flight"
    return "other"


def flight_dx(t):
    return t.points[-1][1] - t.points[0][1]


def group_serves(good, fps, serve_dir="any", max_gap_s=1.5, merge_gap_s=0.5):
    """A serve = an optional toss followed by one or more flight pieces.

    serve_dir: "+x" / "-x" keeps only flights moving that way in the image (the serve
      direction for this camera); "any" keeps all.
    Flight pieces that start within merge_gap_s of the previous piece's end are merged
      (the tracker often loses the ball for a few frames near the net or the player).
    """
    flights = [t for t in good if t.kind == "flight"]
    if serve_dir in ("+x", "-x"):
        sign = 1 if serve_dir == "+x" else -1
        flights = [t for t in flights if sign * flight_dx(t) > 0]
    flights.sort(key=lambda t: t.points[0][0])
    groups = []
    for fl in flights:
        if groups and fl.points[0][0] - groups[-1][-1].points[-1][0] <= merge_gap_s * fps:
            groups[-1].append(fl)
        else:
            groups.append([fl])
    tosses = sorted((t for t in good if t.kind == "toss"), key=lambda t: t.points[0][0])
    serves = []
    for g in groups:
        start = g[0].points[0][0]
        before = [t for t in tosses if 0 <= start - t.points[-1][0] <= max_gap_s * fps]
        toss = before[-1] if before else None
        serves.append((toss, g))
    return serves


# ---------------------------------------------------------------- main

def main():
    args = parse_args()
    os.makedirs(args.out, exist_ok=True)

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {args.video}")
    file_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    fps = args.fps or file_fps
    W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    start_f = int(round(args.start * file_fps))
    end_f = int(round(args.end * file_fps)) if args.end else None
    cap.set(cv2.CAP_PROP_POS_FRAMES, start_f)

    table_poly = None
    if args.table:
        with open(args.table) as f:
            pts = np.array(json.load(f)["table"], dtype=np.float32)
        hull = cv2.convexHull(pts)
        table_poly = hull.reshape(-1, 1, 2)

    bg_sub = cv2.createBackgroundSubtractorMOG2(history=300, varThreshold=20, detectShadows=True)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))

    writer = None
    if not args.no_video:
        writer = cv2.VideoWriter(os.path.join(args.out, "overlay.mp4"),
                                 cv2.VideoWriter_fourcc(*"mp4v"), file_fps, (W, H))

    active, finished = [], []
    cand_rows = []
    cuts = []
    settle_until = -1
    frame_idx = start_f
    warmup = 10  # let the background model settle
    while True:
        ok, frame = cap.read()
        if not ok or (end_f is not None and frame_idx >= end_f):
            break
        cands, fg_frac = detect_candidates(frame, bg_sub, args, kernel)
        if fg_frac > args.cut_frac:
            # camera cut or camera movement: end all tracks and let the background re-settle
            finished.extend(active)
            active = []
            cuts.append(frame_idx)
            settle_until = frame_idx + warmup
        if frame_idx - start_f < warmup or frame_idx < settle_until:
            cands = []
        for c in cands:
            cand_rows.append((frame_idx, c["x"], c["y"], c["area"], c["aspect"]))

        matches, used_t, used_c = associate(active, cands, frame_idx)
        for ti, ci in matches:
            c = cands[ci]
            active[ti].points.append((frame_idx, c["x"], c["y"], c["area"]))
            active[ti].miss = 0
        still = []
        for ti, t in enumerate(active):
            if ti not in used_t:
                t.miss += 1
            (still if t.miss <= args.max_miss else finished).append(t)
        active = still
        for ci, c in enumerate(cands):
            if ci not in used_c:
                active.append(Track(frame_idx, c))

        if writer is not None:
            vis = frame.copy()
            if table_poly is not None:
                cv2.polylines(vis, [table_poly.astype(np.int32)], True, (0, 255, 255), 1)
            for c in cands:
                cv2.circle(vis, (int(c["x"]), int(c["y"])), 6, (180, 180, 180), 1)
            for t in active:
                if len(t.points) >= 3:
                    col = tuple(int(v) for v in np.random.default_rng(t.id).integers(80, 255, 3))
                    pts = np.array([(int(p[1]), int(p[2])) for p in t.points[-30:]], np.int32)
                    cv2.polylines(vis, [pts], False, col, 2)
            cv2.putText(vis, f"f{frame_idx} t={frame_idx / file_fps:.2f}s", (8, H - 10),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
            writer.write(vis)
        frame_idx += 1

    finished.extend(active)
    cap.release()

    # keep tracks that look like a flying ball: long enough and actually moving
    good = [t for t in finished
            if len(t.points) >= args.min_track_len and t.travel() >= args.min_track_travel]
    good.sort(key=lambda t: t.points[0][0])

    with open(os.path.join(args.out, "candidates.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["frame", "x", "y", "area", "aspect"])
        w.writerows(cand_rows)

    bounce_rows = []
    with open(os.path.join(args.out, "tracks.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["track_id", "kind", "frame", "t", "x", "y", "area"])
        for t in good:
            t.kind = classify_track(t)
            for fr, x, y, a in t.points:
                w.writerow([t.id, t.kind, fr, round(fr / fps, 4), round(x, 1), round(y, 1), a])
            t.bounces = find_bounces(t.points, table_poly) if t.kind == "flight" else []
            for fr, x, y, on_table, kink in t.bounces:
                bounce_rows.append((t.id, fr, round(fr / fps, 4), round(x, 1), round(y, 1), on_table, round(kink, 1)))

    with open(os.path.join(args.out, "bounces.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["track_id", "frame", "t", "x", "y", "on_table", "kink_px_per_frame"])
        w.writerows(bounce_rows)

    serves = group_serves(good, fps, args.serve_dir)
    with open(os.path.join(args.out, "serves.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["serve", "toss_track", "toss_start_t", "toss_rise_px", "flight_tracks",
                    "flight_start_t", "flight_end_t", "flight_dx_px", "n_bounces", "bounce_times"])
        for k, (toss, g) in enumerate(serves):
            row = [k]
            if toss is not None:
                ys = [p[2] for p in toss.points]
                row += [toss.id, round(toss.points[0][0] / fps, 3), round(ys[0] - min(ys), 1)]
            else:
                row += ["", "", ""]
            bounces = [b for fl in g for b in fl.bounces]
            row += [" ".join(str(fl.id) for fl in g),
                    round(g[0].points[0][0] / fps, 3), round(g[-1].points[-1][0] / fps, 3),
                    round(g[-1].points[-1][1] - g[0].points[0][1], 1), len(bounces),
                    " ".join(str(round(b[0] / fps, 3)) for b in bounces)]
            w.writerow(row)

    summary = {
        "video": os.path.basename(args.video),
        "size": [W, H],
        "file_fps": file_fps,
        "analysis_fps": fps,
        "frames": frame_idx - start_f,
        "candidates": len(cand_rows),
        "camera_cuts": cuts,
        "tracks_total": len(finished),
        "tracks_kept": len(good),
        "serves": len(serves),
        "serves_with_toss": sum(1 for a, _ in serves if a is not None),
        "tracks_by_kind": {k: sum(1 for t in good if t.kind == k) for k in ("toss", "flight", "other")},
        "bounces": len(bounce_rows),
        "bounces_on_table": sum(1 for b in bounce_rows if b[5]),
        "track_lengths": [len(t.points) for t in good],
    }
    with open(os.path.join(args.out, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)

    plot_tracks(good, bounce_rows, fps, os.path.join(args.out, "tracks.png"))

    if writer is not None:
        writer.release()
        # second pass: draw final kept tracks and bounces on top of the overlay
        annotate_overlay(os.path.join(args.out, "overlay.mp4"), good, bounce_rows, start_f)

    print(json.dumps({k: v for k, v in summary.items() if k != "track_lengths"}, indent=2))


def annotate_overlay(path, good, bounce_rows, start_f):
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS)
    W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    tmp = path + ".tmp.mp4"
    out = cv2.VideoWriter(tmp, cv2.VideoWriter_fourcc(*"mp4v"), fps, (W, H))
    by_frame = {}
    for t in good:
        for fr, x, y, _ in t.points:
            by_frame.setdefault(fr, []).append((t.id, x, y))
    bounce_frames = {}
    for tid, fr, _, x, y, _, _ in bounce_rows:
        for k in range(fr, fr + 8):
            bounce_frames.setdefault(k, []).append((x, y))
    f = start_f
    while True:
        ok, img = cap.read()
        if not ok:
            break
        for tid, x, y in by_frame.get(f, []):
            cv2.circle(img, (int(x), int(y)), 9, (0, 255, 0), 2)
            cv2.putText(img, str(tid), (int(x) + 10, int(y) - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 0), 1)
        for x, y in bounce_frames.get(f, []):
            cv2.circle(img, (int(x), int(y)), 14, (0, 0, 255), 2)
        out.write(img)
        f += 1
    cap.release()
    out.release()
    os.replace(tmp, path)


def plot_tracks(good, bounce_rows, fps, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 6), sharex=True)
    style = {"toss": ("tab:blue", "toss"), "flight": ("tab:green", "flight"), "other": ("tab:gray", "other")}
    seen = set()
    for t in good:
        ts = [p[0] / fps for p in t.points]
        col, lab = style[t.kind]
        lab = lab if lab not in seen else None
        seen.add(style[t.kind][1])
        ax1.plot(ts, [p[1] for p in t.points], ".-", ms=3, lw=1, color=col, label=lab)
        ax2.plot(ts, [p[2] for p in t.points], ".-", ms=3, lw=1, color=col)
    for _, _, tt, x, y, _, _ in bounce_rows:
        ax1.plot(tt, x, "rv", ms=6)
        ax2.plot(tt, y, "rv", ms=6)
    ax1.set_ylabel("x (px)")
    ax2.set_ylabel("y (px, down is larger)")
    ax2.invert_yaxis()
    ax2.set_xlabel("time (s)")
    ax1.set_title("Ball tracks (red = detected bounces)")
    ax1.legend(loc="upper right", fontsize=8)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)


if __name__ == "__main__":
    main()
