# Makes voice.f32 for FbankTest: 1.3 s at 16 kHz, mono float32 in -1 .. 1 — a voiced sound (a 140 Hz pulse train
# through two formants), a pause of silence, and noise, so every part of the filterbank is exercised.
import math
import os
import random
import struct
import sys

here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
rate = 16000
random.seed(7)
out = []
y1 = y2 = z1 = z2 = 0.0
for n in range(int(rate * 1.3)):
    t = n / rate
    if t < 0.6:
        x = 1.0 if n % int(rate / 140) == 0 else 0.0
    elif t < 0.8:
        x = 0.0
    else:
        x = random.uniform(-0.3, 0.3)
    # Two resonators (700 Hz and 1200 Hz).
    r1, f1 = 0.97, 700.0
    y = x + 2 * r1 * math.cos(2 * math.pi * f1 / rate) * y1 - r1 * r1 * y2
    y2, y1 = y1, y
    r2, f2 = 0.95, 1200.0
    z = y + 2 * r2 * math.cos(2 * math.pi * f2 / rate) * z1 - r2 * r2 * z2
    z2, z1 = z1, z
    out.append(max(-1.0, min(1.0, z * 0.02)))
open(os.path.join(here, 'voice.f32'), 'wb').write(struct.pack('<%df' % len(out), *out))
