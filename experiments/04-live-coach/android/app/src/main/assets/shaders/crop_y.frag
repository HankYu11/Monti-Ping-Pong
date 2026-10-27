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
out vec4 outColor;
float luma(float x, float y) {
    vec3 c = texture(tex, vec2((x + 0.5) / size.x, (y + 0.5) / size.y)).rgb;
    return dot(c, wY);
}
void main() {
    vec2 f = floor(gl_FragCoord.xy);
    float y = origin.y + f.y;
    float x = origin.x + 4.0 * f.x;
    outColor = ym.x * vec4(luma(x, y), luma(x + 1.0, y), luma(x + 2.0, y), luma(x + 3.0, y)) + ym.y;
}
