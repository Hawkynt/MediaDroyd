#!/bin/sh
# Makes deshake.log for DeshakeTest: FFmpeg's deshake (its defaults: 20 reference frames, 16-pixel search) on 60
# frames of its testsrc2 pattern shaken by a crop that wanders, its per-frame log — the motion found (orig), the
# running average and the correction, x and y and angle and zoom — the oracle the smoothing is held to.
# Needs ffmpeg (made with n9.1).
set -e
here=$(cd "$(dirname "$0")" && pwd)
ffmpeg -v error -y -f lavfi -i "testsrc2=size=384x288:rate=25:duration=2.4" \
  -vf "crop=320:240:x='32+12*sin(n*1.7)+5*sin(n*0.31)':y='24+9*cos(n*2.3)+4*sin(n*0.17)',deshake=filename=$here/deshake.log" \
  -f null -
