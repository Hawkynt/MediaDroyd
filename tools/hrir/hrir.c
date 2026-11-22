/*
 * Writes the head-related impulse responses VirtualSurround reads (core/player/src/main/resources/.../hrir-<rate>.f32):
 * MIT's KEMAR measurements (Gardner and Martin 1994, the normal pinna, as libmysofa ships them in SOFA) through
 * libmysofa at <rate>, unnormalised, for the directions of ITU-R BS.775's loudspeakers in VirtualSurround.DIRECTIONS'
 * order — 0, +30, -30, +90, -90, +110, -110, +150, -150 degrees on the horizontal plane, positive to the left as SOFA
 * counts — each the left ear's then the right ear's TAPS samples, any delay libmysofa holds apart put back in front,
 * as little-endian float32.
 *
 *   cc -O2 -o hrir tools/hrir/hrir.c -lmysofa -lm
 *   ./hrir /usr/share/libmysofa/MIT_KEMAR_normal_pinna.sofa 48000 > hrir-48000.f32
 */
#include <math.h>
#include <mysofa.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAPS 128

static const float DIRECTIONS[] = {0, 30, -30, 90, -90, 110, -110, 150, -150};

int main(int argc, char **argv) {
  if (argc != 3) {
    fprintf(stderr, "usage: hrir <file.sofa> <rate>\n");
    return 2;
  }
  float rate = (float)atof(argv[2]);
  int length = 0;
  int err = 0;
  struct MYSOFA_EASY *easy = mysofa_open_no_norm(argv[1], rate, &length, &err);
  if (!easy) {
    fprintf(stderr, "cannot open %s: %d\n", argv[1], err);
    return 1;
  }
  float *left = calloc(length, sizeof(float));
  float *right = calloc(length, sizeof(float));
  for (size_t d = 0; d < sizeof(DIRECTIONS) / sizeof(DIRECTIONS[0]); d++) {
    float a = DIRECTIONS[d] * (float)M_PI / 180.0f;
    float delayLeft = 0;
    float delayRight = 0;
    mysofa_getfilter_float(easy, cosf(a), sinf(a), 0.0f, left, right, &delayLeft, &delayRight);
    float *ears[2] = {left, right};
    float delays[2] = {delayLeft, delayRight};
    for (int e = 0; e < 2; e++) {
      int shift = (int)lroundf(delays[e] * rate);
      for (int i = 0; i < TAPS; i++) {
        int from = i - shift;
        float v = from >= 0 && from < length ? ears[e][from] : 0.0f;
        fwrite(&v, sizeof v, 1, stdout);
      }
    }
  }
  mysofa_close(easy);
  return 0;
}
