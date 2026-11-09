#!/usr/bin/env python3
"""Render a synthetic high-speed clip of a marked table tennis ball with known spin.

Used to test spinmeter.py before real footage exists: if the meter can't recover a spin
we set ourselves, it won't work on real video either.

The ball flies across a plain background while spinning about a given axis. Rendering is
per pixel: each visible surface point is rotated back into the ball's own frame and
coloured by the marking pattern. Motion blur averages several sub-frame renders over the
exposure time. The result is written as an MP4 (lossy, like a phone) plus a JSON file with
the ground truth.

Camera axes: x right, y down, z toward the camera. A spin axis of (0, 0, 1) rotates the
pattern in the image plane, which is what backspin or topspin looks like from the side.

Usage:
  python spinsim.py --out sim.mp4 --rps 30 --axis 0 0 1 --fps 240 --diameter 50
"""
import argparse
import json
import shutil
import subprocess

import numpy as np

try:
    import cv2
except ImportError:        # only needed for --writer cv2
    cv2 = None


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", required=True, help="output .mp4 (ground truth goes to the same name .json)")
    p.add_argument("--rps", type=float, default=30.0, help="spin, revolutions per second (sign = direction)")
    p.add_argument("--axis", type=float, nargs=3, default=[0, 0, 1], help="spin axis in camera coordinates")
    p.add_argument("--fps", type=float, default=240.0)
    p.add_argument("--frames", type=int, default=60)
    p.add_argument("--diameter", type=float, default=50.0, help="ball diameter in pixels")
    p.add_argument("--speed", type=float, default=6.0, help="ball speed across the image, pixels per frame")
    p.add_argument("--exposure", type=float, default=0.25,
                   help="exposure as a fraction of the frame time (0.25 at 240 FPS ≈ 1/960 s)")
    p.add_argument("--blur-samples", type=int, default=6)
    p.add_argument("--pattern", choices=["half", "line", "half+line", "half+line+dot", "dots"],
                   default="half+line+dot")
    p.add_argument("--noise", type=float, default=4.0, help="Gaussian noise, grey levels")
    p.add_argument("--size", type=int, nargs=2, default=[640, 360], help="frame width height")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--writer", choices=["auto", "ffmpeg", "cv2"], default="auto",
                   help="auto: ffmpeg (H.264) when available, else OpenCV (MPEG-4). The app's\n"
                        "frames are H.264, and browser WebCodecs cannot decode OpenCV's mp4v,\n"
                        "so the web engine needs ffmpeg output.")
    p.add_argument("--range", choices=["pc", "tv"], default="pc", dest="rng_",
                   help="colour range tagged on the file: pc = full (0-255, what phones write),\n"
                        "tv = limited (16-235). Reading one as the other was a real 27%% error.")
    p.add_argument("--crf", type=int, default=14, help="H.264 quality (lower = better)")
    p.add_argument("--gain", type=float, default=1.0,
                   help="multiply brightness before quantising: a brighter or dimmer room")
    p.add_argument("--pedestal", type=float, default=0.0,
                   help="add a constant brightness (a lifted black level, haze, or a\n"
                        "limited-range pedestal). Spin must not depend on gain or pedestal.")
    p.add_argument("--gamma", type=float, default=1.0, help="tone curve exponent applied before quantising")
    p.add_argument("--gravity", type=float, default=0.1,
                   help="downward acceleration in px/frame^2 (0.1 ~ 9.8 m/s^2 for a 25 px ball at 240 fps)")
    p.add_argument("--axis-near-pole", type=float, default=None, metavar="DEG",
                   help="override --axis: spin axis this many degrees from the black half's pole "
                        "(the hard case for pole tracking)")
    return p.parse_args()


def rot_matrix(axis, angle):
    """Rodrigues rotation matrix."""
    a = np.asarray(axis, dtype=float)
    a = a / np.linalg.norm(a)
    K = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) + np.sin(angle) * K + (1 - np.cos(angle)) * (K @ K)


def pattern_value(p, kind, dots):
    """Albedo (0 = black, 1 = white) for unit surface points p (N x 3) in the ball frame."""
    white = np.ones(len(p))
    if kind in ("half", "half+line", "half+line+dot"):
        white[p[:, 0] < 0] = 0.08                      # one hemisphere black
    if kind == "half+line+dot":
        # a dot (~8 mm) on the white half, between the line and the seam: breaks the
        # pattern's 180-degree symmetry about the white pole
        c = np.array([0.5, 0.6, 0.62])
        c = c / np.linalg.norm(c)
        white[p @ c > np.cos(np.radians(11))] = 0.08
    if kind in ("line", "half+line", "half+line+dot"):
        band = np.abs(p[:, 1]) < 0.12                  # great circle orthogonal to the half split
        if kind != "line":
            white[band & (p[:, 0] >= 0)] = 0.08
        if kind == "line":
            white[band] = 0.08
            white[(p[:, 2] > 0.93)] = 0.08             # one dot breaks the line's symmetry
    if kind == "dots":
        d = p @ dots.T                                  # cosine to each dot centre
        white[(d > np.cos(np.radians(11))).any(axis=1)] = 0.08
    return white


def render(frame_size, centre, radius, R, kind, dots, light):
    W, H = frame_size
    img = np.full((H, W), 70.0)                         # plain grey background
    x0, x1 = int(centre[0] - radius - 2), int(centre[0] + radius + 3)
    y0, y1 = int(centre[1] - radius - 2), int(centre[1] + radius + 3)
    xs, ys = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
    dx, dy = (xs - centre[0]) / radius, (ys - centre[1]) / radius
    r2 = dx ** 2 + dy ** 2
    inside = r2 < 1.0
    n = np.stack([dx[inside], dy[inside], np.sqrt(1 - r2[inside])], axis=1)   # camera-frame normals
    p_body = n @ R                                      # R^T n for each row
    albedo = pattern_value(p_body, kind, dots)
    shade = 0.35 + 0.65 * np.clip(n @ light, 0, 1)      # fixed light: shading does not rotate
    patch = img[y0:y1, x0:x1]
    vals = patch.copy()
    vals[inside] = 235.0 * albedo * shade + 10
    # anti-aliased edge
    edge = np.clip((1.0 - np.sqrt(r2)) * radius + 0.5, 0, 1)
    patch[:] = patch * (1 - edge) + vals * edge
    return img



class FfmpegWriter:
    """Grey frames -> H.264 in an MP4, tagged with a colour range.

    The phone records H.264 and tags it full range; the engine reads the tag. Writing the
    same pixels under both tags is how the full/limited-range reading bug is tested.
    """

    def __init__(self, path, size, crf=14, rng="pc"):
        W, H = size
        self.proc = subprocess.Popen(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
             "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}", "-r", "30", "-i", "-",
             "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", str(crf),
             "-pix_fmt", "yuv420p", "-color_range", rng,
             "-x264-params", f"fullrange={1 if rng == 'pc' else 0}",
             "-movflags", "+faststart", path],
            stdin=subprocess.PIPE)

    def write(self, img):
        self.proc.stdin.write(np.ascontiguousarray(img, dtype=np.uint8).tobytes())

    def close(self):
        self.proc.stdin.close()
        if self.proc.wait() != 0:
            raise RuntimeError("ffmpeg failed")


class Cv2Writer:
    def __init__(self, path, size):
        if cv2 is None:
            raise RuntimeError("--writer cv2 needs opencv-python installed")
        self.w = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), 30.0, size)

    def write(self, img):
        self.w.write(cv2.cvtColor(img, cv2.COLOR_GRAY2BGR))

    def close(self):
        self.w.release()


def open_writer(a, W, H):
    use = a.writer
    if use == "auto":
        use = "ffmpeg" if shutil.which("ffmpeg") else "cv2"
    if use == "ffmpeg":
        return FfmpegWriter(a.out, (W, H), a.crf, a.rng_)
    return Cv2Writer(a.out, (W, H))


def main():
    a = parse_args()
    rng = np.random.default_rng(a.seed)
    W, H = a.size
    radius = a.diameter / 2
    axis = np.asarray(a.axis, float) / np.linalg.norm(a.axis)
    omega = 2 * np.pi * a.rps                           # rad/s
    dots = rng.normal(size=(8, 3))
    dots /= np.linalg.norm(dots, axis=1, keepdims=True)
    light = np.array([-0.3, -0.5, 0.8])
    light /= np.linalg.norm(light)
    R0 = rot_matrix(rng.normal(size=3), rng.uniform(0, 2 * np.pi))   # random starting orientation
    if a.axis_near_pole is not None:
        e = R0 @ np.array([-1.0, 0, 0])                 # black pole in camera coordinates at t=0
        u = np.cross(e, rng.normal(size=3))
        u /= np.linalg.norm(u)
        d = np.radians(a.axis_near_pole)
        axis = np.cos(d) * e + np.sin(d) * u

    writer = open_writer(a, W, H)
    start = np.array([radius + 20.0, H * 0.55])
    vel = np.array([a.speed, -a.speed * 0.15 - a.gravity * a.frames / 2])   # rises, then falls
    centres = []
    max_frames = int((W - start[0] - radius - 5) / max(a.speed, 1e-6))
    if a.frames > max_frames:
        a.frames = max_frames   # stop before the ball leaves the frame
    for i in range(a.frames):
        acc = np.zeros((H, W))
        for s in range(a.blur_samples):
            t = (i + a.exposure * (s + 0.5) / a.blur_samples) / a.fps
            fpos = i + a.exposure * (s + 0.5) / a.blur_samples
            c = start + vel * fpos + np.array([0.0, 0.5 * a.gravity * fpos ** 2])
            R = rot_matrix(axis, omega * t) @ R0
            acc += render((W, H), c, radius, R, a.pattern, dots, light)
        img = acc / a.blur_samples + rng.normal(0, a.noise, (H, W))
        # the camera's response: spin is geometry, so it must survive all of this
        if a.gamma != 1.0:
            img = 255.0 * np.clip(img / 255.0, 0, 1) ** a.gamma
        if a.gain != 1.0 or a.pedestal:
            img = a.gain * img + a.pedestal
        img = np.clip(img, 0, 255).astype(np.uint8)
        writer.write(img)
        fpos = i + a.exposure / 2
        centres.append((start + vel * fpos + np.array([0.0, 0.5 * a.gravity * fpos ** 2])).tolist())
    writer.close()

    # the track a perfect live tracker would hand the engine: [frame, x, y, radius].
    # measureCapture(useTrack) measures along such a track instead of searching for the ball.
    track = [[i, round(c[0], 2), round(c[1], 2), round(radius, 2)] for i, c in enumerate(centres)]
    truth = {"rps": a.rps, "rpm": a.rps * 60, "axis": axis.tolist(), "fps": a.fps, "track": track,
             "diameter_px": a.diameter, "speed_px_per_frame": a.speed, "exposure": a.exposure,
             "pattern": a.pattern, "noise": a.noise, "frames": a.frames,
             "colour_range": a.rng_, "gain": a.gain, "pedestal": a.pedestal, "gamma": a.gamma,
             "note": "file is written at 30 FPS playback; pass --fps to spinmeter.py"}
    with open(a.out.rsplit(".", 1)[0] + ".json", "w") as f:
        json.dump(truth, f, indent=2)
    print(json.dumps(truth))


if __name__ == "__main__":
    main()
