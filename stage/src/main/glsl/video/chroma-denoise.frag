// A tape's colour noise removed (ChromaDenoise, #446): each pixel keeps its brightness, its colour (BT.709's Cb and Cr)
// the weighted mean of the colours around it (5 x 5, a Gaussian of 2 pixels) in the frame shown and, where nothing
// moves, in the frames before and after (uPrev, uNext) — weighed by how alike their brightness is to this pixel's
// (uGuide2: twice the deviation squared, 8-bit steps), so the colour stays on its own side of an edge. A side is taken
// in full where its brightness differs by at most uMotion over the pixel and its neighbours left and right, in part
// up to twice it. Read at texel centres, nearest.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uPrev;
uniform sampler2D uNext;
uniform vec2 uTexel;
uniform float uGuide2;
uniform float uMotion;
varying vec2 vTex;

const vec3 KY = vec3(0.2126, 0.7152, 0.0722);

float lumaOf(vec3 c) {
  return dot(c, KY) * 255.0;
}

float share(sampler2D side) {
  float motion = 0.0;
  for (int dx = -1; dx <= 1; dx++) {
    vec2 at = clamp(vTex + vec2(float(dx) * uTexel.x, 0.0), 0.0, 1.0);
    motion = max(motion, abs(lumaOf(texture2D(side, at).rgb) - lumaOf(texture2D(uTex, at).rgb)));
  }
  return clamp(2.0 - motion / uMotion, 0.0, 1.0);
}

// Adds [frame]'s taps around this pixel, weighed by [weight], to [sum] (Cb, Cr, weight).
vec3 taps(sampler2D frame, float weight, float luma, vec3 sum) {
  for (int dy = -2; dy <= 2; dy++) {
    for (int dx = -2; dx <= 2; dx++) {
      vec2 offset = vec2(float(dx), float(dy));
      vec3 c = texture2D(frame, clamp(vTex + offset * uTexel, 0.0, 1.0)).rgb * 255.0;
      float l = dot(c, KY);
      float d = l - luma;
      float w = weight * exp(-dot(offset, offset) / 8.0 - d * d / uGuide2);
      sum += vec3((c.b - l) / 1.8556, (c.r - l) / 1.5748, 1.0) * w;
    }
  }
  return sum;
}

void main() {
  vec3 here = texture2D(uTex, vTex).rgb * 255.0;
  float luma = dot(here, KY);
  vec3 sum = taps(uTex, 1.0, luma, vec3(0.0));
  float before = share(uPrev);
  if (before > 0.0) sum = taps(uPrev, before, luma, sum);
  float after = share(uNext);
  if (after > 0.0) sum = taps(uNext, after, luma, sum);
  float cb = sum.x / sum.z;
  float cr = sum.y / sum.z;
  float r = luma + 1.5748 * cr;
  float b = luma + 1.8556 * cb;
  float g = (luma - KY.r * r - KY.b * b) / KY.g;
  gl_FragColor = vec4(clamp(vec3(r, g, b) / 255.0, 0.0, 1.0), 1.0);
}
