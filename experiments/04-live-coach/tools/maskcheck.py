#!/usr/bin/env python3
"""Does the graphics chip's moving-pixel test agree with the processor's, to the pixel?

The chip can do Detector.makeMask far cheaper than the processor can -- it already has every
pixel in a shader, and the processor has to walk six float arrays over three megabytes to reach
the same answer. The catch is that a mask which is ALMOST the same is worse than useless: the
blobs it feeds are the whole of the tracker's evidence, and a pixel that flips on the boundary
moves a ball's measured radius and with it the window, the rate, everything.

So before any of it goes near a phone: compile the shader that would run there, run the Kotlin
rule beside it on the same bytes, and count the pixels where they differ. Mesa's software
rasteriser does the running, so this needs no device and no GPU -- what it checks is the
ARITHMETIC, which is where the mismatches live (byte rounding, truncation, the order of the
clamp). What it cannot check is a driver: that is what the on-device count is for.

    python3 tools/maskcheck.py

The shader body is read from the app's own assets, so this cannot drift from what ships.
"""
import os, sys
import numpy as np
import moderngl

HERE = os.path.dirname(os.path.abspath(__file__))
BODY = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "shaders", "mask_body.glsl")
W, H = 480, 270

def reference(fy, fu, fv, by, bu, bv, inv):
    """Detector.makeMask, on the bytes the processor actually holds, in float32 as Kotlin does."""
    f = np.float32
    gv = np.floor(np.clip((fy.astype(f) * inv[:, None].astype(f)).astype(f), 0, 255))
    return ((np.abs(gv - by) > 30.0) | (np.abs(fu - bu) > 20.0) | (np.abs(fv - bv) > 20.0)).astype(np.uint8)

def main():
    ctx = moderngl.create_context(standalone=True, backend="egl")
    body = open(BODY).read()
    frag = """#version 330 core
in vec2 uv; out vec4 col;
uniform sampler2D tex;      // this frame: r = y, g = u + 0.5, b = v + 0.5
uniform sampler2D bgTex;    // the background, in the processor's bytes, scaled into 0..1
uniform sampler2D rowTex;   // 1 / the row's ratio, one texel a row
""" + body + """
void main() {
    vec3 c = texture(tex, uv).rgb;
    vec3 b = texture(bgTex, uv).rgb;
    float inv = texture(rowTex, vec2(0.5, uv.y)).r;
    float m = movedPixel(c, b, inv);
    col = vec4(c.r, c.g, c.b, m);
}"""
    prog = ctx.program(vertex_shader="""#version 330 core
in vec2 pos; out vec2 uv;
void main() { uv = pos * 0.5 + 0.5; gl_Position = vec4(pos, 0.0, 1.0); }""", fragment_shader=frag)

    rng = np.random.default_rng(7)
    worst = 0
    for case in range(6):
        # bytes, because bytes are what both sides really see. Case 0 is the nasty one: a
        # background a hair from the frame, so a great many pixels sit exactly on the bar.
        if case == 0:
            fy = rng.integers(0, 256, (H, W), np.int32)
            by = np.clip(fy + rng.integers(-32, 33, (H, W)), 0, 255)
        else:
            fy = rng.integers(0, 256, (H, W), np.int32); by = rng.integers(0, 256, (H, W), np.int32)
        fu = rng.integers(0, 256, (H, W), np.int32); fv = rng.integers(0, 256, (H, W), np.int32)
        bu = rng.integers(0, 256, (H, W), np.int32); bv = rng.integers(0, 256, (H, W), np.int32)
        inv = (1.0 / rng.uniform(0.9, 1.1, H)).astype(np.float32) if case != 1 else np.ones(H, np.float32)

        frame = np.dstack([fy, fu, fv, np.full((H, W), 255)]).astype(np.uint8)
        bgimg = np.dstack([by, bu, bv, np.full((H, W), 255)]).astype(np.uint8)
        t0 = ctx.texture((W, H), 4, frame.tobytes()); t0.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t1 = ctx.texture((W, H), 4, bgimg.tobytes()); t1.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t2 = ctx.texture((1, H), 1, inv.tobytes(), dtype="f4"); t2.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t0.use(0); t1.use(1); t2.use(2)
        prog["tex"].value = 0; prog["bgTex"].value = 1; prog["rowTex"].value = 2
        fbo = ctx.simple_framebuffer((W, H)); fbo.use()
        quad = ctx.buffer(np.array([-1,-1, 3,-1, -1,3], np.float32).tobytes())
        ctx.vertex_array(prog, [(quad, "2f", "pos")]).render(moderngl.TRIANGLES)
        got = np.frombuffer(fbo.read(components=4), np.uint8).reshape(H, W, 4)[:, :, 3]
        got = (got > 127).astype(np.uint8)

        want = reference(fy, fu, fv, by, bu, bv, inv)
        bad = int((got != want).sum())
        worst = max(worst, bad)
        print(f"  case {case}: {bad:>6} of {W*H} pixels differ   ({'ok' if bad == 0 else 'MISMATCH'})"
              + ("   [background within 32 levels: most pixels sit on the bar]" if case == 0 else ""))
        for t in (t0, t1, t2): t.release()
        fbo.release(); quad.release()
    print()
    if worst == 0:
        print("the chip's mask and the processor's are the same mask.")
    else:
        print(f"they are NOT the same mask: {worst} pixels at worst. Do not ship it.")
    return 0 if worst == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
