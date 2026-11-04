# Spin from the flight alone: each measured stretch of a club serve, fitted in table coordinates
# (from calib.py's camera) with gravity, drag and the Magnus push; compared with the marked reading.
import json, sys, numpy as np, cv2
from scipy.optimize import least_squares
G = np.array([0, 0, -9.81]); KD = 0.126; KM = 0.0173; BALL_R = 0.02; FPS = 240
poses = json.load(open(sys.argv[1])); rows = [json.loads(l) for l in open(sys.argv[2])]
def Kmat(f): return np.array([[f, 0, 960], [0, f, 540], [0, 0, 1.0]])
def simulate(p0, v0, w, n, dt=1 / FPS):
    p, v, out = p0.copy(), v0.copy(), []
    for _ in range(n):
        out.append(p.copy())
        def acc(v): return G - KD * np.linalg.norm(v) * v + KM * np.cross(w, v)
        k1v = acc(v); k1p = v; k2v = acc(v + k1v * dt / 2); k2p = v + k1v * dt / 2
        k3v = acc(v + k2v * dt / 2); k3p = v + k2v * dt / 2; k4v = acc(v + k3v * dt); k4p = v + k3v * dt
        p = p + dt / 6 * (k1p + 2 * k2p + 2 * k3p + k4p); v = v + dt / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
    return np.array(out)
def basis(v):
    h = np.array([v[0], v[1], 0.0]); h /= max(1e-9, np.linalg.norm(h))
    e1 = np.cross(h, [0, 0, 1.0]); return e1, np.array([0, 0, 1.0])   # backspin (lifts), sidespin (vertical axis)
def fit_stretch(pose, pts, use_spin=True):
    f = pose["f"]; R = np.array(pose["R"]); t = np.array(pose["tvec"]); K = Kmat(f); Ki = np.linalg.inv(K)
    fr = pts[:, 0] - pts[0, 0]; n = int(fr[-1]) + 1
    def to_world(u, v, r):
        Z = f * BALL_R / r; pc = Z * (Ki @ [u, v, 1.0]); return R.T @ (pc - t)
    a = to_world(*pts[0, 1:4]); b = to_world(*pts[-1, 1:4])
    v0 = (b - a) / (fr[-1] / FPS); x0 = np.r_[a, v0, 0.0, 0.0]
    def proj(P):
        pc = (R @ P.T).T + t; uv = (K @ pc.T).T; return uv[:, :2] / uv[:, 2:3], f * BALL_R / pc[:, 2]
    def res(x):
        e1, e2 = basis(x[3:6]); w = x[6] * e1 + x[7] * e2 if use_spin else np.zeros(3)
        P = simulate(x[:3], x[3:6], w, n)[fr.astype(int)]
        uv, rr = proj(P)
        rr = np.maximum(rr, 1e-3)
        # a weak prior: spins over ~100 rev/s are rare in serves
        return np.r_[((uv - pts[:, 1:3]) / 2.0).ravel(), np.log(rr / pts[:, 3]) / 0.12, x[6:8] / 100.0]
    sol = least_squares(res, x0, loss="soft_l1", f_scale=2.0)
    r = res(sol.x); px = np.abs(r[: 2 * len(pts)]) * 2.0
    return sol.x, float(np.median(px))
def marked(pose, ph, v0):
    R = np.array(pose["R"]); a = np.array(ph["camAxis"], float); a = -np.array([a[0], a[1], -a[2]])  # left-handed engine axes -> OpenCV: the axis flips
    w = R.T @ (a * ph["rps"]); e1, e2 = basis(v0); return w @ e1, w @ e2, w
out = []
for row in rows:
    pose = poses[row["setup"]]; cx, cy = row["rect"][:2]
    for ph in row["phases"]:
        if ph.get("balls"):     # the measuring pass: full size, the ball's width across its blur for its size
            b = np.array([q for q in ph["balls"] if q], float)
            pts = np.c_[b[:, 0], cx + b[:, 1], cy + b[:, 2], b[:, 4]]
        else:
            tr = np.array(ph["seg"]["track"], float)
            pts = np.c_[tr[:, 0], cx + 2 * tr[:, 1], cy + 2 * tr[:, 2], 2 * tr[:, 3]]
        if len(pts) < 10: continue
        x, px = fit_stretch(pose, pts)
        back, side = x[6], x[7]; mb, ms, w = marked(pose, ph, x[3:6])
        out.append(dict(file=row["file"], setup=row["setup"], phase=ph["phase"], clear=ph["clear"], n=len(pts), speed=float(np.linalg.norm(x[3:6])),
                        p0=x[:3].round(2).tolist(), v0=x[3:6].round(2).tolist(), fit_back=float(back), fit_side=float(side), mark_back=float(mb), mark_side=float(ms), mark_rps=ph["rps"], px=px, type=ph["type"]))
        o = out[-1]
        print(f"{o['file'][:10]:10} {o['setup']} {o['phase'][:6]:6} n{o['n']:3d} {'C' if o['clear'] else '-'} v {o['speed']:4.1f} m/s  p0 {o['p0']}  back: marked {mb:6.1f} flight {back:6.1f} | side: marked {ms:6.1f} flight {side:6.1f}  fit {px:.1f}px  ({o['type']})")
json.dump(out, open(sys.argv[3], "w"))
ok = [o for o in out if o["clear"] and o["n"] >= 20]
eb = [abs(o["fit_back"] - o["mark_back"]) for o in ok]; es = [abs(o["fit_side"] - o["mark_side"]) for o in ok]
sg = sum((o["fit_back"] > 0) == (o["mark_back"] > 0) for o in ok if abs(o["mark_back"]) > 8)
print(f"clear stretches of 20+ frames: {len(ok)}; backspin part: sign right {sg}/{sum(abs(o['mark_back']) > 8 for o in ok)}, median error {np.median(eb):.1f} rps; sidespin part: median error {np.median(es):.1f} rps")
