// The colour moved back onto the picture (ChromaShift, #444): the brightness of each pixel kept, its colour (BT.709's
// Cb and Cr) taken from uShift away — in texture units, a quarter pixel sampled between pixels by the texture's
// linear filtering — so a tape's colour sitting beside its brightness lines up with it again.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTex;
uniform vec2 uShift;
varying vec2 vTex;

const vec3 KY = vec3(0.2126, 0.7152, 0.0722);

void main() {
  vec3 here = texture2D(uTex, vTex).rgb;
  vec3 from = texture2D(uTex, clamp(vTex + uShift, 0.0, 1.0)).rgb;
  float y = dot(here, KY);
  float yFrom = dot(from, KY);
  float cb = (from.b - yFrom) / 1.8556;
  float cr = (from.r - yFrom) / 1.5748;
  float r = y + 1.5748 * cr;
  float b = y + 1.8556 * cb;
  float g = (y - KY.r * r - KY.b * b) / KY.g;
  gl_FragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
}
