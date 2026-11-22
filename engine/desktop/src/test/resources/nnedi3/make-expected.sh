#!/bin/sh
# Makes the expected files of GlStageTest's NNEDI3 cases: FFmpeg's nnedi filter (n9.1) on in.gray, keeping the top
# field ("top.gray") and the bottom field ("bottom.gray") and rebuilding the other, with the predictor the vendored
# shader carries — an 8 x 4 window, 32 neurons, the fast quality, no prescreener — and tritical's weights, fetched to a
# temporary folder (nnedi3_weights.bin from dubhater's vapoursynth-nnedi3; used only to make these files).
set -e
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -sL -o "$work/nnedi3_weights.bin" https://github.com/dubhater/vapoursynth-nnedi3/raw/master/src/nnedi3_weights.bin
python3 -I "$here/make-input.py" "$here"
for field in t:top b:bottom; do
  ffmpeg -v error -y -f rawvideo -pix_fmt gray -s 96x64 -i "$here/in.gray" \
    -vf "nnedi=weights=$work/nnedi3_weights.bin:field=${field%%:*}:nsize=s8x4:nns=n32:qual=fast:pscrn=none:etype=${ETYPE:-a}" \
    -f rawvideo -pix_fmt gray "$here/${field##*:}.gray"
done
