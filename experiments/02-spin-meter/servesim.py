#!/usr/bin/env python3
"""Render a whole serve, with the spin set by hand, as a clip the app can read.

`spinsim.py` renders a ball with a known spin flying in a straight line across a plain
grey background. That is enough to test the spin measurement on its own, and the sweep in
the README is what proves the rate to half a rev/s. It is not enough to test the APP: the
app has to find the serve before it can measure it, and a straight line across grey is not
a serve. There is no toss, no contact, no room, no flicker, no table, and the flight does
not bend the way the spin says it should.

So this renders the thing the app actually sees, at the phone's own size and frame rate:

    idle room -> a toss thrown up and falling -> contact -> a flight that drags and
    curves -> a bounce off the table -> out of frame -> idle room

and writes the ground truth beside it. The whole chain can then be run with no phone and
no table tennis court:

    servesim.py (truth) -> .mp4 -> desktop/RingSim (the real tracker, the real window
    ring, the real capture writer) -> .cap -> web/test/cap.mjs (the real engine) -> spin

Every stage but the shader itself and the camera is the shipping code.

WHAT THIS PROVES, AND WHAT IT DOES NOT
--------------------------------------
Proves: the measurement and the plumbing. The spin that comes out is compared against the
spin that went in -- rate, axis, and the split into lift / curve / gyro -- on a flight
shaped like a serve, at any rate and any axis, repeatably, with no human able to serve it.
A player cannot produce a pure sidespin. This can.

Does not prove: the camera. Real rolling shutter, the phone's own H.264 encoder, real room
light, a scuffed ball, a hand in the way. Those are what the club clips and the hand counts
are for. The two tests cover different halves and neither replaces the other.

CIRCULARITY
-----------
The ground truth is the spin this file was TOLD to render, and the renderer puts it on the
ball's surface geometrically -- it never computes a spin. The flight's drag and Magnus use
published coefficients (below), not the app's model, so a trajectory cross-check against
the app is not checking the app against itself. What a simulator cannot catch is an error
shared by the renderer and the meter: both assume a sphere, lit from one side, seen through
a pinhole. That assumption is what the real footage tests.

PHYSICS
-------
Ball: 40 mm, 2.7 g (ITTF). Air: rho = 1.2 kg/m^3.
  drag    a = -(rho A Cd / 2m) |v| v                       Cd = 0.40
  Magnus  a = +(rho A Cl / 2m) |v|^2 unit(w x v)           Cl = min(0.35, 0.45 S)
          S = r|w| / |v|, the spin parameter
Cd for a table tennis ball sits at 0.38-0.50 over the Reynolds numbers a serve lives in,
and the Magnus coefficient saturates near 0.35; both are command-line flags, because the
trajectory is the only thing they touch. The spin itself is exact whatever they are.

AXES
----
The spin meter's camera frame, which spinsim.py also uses, and the truth is written in it:

    x right, y DOWN, z toward the camera

With the standard cross product this frame is self-consistent for spin: a ball flying to
the right (+x) with topspin has w = +z, and cross(w, v) = +y, which is down -- topspin
dips, as it must. The `_check_signs` assertion at the bottom holds that fixed, because a
sign error here would be invisible in the output and would make every test agree with a
wrong answer.

USAGE
-----
  servesim.py --out serve.mp4 --spin backspin --rps 40
  servesim.py --out serve.mp4 --axis 0 -1 0 --rps 30      # pure sidespin, no player can
  servesim.py --out serve.mp4 --spin topspin --rps 55 --toward 40   # head-on
"""
import argparse
import json
import shutil
import subprocess
import sys

import numpy as np

from spinsim import COLOUR_PATTERNS, pattern_colour, pattern_value, rot_matrix

# --- the ball and the air -------------------------------------------------------------
BALL_D = 0.040                      # m, ITTF
BALL_R = BALL_D / 2
BALL_M = 2.7e-3                     # kg, ITTF
RHO = 1.2                           # kg/m^3
AREA = np.pi * BALL_R ** 2
G = 9.81                            # m/s^2

# --- the spin a player would name, as an axis for a ball flying to the picture's right --
# Derived, not guessed: topspin means the top of the ball moves the way the ball is going.
# With up = -y and the ball going +x, the top point's velocity is w x (-r y), which is +x
# only for w = +z. Everything else follows from that one.
# Named against the FLIGHT, not the room. A serve leaves the paddle rising and a little
# toward the camera, so an axis fixed in the room is never pure about the line the ball
# actually flies -- "backspin" straight down the z axis comes out with a tenth of its spin
# along the flight. Naming them against the flight is what makes a known answer known:
# `backspin` is exactly lift = +rps, curve = 0, gyro = 0, and nothing else.
#
# Writing w = a f + b u + c right and turning the handle on the engine's own split gives
# lift = -c, curve = b, gyro = a. These are that, read backwards.
SPINS = {                               # (along the flight, up, right-of-flight)
    "backspin":      (0.0, 0.0, -1.0),  # lift +1
    "topspin":       (0.0, 0.0, 1.0),   # lift -1
    "sidespin":      (0.0, 1.0, 0.0),   # curve +1
    "sidespin-r":    (0.0, -1.0, 0.0),  # curve -1
    "gyro":          (1.0, 0.0, 0.0),   # along the flight: bends nothing at all
    "none":          (0.0, 0.0, -1.0),  # the axis is arbitrary; --rps 0 is what makes it none
    # mixtures, for the cases a player can actually produce, as a control
    "side-backspin": (0.0, 0.707, -0.707),
    "side-topspin":  (0.0, 0.707, 0.707),
}


def flight_frame(f):
    """The engine's own frame about a flight direction: (f, up, right)."""
    f = np.asarray(f, float) / np.linalg.norm(f)
    u = np.array([0.0, -1.0, 0.0])
    u = u - np.dot(u, f) * f
    u /= np.linalg.norm(u)
    return f, u, -np.cross(f, u)


def parse_args():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", required=True, help="output .mp4 (truth goes to the same name .json)")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--spin", choices=sorted(SPINS), help="the spin by name, for a ball going right")
    g.add_argument("--axis", type=float, nargs=3, metavar=("X", "Y", "Z"),
                   help="the spin axis in camera coordinates (x right, y down, z toward camera)")
    p.add_argument("--rps", type=float, default=40.0, help="spin, revolutions per second")
    p.add_argument("--fps", type=float, default=240.0)
    p.add_argument("--size", type=int, nargs=2, default=[1920, 1080], metavar=("W", "H"))
    p.add_argument("--fov", type=float, default=66.0, help="horizontal field of view, degrees")
    p.add_argument("--distance", type=float, default=2.5, help="camera to the serve, metres")
    p.add_argument("--toward", type=float, default=8.0,
                   help="degrees of the flight pointing at the camera (0 = side on, 45+ = head on)")
    p.add_argument("--speed", type=float, default=5.6, help="ball speed off the paddle, m/s")
    p.add_argument("--rise", type=float, default=9.0, help="degrees the ball leaves the paddle above level")
    p.add_argument("--toss-drop", type=float, default=2.0, dest="toss_drop",
                   help="how fast the toss is falling at contact, m/s (the rules say 16 cm of rise)")
    p.add_argument("--bounce", dest="bounce", action="store_true", default=True)
    p.add_argument("--no-bounce", dest="bounce", action="store_false")
    p.add_argument("--restitution", type=float, default=0.80, help="how much speed the bounce keeps")
    p.add_argument("--idle", type=int, default=120, help="frames of empty room before the toss")
    p.add_argument("--tail", type=int, default=72, help="frames of empty room after the flight")
    p.add_argument("--pattern", choices=["half", "half+line", "half+line+dot", "half+dots"],
                   default="half+dots", help="the ball's marking; half+dots is the painted ball")
    p.add_argument("--exposure", type=float, default=0.25,
                   help="shutter as a fraction of the frame time (0.25 at 240 fps = 1/960 s)")
    p.add_argument("--blur-samples", type=int, default=6, dest="blur_samples")
    p.add_argument("--noise", type=float, default=3.5, help="sensor noise, grey levels")
    p.add_argument("--flicker", type=float, default=0.06,
                   help="mains flicker depth, 0 to turn it off. 100 Hz lights on a 240 fps camera")
    p.add_argument("--gain", type=float, default=1.0, help="a brighter or dimmer room")
    p.add_argument("--cd", type=float, default=0.40, help="drag coefficient")
    p.add_argument("--cl-max", type=float, default=0.35, dest="cl_max", help="Magnus coefficient ceiling")
    p.add_argument("--cl-slope", type=float, default=0.45, dest="cl_slope", help="Magnus per unit spin parameter")
    p.add_argument("--crf", type=int, default=16, help="H.264 quality (lower = better)")
    p.add_argument("--seed", type=int, default=0)
    return p.parse_args()


# --- the flight -----------------------------------------------------------------------

def accel(v, w, cd, cl_max, cl_slope):
    """Gravity, drag and Magnus on the ball, in m/s^2, camera axes (y is down)."""
    a = np.array([0.0, G, 0.0])
    sp = np.linalg.norm(v)
    if sp < 1e-6:
        return a
    a -= (RHO * AREA * cd / (2 * BALL_M)) * sp * v
    ws = np.linalg.norm(w)
    if ws > 1e-6:
        s = BALL_R * ws / sp                                  # the spin parameter
        cl = min(cl_max, cl_slope * s)
        d = np.cross(w, v)
        n = np.linalg.norm(d)
        if n > 1e-9:
            a += (RHO * AREA * cl / (2 * BALL_M)) * sp * sp * (d / n)
    return a


def in_frame(flight, F, cx, cy, W, H):
    """The flight up to where the ball leaves the picture."""
    out = []
    for p, v in flight:
        d = -p[2]
        if d < 0.3:
            break
        u_px, v_px = cx + F * p[0] / d, cy + F * p[1] / d
        r = F * BALL_R / d
        if u_px > W + r or u_px < -r or v_px > H + r:
            break
        out.append((p, v))
    return out


def fly(p0, v0, w, n, dt, a):
    """Velocity Verlet, which keeps a parabola a parabola at this step size."""
    p, v = np.array(p0, float), np.array(v0, float)
    out = []
    acc = accel(v, w, a.cd, a.cl_max, a.cl_slope)
    for _ in range(n):
        out.append((p.copy(), v.copy()))
        p = p + v * dt + 0.5 * acc * dt * dt
        nxt = accel(v + acc * dt, w, a.cd, a.cl_max, a.cl_slope)
        v = v + 0.5 * (acc + nxt) * dt
        acc = nxt
    return out


# --- the room -------------------------------------------------------------------------

def room(W, H, colour, rng):
    """A hall: a wall with a little on it, the table's edge, and the floor."""
    y = np.arange(H)[:, None].astype(np.float32)
    x = np.arange(W)[None, :].astype(np.float32)
    wall = 96 + 14 * np.sin(x * 0.004) + 7 * np.sin(y * 0.011) - 18 * (y / H)
    img = np.broadcast_to(wall, (H, W)).astype(np.float32).copy()
    # a couple of things on the wall, so the background is not one smooth field
    for (cx, cy, w_, h_, v) in ((int(W * .18), int(H * .17), int(W * .11), int(H * .20), 58),
                                (int(W * .62), int(H * .13), int(W * .14), int(H * .13), 132),
                                (int(W * .87), int(H * .22), int(W * .07), int(H * .30), 70)):
        img[cy:cy + h_, cx:cx + w_] = v + 5 * np.sin(np.arange(w_) * 0.09)[None, :]
    table_top = int(H * 0.60)
    img[table_top:, :] = 62 + 6 * np.sin(x * 0.013)                 # the table, side on
    img[table_top:table_top + max(2, H // 180), :] = 214            # its white edge
    floor = int(H * 0.80)
    img[floor:, :] = 44 + 5 * np.sin(x * 0.02) + 4 * np.sin(y[floor:] * 0.03)
    if not colour:
        return img
    rgb = np.repeat(img[:, :, None], 3, axis=2)
    rgb[table_top:floor] *= np.array([0.42, 0.72, 1.45], np.float32)   # a blue table
    rgb[:table_top] *= np.array([1.02, 1.0, 0.97], np.float32)         # a warm wall
    return np.clip(rgb, 0, 255)


def flicker_rows(H, t, depth, phase_per_frame=100.0 / 240.0):
    """100 Hz mains on a rolling shutter: a band down the rows that moves between frames."""
    if depth <= 0:
        return None
    y = np.arange(H, dtype=np.float32) / H
    return (1 + depth * np.sin(2 * np.pi * (phase_per_frame * (t + y)))).astype(np.float32)


# --- the ball -------------------------------------------------------------------------

def draw_ball(img, centre, radius, R, kind, light, colour):
    """Composite one ball onto the frame, touching only its own box."""
    H, W = img.shape[:2]
    x0, x1 = int(centre[0] - radius - 2), int(centre[0] + radius + 3)
    y0, y1 = int(centre[1] - radius - 2), int(centre[1] + radius + 3)
    x0, y0 = max(x0, 0), max(y0, 0)
    x1, y1 = min(x1, W), min(y1, H)
    if x1 <= x0 or y1 <= y0:
        return
    xs, ys = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
    dx, dy = (xs - centre[0]) / radius, (ys - centre[1]) / radius
    r2 = dx ** 2 + dy ** 2
    inside = r2 < 1.0
    if not inside.any():
        return
    n = np.stack([dx[inside], dy[inside], np.sqrt(np.clip(1 - r2[inside], 0, None))], axis=1)
    p_body = n @ R
    shade = 0.35 + 0.65 * np.clip(n @ light, 0, 1)
    patch = img[y0:y1, x0:x1]
    vals = patch.copy()
    if colour:
        vals[inside] = 235.0 * pattern_colour(p_body, kind) * shade[:, None] + 10
    else:
        vals[inside] = 235.0 * pattern_value(p_body, kind, None) * shade + 10
    edge = np.clip((1.0 - np.sqrt(r2)) * radius + 0.5, 0, 1)
    if colour:
        edge = edge[:, :, None]
    patch[:] = patch * (1 - edge) + vals * edge


class FfmpegWriter:
    def __init__(self, path, size, fps, crf, colour):
        W, H = size
        self.proc = subprocess.Popen(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
             "-f", "rawvideo", "-pix_fmt", "rgb24" if colour else "gray",
             "-s", f"{W}x{H}", "-r", f"{fps:g}", "-i", "-",
             "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", str(crf),
             "-pix_fmt", "yuv420p", "-color_range", "pc",
             "-x264-params", "fullrange=1", "-movflags", "+faststart", path],
            stdin=subprocess.PIPE)

    def write(self, img):
        self.proc.stdin.write(np.ascontiguousarray(img, dtype=np.uint8).tobytes())

    def close(self):
        self.proc.stdin.close()
        if self.proc.wait() != 0:
            raise RuntimeError("ffmpeg failed")


# --- the spin, split the way the engine splits it --------------------------------------

def spin_components(w, f):
    """lift / curve / gyro, exactly as engine.js spinComponents does it.

    Copied deliberately: the point is to compare like with like. It is a coordinate change
    on a spin vector that is already known, so it adds no assumption of its own.
    """
    f = np.asarray(f, float) / np.linalg.norm(f)
    u = np.array([0.0, -1.0, 0.0])
    u = u - np.dot(u, f) * f
    u /= np.linalg.norm(u)
    right = -np.cross(f, u)
    magnus = np.cross(w, f)
    return {"lift": float(np.dot(magnus, u)), "curve": float(np.dot(magnus, right)),
            "gyro": float(np.dot(w, f)),
            "tilt_deg": float(np.degrees(np.arctan2(np.dot(magnus, right), np.dot(magnus, u))))}


def _check_signs():
    """A sign error here would be invisible, so it is asserted rather than argued.

    A ball flying to the picture's right with topspin must be pushed down, and the engine's
    own split must call that negative lift -- which is what the real serves labelled
    `topspin` in web/test/labels.mjs carry.
    """
    v = np.array([1.0, 0.0, 0.0])
    def about(name, rps):
        fh, uh, rh = flight_frame(v)
        c = SPINS[name]
        return rps * (c[0] * fh + c[1] * uh + c[2] * rh)
    top = about("topspin", 30.0)
    assert accel(v * 6.0, 2 * np.pi * top, 0.4, 0.35, 0.45)[1] > G, "topspin must push the ball down"
    assert spin_components(top, v)["lift"] < -29.9, "topspin is lift -rps and nothing else"
    back = about("backspin", 30.0)
    assert accel(v * 6.0, 2 * np.pi * back, 0.4, 0.35, 0.45)[1] < G, "backspin must hold the ball up"
    c = spin_components(back, v)
    assert c["lift"] > 29.9 and abs(c["curve"]) < 1e-6 and abs(c["gyro"]) < 1e-6, f"not pure: {c}"
    c = spin_components(about("sidespin", 30.0), v)
    assert c["curve"] > 29.9 and abs(c["lift"]) < 1e-6, f"sidespin is curve +rps: {c}"
    c = spin_components(about("gyro", 30.0), v)
    assert c["gyro"] > 29.9 and abs(c["lift"]) < 1e-6 and abs(c["curve"]) < 1e-6, f"gyro: {c}"


def main():
    _check_signs()
    a = parse_args()
    rng = np.random.default_rng(a.seed)
    if a.spin == "none":
        a.rps = 0.0                                           # a float serve: the axis means nothing
    W, H = a.size
    colour = a.pattern in COLOUR_PATTERNS
    dt = 1.0 / a.fps
    F = (W / 2) / np.tan(np.radians(a.fov) / 2)              # focal length in pixels
    cx, cy = W / 2, H / 2

    # The serve, in metres, camera axes (x right, y down, z toward the camera). The camera
    # is at the origin; the table is --distance away, so the ball's z is negative.
    depth = a.distance
    contact = np.array([-1.15, -0.10, -depth])                # left of centre, a little high
    rise, toward = np.radians(a.rise), np.radians(a.toward)
    v0 = a.speed * np.array([np.cos(rise) * np.cos(toward), -np.sin(rise),
                             np.cos(rise) * np.sin(toward)])

    table_y = 0.26                                            # the table top, below the contact
    n_air = int(2.2 * a.fps)

    def path_for(axis):
        """The flight the ball takes, and the direction the engine will measure it along."""
        w = 2 * np.pi * a.rps * axis
        raw = fly(contact, v0, w, n_air, dt, a)
        flight, bounce_at = [], None
        for i, (p, v) in enumerate(raw):
            if a.bounce and bounce_at is None and p[1] >= table_y and v[1] > 0:
                bounce_at = i
                flight.extend(fly(np.array([p[0], table_y, p[2]]),
                                  np.array([v[0] * 0.95, -v[1] * a.restitution, v[2] * 0.95]),
                                  w * 0.80, n_air - i, dt, a))  # the table takes some spin off
                break
            flight.append((p, v))
        flight = in_frame(flight, F, cx, cy, W, H)
        # the stretch the engine keeps: 12 frames after contact (it skips the paddle) to the bounce
        lo = 12
        hi = min(bounce_at if bounce_at is not None else len(flight), len(flight))
        vs = np.array([v for _, v in flight[lo:max(lo + 5, hi)]])
        if not len(vs):
            sys.exit(f"the flight only lasted {len(flight)} frames; check --speed/--toward/--distance")
        f = vs.mean(axis=0)
        return flight, bounce_at, f / np.linalg.norm(f), float(np.linalg.norm(vs[0]))

    if a.axis:
        axis = np.array(a.axis, float); axis /= np.linalg.norm(axis)
        flight, bounce_at, f_dir, launch = path_for(axis)
    else:
        # A named spin is named against the FLIGHT, but heavy spin BENDS the flight, so the
        # line it was named against is not the line the engine ends up measuring along.
        # Four passes settle it: fly, see where it actually went, re-aim, fly again. Without
        # this a "pure sidespin" comes out with a sixth of its spin reading as gyro -- true
        # of the clip, and useless as a known answer.
        c = SPINS[a.spin or "backspin"]
        aim = v0 / np.linalg.norm(v0)
        for _ in range(4):
            fh, uh, rh = flight_frame(aim)
            axis = c[0] * fh + c[1] * uh + c[2] * rh
            axis /= np.linalg.norm(axis)
            flight, bounce_at, f_dir, launch = path_for(axis)
            aim = f_dir
    w = 2 * np.pi * a.rps * axis                              # rad/s, camera axes

    # the toss: thrown up, falling at --toss-drop when the paddle meets it. Gravity only;
    # drag on a 2 m/s ball is under 2% and the toss is not what is being measured.
    up = 2.7
    t_toss = (up + a.toss_drop) / G
    n_toss = int(round(t_toss * a.fps))
    # placed so the ball arrives exactly at the contact point: p(t) = c + v_drop t + g t^2/2,
    # with t = 0 at contact and negative before it
    toss = []
    for i in range(n_toss):
        t = (i - n_toss) * dt
        p = contact + np.array([0.02 * t, a.toss_drop * t - 0.5 * G * t * t, 0.0])
        toss.append((p, np.array([0.02, a.toss_drop - G * t, 0.0])))

    if len(flight) < 40:
        sys.exit(f"the flight only lasted {len(flight)} frames; check --speed/--toward/--distance")

    frames = a.idle + len(toss) + len(flight) + a.tail
    contact_frame = a.idle + len(toss)
    light = np.array([-0.3, -0.5, 0.8]); light /= np.linalg.norm(light)
    R0 = rot_matrix(rng.normal(size=3), rng.uniform(0, 2 * np.pi))
    bg = room(W, H, colour, rng)
    writer = FfmpegWriter(a.out, (W, H), a.fps, a.crf, colour)

    def state(i):
        """Where the ball is and how fast it spins at whole-frame index i, or None."""
        if i < a.idle or i >= a.idle + len(toss) + len(flight):
            return None
        if i < contact_frame:
            p, v = toss[i - a.idle]
            return p, v, np.zeros(3), (i - a.idle - len(toss)) * dt
        k = i - contact_frame
        if k >= len(flight):
            return None
        p, v = flight[k]
        ww = w * (0.80 if bounce_at is not None and k >= bounce_at else 1.0)
        return p, v, ww, k * dt

    track, truth_path = [], []
    for i in range(frames):
        acc = np.zeros_like(bg)
        got = None
        for s in range(a.blur_samples):
            sub = i + a.exposure * (s + 0.5) / a.blur_samples
            lo, hi = int(np.floor(sub)), int(np.floor(sub)) + 1
            s0, s1 = state(lo), state(hi)
            frame = bg.copy()
            if s0 is not None:
                fr = sub - lo
                p = s0[0] + (s1[0] - s0[0]) * fr if s1 is not None else s0[0] + s0[1] * fr * dt
                spin_t = s0[3] + fr * dt
                ww = s0[2]
                d = -p[2]
                if d > 0.3:
                    centre = (cx + F * p[0] / d, cy + F * p[1] / d)
                    radius = F * BALL_R / d
                    ang = np.linalg.norm(ww) * spin_t
                    R = (rot_matrix(ww / np.linalg.norm(ww), ang) @ R0
                         if np.linalg.norm(ww) > 1e-9 else R0)
                    draw_ball(frame, centre, radius, R, a.pattern, light, colour)
                    got = (centre, radius, p, s0[1], ww)
            acc += frame
        img = acc / a.blur_samples
        fl = flicker_rows(H, i, a.flicker)
        if fl is not None:
            img *= fl[:, None, None] if colour else fl[:, None]
        if a.gain != 1.0:
            img *= a.gain
        img += rng.standard_normal(img.shape, dtype=np.float32) * a.noise
        writer.write(np.clip(img, 0, 255))
        if got is not None and i >= contact_frame:
            (ux, vy), r_px, p, v, ww = got
            track.append([i, round(float(ux), 2), round(float(vy), 2), round(float(r_px), 2)])
            truth_path.append([i, *[round(float(q), 4) for q in p], *[round(float(q), 4) for q in v]])
    writer.close()

    comp = spin_components(a.rps * axis, f_dir)               # rev/s, the engine's units
    truth = {
        "rps": a.rps, "rpm": a.rps * 60,
        "axis": [round(float(q), 6) for q in axis],
        "omega_rad_s": [round(float(q), 4) for q in w],
        "spin": a.spin, "pattern": a.pattern,
        "flight_dir": [round(float(q), 6) for q in f_dir],
        "toward_camera_deg": round(float(np.degrees(np.arcsin(np.clip(f_dir[2], -1, 1)))), 2),
        "expected": {k: round(v, 3) for k, v in comp.items()},
        "speed_m_s": round(launch, 3),
        "fps": a.fps, "size": [W, H], "frames": frames,
        "contact_frame": contact_frame,
        "bounce_frame": (contact_frame + bounce_at) if bounce_at is not None else None,
        "ball_px": round(float(2 * F * BALL_R / a.distance), 2),
        "track": track, "path_m": truth_path,
        "physics": {"cd": a.cd, "cl_max": a.cl_max, "cl_slope": a.cl_slope,
                    "restitution": a.restitution if a.bounce else None,
                    "spin_kept_on_bounce": 0.80},
        "camera": {"fov_deg": a.fov, "focal_px": round(float(F), 1), "distance_m": a.distance},
        "note": "camera axes: x right, y down, z toward the camera. lift>0 is backspin.",
    }
    with open(a.out.rsplit(".", 1)[0] + ".json", "w") as fh:
        json.dump(truth, fh, indent=2)
    brief = {k: truth[k] for k in ("rps", "spin", "axis", "expected", "toward_camera_deg",
                                   "ball_px", "contact_frame", "bounce_frame", "frames")}
    print(json.dumps(brief))


if __name__ == "__main__":
    if not shutil.which("ffmpeg"):
        sys.exit("servesim needs ffmpeg: the app's frames are H.264")
    main()
