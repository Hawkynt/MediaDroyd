# Makes the inputs of VirtualSurroundTest: surround51.s16 and surround71.s16 (one second of 48 kHz 16-bit 5.1 and 7.1
# in WAVE order, a tone of its own in each channel, quiet enough never to clip), and the head-related responses as
# FFmpeg's headphone filter reads them: hrir-<direction>.wav, stereo float32, the app's own resource
# (core/player/src/main/resources/.../hrir-48000.f32) scaled as VirtualSurround scales it.
import math
import os
import struct
import sys

here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
resource = os.path.join(here, '../../../main/resources/com/hawkynt/mediadroyd/player/hrir-48000.f32')
taps = 128
raw = open(resource, 'rb').read()
ir = struct.unpack('<%df' % (len(raw) // 4), raw)
# VirtualSurround.scale: the centre's left ear at unit energy.
centre = ir[0:taps]
scale = 1 / math.sqrt(sum(x * x for x in centre))
for d, name in enumerate(['0', 'l30', 'r30', 'l90', 'r90', 'l110', 'r110', 'l150', 'r150']):
    left = ir[(2 * d) * taps:(2 * d + 1) * taps]
    right = ir[(2 * d + 1) * taps:(2 * d + 2) * taps]
    data = b''.join(struct.pack('<ff', l * scale, r * scale) for l, r in zip(left, right))
    header = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 36 + len(data), b'WAVE', b'fmt ', 16, 3, 2, 48000,
                         48000 * 8, 8, 32, b'data', len(data))
    open(os.path.join(here, 'hrir-%s.wav' % name), 'wb').write(header + data)
rate = 48000
for channels, name in [(6, 'surround51.s16'), (8, 'surround71.s16')]:
    out = bytearray()
    for n in range(rate):
        t = n / rate
        for c in range(channels):
            out += struct.pack('<h', int(1500 * math.sin(2 * math.pi * (110 * (c + 1) + 7 * c * c) * t + c)))
    open(os.path.join(here, name), 'wb').write(out)
