#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// A capture's brightness plane, cut from a decoded frame on the graphics chip (CaptureGl):
// each output pixel holds 4 neighbouring pixels' brightness (R, G, B, A = x, x+1, x+2, x+3),
// so the read-back is the plane itself, row by row (row 0 = the frame's row origin.y).
// From the colour the chip's sampler gives, undone with the matrix it used
// (wY: BT.709 or BT.601, chosen by CaptureGl's check against a decoded frame: a mismatch
// leaks colour into brightness), then mapped to the decoder's own bytes (ym, ColourFit).
precision highp float;
uniform samplerExternalOES tex;
uniform vec2 size;     // the whole frame, pixels
uniform vec2 origin;   // the crop's top left, pixels
uniform vec3 wY;       // brightness weights of R, G, B
uniform vec2 ym;       // then to the decoder's own bytes: gain, offset (ColourFit)
// 1 where the frame as the chip holds it runs the other way (GlTap's live check: the camera's
// buffer against the recording). 0, which is what a decoded frame needs, is the default: an
// unset uniform is zero, and CaptureGl leaves it so.
uniform vec2 mirror;
out vec4 outColor;
float luma(float x, float y) {
    float sx = mix(x, size.x - 1.0 - x, mirror.x);
    float sy = mix(y, size.y - 1.0 - y, mirror.y);
    vec3 c = texture(tex, vec2((sx + 0.5) / size.x, (sy + 0.5) / size.y)).rgb;
    return dot(c, wY);
}
void main() {
    vec2 f = floor(gl_FragCoord.xy);
    float y = origin.y + f.y;
    float x = origin.x + 4.0 * f.x;
    outColor = ym.x * vec4(luma(x, y), luma(x + 1.0, y), luma(x + 2.0, y), luma(x + 3.0, y)) + ym.y;
}
