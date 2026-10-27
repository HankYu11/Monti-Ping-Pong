// A camera frame (a graphics buffer, from an ImageReader) as an OpenGL texture, without copying:
// the one thing Java can't do (no EGLImage from a HardwareBuffer there). GlTap draws from it.
#include <jni.h>
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer_jni.h>

// Binds the buffer to the external texture [tex] of the current context; returns the EGLImage
// (to release once the chip is done with it), or 0.
JNIEXPORT jlong JNICALL
Java_com_monti_servecoach_HwTex_bind(JNIEnv *env, jclass cls, jobject hardwareBuffer, jint tex) {
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) return 0;
    EGLClientBuffer cb = eglGetNativeClientBufferANDROID(ahb);
    if (!cb) return 0;
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR img = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
    if (img == EGL_NO_IMAGE_KHR) return 0;
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, (GLuint) tex);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES) img);
    return (jlong) (intptr_t) img;
}

JNIEXPORT void JNICALL
Java_com_monti_servecoach_HwTex_release(JNIEnv *env, jclass cls, jlong image) {
    if (image) eglDestroyImageKHR(eglGetCurrentDisplay(), (EGLImageKHR) (intptr_t) image);
}

// The EGLImage of a buffer, made once and kept (the camera cycles the same few buffers): 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_monti_servecoach_HwTex_image(JNIEnv *env, jclass cls, jobject hardwareBuffer) {
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) return 0;
    EGLClientBuffer cb = eglGetNativeClientBufferANDROID(ahb);
    if (!cb) return 0;
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR img = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
    return img == EGL_NO_IMAGE_KHR ? 0 : (jlong) (intptr_t) img;
}

// Attaches an EGLImage to the external texture [tex] of the current context.
JNIEXPORT void JNICALL
Java_com_monti_servecoach_HwTex_attach(JNIEnv *env, jclass cls, jlong image, jint tex) {
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, (GLuint) tex);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES) (intptr_t) image);
}
