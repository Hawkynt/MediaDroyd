# Makes in.gray for the NNEDI3 test: 96 x 64, 8-bit grey — diagonal edges at several angles, a ring and fine lines,
# what an edge-directed interpolator is for.
import math
import os
import sys

here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
w, h = 96, 64
out = bytearray()
for y in range(h):
    for x in range(w):
        v = 40
        if x * 0.6 + y > 50: v = 200
        if (x - 70) ** 2 + (y - 30) ** 2 < 15 ** 2 and (x - 70) ** 2 + (y - 30) ** 2 > 10 ** 2: v = 230 - v // 2
        if abs(x - 2.5 * y + 20) < 1.5: v = 20
        v += int(10 * math.sin(x / 5.0) * math.cos(y / 7.0))
        out.append(max(0, min(255, v)))
open(os.path.join(here, 'in.gray'), 'wb').write(out)
