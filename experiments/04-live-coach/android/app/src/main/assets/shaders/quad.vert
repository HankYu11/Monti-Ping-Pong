#version 300 es
// a full-screen quad; the fragment shaders work from gl_FragCoord
in vec2 pos;
void main() { gl_Position = vec4(pos, 0.0, 1.0); }
