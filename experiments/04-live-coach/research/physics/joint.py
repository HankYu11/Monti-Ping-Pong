# The serve before and after its bounce in one fit: both stretches meet on the table (z = ball
# radius) at the same point and moment, which pins the ball's distance there.
import json, sys, numpy as np
from scipy.optimize import least_squares
sys.path.insert(0, sys.argv[0].rsplit("/", 1)[0])
from fit3d_core import simulate, basis, Kmat, marked, fit_stretch, BALL_R, FPS, G, KD, KM
RW = float(__import__("os").environ.get("RW", "0.12"))     # how much the ball's size counts (its distance)
def simulate_pv(p, v, w, n, dt):
    def acc(v): return G - KD * np.linalg.norm(v) * v + KM * np.cross(w, v)
    for _ in range(n):
        k1v = acc(v); k1p = v; k2v = acc(v + k1v * dt / 2); k2p = v + k1v * dt / 2
        k3v = acc(v + k2v * dt / 2); k3p = v + k2v * dt / 2; k4v = acc(v + k3v * dt); k4p = v + k3v * dt
        p = p + dt / 6 * (k1p + 2 * k2p + 2 * k3p + k4p); v = v + dt / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
    return p, v
def pts_of(row, ph):
    cx, cy = row["rect"][:2]; b = np.array([q for q in ph["balls"] if q], float)
    return np.c_[b[:, 0], cx + b[:, 1], cy + b[:, 2], b[:, 4]]
def joint(pose, A, B):
    f = pose["f"]; R = np.array(pose["R"]); t = np.array(pose["tvec"]); K = Kmat(f); Ki = np.linalg.inv(K)
    def proj(P):
        pc = (R @ P.T).T + t; uv = (K @ pc.T).T; return uv[:, :2] / uv[:, 2:3], f * BALL_R / np.maximum(pc[:, 2], 1e-3)
    def ray_to_table(u, v):   # where the camera ray through (u, v) meets z = ball radius
        C = -R.T @ t; d = R.T @ (Ki @ [u, v, 1.0]); s = (BALL_R - C[2]) / d[2]; return C + s * d
    # start: the bounce between the two stretches, on the table under the mid point
    fb = 0.5 * (A[-1, 0] + B[0, 0]); um = 0.5 * (A[-1, 1:3] + B[0, 1:3]); pb = ray_to_table(*um)
    ta = (A[0, 0] - fb) / FPS; tb = (B[-1, 0] - fb) / FPS
    pa = ray_to_table(*A[0, 1:3]); pa[2] = 0.15; pz = ray_to_table(*B[-1, 1:3]); pz[2] = 0.1
    vin = (pb - pa) / (-ta); vout = (pz - pb) / tb; vin[2] = -1.0; vout[2] = 1.0
    clip = lambda v: v if np.all(np.isfinite(v)) and np.linalg.norm(v) < 15 else np.array([4.0, 0, v[2]])
    vin, vout = clip(vin), clip(vout)
    x0 = np.r_[pb[:2], fb, vin, vout, 0, 0, 0, 0]
    def track(p, v, w, frames, fb):        # from the bounce, forward or back in time, in half-frame steps
        ts = (np.asarray(frames, float) - fb) / FPS; order = np.argsort(np.abs(ts)); out = np.zeros((len(ts), 3))
        P, V, tc = p.copy(), v.copy(), 0.0
        for i in order:
            dt_total = ts[i] - tc; n = max(1, int(abs(dt_total) * FPS * 2 + 0.999))
            seg = simulate_pv(P, V, w, n, dt_total / n); P, V, tc = seg[0], seg[1], ts[i]; out[i] = P
        return out
    def res(x):
        p = np.r_[x[0], x[1], BALL_R]; fb = x[2]
        e1, e2 = basis(x[3:6]); w1 = x[9] * e1 + x[10] * e2
        g1, g2 = basis(x[6:9]); w2 = x[11] * g1 + x[12] * g2
        out = []
        for P0, V, W, D in ((p, x[3:6], w1, A), (p, x[6:9], w2, B)):
            P = track(P0, V, W, D[:, 0], fb); uv, rr = proj(P)
            out += [((uv - D[:, 1:3]) / 1.5).ravel(), np.log(rr / D[:, 3]) / RW]
        o = np.concatenate(out); o[~np.isfinite(o)] = 1e3
        return np.r_[o, x[9:13] / 100.0, [x[5] / 20.0 if x[5] > 0 else 0, x[8] / 20.0 if x[8] < 0 else 0]]
    sol = least_squares(res, x0, loss="soft_l1", f_scale=2.0, max_nfev=400)
    x = sol.x; r = res(x)
    nA, nB = len(A), len(B); joint.bias = float(np.median(np.r_[r[2*nA:3*nA], r[3*nA+2*nB:3*nA+3*nB]]) * RW)
    nA = 2 * len(A); nB = 2 * len(B); px = np.median(np.abs(np.r_[r[:nA], r[nA + len(A): nA + len(A) + nB]])) * 1.5
    return x, px
if __name__ == "__main__":
    poses = json.load(open(sys.argv[1])); rows = [json.loads(l) for l in open(sys.argv[2])]
    out = []
    for row in rows:
        pose = poses[row["setup"]]; ph = [p for p in row["phases"] if p.get("balls")]
        pre = [p for p in ph if p["phase"] in ("paddle", "flight")]; post = [p for p in ph if p["phase"] == "bounce"]
        if not (pre and post): continue
        A = pts_of(row, pre[0]); B = pts_of(row, post[0])
        if len(A) < 8 or len(B) < 8: continue
        try: x, px = joint(pose, A, B)
        except Exception as e: print(row["file"], "no fit:", e); continue
        for k, (P, V, wb, ws) in enumerate(((pre[0], x[3:6], x[9], x[10]), (post[0], x[6:9], x[11], x[12]))):
            mb, ms, _ = marked(pose, P, V)
            out.append(dict(file=row["file"], phase=P["phase"], clear=P["clear"], n=len(A if k == 0 else B), fit_back=float(wb), fit_side=float(ws), mark_back=float(mb), mark_side=float(ms)))
            o = out[-1]
            print(f"{o['file'][:10]:10} {o['phase'][:6]:6} n{o['n']:3d} {'C' if o['clear'] else '-'} bounce at x {x[0]:.2f} y {x[1]:.2f} m, frame {x[2]:.1f}  back: marked {mb:6.1f} flight {wb:6.1f} | side: marked {ms:6.1f} flight {ws:6.1f}  fit {px:.1f}px  size bias {joint.bias:+.2f}")
    ok = [o for o in out if o["clear"] and o["n"] >= 12]
    eb = [abs(o["fit_back"] - o["mark_back"]) for o in ok]; es = [abs(o["fit_side"] - o["mark_side"]) for o in ok]
    sg = sum((o["fit_back"] > 0) == (o["mark_back"] > 0) for o in ok if abs(o["mark_back"]) > 8)
    print(f"joint, clear stretches of 12+ frames: {len(ok)}; backspin part: sign right {sg}/{sum(abs(o['mark_back']) > 8 for o in ok)}, median error {np.median(eb):.1f} rps; sidespin part: median error {np.median(es):.1f} rps")
    json.dump(out, open(sys.argv[3], "w"))
