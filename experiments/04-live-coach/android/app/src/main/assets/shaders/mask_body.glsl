// The moving-pixel test, shared by the graphics chip and the processor.
//
// This is Detector.makeMask, pixel for pixel: a pixel moved when its brightness differs from the
// background by more than 30 levels, OR either colour by more than 30/1.5. The brightness is
// first scaled by the row's own ratio against the background, which is how the mains flicker is
// taken out -- `inv` is 1/that ratio, and the processor computes it the same way.
//
// Everything is settled in the units the processor sees, which is BYTES. The chip's picture
// reaches the processor through an 8-bit read-back, so a pixel the shader calls 128.4 arrives as
// 128, and a comparison made before that rounding lands on the wrong side of the bar for any
// pixel sitting on it. BOTH sides of every comparison are rounded here, in asByte, in one place.
//
// That one detail is not hypothetical: rounding the frame and not the background disagreed with
// the processor on 13 to 66 pixels of 129,600, every time, and the colour terms were where it
// hid. tools/maskcheck.py is what found it, before any of this reached a phone.
//
// The background must be a whole number for this to be exact, and it is: it is the median of
// five byte-valued frames, so it IS one of them. While fewer than five are held the median can
// land halfway and the chip's mask must not be trusted -- the caller waits.
//
// Lives in its own file because two things compile it: the app, wrapped in GLSL ES around the
// camera's external texture, and tools/maskcheck.py, wrapped in GLSL 3.30 around an ordinary
// one, which is what proves the two agree without a phone in the room.

float asByte(float x) { return floor(clamp(x * 255.0 + 0.5, 0.0, 255.0)); }

// c: this pixel as the chip computes it   (r = y, g = u + 0.5, b = v + 0.5)
// b: the background, stored the same way
// inv: 1 / the row's brightness ratio
float movedPixel(vec3 c, vec3 b, float inv) {
    float yb = asByte(c.r);
    float gv = floor(clamp(yb * inv, 0.0, 255.0));          // truncated, as Kotlin's toInt()
    bool moved = abs(gv - asByte(b.r)) > 30.0
              || abs(asByte(c.g) - asByte(b.g)) > 20.0
              || abs(asByte(c.b) - asByte(b.b)) > 20.0;
    return moved ? 1.0 : 0.0;
}
