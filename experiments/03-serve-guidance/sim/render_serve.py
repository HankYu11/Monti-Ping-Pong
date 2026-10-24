#!/usr/bin/env python3
"""Render a synthetic backspin serve as a 240 fps side-on clip, with the outcome from the
contact simulator: the toss falls, the paddle brushes under it, the ball flies low over the
table and bounces (the bounce is a contact with a still table, same model). Writes OUT.mp4 (VP9)
and OUT.json (the truth).

Camera: side-on at about the table's height, the server on the left, the ball flies right.
Image x = forward, image y = down. Ball 30 px across by default (40 mm: 1.333 mm per pixel).

  python render_serve.py --out s.mp4 --paddle 6 --angle 10 --fall 1.2 --tilt -20 --seed 3
"""
import argparse, json, subprocess
import numpy as np
from contact import hit, impact, R as BALL_R

p = argparse.ArgumentParser()
p.add_argument("--out", required=True)
p.add_argument("--paddle", type=float, required=True, help="paddle speed at the contact point, m/s")
p.add_argument("--angle", type=float, required=True, help="paddle direction above its face, deg (0 = pure brush)")
p.add_argument("--fall", type=float, default=1.0, help="toss falling speed at contact, m/s")
p.add_argument("--kp", type=float, default=0.70)
p.add_argument("--tilt", type=float, default=-20.0, help="face normal leaning from straight up, deg (negative: toward the net)")
p.add_argument("--height", type=float, default=200.0, help="contact height above the table, mm")
p.add_argument("--diam", type=float, default=30.0, help="ball size in the picture, px")
p.add_argument("--exposure", type=float, default=0.5)
p.add_argument("--noise", type=float, default=3.0)
p.add_argument("--seed", type=int, default=0)
a = p.parse_args()
rng = np.random.default_rng(a.seed)
W, H, FPS = 1280, 720, 240.0
DIAM = a.diam
MMPX = 40.0 / DIAM
rad = DIAM / 2
TABLE_Y = 0.72 * H                     # the table's top edge in the picture

out = hit(a.paddle, a.angle, a.fall, a.kp, a.tilt)
v_out, w_out, t_w, n_w, v_pad = out["v"], out["w"], out["along"], out["normal"], out["v_paddle"]

# --- the path, in mm (world: x forward, y up from the table), frames from contact (0 = the hit)
g = 9810.0
PRE, POST, LEAD = 45, 90, 30
c0 = np.array([330.0, a.height, 0.0])
Rmm = BALL_R * 1000
# after the hit: fly until the ball touches the table, bounce once (contact with a still table)
t_b = None
vy, y0 = v_out[1] * 1000, a.height - Rmm
disc = vy * vy + 2 * g * y0
if disc > 0: t_b = (vy + np.sqrt(disc)) / g     # seconds to the bounce
if t_b is not None:
    v_in = v_out - np.array([0, g / 1000 * t_b, 0])
    v_b, w_b, _ = impact(v_in, w_out, [0, 0, 0], [0, 1, 0], a.kp, mu=0.25)   # a table: low friction, no rubber
    p_b = c0 + v_out * 1000 * t_b - np.array([0, 0.5 * g * t_b * t_b, 0])

def ball_pos(t):                                   # t in frames, may be fractional
    s = t / FPS
    if t < 0:                                      # the toss: falling at `fall` when it reaches the paddle
        return c0 + np.array([0, -a.fall, 0]) * 1000 * s - np.array([0, 0.5 * g * s * s, 0])
    if t_b is None or s < t_b:
        return c0 + v_out * 1000 * s - np.array([0, 0.5 * g * s * s, 0])
    s -= t_b
    return p_b + v_b * 1000 * s - np.array([0, 0.5 * g * s * s, 0])
def ball_turn(t):                                  # the ball's turn so far, as a rotation vector (world)
    s = t / FPS
    if t < 0: return np.zeros(3)
    if t_b is None or s < t_b: return w_out * s
    return w_out * t_b + w_b * (s - t_b)           # fine while both spins share an axis (side-on: z)

# world (x fwd, y up, z toward camera) -> camera (x right, y down, z toward camera). Flipping y
# mirrors the axes, and a spin axis turns the other way under a mirror, hence the minus.
to_cam = np.diag([1.0, -1.0, 1.0])
def to_px(pw):
    return np.array([pw[0] / MMPX + 120, TABLE_Y - pw[1] / MMPX])

# --- the paddle: a blade seen side-on (a thin dark bar), moving with v_pad, never below the table
BLADE = 150.0 / MMPX
def paddle_at(t):
    # the contact point is 40% out from the blade's centre toward its tip (forward)
    return c0 - 0.4 * (BLADE * MMPX / 2) * t_w - (Rmm + 3) * n_w + v_pad * 1000 * t / FPS

def rot(axis, angle):
    k = np.asarray(axis, float); k /= np.linalg.norm(k) + 1e-12
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + np.sin(angle) * K + (1 - np.cos(angle)) * K @ K
def unit(v): v = np.asarray(v, float); return v / np.linalg.norm(v)
def ring(az):
    t = np.radians(az); return unit([0.5, 0.866 * np.cos(t), 0.866 * np.sin(t)])
DOTS = [(ring(0), np.array([0.80, 0.14, 0.17])), (ring(120), np.array([0.13, 0.22, 0.66])), (ring(240), np.array([0.15, 0.62, 0.25]))]
R0 = rot(rng.normal(size=3), rng.uniform(0, 2 * np.pi))
light = unit([-0.3, -0.5, 0.8])
def orient(t):
    rv = -(to_cam @ ball_turn(t))
    ang = np.linalg.norm(rv)
    return (rot(rv, ang) if ang > 0 else np.eye(3)) @ R0

def albedo(pb):
    col = np.ones((len(pb), 3)) * np.array([0.95, 0.95, 0.93])
    col[pb[:, 0] < 0.05 * np.sin(3 * np.arctan2(pb[:, 2], pb[:, 1]) + 1.0)] = 0.06
    for d, c in DOTS: col[pb @ d > np.cos(0.2)] = c
    return col

yy, xx = np.mgrid[0:H, 0:W]
bg = np.zeros((H, W, 3)); bg[...] = np.array([150, 140, 125]) * (0.8 + 0.2 * xx[..., None] / W)
bg += rng.normal(0, 6, (H // 8 + 1, W // 8 + 1, 3)).repeat(8, 0).repeat(8, 1)[:H, :W]
top = int(TABLE_Y)
bg[top:top + 4] = np.array([235, 235, 235])          # the white edge line
bg[top + 4:top + 20] = np.array([30, 70, 130])       # the table's side
bg[top + 20:] = np.array([60, 55, 50])               # under the table, darker

def draw_ball(img, c, Rm):
    x0, x1 = int(c[0] - rad - 2), int(c[0] + rad + 3); y0, y1 = int(c[1] - rad - 2), int(c[1] + rad + 3)
    x0, x1, y0, y1 = max(x0, 0), min(x1, W), max(y0, 0), min(y1, H)
    if x1 <= x0 or y1 <= y0: return
    xs, ys = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
    dx, dy = (xs - c[0]) / rad, (ys - c[1]) / rad; r2 = dx ** 2 + dy ** 2; ins = r2 < 1
    n = np.stack([dx[ins], dy[ins], np.sqrt(1 - r2[ins])], 1)
    vals = img[y0:y1, x0:x1].copy()
    vals[ins] = 250 * albedo(n @ Rm) * (0.45 + 0.55 * np.clip(n @ light, 0, 1))[:, None] + 5
    e = np.clip((1 - np.sqrt(r2)) * rad + 0.5, 0, 1)[..., None]
    img[y0:y1, x0:x1] = img[y0:y1, x0:x1] * (1 - e) + vals * e

def draw_paddle(img, cw):
    c = to_px(cw); d = to_cam @ t_w; d = d[:2] / np.linalg.norm(d[:2])
    nrm = np.array([-d[1], d[0]])
    L = BLADE / 2 + 75
    x0, x1, y0, y1 = (max(int(c[0] - L), 0), min(int(c[0] + L), W), max(int(c[1] - L), 0), min(int(c[1] + L), top))
    if x1 <= x0 or y1 <= y0: return
    sub = img[y0:y1, x0:x1]
    rel = np.stack([xx[y0:y1, x0:x1] - c[0], yy[y0:y1, x0:x1] - c[1]], -1)
    along, across = rel @ d, rel @ nrm
    sub[(np.abs(along) < BLADE / 2) & (np.abs(across) < 5)] = np.array([25, 22, 22])
    sub[(along < -BLADE / 2) & (along > -BLADE / 2 - 70) & (np.abs(across) < 6)] = np.array([160, 120, 80])

blur = max(4, int(1.5 * np.linalg.norm(v_out) * 1000 / FPS / MMPX * a.exposure) + 1)
cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", "30", "-i", "-",
       "-c:v", "libvpx-vp9", "-crf", "28", "-b:v", "0", "-g", "30", "-row-mt", "1", "-cpu-used", "5", "-pix_fmt", "yuv420p", a.out]
ff = subprocess.Popen(cmd, stdin=subprocess.PIPE)
for i in range(LEAD + PRE + POST + 30):
    k = i - LEAD - PRE                               # frames from contact
    acc = np.zeros((H, W, 3))
    for s in range(blur):
        t = k + a.exposure * (s + 0.5) / blur - 0.5
        img = bg.copy()
        if -25 <= t <= 10: draw_paddle(img, paddle_at(t))
        if -PRE <= t <= POST: draw_ball(img, to_px(ball_pos(t)), orient(t))
        acc += img
    img = np.clip(acc / blur + rng.normal(0, a.noise, (H, W, 3)), 0, 255).astype(np.uint8)
    ff.stdin.write(img.tobytes())
ff.stdin.close(); ff.wait()
truth = dict(paddle=a.paddle, angle=a.angle, fall=a.fall, kp=a.kp, tilt=a.tilt, height=a.height,
             rps=out["rps"], speed=out["speed"], ratio=out["ratio"], sliding=out["sliding"], backspin=out["backspin"],
             launch_deg=float(np.degrees(np.arctan2(v_out[1], v_out[0]))), contact_frame=LEAD + PRE,
             bounce_frame=None if t_b is None else LEAD + PRE + t_b * FPS,
             rps_after_bounce=None if t_b is None else float(np.linalg.norm(w_b) / (2 * np.pi)))
json.dump(truth, open(a.out.rsplit(".", 1)[0] + ".json", "w"))
print(json.dumps(truth))
