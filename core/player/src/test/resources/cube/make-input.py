# Makes the inputs of CubeLutTest: look.cube (a 3D table of 5: warm, lifted shadows, cross-talk between the channels,
# nonlinear enough that the interpolation matters), curve.cube (a 1D table of 9 on a domain of 0 .. 2) and
# picture.rgb (16 x 16 rgb24, colours spread over the cube).
import os
import sys

here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
n = 5
lines = ['TITLE "Test look"', '# a look for the tests', 'LUT_3D_SIZE %d' % n, 'DOMAIN_MIN 0.0 0.0 0.0',
         'DOMAIN_MAX 1.0 1.0 1.0', '']
for b in range(n):
    for g in range(n):
        for r in range(n):
            R, G, B = r / (n - 1), g / (n - 1), b / (n - 1)
            o = (min(1, 0.05 + 0.9 * R ** 0.8 + 0.1 * G * B), min(1, 0.03 + 0.85 * G ** 1.1 + 0.08 * R),
                 min(1, 0.02 + 0.8 * B ** 1.3 + 0.05 * R * G))
            lines.append('%.6f %.6f %.6f' % o)
open(os.path.join(here, 'look.cube'), 'w').write('\n'.join(lines) + '\n')
m = 9
lines = ['LUT_1D_SIZE %d' % m, 'DOMAIN_MIN 0 0 0', 'DOMAIN_MAX 2 2 2']
for i in range(m):
    x = i / (m - 1)
    lines.append('%.6f %.6f %.6f' % (x ** 0.5, x, x ** 2))
open(os.path.join(here, 'curve.cube'), 'w').write('\n'.join(lines) + '\n')
w = h = 16
px = bytearray()
for y in range(h):
    for x in range(w):
        px += bytes([(x * 17) % 256, (y * 16 + x * 3) % 256, (x * y * 7 + 40) % 256])
open(os.path.join(here, 'picture.rgb'), 'wb').write(px)
