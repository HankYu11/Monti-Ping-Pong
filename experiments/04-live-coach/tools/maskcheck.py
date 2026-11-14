#!/usr/bin/env python3
"""Does the graphics chip's moving-pixel mask agree with the processor's, to the pixel?

The finder's dominant cost is Detector.makeMask walking six float arrays over three megabytes
for every frame. The chip already holds every pixel in a shader and can do it for nothing. The
catch is that a mask which is ALMOST right is worse than no change: the blobs are the whole of
the tracker's evidence, and a pixel that flips on the boundary moves a ball's measured radius
and with it the window, the rate, everything.

So the shader is checked before it is shipped, here, with no phone and no GPU. Mesa's software
rasteriser compiles THE APP'S OWN shader files and runs them; Detector.rowRatios and makeMask
are ported beside them; the disagreeing pixels are counted. What this proves is the ARITHMETIC
-- byte rounding, the 16-bit packing, the order of the clamp, the five-row average -- which is
where mask bugs live and where none of them announce themselves. What it cannot prove is a
driver. That is what the on-device count is for.

    python3 tools/maskcheck.py

It has already earned itself twice: once on rounding the frame's bytes but not the background's
(13 to 66 pixels wrong, every time, hidden entirely in the colour terms), and once on the row
sums, which do not fit in a byte and do not fit exactly in a half float either.
"""
import os, sys
import numpy as np
import moderngl

HERE = os.path.dirname(os.path.abspath(__file__))
SH = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "shaders")
W, H = 480, 270
HEAD = "#version 330 core\n"


def reference(fy, fu, fv, by, bu, bv, col0=0):
    """Detector.rowRatios + makeMask, on bytes, in float32 as Kotlin does it."""
    f = np.float32
    sg = np.zeros(H, f); sb = np.zeros(H, f)
    cols = np.arange(col0, W, 4)
    m = by[:, cols] > 20.0
    sg = np.where(m, fy[:, cols], 0).sum(axis=1, dtype=np.float64).astype(f)
    sb = np.where(m, by[:, cols], 0).sum(axis=1, dtype=np.float64).astype(f)
    raw = np.where(sb > 0, sg / np.maximum(sb, 1e-9), 1.0).astype(f)
    rows = np.empty(H, f)
    for y in range(H):
        lo, hi = max(0, y - 2), min(H - 1, y + 2)
        rows[y] = np.clip(raw[lo:hi + 1].mean(dtype=np.float64), 0.5, 2.0)
    inv = (1.0 / rows).astype(f)
    gv = np.floor(np.clip((fy.astype(f) * inv[:, None]).astype(f), 0, 255))
    return (((np.abs(gv - by) > 30.0) | (np.abs(fu - bu) > 20.0) | (np.abs(fv - bv) > 20.0))
            .astype(np.uint8)), raw, rows


def main():
    ctx = moderngl.create_context(standalone=True, backend="egl")
    body = open(os.path.join(SH, "mask_body.glsl")).read()
    # the app's OWN vertex shader. Substituting a convenient one here is how a missing
    # interpolator got past this check once already: quad.vert hands on nothing but a position,
    # and a fragment shader that declared "in vec2 uv" would not have linked on a phone.
    vert = HEAD + open(os.path.join(SH, "quad.vert")).read().split("\n", 1)[1]
    rows_prog = ctx.program(vertex_shader=vert, fragment_shader=HEAD + open(os.path.join(SH, "rows.frag")).read())
    mask_prog = ctx.program(vertex_shader=vert, fragment_shader=HEAD + body + open(os.path.join(SH, "mask.frag")).read())
    quad = ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], np.float32).tobytes())

    rng = np.random.default_rng(11)
    worst = 0
    cases = [
        ("background within 32 levels: most pixels on the bar", "near"),
        ("flat background 0: every column skipped, ratio falls back to 1", "dark"),
        ("random everything", "rand"),
        ("background all 255: the sums run to their maximum", "bright"),
        ("a real flicker: rows banded 0.9 to 1.1", "band"),
        ("random everything, another draw", "rand"),
    ]
    for name, kind in cases:
        if kind == "near":
            fy = rng.integers(0, 256, (H, W), np.int32); by = np.clip(fy + rng.integers(-32, 33, (H, W)), 0, 255)
        elif kind == "dark":
            fy = rng.integers(0, 256, (H, W), np.int32); by = np.zeros((H, W), np.int32)
        elif kind == "bright":
            fy = rng.integers(0, 256, (H, W), np.int32); by = np.full((H, W), 255, np.int32)
        elif kind == "band":
            base = rng.integers(40, 220, (H, W), np.int32)
            band = (1.0 + 0.1 * np.sin(np.arange(H) * 0.4))[:, None]
            fy = np.clip((base * band).astype(np.int32), 0, 255); by = base
        else:
            fy = rng.integers(0, 256, (H, W), np.int32); by = rng.integers(0, 256, (H, W), np.int32)
        fu = rng.integers(0, 256, (H, W), np.int32); fv = rng.integers(0, 256, (H, W), np.int32)
        bu = rng.integers(0, 256, (H, W), np.int32); bv = rng.integers(0, 256, (H, W), np.int32)

        frame = np.dstack([fy, fu, fv, np.full((H, W), 255)]).astype(np.uint8)
        bgimg = np.dstack([by, bu, bv, np.full((H, W), 255)]).astype(np.uint8)
        tS = ctx.texture((W, H), 4, frame.tobytes()); tB = ctx.texture((W, H), 4, bgimg.tobytes())
        for t in (tS, tB):
            t.filter = (moderngl.NEAREST, moderngl.NEAREST); t.repeat_x = False; t.repeat_y = False

        # pass 2: the row sums, into a 1 x H RGBA8 target
        fRows = ctx.simple_framebuffer((1, H)); fRows.use()
        tS.use(0); tB.use(1)
        rows_prog["smallTex"].value = 0; rows_prog["bgTex"].value = 1
        rows_prog["smallSize"].value = (float(W), float(H))
        rows_prog["col0"].value = 0.0
        ctx.vertex_array(rows_prog, [(quad, "2f", "pos")]).render(moderngl.TRIANGLES)
        rowPix = np.frombuffer(fRows.read(components=4), np.uint8).reshape(H, 1, 4)
        tR = ctx.texture((1, H), 4, rowPix.tobytes())
        tR.filter = (moderngl.NEAREST, moderngl.NEAREST); tR.repeat_x = False; tR.repeat_y = False

        # pass 3: the mask, in alpha
        fOut = ctx.simple_framebuffer((W, H)); fOut.use()
        ctx.disable(moderngl.BLEND)
        tS.use(0); tB.use(1); tR.use(2)
        mask_prog["smallTex"].value = 0; mask_prog["bgTex"].value = 1; mask_prog["rowTex"].value = 2
        mask_prog["smallSize"].value = (float(W), float(H))
        ctx.vertex_array(mask_prog, [(quad, "2f", "pos")]).render(moderngl.TRIANGLES)
        got = (np.frombuffer(fOut.read(components=4), np.uint8).reshape(H, W, 4)[:, :, 3] > 127).astype(np.uint8)

        want, raw, rowsRef = reference(fy, fu, fv, by.astype(np.int32), bu, bv)
        # the sums, decoded, so a disagreement can be read rather than guessed at
        sgGot = rowPix[:, 0, 0].astype(float) + rowPix[:, 0, 1].astype(float) * 256.0
        cols = np.arange(0, W, 4); m = by[:, cols] > 20
        sgRef = np.where(m, fy[:, cols], 0).sum(axis=1)
        sumBad = int((sgGot != sgRef).sum())
        bad = int((got != want).sum())

        # The handover, which is the other half of being right. The chip works in the picture's
        # own way round; the finders work in the flipped one, and cut their mask from the
        # FLIPPED picture -- so the row gains are averaged over rows in a different order, and
        # the five-row window clamps at the opposite edge. Turning the chip's mask afterwards is
        # only the same answer if that makes no difference. Checked here rather than reasoned
        # about: an upside-down mask is not a loud failure, it is a session that finds nothing.
        turned = 0; per = {}
        for flip in (1, 2, 3):
            def t(a):
                b = a[::-1, :] if flip & 2 else a
                return b[:, ::-1] if flip & 1 else b
            # with a left-right turn the chip must start where the turned picture's column 0
            # lands, which for 480 columns is column 3 -- told to it as col0
            cs = (W - 1) % 4 if flip & 1 else 0
            rows_prog["col0"].value = float(cs)
            fRows.use(); tS.use(0); tB.use(1)
            ctx.vertex_array(rows_prog, [(quad, "2f", "pos")]).render(moderngl.TRIANGLES)
            rp = np.frombuffer(fRows.read(components=4), np.uint8).reshape(H, 1, 4)
            tRf = ctx.texture((1, H), 4, rp.tobytes())
            tRf.filter = (moderngl.NEAREST, moderngl.NEAREST); tRf.repeat_x = False; tRf.repeat_y = False
            fOut.use(); tS.use(0); tB.use(1); tRf.use(2)
            ctx.vertex_array(mask_prog, [(quad, "2f", "pos")]).render(moderngl.TRIANGLES)
            gotF = (np.frombuffer(fOut.read(components=4), np.uint8).reshape(H, W, 4)[:, :, 3] > 127).astype(np.uint8)
            tRf.release()
            wantF, _, _ = reference(t(fy), t(fu), t(fv), t(by).astype(np.int32), t(bu), t(bv))
            per[flip] = int((t(gotF) != wantF).sum()); turned = max(turned, per[flip])
        bad = max(bad, turned)
        worst = max(worst, bad)
        print(f"  {name:<52} {bad:>6} of {W*H} differ   row sums wrong on {sumBad} of {H} rows"
              + f"   turned: {per}" + ("" if bad == 0 else "   MISMATCH"))
        for t in (tS, tB, tR): t.release()
        fRows.release(); fOut.release()

    print()
    if worst == 0:
        print("the chip's mask and the processor's are the same mask.")
        return 0
    print(f"they are NOT the same mask: {worst} pixels at worst. Do not ship it.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
