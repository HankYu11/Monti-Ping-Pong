#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// Every strideth column of every stepth row of the crop, as brightness: 4 samples an output
// pixel (R, G, B, A = the next four sampled columns), so the read-back is the row sample
// itself, row by row.
//
// A windowed capture divides each row by how bright it was against the background, and that
// ratio is the median over these samples (Windows.rowBright). Cutting the window alone is not
// enough: without the row brightness the crop outside the window stops flickering with the
// light and the correction inside it goes wrong.
//
// The row step is what makes this affordable on the chip: the whole picture's rows at every
// sixteenth column is 130 KB a frame, every other row 65 KB, which is what the window itself
// costs. The rows between are interpolated, and WindowsPartsTest measures what that costs
// against reading every row: a step of 2 moves the row brightness by 0.002 on average, 4 by
// 0.004, 8 by 0.012, where the row-to-row noise on the captures recorded is itself 0.0066.
// It can be this coarse because the flicker is a rolling-shutter band, and at 240 frames a
// second a frame's rows span well under half a mains cycle -- about a quarter of one across a
// typical crop -- so the row profile is a smooth arc.
precision highp float;
uniform samplerExternalOES tex;
uniform vec2 size;     // the whole frame, pixels
uniform vec2 origin;   // the crop's top left, pixels
uniform vec3 wY;       // brightness weights of R, G, B
uniform vec2 ym;       // then to the decoder's own bytes: gain, offset (ColourFit)
uniform vec2 steps;    // x: the column stride, y: the row step
uniform vec2 mirror;   // see crop_y.frag
out vec4 outColor;
float luma(float x, float y) {
    float sx = mix(x, size.x - 1.0 - x, mirror.x);
    float sy = mix(y, size.y - 1.0 - y, mirror.y);
    vec3 c = texture(tex, vec2((sx + 0.5) / size.x, (sy + 0.5) / size.y)).rgb;
    return dot(c, wY);
}
void main() {
    vec2 f = floor(gl_FragCoord.xy);
    float y = origin.y + f.y * steps.y;
    float x = origin.x + 4.0 * f.x * steps.x;
    outColor = ym.x * vec4(luma(x, y), luma(x + steps.x, y), luma(x + 2.0 * steps.x, y),
                           luma(x + 3.0 * steps.x, y)) + ym.y;
}
