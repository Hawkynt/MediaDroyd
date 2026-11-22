#!/bin/sh
# Makes the expected file of CrossfeedTest: FFmpeg's bs2b filter (libbs2b 3.1.0, its default level: 700 Hz, 4.5 dB)
# on stereo.s16, 16-bit in and out, so libbs2b's own bs2b_cross_feed_s16 runs.
set -e
here=$(cd "$(dirname "$0")" && pwd)
python3 -I "$here/make-input.py" "$here"
ffmpeg -v error -y -f s16le -ar 44100 -ac 2 -i "$here/stereo.s16" -af bs2b=profile=default \
  -f s16le -c:a pcm_s16le "$here/stereo-bs2b.s16"
# VirtualSurroundTest: FFmpeg's headphone filter in the time domain over the same responses. It lowers its output by
# 3 dB per input channel, the LFE with it; gain gives that back, so the levels are the app's.
python3 -I "$here/make-surround.py" "$here"
h=$here
ffmpeg -v error -y -f s16le -ar 48000 -ac 6 -channel_layout 5.1 -i "$h/surround51.s16" \
  -i "$h/hrir-l30.wav" -i "$h/hrir-r30.wav" -i "$h/hrir-0.wav" -i "$h/hrir-0.wav" -i "$h/hrir-l110.wav" \
  -i "$h/hrir-r110.wav" \
  -filter_complex "[0:a][1:a][2:a][3:a][4:a][5:a][6:a]headphone=map=FL|FR|FC|LFE|BL|BR:type=time:hrir=stereo:gain=18" \
  -f s16le -c:a pcm_s16le "$h/surround51-headphone.s16"
ffmpeg -v error -y -f s16le -ar 48000 -ac 8 -channel_layout 7.1 -i "$h/surround71.s16" \
  -i "$h/hrir-l30.wav" -i "$h/hrir-r30.wav" -i "$h/hrir-0.wav" -i "$h/hrir-0.wav" -i "$h/hrir-l150.wav" \
  -i "$h/hrir-r150.wav" -i "$h/hrir-l90.wav" -i "$h/hrir-r90.wav" \
  -filter_complex "[0:a][1:a][2:a][3:a][4:a][5:a][6:a][7:a][8:a]headphone=map=FL|FR|FC|LFE|BL|BR|SL|SR:type=time:gain=24" \
  -f s16le -c:a pcm_s16le "$h/surround71-headphone.s16"
rm -f "$h"/hrir-*.wav
