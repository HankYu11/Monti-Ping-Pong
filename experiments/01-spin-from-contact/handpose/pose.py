# The body pose in frames of a clip: per frame both wrists, index and pinky knuckles and thumbs, in pixels and world (m).
import sys, subprocess, json, numpy as np, mediapipe as mp
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision
clip, f0, f1 = sys.argv[1], max(0, int(sys.argv[2])), int(sys.argv[3])
det = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
    base_options=mpt.BaseOptions(model_asset_path=sys.argv[4]), running_mode=vision.RunningMode.VIDEO,
    min_pose_detection_confidence=0.3, min_tracking_confidence=0.3))
raw = subprocess.run(["ffmpeg", "-v", "error", "-i", clip, "-vf", f"select='between(n\\,{f0}\\,{f1})'", "-vsync", "0", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, stdin=subprocess.DEVNULL).stdout
frames = np.frombuffer(raw, np.uint8).reshape(-1, 1080, 1920, 3)
names = {"lw": 15, "rw": 16, "lp": 17, "rp": 18, "li": 19, "ri": 20, "lt": 21, "rt": 22, "le": 13, "re": 14}
for k, fr in enumerate(frames):
    r = det.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(fr)), int((f0 + k) * 1000 / 240))
    if not r.pose_landmarks: print(json.dumps({"frame": f0 + k})); continue
    lm, wl = r.pose_landmarks[0], r.pose_world_landmarks[0]
    print(json.dumps({"frame": f0 + k, **{n: {"px": [round(lm[i].x * 1920, 1), round(lm[i].y * 1080, 1)], "w": [round(wl[i].x, 4), round(wl[i].y, 4), round(wl[i].z, 4)], "vis": round(lm[i].visibility, 2)} for n, i in names.items()}}))
