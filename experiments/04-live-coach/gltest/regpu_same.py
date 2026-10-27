# A capture (.cap, made the way the app does now: decoded planes copied out) made again the
# new way: the same frames through the app's shaders (Mesa), full range. -> <out>/<name>.cap
import sys, json, struct, subprocess, os
cap, clip, shaders, outdir = sys.argv[1:5]
buf = open(cap, "rb").read(); hl = struct.unpack(">I", buf[:4])[0]; head = json.loads(buf[4:4 + hl])
W, H = head["W"], head["H"]; x0, y0, w, h = head["rect"]; n = head["n"]; first = head["first"]
w8 = w - w % 8
dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", clip, "-vf", f"select=between(n\\,{first}\\,{first + n - 1})", "-vsync", "0",
                        "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
crop = subprocess.run([os.path.dirname(__file__) + "/crop", shaders, str(W), str(H), str(x0), str(y0), str(w8), str(h)], stdin=dec.stdout, capture_output=True)
frames = crop.stdout; per = w8 * h * 3 // 2
head2 = dict(head, rect=[x0, y0, w8, h], n=len(frames) // per, full=head.get("full", False))
hb = json.dumps(head2).encode()
os.makedirs(outdir, exist_ok=True)
open(os.path.join(outdir, os.path.basename(cap)), "wb").write(struct.pack(">I", len(hb)) + hb + frames)
print(os.path.basename(cap), "frames", len(frames) // per, "of", n, crop.stderr.decode().strip())
