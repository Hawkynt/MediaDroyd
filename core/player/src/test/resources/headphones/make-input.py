# Makes stereo.s16 for CrossfeedTest: two seconds of 44.1 kHz stereo, 16-bit little-endian — a low tone hard left,
# a high tone hard right and a click train in the middle, loud enough to clip in places.
import math
import os
import struct
import sys

here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
rate = 44100
out = bytearray()
for n in range(rate * 2):
    t = n / rate
    left = 20000 * math.sin(2 * math.pi * 220 * t)
    right = 14000 * math.sin(2 * math.pi * 3000 * t + 0.3)
    click = 30000 if n % 4410 < 3 else 0
    out += struct.pack('<hh', int(max(-32768, min(32767, left + click))), int(max(-32768, min(32767, right + click))))
open(os.path.join(here, 'stereo.s16'), 'wb').write(out)
