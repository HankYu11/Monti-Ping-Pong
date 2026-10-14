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
    p.add_argument("--hfov", type=float, default=68.0,
                   help="camera's horizontal field of view in degrees (for the toward/away-from-camera "
                        "part of the flight); Pixel 8 main camera in 16:9 video is roughly 68")
    p.add_argument("--auto", action="store_true",
                   help="find the serve's flight automatically (overrides --start/--end/--roi)")
    p.add_argument("--min-radius", type=float, default=6.0, help="pixels")
    p.add_argument("--inner", type=float, default=0.15, help="inner polar radius, fraction of ball radius")
    p.add_argument("--outer", type=float, default=0.80, help="outer polar radius (avoid the dark edge)")
    p.add_argument("--n-angle", type=int, default=360)
    p.add_argument("--n-radius", type=int, default=24)
    p.add_argument("--method", choices=["pole", "3d", "2d"], default="pole",
                   help="pole: track the black hemisphere's pole (robust to blur, small balls; needs a "
                        "half-black ball); 3d: match surface detail between frames (needs a sharp, "
                        "larger ball); 2d: in-image rotation only (v1)")
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


def deflicker(frames, background):
    """Mains-powered lights pulse at twice the mains frequency; at 240 FPS that makes
    frames alternately brighter and darker, often in horizontal bands (rolling shutter).
    Rescale every row of every frame to match the background's brightness. The ball is
    small, so a per-row median ratio is unaffected by it."""
    out = []
    bg = np.maximum(background, 1.0)
    for g in frames:
        ratio = np.median(g / bg, axis=1)
        ratio = cv2.GaussianBlur(ratio.reshape(-1, 1).astype(np.float32), (1, 0), 9).ravel()
        out.append(g / np.maximum(ratio, 0.2)[:, None])
    return out


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


# ---------------------------------------------------------------- pole tracking

def smooth_centres(balls, deg=2, tol=2.5):
    """A ball in flight moves smoothly, so fit x(t) and y(t) with low-order polynomials.
    This removes the jitter caused by the detector seeing mostly the black half."""
    t = np.array([i for i, b in enumerate(balls) if b is not None], dtype=float)
    xy = np.array([b[:2] for b in balls if b is not None])
    keep = np.ones(len(t), bool)
    for _ in range(3):
        cx = np.polyfit(t[keep], xy[keep, 0], deg)
        cy = np.polyfit(t[keep], xy[keep, 1], deg)
        res = np.hypot(np.polyval(cx, t) - xy[:, 0], np.polyval(cy, t) - xy[:, 1])
        keep = res < max(tol, 2.5 * np.median(res[keep]))
    return cx, cy


def measure_pole(frames, balls, fps, inner=0.92, shading_bins=10):
    """Track the direction of the black hemisphere's pole, b(t), and fit its rotation.

    For a ball with one black hemisphere seen by a distant camera:
      - the black share of the visible disc is f = (1 + b_z) / 2 (exactly linear), and
      - the black area's centre lies toward (b_x, b_y) in the image.
    So each frame gives the pole's 3D direction from coarse, blur-tolerant measurements.
    The pole then turns around the spin axis: fit a plane to b(t) (its normal is the axis)
    and a straight line to the angle around the axis over time (its slope is the rate).
    """
    idx = [i for i, b in enumerate(balls) if b is not None]
    if len(idx) < 8:
        return None
    cxp, cyp = smooth_centres(balls)
    r = float(np.median([balls[i][2] for i in idx]))
    H, W = frames[0].shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

    D_all, rows = [], []
    for i in idx:
        cx, cy = np.polyval(cxp, i), np.polyval(cyp, i)
        x0, x1 = int(max(0, cx - 2.6 * r)), int(min(W, cx + 2.6 * r + 1))
        y0, y1 = int(max(0, cy - 2.6 * r)), int(min(H, cy + 2.6 * r + 1))
        g = frames[i][y0:y1, x0:x1]
        dx, dy = xx[y0:y1, x0:x1] - cx, yy[y0:y1, x0:x1] - cy
        rr = np.hypot(dx, dy)
        disc = rr <= inner * r
        ring = (rr >= 1.6 * r) & (rr <= 2.5 * r)
        if disc.sum() < 20 or ring.sum() < 20:
            continue
        wall = float(np.median(g[ring]))                 # per-frame: cancels light flicker
        D = np.clip(1.0 - g[disc] / max(wall, 1.0), 0, 1)
        D_all.append(D)
        rows.append((i, D, dx[disc] / r, dy[disc] / r))
    if len(rows) < 8:
        return None
    Dcat = np.concatenate(D_all)
    if np.percentile(Dcat, 95) - np.percentile(Dcat, 10) < 0.15:
        return {"error": "marking contrast too low"}
    # Lighting varies across the ball (lit from above: top bright, bottom in shadow). As the
    # ball turns, every spot shows both white and black, so per spot on the disc, how dark
    # "white" and "black" look there can be read off all frames. Normalising each pixel by
    # its own spot's levels removes the lighting gradient, which otherwise couples the
    # pole's up/down direction with its toward-camera component and tilts the axis.
    nb = shading_bins
    UX = np.concatenate([row[2] for row in rows])
    UY = np.concatenate([row[3] for row in rows])
    bi = np.clip(((UX + 1) / 2 * nb).astype(int), 0, nb - 1)
    bj = np.clip(((UY + 1) / 2 * nb).astype(int), 0, nb - 1)
    lo_map = np.full((nb, nb), np.percentile(Dcat, 10))
    hi_map = np.full((nb, nb), np.percentile(Dcat, 95))
    for a in range(nb):
        for b_ in range(nb):
            sel = Dcat[(bi == a) & (bj == b_)]
            if len(sel) >= 30:
                lo_map[a, b_] = np.percentile(sel, 10)
                hi_map[a, b_] = np.percentile(sel, 95)
    lo_map = cv2.GaussianBlur(lo_map.astype(np.float32), (3, 3), 0.8)
    hi_map = cv2.GaussianBlur(hi_map.astype(np.float32), (3, 3), 0.8)
    t_list, B = [], []
    for i, D, ux, uy in rows:
        a = np.clip(((ux + 1) / 2 * nb).astype(int), 0, nb - 1)
        b_ = np.clip(((uy + 1) / 2 * nb).astype(int), 0, nb - 1)
        if shading_bins > 1:
            dw, db = lo_map[a, b_], hi_map[a, b_]
        else:
            dw, db = np.percentile(Dcat, 10), np.percentile(Dcat, 95)
        s = np.clip((D - dw) / np.maximum(db - dw, 0.05), 0, 1)
        f = float(s.mean())
        bz = float(np.clip(2 * f - 1, -0.98, 0.98))
        w = s.sum()
        if w > 1e-6:
            mx, my = float((s * ux).sum() / w), float((s * uy).sum() / w)
        else:
            mx, my = 0.0, 0.0
        n = np.hypot(mx, my)
        rho = np.sqrt(1 - bz * bz)
        b = np.array([rho * mx / n, rho * my / n, bz]) if n > 1e-6 else np.array([0, 0, np.sign(bz)])
        t_list.append(i)
        B.append(b / np.linalg.norm(b))
    t = np.array(t_list, float)
    B = np.array(B)

    return fit_rotating_vector(t, B, fps)


def fit_rotating_vector(t, B, fps, step_rps=0.05):
    """Fit b(t) = c + cos(w t) P + sin(w t) Q to the pole track for every spin rate w.

    A unit vector turning about axis a has exactly this form (c along a, P and Q
    perpendicular to a), and for a fixed w it is a linear least-squares problem. Scanning
    w from 0.5 rps up to half the frame rate and keeping the best fit never loses count
    of turns. The axis is P x Q (rotation goes from P toward Q)."""
    n = len(t)
    var = float(((B - B.mean(0)) ** 2).sum())
    if var / n < 0.02:                                     # pole hardly moves: no spin
        return {"rps": 0.0, "axis": [0.0, 0.0, 1.0], "deg_per_frame": 0.0, "score": 1.0,
                "runner_up_score": float("nan"), "pairs": n - 1, "angle_fit_r2": None,
                "angle_rms_deg": None, "pole_swing": round(float(np.sqrt(var / n)), 3),
                "pole_track": {"frames": t.astype(int).tolist(), "pole": np.round(B, 3).tolist(),
                               "angle_deg": [0.0] * n}}
    rates = np.arange(0.5, fps / 2, step_rps)
    best = None
    resid = np.empty(len(rates))
    for k, rps in enumerate(rates):
        w = 2 * np.pi * rps / fps                          # radians per frame
        X = np.column_stack([np.ones(n), np.cos(w * t), np.sin(w * t)])
        coef, *_ = np.linalg.lstsq(X, B, rcond=None)
        res = float(((X @ coef - B) ** 2).sum())
        resid[k] = res
        if best is None or res < best[0]:
            best = (res, rps, coef)
    res, rps, coef = best
    c, P, Q = coef
    a = np.cross(P, Q)
    a = a / (np.linalg.norm(a) + 1e-12)
    r2 = 1 - res / var
    # second-best rate that is clearly a different answer (not a neighbour of the best)
    far = np.abs(rates - rps) > 2.0
    runner = 1 - float(resid[far].min()) / var if far.any() else float("nan")
    w = 2 * np.pi * rps / fps
    ang = np.degrees(w * t)
    swing = float(0.5 * (np.linalg.norm(P) + np.linalg.norm(Q)))
    rms_deg = float(np.degrees(np.sqrt(res / n)))
    # quality: good fit, clearly better than any other rate, and the pole visibly swings
    score = float(max(0.0, r2) * min(1.0, swing / 0.4))
    if np.isfinite(runner) and runner > r2 - 0.05:
        score *= 0.5                                       # ambiguous between two rates
    return {"rps": float(rps), "axis": a.tolist(), "deg_per_frame": float(np.degrees(w)),
            "score": score, "runner_up_score": runner, "pairs": n - 1,
            "angle_fit_r2": round(float(r2), 4), "angle_rms_deg": round(rms_deg, 1),
            "pole_swing": round(swing, 2),
            "pole_track": {"frames": t.astype(int).tolist(), "pole": np.round(B, 3).tolist(),
                           "angle_deg": np.round(ang, 1).tolist()}}


# ---------------------------------------------------------------- automatic flight finding

def find_flight(path, scale=0.5, min_len=12, skip_after_contact=12, max_frames=40):
    """Find the serve's flight automatically: the longest track of a small, round, moving
    blob that travels mostly sideways. Returns (start, end, roi) in full-resolution frame
    coordinates, or None. The first frames after contact are skipped (racket nearby)."""
    cap = cv2.VideoCapture(path)
    small = []
    while True:
        ok, img = cap.read()
        if not ok:
            break
        g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        small.append(cv2.resize(g, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA))
    cap.release()
    if len(small) < min_len:
        return None
    bgf = np.median(np.stack(small[::4]), axis=0).astype(np.float32)
    small = [np.clip(g, 0, 255).astype(np.uint8) for g in deflicker([g.astype(np.float32) for g in small], bgf)]
    bg = np.median(np.stack(small[::4]), axis=0).astype(np.int16)
    cands = []
    for i, g in enumerate(small):
        mask = (np.abs(g.astype(np.int16) - bg) > 30).astype(np.uint8)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
        n, lab, st, cen = cv2.connectedComponentsWithStats(mask, 8)
        frame_c = []
        for k in range(1, n):
            x, y, w, h, area = st[k]
            if not (12 <= area <= 900):
                continue
            if max(w, h) / max(1, min(w, h)) > 1.7 or area / float(w * h) < 0.5:
                continue
            frame_c.append((float(cen[k][0]), float(cen[k][1]), float(np.sqrt(area / np.pi))))
        cands.append(frame_c)
    # greedy linking with a constant-velocity guess
    tracks, active = [], []
    for i, cs in enumerate(cands):
        used = set()
        for tr in active:
            (f1, x1, y1, r1) = tr[-1]
            if len(tr) > 1:
                f0, x0, y0, _ = tr[-2]
                px, py = x1 + (x1 - x0) / max(1, f1 - f0) * (i - f1), y1 + (y1 - y0) / max(1, f1 - f0) * (i - f1)
            else:
                px, py = x1, y1
            best, bd = None, 25.0
            for j, (x, y, r) in enumerate(cs):
                d = np.hypot(x - px, y - py)
                if j not in used and d < bd:
                    best, bd = j, d
            if best is not None:
                used.add(best)
                tr.append((i,) + cs[best])
        still = [tr for tr in active if i - tr[-1][0] <= 2]
        tracks += [tr for tr in active if i - tr[-1][0] > 2]
        active = still + [[(i,) + c] for j, c in enumerate(cs) if j not in used]
    tracks += active
    good = []
    for tr in tracks:
        if len(tr) < min_len:
            continue
        dx = tr[-1][1] - tr[0][1]
        dy = tr[-1][2] - tr[0][2]
        if abs(dx) > 60 and abs(dx) > abs(dy):
            good.append(tr)
    if not good:
        return None
    tr = max(good, key=len)
    # keep only the steady sideways flight after contact: the longest run of frames moving
    # in the flight direction (this drops the toss before contact and the bounce after)
    sign = np.sign(tr[-1][1] - tr[0][1])
    runs, cur = [], [tr[0]]
    for a, b in zip(tr[:-1], tr[1:]):
        step = (b[1] - a[1]) / max(1, b[0] - a[0])
        if sign * step > 1.5:
            cur.append(b)
        else:
            if len(cur) > 1:
                runs.append(cur)
            cur = [b]
    runs.append(cur)
    tr = max(runs, key=len)
    tr = tr[skip_after_contact:skip_after_contact + max_frames]
    if len(tr) < 8:
        return None
    xs = np.array([p[1] for p in tr]) / scale
    ys = np.array([p[2] for p in tr]) / scale
    r = float(np.median([p[3] for p in tr])) / scale
    pad = 4 * r
    H, W = small[0].shape
    x0, y0 = int(max(0, xs.min() - pad)), int(max(0, ys.min() - pad))
    x1, y1 = int(min(W / scale, xs.max() + pad)), int(min(H / scale, ys.max() + pad))
    return tr[0][0], tr[-1][0] + 1, (x0, y0, x1 - x0, y1 - y0)


# ---------------------------------------------------------------- main

def measure(frames, fps, args):
    background = np.median(np.stack(frames), axis=0)
    frames = deflicker(frames, background)
    background = np.median(np.stack(frames), axis=0)
    balls = [find_ball(g, background, args.min_radius) for g in frames]
    if args.method in ("3d", "pole"):
        return measure_with_3d(frames, balls, fps, args.method, args)
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


def flight_geometry(balls, fps, frame_width, hfov_deg):
    """3D flight direction and real-world speed/acceleration from the ball's image track.

    Sideways motion comes from the image velocity; motion toward/away from the camera comes
    from the ball's apparent size (radius grows as 1/distance). The ball's known size
    (40 mm) gives the scale in mm per pixel. Camera axes: x right, y down, z toward camera.
    """
    t = np.array([i for i, b in enumerate(balls) if b is not None], float)
    xy = np.array([b[:2] for b in balls if b is not None])
    rr = np.array([b[2] for b in balls if b is not None])
    keep = np.ones(len(t), bool)
    for _ in range(3):
        px = np.polyfit(t[keep], xy[keep, 0], 2)
        py = np.polyfit(t[keep], xy[keep, 1], 2)
        res = np.hypot(np.polyval(px, t) - xy[:, 0], np.polyval(py, t) - xy[:, 1])
        keep = res < max(2.5, 3 * np.median(res[keep]))
    tm = t.mean()
    vx, vy = np.polyval(np.polyder(px), tm), np.polyval(np.polyder(py), tm)    # px / frame
    ax, ay = 2 * px[0], 2 * py[0]                                              # px / frame^2
    dlnr = np.polyfit(t[keep], np.log(rr[keep]), 1)[0]                         # per frame
    f_px = (frame_width / 2) / np.tan(np.radians(hfov_deg) / 2)
    fdir = np.array([vx / f_px, vy / f_px, dlnr])          # z: toward the camera
    fdir /= np.linalg.norm(fdir)
    r_med = float(np.median(rr))
    mm_px = 40.0 / (2 * r_med)
    to_ms = mm_px / 1000 * fps
    # Which way is down? A flying ball accelerates downward (gravity, give or take ~25% from
    # spin), so the acceleration's dominant image direction tells whether the video is
    # upright, upside down or sideways, whatever the file's rotation metadata did.
    turn = upright_rotation(ax, ay)
    fdir = turn @ fdir
    acc = turn @ np.array([ax, ay, 0.0])
    return {
        "flight_dir_camera": fdir,
        "upright_turn": turn,
        "video_rotated_deg": int(round(np.degrees(np.arctan2(turn[1, 0], turn[0, 0])))) % 360,
        "speed_in_image_m_s": float(np.hypot(vx, vy) * to_ms),
        "toward_camera_deg": float(np.degrees(np.arcsin(np.clip(fdir[2], -1, 1)))),
        "down_accel_m_s2": float(acc[1] * to_ms * fps),
        "mm_per_px": mm_px,
    }


def upright_rotation(ax, ay):
    """Rotation about the viewing axis that turns the measured acceleration to image-down
    (+y). Snaps to 0/90/180/270 degrees; returns the identity if the acceleration is tiny."""
    if np.hypot(ax, ay) < 1e-6:
        return np.eye(3)
    if abs(ay) >= abs(ax):
        if ay > 0:
            return np.eye(3)
        return np.array([[-1.0, 0, 0], [0, -1.0, 0], [0, 0, 1.0]])       # upside down
    if ax > 0:                                                          # down is image-right
        return np.array([[0, -1.0, 0], [1.0, 0, 0], [0, 0, 1.0]])
    return np.array([[0, 1.0, 0], [-1.0, 0, 0], [0, 0, 1.0]])          # down is image-left


def spin_components(rps, axis, fdir, up_img=(0.0, -1.0, 0.0)):
    """Split the spin by what it does to the flight (Magnus effect), relative to the real
    3D flight direction:
      backspin(+)/topspin(-): rotation about the horizontal axis across the flight -> lift/dip
      sidespin: rotation about the vertical axis -> the ball curves left(-)/right(+), as seen by
                the server looking along the flight
      gyro: rotation about the flight direction itself (corkscrew) -> no curve
    Assumes the camera is level (image up = real up)."""
    w = rps * np.asarray(axis, float)
    f = np.asarray(fdir, float)
    u = np.asarray(up_img, float)
    u = u - (u @ f) * f
    u /= np.linalg.norm(u)
    right = -np.cross(f, u)            # server's right (see README: camera axes are x right, y down, z to camera)
    magnus = np.cross(w, f)            # direction of the Magnus force (up to a positive factor)
    lift = float(magnus @ u)           # > 0: lift = backspin
    curve = float(magnus @ right)      # > 0: curves to the server's right
    gyro = float(w @ f)
    tilt = float(np.degrees(np.arctan2(curve, lift)))   # 0 = pure backspin, +-90 = pure sidespin, 180 = topspin
    return lift, curve, gyro, tilt


def spin_label(total, lift, curve, gyro, no_spin_rps=3.0):
    if total < no_spin_rps:
        return "no spin"
    parts = []
    if abs(curve) >= 0.35 * total:
        parts.append("side")
    if abs(lift) >= 0.35 * total:
        parts.append("back" if lift > 0 else "top")
    if not parts:
        return "gyro (corkscrew)" if abs(gyro) >= 0.5 * total else "mixed"
    label = "-".join(parts) + "spin"
    if abs(curve) >= 0.35 * total:
        label += f" (curves {'right' if curve > 0 else 'left'})"
    if abs(gyro) >= 0.5 * total:
        label += " + gyro"
    return label


def measure_with_3d(frames, balls, fps, method="3d", args=None):
    found = [b for b in balls if b is not None]
    if len(found) < 4:
        return {"error": "ball found in fewer than 4 frames", "frames_with_ball": len(found)}, []
    r = measure_3d(frames, balls, fps) if method == "3d" else measure_pole(frames, balls, fps)
    if r is None:
        return {"error": "ball found in too few consecutive frames"}, []
    if "error" in r:
        return r, []
    axis = np.array(r["axis"])
    rps = r["rps"]
    xs = [b[0] for b in found]
    flight = "right" if xs[-1] > xs[0] else "left"
    width = getattr(args, "frame_width", None) or frames[0].shape[1]
    hfov = getattr(args, "hfov", 68.0)
    geo = flight_geometry(balls, fps, width, hfov)
    axis = geo["upright_turn"] @ axis
    lift, curve, gyro, tilt = spin_components(rps, axis, geo["flight_dir_camera"])
    result = {
        "rps": round(rps, 2),
        "rpm": round(rps * 60, 0),
        "spin_type": spin_label(rps, lift, curve, gyro),
        "backspin_rps": round(max(0.0, lift), 1),
        "topspin_rps": round(max(0.0, -lift), 1),
        "sidespin_rps": round(abs(curve), 1),
        "sidespin_curves": ("right" if curve > 0 else "left") if abs(curve) >= 1.0 else "none",
        "gyro_rps": round(abs(gyro), 1),
        "axis_tilt_deg": round(tilt, 0),
        "axis_camera_xyz": [round(v, 3) for v in axis],
        "flight": {
            "direction_in_image": flight,
            "toward_camera_deg": round(geo["toward_camera_deg"], 1),
            "speed_m_s": round(geo["speed_in_image_m_s"], 2),
            "down_accel_m_s2": round(geo["down_accel_m_s2"], 1),
            "video_rotated_deg": geo["video_rotated_deg"],
            "note": "gravity alone = 9.8; less = lift (backspin), more = dip (topspin); "
                    "rough, the scale comes from the ball's size in pixels",
        },
        "deg_per_frame": round(r["deg_per_frame"], 2),
        "fit_score": round(r["score"], 3),
        "reliable": bool(r["score"] >= RELIABLE_FIT),
        "warning": None if r["score"] >= RELIABLE_FIT else (
            "low fit score: the marking is probably blurred or too small. Use 240 FPS, more light, "
            "and check the ball is at least 20 px across. Don't trust this number."),
        "runner_up_score": round(r["runner_up_score"], 3),
        "frame_pairs_used": r["pairs"],
        "ball_radius_px": round(float(np.median([b[2] for b in found])), 1),
        "max_measurable_rps": fps / 2,
        "fps": fps,
        "method": method,
    }
    for k in ("angle_fit_r2", "angle_rms_deg", "pole_swing"):
        if k in r:
            result[k] = r[k]
    return result, ([r["pole_track"]] if "pole_track" in r else [])


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
    if args.auto:
        found = find_flight(args.video)
        if found is None:
            print(json.dumps({"error": "no ball flight found automatically; use --start/--end/--roi"}))
            return
        args.start, args.end, args.roi = found
    cap = cv2.VideoCapture(args.video)
    ok, first = cap.read()
    cap.release()
    args.frame_width = first.shape[1] if ok else None
    frames = read_frames(args.video, args.start, args.end, args.roi)
    result, steps = measure(frames, args.fps, args)
    result["segment"] = {"start": args.start, "end": args.end, "roi": list(args.roi) if args.roi else None}
    print(json.dumps(result, indent=2))
    if args.out:
        with open(args.out, "w") as f:
            json.dump({"result": result, "steps": steps}, f, indent=2)
    if args.plot and steps:
        if "pole" in steps[0]:
            plot_pole(steps[0], args.fps, args.plot, result)
        else:
            plot(steps, args.fps, args.plot, result)


def plot_pole(track, fps, path, result):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    t = np.array(track["frames"]) / fps * 1000
    B = np.array(track["pole"])
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 3.6))
    for k, lab in enumerate(["x (right)", "y (down)", "z (to camera)"]):
        a1.plot(t, B[:, k], "o-", ms=3, label=lab)
    a1.set_xlabel("time (ms)")
    a1.set_ylabel("black pole direction")
    a1.set_title("Pole of the black half, per frame")
    a1.legend(fontsize=8)
    ang = np.array(track["angle_deg"])
    a2.plot(t, ang / 360.0, "o", ms=3)
    k, c = np.polyfit(t, ang / 360.0, 1)
    a2.plot(t, k * t + c, "r-", lw=1)
    a2.set_xlabel("time (ms)")
    a2.set_ylabel("revolutions about the spin axis")
    a2.set_title(f"{result.get('rps', '?')} rps = {result.get('rpm', '?')} RPM, {result.get('spin_type', '')}")
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)


if __name__ == "__main__":
    main()
