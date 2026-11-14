// Pass 2 of the chip's mask: each row's brightness against the background's, as one texel.
//
// Detector.rowRatios, exactly: every 4th column of the row, skipping columns where the
// background is 20 or darker, summing this frame's brightness and the background's separately.
// The ratio itself is NOT taken here -- the processor averages five rows' RATIOS, so the ratios
// have to survive to pass 3 one per row, which means the two sums travel instead.
//
// The sums reach 120 x 255 = 30,600, which no 8-bit channel holds and no half float holds
// exactly either (FP16 carries 11 bits of mantissa and cannot represent integers past 2048 --
// on a Mali that silently truncates and every ratio drifts). Rendering to a float texture needs
// EXT_color_buffer_float, which plenty of mid-range Android parts do not have. So each sum goes
// into two bytes, low then high, and comes back out as an exact integer. 16 bits holds 65,535.
//
// precision highp float is not decoration here: at mediump this arithmetic is wrong.

uniform sampler2D smallTex;     // the small picture: r = y, g = u + 0.5, b = v + 0.5
uniform sampler2D bgTex;        // the background, stored the same way
uniform vec2 smallSize;         // 480 x 270
// Which column the row's sample starts on, 0 to 3.
//
// The processor cuts its mask from the picture turned the way the camera gives it, and this
// shader works on the picture before it is turned. A turn top to bottom costs nothing -- the
// same rows, in the other order, and a five-row average does not care. A turn left to right
// does: rowRatios walks every 4th column from 0, and column 0 of the turned picture is column
// 479 of this one, so without this the two sum DIFFERENT columns and the gains part company.
// Measured, not guessed at: 96 pixels of 129,600 on random footage, every one of them a pixel
// sitting on the threshold, which is exactly where a ball's edge sits.
uniform float col0;
in vec2 uv;
out vec4 fragColor;

vec2 twoBytes(float v) { float hi = floor(v / 256.0); return vec2(v - hi * 256.0, hi) / 255.0; }

void main() {
    float sg = 0.0, sb = 0.0;
    float y = uv.y;
    for (int i = 0; i < 120; i++) {
        float x = (float(i) * 4.0 + col0 + 0.5) / smallSize.x;
        float b = floor(clamp(texture(bgTex, vec2(x, y)).r * 255.0 + 0.5, 0.0, 255.0));
        if (b > 20.0) {
            sg += floor(clamp(texture(smallTex, vec2(x, y)).r * 255.0 + 0.5, 0.0, 255.0));
            sb += b;
        }
    }
    fragColor = vec4(twoBytes(sg), twoBytes(sb));
}
