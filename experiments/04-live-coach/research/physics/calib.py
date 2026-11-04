# Camera from the table: focal length, rotation, position, fitted so the server's half of the table
# (1.37 x 1.525 m, x from the end line toward the net, y across, z up) lands on its lines.
import cv2, numpy as np, json, sys
from scipy.optimize import least_squares
L, Wd = 1.37, 1.525; hw = Wd / 2
NH, NO = 0.1525, 0.1525    # the net: 15.25 cm high, reaching 15.25 cm past each side
LINES = {  # model lines: two 3D points each (the table top at z = 0)
  "end": ((0, -hw, 0), (0, hw, 0)), "net": ((L, -hw, 0), (L, hw, 0)), "far": ((0, hw, 0), (L, hw, 0)),
  "near": ((0, -hw, 0), (L, -hw, 0)), "centre": ((0, 0, 0), (L, 0, 0)), "nettop": ((L, -hw - NO, NH), (L, hw + NO, NH)) }
W, H = 1920, 1080
def K_of(f): return np.array([[f, 0, W/2], [0, f, H/2], [0, 0, 1.0]])
def project(p, P3):
    f, rv, tv = p[0], p[1:4], p[4:7]
    pts, _ = cv2.projectPoints(np.array(P3, float).reshape(-1, 1, 3), rv, tv, K_of(f), None)
    return pts.reshape(-1, 2)
def line_img(p, name):
    return project(p, list(LINES[name]))
def dist(q, pts):  # signed distance of pts to the infinite line through q[0], q[1]
    d = q[1] - q[0]; n = np.array([-d[1], d[0]]) / np.linalg.norm(d)
    return (pts - q[0]) @ n
def fit(obs, p0, loss="soft_l1"):
    def res(p): return np.concatenate([dist(line_img(p, k), np.array(v)) for k, v in obs.items() if len(v)])
    return least_squares(res, p0, loss=loss, f_scale=3.0).x
def init(corners, f=1420.0):
    obj = np.array([(x, y, 0) for (x, y), _ in corners], float); img = np.array([q for _, q in corners], float)
    best = None
    for ff in np.linspace(900, 2600, 35):
        ok, rv, tv = cv2.solvePnP(obj, img, K_of(ff), None, flags=cv2.SOLVEPNP_IPPE)
        e = np.abs(project(np.r_[ff, rv.ravel(), tv.ravel()], obj) - img).max()
        if best is None or e < best[0]: best = (e, np.r_[ff, rv.ravel(), tv.ravel()])
    return best[1]
def snap(img, p, obs, names=("end", "far", "near", "centre", "nettop"), band=10):
    """add the white pixels lying along each projected line (within the visible part)"""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    white = (hsv[..., 1] < 70) & (hsv[..., 2] > 160)
    ys, xs = np.nonzero(white); P = np.stack([xs, ys], 1).astype(float)
    out = {k: list(v) for k, v in obs.items()}
    for k in names:
        q = line_img(p, k); d = np.abs(dist(q, P))
        t = (P - q[0]) @ (q[1] - q[0]) / np.sum((q[1] - q[0]) ** 2)
        sel = P[(d < band) & (t > 0.02) & (t < 0.98)]
        if len(sel) > 60: out[k] = [tuple(x) for x in sel[:: max(1, len(sel) // 400)]]
    return out
def draw(img, p, path):
    im = img.copy()
    for k in LINES:
        q = line_img(p, k).astype(int); cv2.line(im, tuple(q[0]), tuple(q[1]), (0, 255, 255) if k == "nettop" else (0, 0, 255), 2)
    cv2.imwrite(path, cv2.resize(im, (960, 540)))
def pose(p):
    R, _ = cv2.Rodrigues(p[1:4]); C = -R.T @ p[4:7]
    return {"f": p[0], "hfov": 2 * np.degrees(np.arctan(W / 2 / p[0])), "camera_m": C.round(3).tolist(), "R": R.tolist(), "rvec": p[1:4].tolist(), "tvec": p[4:7].tolist()}
if __name__ == "__main__":
    setups = json.load(open(sys.argv[1]))
    out = {}
    for name, s in setups.items():
        img = cv2.imread(s["frame"])
        p = init([((x, y), q) for (x, y, q) in s["corners"]])
        obs = {k: v for k, v in s["lines"].items()}
        p = fit(obs, p, loss="linear")
        for it in range(3):
            p = fit(snap(img, p, obs), p)
        sn = snap(img, p, obs); r = np.concatenate([dist(line_img(p, k), np.array(v)) for k, v in sn.items() if len(v)])
        draw(img, p, f"fit_{name}.jpg")
        out[name] = dict(pose(p), resid_px=float(np.median(np.abs(r))), npts=len(r))
        print(name, "f %.0f hfov %.1f camera %s resid %.2f px" % (p[0], out[name]["hfov"], out[name]["camera_m"], out[name]["resid_px"]))
    json.dump(out, open(sys.argv[2], "w"), indent=1)
