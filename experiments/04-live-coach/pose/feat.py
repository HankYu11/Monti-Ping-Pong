# Pose features over a clip, as the app would see it: 480x270 frames at 15 a second.
import sys, json, subprocess, numpy as np, mediapipe as mp
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision
model, clips = sys.argv[1], sys.argv[2:]
opts = vision.PoseLandmarkerOptions(base_options=mpt.BaseOptions(model_asset_path=model), running_mode=vision.RunningMode.VIDEO, num_poses=1)
for clip in clips:
    det = vision.PoseLandmarker.create_from_options(opts)
    W, H = 480, 270
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", clip, "-vf", f"fps=15,scale={W}:{H}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
    n = len(raw) // (W * H * 3)
    rows = []
    for k in range(n):
        img = np.frombuffer(raw[k*W*H*3:(k+1)*W*H*3], np.uint8).reshape(H, W, 3)
        r = det.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(img)), int(k * 1000 / 15))
        if not r.pose_landmarks: rows.append(None); continue
        L = r.pose_landmarks[0]
        P = lambda i: (L[i].x * W, L[i].y * H, L[i].visibility)
        hip = ((P(23)[0] + P(24)[0]) / 2, (P(23)[1] + P(24)[1]) / 2)
        sh = ((P(11)[0] + P(12)[0]) / 2, (P(11)[1] + P(12)[1]) / 2)
        torso = np.hypot(sh[0] - hip[0], sh[1] - hip[1])
        wr = min(P(15)[1], P(16)[1])  # the higher wrist (smaller y)
        rows.append(dict(t=k/15, hx=hip[0], hy=hip[1], torso=torso, wrist_above_sh=(sh[1] - wr) / max(1, torso)))
    det.close()
    seen = [r for r in rows if r]
    if len(seen) < 3: print(json.dumps(dict(clip=clip.split('/')[-1], frames=n, seen=len(seen))), flush=True); continue
    torso = np.median([r['torso'] for r in seen])
    hx = np.array([r['hx'] for r in seen]); ts = np.array([r['t'] for r in seen])
    # hip travel: largest sideways move within any 1 s, in torso lengths
    travel = max(abs(hx[j] - hx[i]) for i in range(len(seen)) for j in range(i, len(seen)) if ts[j] - ts[i] <= 1.0) / torso
    print(json.dumps(dict(clip=clip.split('/')[-1], frames=n, seen=len(seen), torso=round(float(torso), 1), travel1s=round(float(travel), 2),
                          total=round(float((hx.max() - hx.min()) / torso), 2), wristUp=round(float(max(r['wrist_above_sh'] for r in seen)), 2))), flush=True)
