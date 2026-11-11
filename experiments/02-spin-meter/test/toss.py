#!/usr/bin/env python3
"""Measure the spin on a ball tossed straight up, which the engine refuses by name.

A vertical toss is the easiest way for a person to put a known spin on a ball: you twist it about
the axis you throw it along, and you know which way you twisted. It is also the one flight the
serve detector exists to reject -- flightRule returns "upward (toss)" on the way up and "straight
drop" on the way down, because dy > 2|dx|. That rule is right for finding serves and useless for
checking spin, since the ball's own pixels are perfectly good.

So this cuts the ball's disc out of each frame and pastes it, never resampled, onto the clip's own
static background, drifting sideways as it falls. The result looks like a flight across the
picture and the engine measures it. The rotation is whatever the ball really did; only where the
ball sits on the wall is invented, and nothing inside its outline is touched.

    python3 test/toss.py clip.mp4 out.mp4            # writes out.mp4 and out_track.json
    node ../web/test/... or web/test/bench.mjs out.mp4

What this proves: that the engine's spin fit reads a real ball the way a person reading the same
frames does. What it cannot prove: anything about serve detection, flight geometry or speed --
the trajectory is made up, so lift/curve/gyro are split against a flight that never happened.
Read camAxis and rps, and project them yourself onto the true (vertical) flight.
"""
import sys, json, glob, os, shutil, subprocess, tempfile
import numpy as np
from PIL import Image

def frames(clip, d):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', clip, '-q:v', '2', f'{d}/f_%04d.png'], check=True)
    return sorted(glob.glob(f'{d}/f_*.png'))

def blobs(mask, least=40):
    seen = np.zeros_like(mask, bool); out = []
    ys, xs = np.where(mask)
    for sy, sx in zip(ys, xs):
        if seen[sy, sx]: continue
        st = [(sy, sx)]; seen[sy, sx] = True; pts = []
        while st:
            y, x = st.pop(); pts.append((y, x))
            for dy, dx in ((1,0),(-1,0),(0,1),(0,-1)):
                ny, nx = y+dy, x+dx
                if 0 <= ny < mask.shape[0] and 0 <= nx < mask.shape[1] and mask[ny,nx] and not seen[ny,nx]:
                    seen[ny,nx] = True; st.append((ny,nx))
        if len(pts) >= least: out.append(np.array(pts))
    return out

def track(fs, box):
    """the ball's longest unbroken run: a compact blob that is not there in the background"""
    small = [np.asarray(Image.open(f).convert('L').resize((960,540), Image.BILINEAR)).astype(np.int16) for f in fs]
    bg = np.median(np.stack(small[::9]), axis=0)
    y0, y1, x0, x1 = box
    got = []
    for i, im in enumerate(small):
        d = np.abs(im - bg); m = np.zeros_like(d, bool)
        m[y0:y1, x0:x1] = d[y0:y1, x0:x1] > 42
        best = None
        for p in blobs(m):
            by0, by1 = p[:,0].min(), p[:,0].max(); bx0, bx1 = p[:,1].min(), p[:,1].max()
            h, w = by1-by0+1, bx1-bx0+1
            if not (9 <= w <= 34 and 9 <= h <= 34): continue
            fill = len(p)/(w*h)
            if fill < 0.6 or not (0.65 < w/h < 1.55): continue
            sc = fill * min(w, h)
            if best is None or sc > best[0]: best = (sc, (bx0+bx1+1)/2.0, (by0+by1+1)/2.0, (w+h)/4.0)
        if best: got.append([i, round(best[1]*2,1), round(best[2]*2,1), round(best[3]*2,1)])
    runs, cur = [], [got[0]] if got else []
    for a, b in zip(got, got[1:]):
        if b[0]-a[0] <= 3: cur.append(b)
        else: runs.append(cur); cur = [b]
    if cur: runs.append(cur)
    return max(runs, key=len) if runs else []

def restage(fs, tr, out, drift, x_start=240.0):
    bg = np.median(np.stack([np.asarray(Image.open(f).convert('RGB')).astype(np.uint8) for f in fs[::11]]), axis=0)
    H, W = bg.shape[:2]
    d = tempfile.mkdtemp(); f0 = tr[0][0]; new = []
    for t in tr:
        i, cx, cy, r = int(t[0]), t[1], t[2], t[3]
        R = int(round(r*1.45)); sx, sy = int(round(cx)), int(round(cy))
        nx = int(round(x_start + drift*(i-f0)))
        if min(sx-R, sy-R, nx-R) < 0 or sx+R >= W or sy+R >= H or nx+R >= W: continue
        src = np.asarray(Image.open(fs[i]).convert('RGB')).astype(np.float64)
        yy, xx = np.mgrid[0:2*R, 0:2*R]
        a = np.clip((r*1.34 - np.hypot(xx-(R-0.5), yy-(R-0.5))) / max(1.0, r*0.18), 0, 1)[..., None]
        dst = bg.astype(np.float64).copy()
        dst[sy-R:sy+R, nx-R:nx+R] = src[sy-R:sy+R, sx-R:sx+R]*a + dst[sy-R:sy+R, nx-R:nx+R]*(1-a)
        Image.fromarray(dst.clip(0,255).astype(np.uint8)).save('%s/f_%04d.png' % (d, len(new)))
        new.append([len(new), float(nx), float(sy), r])
    subprocess.run(['ffmpeg','-v','error','-y','-framerate','30','-i',f'{d}/f_%04d.png','-an','-c:v','libx264',
                    '-preset','slow','-crf','12','-pix_fmt','yuv420p','-color_range','pc',
                    '-x264-params','fullrange=1','-movflags','+faststart', out], check=True)
    shutil.rmtree(d, ignore_errors=True)
    json.dump(new, open(out.replace('.mp4','_track.json'),'w'))
    return new

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    clip, out = sys.argv[1], sys.argv[2]
    drift = float(sys.argv[3]) if len(sys.argv) > 3 else 11.0
    # where on the picture to look for the ball, at half size: above the hand, clear of the thrower
    box = tuple(int(v) for v in sys.argv[4].split(',')) if len(sys.argv) > 4 else (0, 400, 330, 700)
    d = tempfile.mkdtemp()
    try:
        fs = frames(clip, d)
        tr = track(fs, box)
        if len(tr) < 12:
            print('only %d frames of ball found: widen the box or check the clip' % len(tr)); sys.exit(1)
        new = restage(fs, tr, out, drift)
        print('%s: %d frames of flight staged from %d filmed, drift %.1f px/frame'
              % (os.path.basename(out), len(new), len(fs), drift))
    finally:
        shutil.rmtree(d, ignore_errors=True)
