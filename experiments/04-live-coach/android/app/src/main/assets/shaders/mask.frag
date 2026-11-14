// Pass 3 of the chip's mask: the moving-pixel test, with the mask in the alpha channel.
//
// Alpha was being written as 1.0 and thrown away, so the mask rides out on the same read-back
// the picture already uses -- no second buffer, no extra bandwidth. The processor then reads
// byte 3 of every pixel and goes straight to the opening and the blobs, with makeMask's six
// float arrays over three megabytes gone from its side entirely.
//
// The five-row smoothing happens HERE and not in pass 2, because a fragment cannot see its
// neighbours' results in the pass that produces them. The row texture is sampled five times with
// whole-texel offsets and must be bound GL_NEAREST: its texels are packed byte pairs, and linear
// filtering would interpolate the low and high bytes independently and return nonsense. The x
// coordinate is pinned to 0.5 rather than trusted to an interpolator, because the texture is one
// texel wide and some drivers do not land where you would hope.

uniform sampler2D smallTex;     // the small picture: r = y, g = u + 0.5, b = v + 0.5
uniform sampler2D bgTex;        // the background, stored the same way
uniform sampler2D rowTex;       // pass 2: each row's two sums, as byte pairs
uniform vec2 smallSize;         // 480 x 270
out vec4 fragColor;

float fromBytes(vec2 b) { return b.x * 255.0 + b.y * 255.0 * 256.0; }

void main() {
    // the row's ratio, averaged with its neighbours' -- the ratios, as the processor averages
    // them, and over however many of the five exist at the picture's edges
    float sum = 0.0, n = 0.0;
    float rows = smallSize.y;
    vec2 uv = gl_FragCoord.xy / smallSize;      // quad.vert hands on no uv; nothing here may
    float row = floor(gl_FragCoord.y);          // depend on an interpolator it does not have
    for (int k = -2; k <= 2; k++) {
        float r = row + float(k);
        if (r < 0.0 || r > rows - 1.0) continue;
        vec4 p = texture(rowTex, vec2(0.5, (r + 0.5) / rows));
        float sg = fromBytes(p.rg), sb = fromBytes(p.ba);
        sum += (sb > 0.0) ? sg / sb : 1.0;
        n += 1.0;
    }
    float ratio = clamp(sum / n, 0.5, 2.0);
    vec3 c = texture(smallTex, uv).rgb;
    fragColor = vec4(c, movedPixel(c, texture(bgTex, uv).rgb, 1.0 / ratio));
}
