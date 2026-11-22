// One field of a frame as a picture of half its height (NNEDI3's deinterlacing, #443): row r, counted from the top, is
// the frame's row 2r + uParity; uSize is the frame's size. The stage's frames hold the top row at v = 1. Read at texel
// centres, nearest.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform float uParity;
varying vec2 vTex;

void main() {
  float rows = floor(uSize.y * 0.5);
  float r = floor((1.0 - vTex.y) * rows);
  float y = 2.0 * r + uParity;
  float x = floor(vTex.x * uSize.x);
  gl_FragColor = vec4(texture2D(uTex, vec2(x + 0.5, uSize.y - y - 0.5) / uSize).rgb, 1.0);
}
