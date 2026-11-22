// A shaky picture steadied (Deshake, #449): each pixel read from where the correction uSteady (texture coordinates to
// texture coordinates, Deshake.textureMatrix) puts it, linearly, mirrored back in where that lies past an edge, as
// FFmpeg's deshake mirrors by default.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTex;
uniform mat3 uSteady;
varying vec2 vTex;

vec2 mirrored(vec2 p) {
  return 1.0 - abs(1.0 - mod(abs(p), 2.0));
}

void main() {
  vec3 from = uSteady * vec3(vTex, 1.0);
  gl_FragColor = vec4(texture2D(uTex, mirrored(from.xy)).rgb, 1.0);
}
