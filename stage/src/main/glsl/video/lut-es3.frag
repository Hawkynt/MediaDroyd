#version 300 es
// A .cube table on the picture (CubeLut, #448): each pixel scaled from the table's domain (uDomainMin .. uDomainMax)
// to its entries, clamped. A 3D table (uThreeD) read by tetrahedra — the cell cut into six, four entries weighed, as
// FFmpeg's lut3d reads it — from uLut, its blue slices of uSize x uSize (red across, green up) laid out uTiles to a
// row; a 1D table linearly from one row of uSize, each channel on its own. Entries read by texelFetch, exactly.
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform highp sampler2D uLut;
uniform int uSize;
uniform int uTiles;
uniform int uThreeD;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
in vec2 vTex;
out vec4 fragColor;

vec3 entry(ivec3 i) {
  ivec2 at = ivec2((i.b % uTiles) * uSize + i.r, (i.b / uTiles) * uSize + i.g);
  return texelFetch(uLut, at, 0).rgb;
}

void main() {
  vec4 here = texture(uTex, vTex);
  vec3 s = clamp((here.rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0) * float(uSize - 1);
  ivec3 i = min(ivec3(floor(s)), ivec3(uSize - 2));
  vec3 d = s - vec3(i);
  vec3 out3;
  if (uThreeD == 0) {
    vec3 lo = vec3(texelFetch(uLut, ivec2(i.r, 0), 0).r, texelFetch(uLut, ivec2(i.g, 0), 0).g,
      texelFetch(uLut, ivec2(i.b, 0), 0).b);
    vec3 hi = vec3(texelFetch(uLut, ivec2(i.r + 1, 0), 0).r, texelFetch(uLut, ivec2(i.g + 1, 0), 0).g,
      texelFetch(uLut, ivec2(i.b + 1, 0), 0).b);
    out3 = mix(lo, hi, d);
  } else {
    vec3 c000 = entry(i);
    vec3 c111 = entry(i + ivec3(1, 1, 1));
    float fr = d.r;
    float fg = d.g;
    float fb = d.b;
    if (fr > fg && fg > fb) {
      out3 = (1.0 - fr) * c000 + (fr - fg) * entry(i + ivec3(1, 0, 0))
        + (fg - fb) * entry(i + ivec3(1, 1, 0)) + fb * c111;
    } else if (fr > fg && fr > fb) {
      out3 = (1.0 - fr) * c000 + (fr - fb) * entry(i + ivec3(1, 0, 0))
        + (fb - fg) * entry(i + ivec3(1, 0, 1)) + fg * c111;
    } else if (fr > fg) {
      out3 = (1.0 - fb) * c000 + (fb - fr) * entry(i + ivec3(0, 0, 1))
        + (fr - fg) * entry(i + ivec3(1, 0, 1)) + fg * c111;
    } else if (fb > fg) {
      out3 = (1.0 - fb) * c000 + (fb - fg) * entry(i + ivec3(0, 0, 1))
        + (fg - fr) * entry(i + ivec3(0, 1, 1)) + fr * c111;
    } else if (fb > fr) {
      out3 = (1.0 - fg) * c000 + (fg - fb) * entry(i + ivec3(0, 1, 0))
        + (fb - fr) * entry(i + ivec3(0, 1, 1)) + fr * c111;
    } else {
      out3 = (1.0 - fg) * c000 + (fg - fr) * entry(i + ivec3(0, 1, 0))
        + (fr - fb) * entry(i + ivec3(1, 1, 0)) + fb * c111;
    }
  }
  fragColor = vec4(clamp(out3, 0.0, 1.0), here.a);
}
