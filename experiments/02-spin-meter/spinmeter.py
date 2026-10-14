#!/usr/bin/env python3
"""Measure the spin rate of a marked table tennis ball from high-speed video (v1, 2D).

Like a baseball spin-rate tracker, but with one phone camera: the ball carries a visible
marking (for example half black + one line), the camera looks at the flight from the side,
and the meter measures how far the marking turns between consecutive frames.

v1 measures the rotation in the image plane, i.e. the spin component about the camera's
viewing axis. With the camera side-on to the flight, that is the backspin/topspin
component, which is what matters for a backspin serve. Sidespin (a vertical spin axis)
and the full 3D axis need v2 (dot pattern, 3D orientation fit).

Method per frame pair:
  1. Find the ball (moving blob against a background median), get centre and radius.
  2. Resample the ball's inner disc into polar coordinates (angle x radius).
  3. The rotation angle between frames is the shift that best aligns the two polar
     images (circular cross-correlation along the angle axis, via FFT).
  4. Fit a straight line to the accumulated angle over time -> spin rate.

Aliasing: per-frame rotation is only measurable up to half a turn. At 240 FPS that is
120 rps (7200 RPM). Serves by top players measured 14-63 rps; beginners spin less.

Usage:
  python spinmeter.py VIDEO --fps 240 [--out result.json] [--plot angles.png]
Sign convention: positive = clockwise as seen in the image. For a ball flying to the right
in the image, clockwise is topspin and counter-clockwise is backspin; flip for a ball
flying left. The script reports both, using the measured flight direction.
"""
import argparse
import json

import cv2
import numpy as np


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("video")
    p.add_argument("--fps", type=float, required=True, help="real capture frame rate (e.g. 240)")
    p.add_argument("--start", type=int, default=0, help="first frame")
    p.add_argument("--end", type=int, default=None, help="last frame (exclusive)")
    p.add_argument("--roi", type=int, nargs=4, metavar=("X", "Y", "W", "H"),
                   help="only look inside this rectangle (pixels, after the phone's rotation)")
    p.add_argument("--min-radius", type=float, default=6.0, help="pixels")
    p.add_argument("--inner", type=float, default=0.15, help="inner polar radius, fraction of ball radius")
    p.add_argument("--outer", type=float, default=0.80, help="outer polar radius (avoid the dark edge)")
    p.add_argument("--n-angle", type=int, default=360)
    p.add_argument("--n-radius", type=int, default=24)
    p.add_argument("--method", choices=["3d", "2d"], default="3d",
                   help="3d: fit the full 3D rotation (axis + rate); 2d: in-image rotation only (v1)")
    p.add_argument("--out", help="write the result JSON here")
    p.add_argument("--plot", help="write an angle-vs-time plot here")
    return p.parse_args()


# ---------------------------------------------------------------- ball finding

def read_frames(path, start, end, roi=None):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {path}")
    frames, i = [], 0
    while True:
        ok, img = cap.read()
        if not ok or (end is not None and i >= end):
            break
        if i >= start:
            if roi is not None:
                x, y, w, h = roi
                img = img[y:y + h, x:x + w]
            frames.append(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32))
        i += 1
    cap.release()
    return frames


def find_ball(gray, background, min_radius):
    """Ball = largest compact region that differs from the background.
    Returns (cx, cy, r) or None. Both the black and the white parts of a marked ball differ
    from the background, so the whole ball is found, not just one half."""
    diff = np.abs(gray - background)
    mask = (diff > 25).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    best = None
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if w < 2 * min_radius or h < 2 * min_radius:
            continue
        aspect = max(w, h) / min(w, h)
        fill = area / (np.pi * (max(w, h) / 2) ** 2)
        if aspect > 1.6 or fill < 0.6:
            continue
        if best is None or area > best[0]:
            best = (area, i)
    if best is None:
        return None
    pts = np.column_stack(np.nonzero(labels == best[1]))[:, ::-1].astype(np.float32)
    (cx, cy), r = cv2.minEnclosingCircle(pts)
    # the area-based radius is less sensitive to a few stray edge pixels
    r_area = np.sqrt(best[0] / np.pi)
    return float(cx), float(cy), float(min(r, r_area * 1.05))


# ---------------------------------------------------------------- rotation

def polar_patch(gray, cx, cy, r, inner, outer, n_angle, n_radius):
    """Sample the ball's disc on a polar grid: rows = radius, columns = angle.
    Angle 0 points right, increasing clockwise in image coordinates (y down)."""
    th = np.linspace(0, 2 * np.pi, n_angle, endpoint=False)
    rr = np.linspace(inner, outer, n_radius) * r
    map_x = (cx + rr[:, None] * np.cos(th)[None, :]).astype(np.float32)
    map_y = (cy + rr[:, None] * np.sin(th)[None, :]).astype(np.float32)
    P = cv2.remap(gray, map_x, map_y, cv2.INTER_LINEAR)
    # remove the per-ring mean and the fixed shading gradient's average, normalise contrast
    P = P - P.mean(axis=1, keepdims=True)
    P = P / (P.std(axis=1, keepdims=True) + 1e-6)
    return P


def rotation_between(P0, P1):
    """Angle (degrees, clockwise positive) that rotates P0 onto P1, plus a match score."""
    F0 = np.fft.fft(P0, axis=1)
    F1 = np.fft.fft(P1, axis=1)
    corr = np.fft.ifft(F1 * np.conj(F0), axis=1).real.sum(axis=0)
    n = len(corr)
    k = int(np.argmax(corr))
    # parabolic sub-bin refinement
    y0, y1, y2 = corr[(k - 1) % n], corr[k], corr[(k + 1) % n]
    denom = y0 - 2 * y1 + y2
    frac = 0.5 * (y0 - y2) / denom if abs(denom) > 1e-9 else 0.0
    shift = (k + frac) * 360.0 / n
    if shift > 180:
        shift -= 360
    score = y1 / (P0.size + 1e-9)          # ~1 for a perfect match
    return shift, float(score)


# ---------------------------------------------------------------- 3D rotation fit

CANON = 64          # canonical ball patch size (pixels)
RELIABLE_FIT = 0.75  # below this the reading is not trustworthy (see README: real recordings)
CANON_R = 28.0      # ball radius inside the canonical patch


def canonical_patches(frames, balls, r_med):
    """Resample each frame's ball into a fixed-size patch centred on the ball, so that
    the same (u, v) on the unit disc maps to the same patch pixel in every frame."""
    out, idx = [], []
    s = CANON_R / r_med
    for i, (g, b) in enumerate(zip(frames, balls)):
        if b is None:
            continue
        cx, cy, _ = b
        M = np.array([[s, 0, CANON / 2 - s * cx], [0, s, CANON / 2 - s * cy]], dtype=np.float32)
        out.append(cv2.warpAffine(g, M, (CANON, CANON), flags=cv2.INTER_LINEAR))
        idx.append(i)
    return np.stack(out), idx


def remove_shading(P):
    """The marking rotates with the ball, the lighting does not. Over many frames every
    surface pixel shows white at some point, so a high percentile per pixel estimates
    'white under this lighting'. Dividing by it leaves the marking (albedo)."""
    white = np.percentile(P, 90, axis=0)
    white = cv2.GaussianBlur(white, (0, 0), 2.0)
    return P / np.maximum(white, 1.0)


def fibonacci_sphere(n):
    i = np.arange(n) + 0.5
    phi = np.arccos(1 - 2 * i / n)
    th = np.pi * (1 + 5 ** 0.5) * i
    return np.stack([np.cos(th) * np.sin(phi), np.sin(th) * np.sin(phi), np.cos(phi)], axis=1)


def rodrigues(axes, angles):
    """Batch rotation matrices. axes (N,3) unit, angles (N,) radians -> (N,3,3)."""
    a = axes
    K = np.zeros((len(a), 3, 3))
    K[:, 0, 1], K[:, 0, 2] = -a[:, 2], a[:, 1]
    K[:, 1, 0], K[:, 1, 2] = a[:, 2], -a[:, 0]
    K[:, 2, 0], K[:, 2, 1] = -a[:, 1], a[:, 0]
    s = np.sin(angles)[:, None, None]
    c = (1 - np.cos(angles))[:, None, None]
    return np.eye(3)[None] + s * K + c * (K @ K)


def sample_disc(A, uv):
    """Bilinear sample canonical patches A (T,H,W) at unit-disc points uv (T?,K,2)."""
    x = CANON / 2 + CANON_R * uv[..., 0] - 0.5
    y = CANON / 2 + CANON_R * uv[..., 1] - 0.5
    x0 = np.clip(np.floor(x).astype(int), 0, CANON - 2)
    y0 = np.clip(np.floor(y).astype(int), 0, CANON - 2)
    fx, fy = np.clip(x - x0, 0, 1), np.clip(y - y0, 0, 1)
    if A.ndim == 2:
        g = A
        v00, v01, v10, v11 = g[y0, x0], g[y0, x0 + 1], g[y0 + 1, x0], g[y0 + 1, x0 + 1]
    else:
        t = np.arange(A.shape[0]).reshape((-1,) + (1,) * (uv.ndim - 2))
        v00, v01 = A[t, y0, x0], A[t, y0, x0 + 1]
        v10, v11 = A[t, y0 + 1, x0], A[t, y0 + 1, x0 + 1]
    return (v00 * (1 - fx) * (1 - fy) + v01 * fx * (1 - fy) + v10 * (1 - fx) * fy + v11 * fx * fy)


def disc_grid(step=0.07, rmax=0.82):
    u = np.arange(-rmax, rmax + 1e-9, step)
    U, V = np.meshgrid(u, u)
    m = U ** 2 + V ** 2 <= rmax ** 2
    uv = np.stack([U[m], V[m]], axis=1)
    n = np.column_stack([uv, np.sqrt(1 - (uv ** 2).sum(1))])
    return n


def pair_costs(N0, N1, normals, Rs, min_z=0.25):
    """For each rotation R (per-frame spin), compare frame t+1 at normal n with frame t at
    R^T n. N0, N1: (P,H,W) normalised patches for P frame pairs. Returns (len(Rs),) mean
    normalised cross-correlation (higher is better)."""
    scores = np.empty(len(Rs))
    v1 = sample_disc(N1, np.broadcast_to(normals[None, :, :2], (N1.shape[0],) + normals[:, :2].shape))
    for j, R in enumerate(Rs):
        m = normals @ R            # rows: R^T n
        ok = m[:, 2] > min_z
        if ok.sum() < 30:
            scores[j] = -1
            continue
        v0 = sample_disc(N0, np.broadcast_to(m[None, ok, :2], (N0.shape[0], ok.sum(), 2)))
        a = v0 - v0.mean(1, keepdims=True)
        b = v1[:, ok] - v1[:, ok].mean(1, keepdims=True)
        num = (a * b).sum(1)
        den = np.sqrt((a * a).sum(1) * (b * b).sum(1)) + 1e-9
        scores[j] = float(np.mean(num / den))
    return scores


def measure_3d(frames, balls, fps, n_axes=300, angle_step_deg=4.0, max_pairs=10):
    """Find the constant per-frame 3D rotation that best explains all consecutive frames."""
    rs = [b[2] for b in balls if b is not None]
    r_med = float(np.median(rs))
    P, idx = canonical_patches(frames, balls, r_med)
    N = remove_shading(P)
    pairs = [(k, k + 1) for k in range(len(idx) - 1) if idx[k + 1] == idx[k] + 1]
    if len(pairs) < 3:
        return None
    sel = pairs if len(pairs) <= max_pairs else [pairs[i] for i in
                                                np.linspace(0, len(pairs) - 1, max_pairs).astype(int)]
    N0 = np.stack([N[a] for a, _ in sel])
    N1 = np.stack([N[b] for _, b in sel])
    coarse_normals = disc_grid(step=0.12)
    # coarse search: axes on a hemisphere (the other half is the same rotation with -angle)
    axes = fibonacci_sphere(2 * n_axes)
    axes = axes[axes[:, 2] >= 0] if True else axes
    angles = np.radians(np.arange(-180 + angle_step_deg, 180 + 1e-9, angle_step_deg))
    A = np.repeat(axes, len(angles), axis=0)
    T = np.tile(angles, len(axes))
    keep = np.abs(T) > 1e-9
    A, T = A[keep], T[keep]
    Rs = rodrigues(A, T)
    sc = pair_costs(N0, N1, coarse_normals, Rs)
    # also score "no rotation"
    best = np.argsort(sc)[::-1][:8]
    # refine the best candidates with a shrinking local search on all pairs
    allN0 = np.stack([N[a] for a, _ in pairs])
    allN1 = np.stack([N[b] for _, b in pairs])
    fine_normals = disc_grid(step=0.07)
    results = []
    for j in best:
        axis, ang = A[j].copy(), T[j]
        cur = pair_costs(allN0, allN1, fine_normals, rodrigues(axis[None], np.array([ang])))[0]
        step_ax, step_an = 0.15, np.radians(angle_step_deg)
        for _ in range(12):
            cand_ax = [axis]
            for d in np.eye(3):
                for sgn in (-1, 1):
                    v = axis + sgn * step_ax * d
                    cand_ax.append(v / np.linalg.norm(v))
            cands = [(a, ang + da) for a in cand_ax for da in (-step_an, 0.0, step_an)]
            CA = np.array([c[0] for c in cands])
            CT = np.array([c[1] for c in cands])
            s = pair_costs(allN0, allN1, fine_normals, rodrigues(CA, CT))
            k = int(np.argmax(s))
            if s[k] > cur + 1e-6:
                axis, ang, cur = CA[k], CT[k], s[k]
            else:
                step_ax *= 0.5
                step_an *= 0.5
        results.append((cur, axis, ang))
    results.sort(key=lambda r: r[0], reverse=True)
    score, axis, ang = results[0]
    if ang < 0:                      # report a positive angle about the flipped axis
        axis, ang = -axis, -ang
    second = results[1][0] if len(results) > 1 else float("nan")
    rps = np.degrees(ang) / 360.0 * fps
    return {"rps": float(rps), "axis": axis.tolist(), "deg_per_frame": float(np.degrees(ang)),
            "score": float(score), "runner_up_score": float(second), "pairs": len(pairs)}


# ---------------------------------------------------------------- main

def measure(frames, fps, args):
    background = np.median(np.stack(frames), axis=0)
    balls = [find_ball(g, background, args.min_radius) for g in frames]
    if args.method == "3d":
        return measure_with_3d(frames, balls, fps)
    steps = []
    for i in range(len(frames) - 1):
        b0, b1 = balls[i], balls[i + 1]
        if b0 is None or b1 is None:
            continue
        r = min(b0[2], b1[2])
        P0 = polar_patch(frames[i], b0[0], b0[1], r, args.inner, args.outer, args.n_angle, args.n_radius)
        P1 = polar_patch(frames[i + 1], b1[0], b1[1], r, args.inner, args.outer, args.n_angle, args.n_radius)
        ang, score = rotation_between(P0, P1)
        steps.append({"frame": args.start + i, "deg": ang, "score": score,
                      "cx": b0[0], "cy": b0[1], "r": b0[2]})
    if len(steps) < 3:
        return {"error": "ball found in fewer than 4 consecutive frames", "frames_with_ball":
                sum(b is not None for b in balls)}, steps

    degs = np.array([s["deg"] for s in steps])
    scores = np.array([s["score"] for s in steps])
    good = scores >= max(0.3, np.median(scores) * 0.6)
    # robust per-frame rotation: median of the good steps
    med = float(np.median(degs[good])) if good.any() else float(np.median(degs))
    mad = float(np.median(np.abs(degs[good] - med))) if good.any() else float("nan")
    rps = med * fps / 360.0
    xs = [s["cx"] for s in steps]
    flight = "right" if xs[-1] > xs[0] else "left"
    clockwise = rps > 0
    # ball flying right + clockwise rotation = topspin (top of ball moves forward)
    if abs(rps) < 0.5:
        kind = "no spin"
    elif (flight == "right") == clockwise:
        kind = "topspin component"
    else:
        kind = "backspin component"
    result = {
        "rps": round(abs(rps), 2),
        "rpm": round(abs(rps) * 60, 0),
        "direction_in_image": "clockwise" if clockwise else "counter-clockwise",
        "flight_direction": flight,
        "spin_type": kind,
        "deg_per_frame": round(med, 2),
        "deg_per_frame_mad": round(mad, 2),
        "frame_pairs_used": int(good.sum()),
        "frame_pairs_total": len(steps),
        "ball_radius_px": round(float(np.median([s["r"] for s in steps])), 1),
        "max_measurable_rps": fps / 2,
        "fps": fps,
    }
    return result, steps


def measure_with_3d(frames, balls, fps):
    found = [b for b in balls if b is not None]
    if len(found) < 4:
        return {"error": "ball found in fewer than 4 frames", "frames_with_ball": len(found)}, []
    r = measure_3d(frames, balls, fps)
    if r is None:
        return {"error": "fewer than 3 consecutive frame pairs with the ball"}, []
    axis = np.array(r["axis"])
    rps = r["rps"]
    xs = [b[0] for b in found]
    flight = "right" if xs[-1] > xs[0] else "left"
    fwd = 1.0 if flight == "right" else -1.0
    # Components in camera axes (x right, y down, z toward camera), as signed rps.
    # z: rotation in the image plane, positive = clockwise in the image.
    # With a side-on camera: z = topspin(+)/backspin(-) for a ball flying right,
    # y (vertical axis) = sidespin, x (along the flight) = "gyro"/corkscrew spin.
    comp = axis * rps
    top_back = comp[2] * fwd          # + topspin, - backspin
    result = {
        "rps": round(rps, 2),
        "rpm": round(rps * 60, 0),
        "axis_camera_xyz": [round(v, 3) for v in axis],
        "backspin_rps": round(max(0.0, -top_back), 2),
        "topspin_rps": round(max(0.0, top_back), 2),
        "sidespin_rps": round(abs(comp[1]), 2),
        "sidespin_direction_in_image": ("clockwise seen from above" if comp[1] < 0 else
                                        "counter-clockwise seen from above") if abs(comp[1]) > 0.5 else "none",
        "gyro_rps": round(abs(comp[0]), 2),
        "spin_type": classify(top_back, comp[1], comp[0]),
        "deg_per_frame": round(r["deg_per_frame"], 2),
        "fit_score": round(r["score"], 3),
        "reliable": bool(r["score"] >= RELIABLE_FIT),
        "warning": None if r["score"] >= RELIABLE_FIT else (
            "low fit score: the marking is probably blurred or too small. Use 240 FPS, more light, "
            "and check the ball is at least 20 px across. Don't trust this number."),
        "runner_up_score": round(r["runner_up_score"], 3),
        "frame_pairs_used": r["pairs"],
        "ball_radius_px": round(float(np.median([b[2] for b in found])), 1),
        "flight_direction": flight,
        "max_measurable_rps": fps / 2,
        "fps": fps,
        "method": "3d",
    }
    return result, []


def classify(top_back, side, gyro):
    total = np.sqrt(top_back ** 2 + side ** 2 + gyro ** 2)
    if total < 1.0:
        return "no spin"
    parts = []
    if abs(top_back) >= 0.3 * total:
        parts.append("backspin" if top_back < 0 else "topspin")
    if abs(side) >= 0.3 * total:
        parts.append("sidespin")
    if abs(gyro) >= 0.3 * total:
        parts.append("gyro")
    return "-".join(parts) if parts else "mixed"


def plot(steps, fps, path, result):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    t = np.array([s["frame"] for s in steps]) / fps * 1000
    d = np.array([s["deg"] for s in steps])
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 3.6))
    a1.plot(t, d, "o-", ms=3)
    a1.axhline(result.get("deg_per_frame", 0), color="r", lw=1)
    a1.set_xlabel("time (ms)")
    a1.set_ylabel("rotation per frame (deg)")
    a1.set_title("Per-frame rotation")
    a2.plot(t, np.cumsum(d) / 360.0, "o-", ms=3)
    a2.set_xlabel("time (ms)")
    a2.set_ylabel("revolutions")
    a2.set_title(f"{result.get('rps', '?')} rps = {result.get('rpm', '?')} RPM, {result.get('spin_type', '')}")
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)


def main():
    args = parse_args()
    frames = read_frames(args.video, args.start, args.end, args.roi)
    result, steps = measure(frames, args.fps, args)
    print(json.dumps(result, indent=2))
    if args.out:
        with open(args.out, "w") as f:
            json.dump({"result": result, "steps": steps}, f, indent=2)
    if args.plot and steps:
        plot(steps, args.fps, args.plot, result)


if __name__ == "__main__":
    main()
