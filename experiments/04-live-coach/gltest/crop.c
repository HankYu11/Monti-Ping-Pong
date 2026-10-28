// Runs the app's capture shaders (assets/shaders) on Mesa: RGB frames in (stdin, W x H x 3),
// the capture's planes out (stdout: Y w x h, then U and V w/2 x h/2 each). The only change from
// the app: an ordinary 2D texture instead of the decoder's external one.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static char *load(const char *dir, const char *name) {
    char p[1024]; snprintf(p, sizeof p, "%s/%s", dir, name);
    FILE *f = fopen(p, "rb"); if (!f) { fprintf(stderr, "no %s\n", p); exit(1); }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *s = calloc(n + 1, 1); fread(s, 1, n, f); fclose(f);
    // as the app's, but a 2D texture
    char *e = strstr(s, "#extension GL_OES_EGL_image_external_essl3 : require");
    if (e) memset(e, ' ', strlen("#extension GL_OES_EGL_image_external_essl3 : require"));
    char *x; while ((x = strstr(s, "samplerExternalOES"))) { memcpy(x, "sampler2D         ", 18); }
    return s;
}
static GLuint shader(GLenum t, const char *src) {
    GLuint s = glCreateShader(t); glShaderSource(s, 1, &src, NULL); glCompileShader(s);
    GLint ok; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[2048]; glGetShaderInfoLog(s, sizeof log, NULL, log); fprintf(stderr, "shader: %s\n", log); exit(1); }
    return s;
}
static GLuint program(const char *vs, const char *fs) {
    GLuint p = glCreateProgram(); glAttachShader(p, shader(GL_VERTEX_SHADER, vs)); glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs));
    glBindAttribLocation(p, 0, "pos"); glLinkProgram(p);
    GLint ok; glGetProgramiv(p, GL_LINK_STATUS, &ok); if (!ok) { fprintf(stderr, "link\n"); exit(1); }
    return p;
}
static GLuint target(int w, int h) {
    GLuint t; glGenTextures(1, &t); glBindTexture(GL_TEXTURE_2D, t);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL); return t;
}
int main(int argc, char **argv) {
    if (argc < 8) { fprintf(stderr, "crop shaderDir W H x0 y0 w h [601]\n"); return 1; }
    int m601 = argc > 8 && atoi(argv[8]) == 601;
    float wY[3] = { 0.2126f, 0.7152f, 0.0722f }, cs[2] = { 1.8556f, 1.5748f };
    if (m601) { wY[0] = 0.299f; wY[1] = 0.587f; wY[2] = 0.114f; cs[0] = 1.772f; cs[1] = 1.402f; }
    // maps to bytes (gain, offset in bytes), as the app's ColourFit: argv 9-14
    float ym[2] = { 1, 0 }, um[2] = { 1, 0 }, vm[2] = { 1, 0 };
    if (argc > 14) { ym[0] = atof(argv[9]); ym[1] = atof(argv[10]) / 255; um[0] = atof(argv[11]); um[1] = atof(argv[12]) / 255; vm[0] = atof(argv[13]); vm[1] = atof(argv[14]) / 255; }
    const char *dir = argv[1]; int W = atoi(argv[2]), H = atoi(argv[3]), x0 = atoi(argv[4]), y0 = atoi(argv[5]), w = atoi(argv[6]), h = atoi(argv[7]);
    PFNEGLGETPLATFORMDISPLAYEXTPROC gpd = (void*)eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay d = gpd(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    eglInitialize(d, NULL, NULL); eglBindAPI(EGL_OPENGL_ES_API);
    EGLint xa[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext x = eglCreateContext(d, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, xa);
    eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, x);
    const char *vs = load(dir, "quad.vert");
    GLuint py = program(vs, load(dir, "crop_y.frag")), pc = program(vs, load(dir, "crop_c.frag"));
    GLuint src; glGenTextures(1, &src); glBindTexture(GL_TEXTURE_2D, src);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    GLuint ty = target(w / 4, h), tu = target(w / 8, h / 2), tv = target(w / 8, h / 2);
    GLuint fy, fc; glGenFramebuffers(1, &fy); glGenFramebuffers(1, &fc);
    glBindFramebuffer(GL_FRAMEBUFFER, fy); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ty, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, fc); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tu, 0);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, tv, 0);
    GLenum bufs[2] = { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 }; glDrawBuffers(2, bufs);
    float quad[] = { -1, -1, 1, -1, -1, 1, 1, 1 };
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
    int TH = getenv("TEXH") ? atoi(getenv("TEXH")) : H;     /* the buffer's rows (a decoder pads 1080 to 1088) */
    float SZH = getenv("SIZEH") ? atof(getenv("SIZEH")) : H; /* what the shader is told */
    unsigned char *rgb = malloc((size_t) W * TH * 3), *out = malloc((size_t) w * h * 3 / 2);
    int n = 0;
    while (fread(rgb, 1, (size_t) W * TH * 3, stdin) == (size_t) W * TH * 3) {
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, src);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, W, TH, 0, GL_RGB, GL_UNSIGNED_BYTE, rgb);
        // brightness
        glBindFramebuffer(GL_FRAMEBUFFER, fy); glViewport(0, 0, w / 4, h); glUseProgram(py);
        glUniform1i(glGetUniformLocation(py, "tex"), 0);
        glUniform2f(glGetUniformLocation(py, "size"), W, SZH); glUniform2f(glGetUniformLocation(py, "origin"), x0, y0);
        glUniform3fv(glGetUniformLocation(py, "wY"), 1, wY); glUniform2fv(glGetUniformLocation(py, "ym"), 1, ym);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glReadBuffer(GL_COLOR_ATTACHMENT0); glReadPixels(0, 0, w / 4, h, GL_RGBA, GL_UNSIGNED_BYTE, out);
        // colour
        glBindFramebuffer(GL_FRAMEBUFFER, fc); glViewport(0, 0, w / 8, h / 2); glUseProgram(pc);
        glUniform1i(glGetUniformLocation(pc, "tex"), 0);
        glUniform2f(glGetUniformLocation(pc, "size"), W, SZH); glUniform2f(glGetUniformLocation(pc, "origin"), x0, y0);
        glUniform3fv(glGetUniformLocation(pc, "wY"), 1, wY); glUniform2fv(glGetUniformLocation(pc, "cs"), 1, cs);
        glUniform2fv(glGetUniformLocation(pc, "um"), 1, um); glUniform2fv(glGetUniformLocation(pc, "vm"), 1, vm);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glReadBuffer(GL_COLOR_ATTACHMENT0); glReadPixels(0, 0, w / 8, h / 2, GL_RGBA, GL_UNSIGNED_BYTE, out + w * h);
        glReadBuffer(GL_COLOR_ATTACHMENT1); glReadPixels(0, 0, w / 8, h / 2, GL_RGBA, GL_UNSIGNED_BYTE, out + w * h + w * h / 4);
        fwrite(out, 1, (size_t) w * h * 3 / 2, stdout); n++;
    }
    fprintf(stderr, "%d frames, GL error %x\n", n, glGetError());
    return 0;
}
