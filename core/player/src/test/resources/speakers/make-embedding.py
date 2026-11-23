# Makes voice-embedding.f32 for SpeakerEmbedderTest: what sherpa-onnx's extractor computes for voice.f32 — its
# filterbank (kaldi-native-fbank, as make-expected.py), each bin's mean taken out, through 3D-Speaker's CAM++
# (3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx from sherpa-onnx's speaker-recongition-models release,
# path given as the first argument) on onnxruntime — 192 floats. Needs kaldi-native-fbank, onnxruntime and numpy.
import os
import sys

import kaldi_native_fbank as knf
import numpy as np
import onnxruntime as ort

model = sys.argv[1]
here = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.abspath(__file__))
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
x = np.array([fbank.get_frame(i) for i in range(fbank.num_frames_ready)], dtype=np.float32)
x = x - x.mean(axis=0, keepdims=True)
session = ort.InferenceSession(model)
embedding = session.run(None, {'x': x[None, :, :]})[0][0].astype('<f4')
embedding.tofile(os.path.join(here, 'voice-embedding.f32'))
print(embedding.shape, float(np.linalg.norm(embedding)))
