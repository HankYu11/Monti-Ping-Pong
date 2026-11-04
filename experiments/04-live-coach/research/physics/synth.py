# A made-up serve with known spin through the S camera, fitted back: do the fits recover it?
import json, sys, numpy as np, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fit3d_core import simulate, basis, Kmat, fit_stretch, BALL_R, FPS
import joint as J
pose = json.load(open(sys.argv[1]))["S"]; R = np.array(pose["R"]); t = np.array(pose["tvec"]); K = Kmat(pose["f"])
rng = np.random.default_rng(1)
def project(P):
    pc = (R @ P.T).T + t; uv = (K @ pc.T).T; return uv[:, :2] / uv[:, 2:3], pose["f"] * BALL_R / pc[:, 2]
for back, side in ((40, 0), (30, 20), (-25, 0), (0, 30)):
    p = np.array([0.25, 0.35, 0.22]); v = np.array([3.2, -0.6, 0.2]); e1, e2 = basis(v); w = back * e1 + side * e2
    pts, P, V, fr = [], p.copy(), v.copy(), 0
    while P[2] > BALL_R:
        pts.append((fr, P.copy())); P, V = J.simulate_pv(P, V, w, 1, 1 / FPS); fr += 1
    nA = len(pts); V = np.array([V[0] * 0.85, V[1] * 0.85, -0.9 * V[2]]); w2 = 0.8 * w
    for k in range(40): pts.append((fr, P.copy())); P, V = J.simulate_pv(P, V, w2, 1, 1 / FPS); fr += 1
    F = np.array([a for a, _ in pts], float); X = np.array([b for _, b in pts]); uv, rr = project(X)
    obs = np.c_[F, uv + rng.normal(0, 0.7, uv.shape), rr * np.exp(rng.normal(0, 0.05, len(rr)))]
    A, B = obs[2:nA - 2], obs[nA + 3:]
    xs, _ = fit_stretch(pose, A); xb, _ = fit_stretch(pose, B); xj, _ = J.joint(pose, A, B)
    print(f"true back {back:4d} side {side:4d} | single: pre {xs[6]:6.1f} {xs[7]:6.1f}  post {xb[6]:6.1f} {xb[7]:6.1f} (true post {0.8*back:.0f} {0.8*side:.0f}) | joint: pre {xj[9]:6.1f} {xj[10]:6.1f}  post {xj[11]:6.1f} {xj[12]:6.1f}   n {len(A)}/{len(B)}")
