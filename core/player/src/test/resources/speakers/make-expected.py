# Makes voice-fbank.f32 for FbankTest: kaldi-native-fbank's own Fbank (1.32, the options sherpa-onnx's speaker
# embedding extractor gives it: 16 kHz, 80 bins from 20 Hz to Nyquist - 400, no dither, snip_edges off, samples in
# -1 .. 1) on voice.f32, frame by frame, as float32. Needs `pip install kaldi-native-fbank numpy` (an oracle only).
import os
import struct
import sys

import kaldi_native_fbank as knf
import numpy as np

here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
samples = np.fromfile(os.path.join(here, 'voice.f32'), dtype='<f4')
opts = knf.FbankOptions()
opts.frame_opts.samp_freq = 16000
opts.frame_opts.dither = 0
opts.frame_opts.snip_edges = False
opts.mel_opts.num_bins = 80
opts.mel_opts.low_freq = 20
opts.mel_opts.high_freq = -400
fbank = knf.OnlineFbank(opts)
fbank.accept_waveform(16000, samples.tolist())
fbank.input_finished()
frames = np.array([fbank.get_frame(i) for i in range(fbank.num_frames_ready)], dtype='<f4')
frames.tofile(os.path.join(here, 'voice-fbank.f32'))
print(frames.shape)
