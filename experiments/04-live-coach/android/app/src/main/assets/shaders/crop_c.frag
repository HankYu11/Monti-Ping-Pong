#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// The capture's two colour planes (half size each way), in one pass to two targets: 4 colour
// samples per output pixel, each the mean of its 2x2 block (one filtered read at the block's
// middle). U = (B - Y) / cs.x, V = (R - Y) / cs.y, + 0.5 (BT.709: 1.8556, 1.5748; BT.601:
// 1.772, 1.402), then mapped to the decoder's own bytes (um, vm: ColourFit).
precision highp float;
uniform samplerExternalOES tex;
uniform vec2 size;     // the whole frame, pixels
uniform vec2 origin;   // the crop's top left, pixels (even)
uniform vec3 wY;       // brightness weights of R, G, B
uniform vec2 cs;       // colour scales: (B - Y) / cs.x, (R - Y) / cs.y
uniform vec2 um;       // then to the decoder's own bytes: gain, offset (ColourFit)
uniform vec2 vm;
layout(location = 0) out vec4 outU;
layout(location = 1) out vec4 outV;
vec2 uv(float cx, float cy) {
    vec3 c = texture(tex, vec2((2.0 * cx + 1.0) / size.x, (2.0 * cy + 1.0) / size.y)).rgb;
    float y = dot(c, wY);
    return vec2((c.b - y) / cs.x + 0.5, (c.r - y) / cs.y + 0.5);
}
void main() {
    vec2 f = floor(gl_FragCoord.xy);
    float cy = origin.y * 0.5 + f.y;
    float cx = origin.x * 0.5 + 4.0 * f.x;
    vec2 a = uv(cx, cy), b = uv(cx + 1.0, cy), c = uv(cx + 2.0, cy), d = uv(cx + 3.0, cy);
    outU = um.x * vec4(a.x, b.x, c.x, d.x) + um.y;
    outV = vm.x * vec4(a.y, b.y, c.y, d.y) + vm.y;
}
